/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.connect.util;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.CreateTopicsOptions;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.ClusterAuthorizationException;
import org.apache.kafka.common.errors.LeaderNotAvailableException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.UnsupportedVersionException;
import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.errors.RetriableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Utility to simplify creating and managing topics via the {@link Admin}.
 */
public class TopicAdmin implements AutoCloseable {

    private static final String CLEANUP_POLICY_CONFIG = "cleanup.policy";
    private static final String CLEANUP_POLICY_COMPACT = "compact";

    private static final String MIN_INSYNC_REPLICAS_CONFIG = "min.insync.replicas";

    private static final String UNCLEAN_LEADER_ELECTION_ENABLE_CONFIG = "unclean.leader.election.enable";

    /**
     * A builder of {@link NewTopic} instances.
     */
    public static class NewTopicBuilder {
        private String name;
        private int numPartitions;
        private short replicationFactor;
        private Map<String, String> configs = new HashMap<>();

        NewTopicBuilder(String name) {
            this.name = name;
        }

        /**
         * Specify the desired number of partitions for the topic.
         *
         * @param numPartitions the desired number of partitions; must be positive
         * @return this builder to allow methods to be chained; never null
         */
        public NewTopicBuilder partitions(int numPartitions) {
            this.numPartitions = numPartitions;
            return this;
        }

        /**
         * Specify the desired replication factor for the topic.
         *
         * @param replicationFactor the desired replication factor; must be positive
         * @return this builder to allow methods to be chained; never null
         */
        public NewTopicBuilder replicationFactor(short replicationFactor) {
            this.replicationFactor = replicationFactor;
            return this;
        }

        /**
         * Specify that the topic should be compacted.
         *
         * @return this builder to allow methods to be chained; never null
         */
        public NewTopicBuilder compacted() {
            this.configs.put(CLEANUP_POLICY_CONFIG, CLEANUP_POLICY_COMPACT);
            return this;
        }

        /**
         * Specify the minimum number of in-sync replicas required for this topic.
         *
         * @param minInSyncReplicas the minimum number of in-sync replicas allowed for the topic; must be positive
         * @return this builder to allow methods to be chained; never null
         */
        public NewTopicBuilder minInSyncReplicas(short minInSyncReplicas) {
            this.configs.put(MIN_INSYNC_REPLICAS_CONFIG, Short.toString(minInSyncReplicas));
            return this;
        }

        /**
         * Specify whether the broker is allowed to elect a leader that was not an in-sync replica when no ISRs
         * are available.
         *
         * @param allow true if unclean leaders can be elected, or false if they are not allowed
         * @return this builder to allow methods to be chained; never null
         */
        public NewTopicBuilder uncleanLeaderElection(boolean allow) {
            this.configs.put(UNCLEAN_LEADER_ELECTION_ENABLE_CONFIG, Boolean.toString(allow));
            return this;
        }

        /**
         * Specify the configuration properties for the topic, overwriting any previously-set properties.
         *
         * @param configs the desired topic configuration properties, or null if all existing properties should be cleared
         * @return this builder to allow methods to be chained; never null
         */
        public NewTopicBuilder config(Map<String, Object> configs) {
            if (configs != null) {
                for (Map.Entry<String, Object> entry : configs.entrySet()) {
                    Object value = entry.getValue();
                    this.configs.put(entry.getKey(), value != null ? value.toString() : null);
                }
            } else {
                this.configs.clear();
            }
            return this;
        }

        /**
         * Build the {@link NewTopic} representation.
         *
         * @return the topic description; never null
         */
        public NewTopic build() {
            return new NewTopic(name, numPartitions, replicationFactor).configs(configs);
        }
    }

    /**
     * Obtain a {@link NewTopicBuilder builder} to define a {@link NewTopic}.
     *
     * @param topicName the name of the topic
     * @return the {@link NewTopic} description of the topic; never null
     */
    public static NewTopicBuilder defineTopic(String topicName) {
        return new NewTopicBuilder(topicName);
    }

    private static final Logger log = LoggerFactory.getLogger(TopicAdmin.class);
    private final Map<String, Object> adminConfig;
    private final Admin admin;

    /**
     * Create a new topic admin component with the given configuration.
     *
     * @param adminConfig the configuration for the {@link Admin}
     */
    public TopicAdmin(Map<String, Object> adminConfig) {
        this(adminConfig, Admin.create(adminConfig));
    }

    // visible for testing
    TopicAdmin(Map<String, Object> adminConfig, Admin adminClient) {
        this.admin = adminClient;
        this.adminConfig = adminConfig != null ? adminConfig : Collections.<String, Object>emptyMap();
    }

    /**
     * Get the {@link Admin} client used by this topic admin object.
     * @return the Kafka admin instance; never null
     */
    public Admin admin() {
        return admin;
    }

   /**
     * Attempt to create the topic described by the given definition, returning true if the topic was created or false
     * if the topic already existed.
     *
     * @param topic the specification of the topic
     * @return true if the topic was created or false if the topic already existed.
     * @throws ConnectException            if an error occurs, the operation takes too long, or the thread is interrupted while
     *                                     attempting to perform this operation
     * @throws UnsupportedVersionException if the broker does not support the necessary APIs to perform this request
     */
    public boolean createTopic(NewTopic topic) {
        if (topic == null) return false;
        Set<String> newTopicNames = createTopics(topic);
        return newTopicNames.contains(topic.name());
    }

    /**
     * Attempt to create the topics described by the given definitions, returning all of the names of those topics that
     * were created by this request. Any existing topics with the same name are unchanged, and the names of such topics
     * are excluded from the result.
     * <p>
     * If multiple topic definitions have the same topic name, the last one with that name will be used.
     * <p>
     * Apache Kafka added support for creating topics in 0.10.1.0, so this method works as expected with that and later versions.
     * With brokers older than 0.10.1.0, this method is unable to create topics and always returns an empty set.
     *
     * @param topics the specifications of the topics
     * @return the names of the topics that were created by this operation; never null but possibly empty
     * @throws ConnectException            if an error occurs, the operation takes too long, or the thread is interrupted while
     *                                     attempting to perform this operation
     */
    public Set<String> createTopics(NewTopic... topics) {
        Map<String, NewTopic> topicsByName = new HashMap<>();
        if (topics != null) {
            for (NewTopic topic : topics) {
                if (topic != null) topicsByName.put(topic.name(), topic);
            }
        }
        if (topicsByName.isEmpty()) return Collections.emptySet();
        String bootstrapServers = bootstrapServers();
        String topicNameList = Utils.join(topicsByName.keySet(), "', '");

        // Attempt to create any missing topics
        CreateTopicsOptions args = new CreateTopicsOptions().validateOnly(false);
        Map<String, KafkaFuture<Void>> newResults = admin.createTopics(topicsByName.values(), args).values();

        // Iterate over each future so that we can handle individual failures like when some topics already exist
        Set<String> newlyCreatedTopicNames = new HashSet<>();
        for (Map.Entry<String, KafkaFuture<Void>> entry : newResults.entrySet()) {
            String topic = entry.getKey();
            try {
                entry.getValue().get();
                log.info("Created topic {} on brokers at {}", topicsByName.get(topic), bootstrapServers);
                newlyCreatedTopicNames.add(topic);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof TopicExistsException) {
                    log.debug("Found existing topic '{}' on the brokers at {}", topic, bootstrapServers);
                    continue;
                }
                if (cause instanceof UnsupportedVersionException) {
                    log.debug("Unable to create topic(s) '{}' since the brokers at {} do not support the CreateTopics API." +
                            " Falling back to assume topic(s) exist or will be auto-created by the broker.",
                            topicNameList, bootstrapServers);
                    return Collections.emptySet();
                }
                if (cause instanceof ClusterAuthorizationException) {
                    log.debug("Not authorized to create topic(s) '{}' upon the brokers {}." +
                            " Falling back to assume topic(s) exist or will be auto-created by the broker.",
                            topicNameList, bootstrapServers);
                    return Collections.emptySet();
                }
                if (cause instanceof TopicAuthorizationException) {
                    log.debug("Not authorized to create topic(s) '{}' upon the brokers {}." +
                                    " Falling back to assume topic(s) exist or will be auto-created by the broker.",
                            topicNameList, bootstrapServers);
                    return Collections.emptySet();
                }
                if (cause instanceof TimeoutException) {
                    // Timed out waiting for the operation to complete
                    throw new ConnectException("Timed out while checking for or creating topic(s) '" + topicNameList + "'." +
                            " This could indicate a connectivity issue, unavailable topic partitions, or if" +
                            " this is your first use of the topic it may have taken too long to create.", cause);
                }
                throw new ConnectException("Error while attempting to create/find topic(s) '" + topicNameList + "'", e);
            } catch (InterruptedException e) {
                Thread.interrupted();
                throw new ConnectException("Interrupted while attempting to create/find topic(s) '" + topicNameList + "'", e);
            }
        }
        return newlyCreatedTopicNames;
    }

    /**
     * Fetch the most recent offset for each of the supplied {@link TopicPartition} objects.
     *
     * @param partitions the topic partitions
     * @return the map of offset for each topic partition, or an empty map if the supplied partitions
     *         are null or empty
     * @throws UnsupportedVersionException if the admin client cannot read end offsets
     * @throws TimeoutException if the offset metadata could not be fetched before the amount of time allocated
     *         by {@code request.timeout.ms} expires, and this call can be retried
     * @throws LeaderNotAvailableException if the leader was not available and this call can be retried
     * @throws RetriableException if a retriable error occurs, or the thread is interrupted while attempting 
     *         to perform this operation
     * @throws ConnectException if a non retriable error occurs
     */
    public Map<TopicPartition, Long> endOffsets(Set<TopicPartition> partitions) {
        if (partitions == null || partitions.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<TopicPartition, OffsetSpec> offsetSpecMap = partitions.stream().collect(Collectors.toMap(Function.identity(), tp -> OffsetSpec.latest()));
        ListOffsetsResult resultFuture = admin.listOffsets(offsetSpecMap);
        // Get the individual result for each topic partition so we have better error messages
        Map<TopicPartition, Long> result = new HashMap<>();
        for (TopicPartition partition : partitions) {
            try {
                ListOffsetsResultInfo info = resultFuture.partitionResult(partition).get();
                result.put(partition, info.offset());
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                String topic = partition.topic();
                if (cause instanceof AuthorizationException) {
                    String msg = String.format("Not authorized to get the end offsets for topic '%s' on brokers at %s", topic, bootstrapServers());
                    throw new ConnectException(msg, e);
                } else if (cause instanceof UnsupportedVersionException) {
                    // Should theoretically never happen, because this method is the same as what the consumer uses and therefore
                    // should exist in the broker since before the admin client was added
                    String msg = String.format("API to get the get the end offsets for topic '%s' is unsupported on brokers at %s", topic, bootstrapServers());
                    throw new UnsupportedVersionException(msg, e);
                } else if (cause instanceof TimeoutException) {
                    String msg = String.format("Timed out while waiting to get end offsets for topic '%s' on brokers at %s", topic, bootstrapServers());
                    throw new TimeoutException(msg, e);
                } else if (cause instanceof LeaderNotAvailableException) {
                    String msg = String.format("Unable to get end offsets during leader election for topic '%s' on brokers at %s", topic, bootstrapServers());
                    throw new LeaderNotAvailableException(msg, e);
                } else if (cause instanceof org.apache.kafka.common.errors.RetriableException) {
                    throw (org.apache.kafka.common.errors.RetriableException) cause;
                } else {
                    String msg = String.format("Error while getting end offsets for topic '%s' on brokers at %s", topic, bootstrapServers());
                    throw new ConnectException(msg, e);
                }
            } catch (InterruptedException e) {
                Thread.interrupted();
                String msg = String.format("Interrupted while attempting to read end offsets for topic '%s' on brokers at %s", partition.topic(), bootstrapServers());
                throw new RetriableException(msg, e);
            }
        }
        return result;
    }

    @Override
    public void close() {
        admin.close();
    }

    private String bootstrapServers() {
        Object servers = adminConfig.get(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG);
        return servers != null ? servers.toString() : "<unknown>";
    }
}
