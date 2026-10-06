package com.ayush.flightsearch;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

public class KafkaConnectionTest {

    public static void main(String[] args) {
        String bootstrapServers = "localhost:9092";
        String groupId = "flight-search-group";
        String topic = "flight-events-replicated";

        System.out.println("========== Kafka Connection Test ==========\n");

        // Test 1: Check Broker Connection
        System.out.println("Test 1: Checking Broker Connection...");
        if (testBrokerConnection(bootstrapServers)) {
            System.out.println("✓ Successfully connected to Kafka broker at " + bootstrapServers + "\n");
        } else {
            System.out.println("✗ Failed to connect to Kafka broker at " + bootstrapServers + "\n");
            return;
        }

        // Test 2: Check Topic Exists
        System.out.println("Test 2: Checking if topic '" + topic + "' exists...");
        if (checkTopicExists(bootstrapServers, topic)) {
            System.out.println("✓ Topic '" + topic + "' exists\n");
        } else {
            System.out.println("✗ Topic '" + topic + "' does not exist\n");
        }

        // Test 3: Check Consumer Group
        System.out.println("Test 3: Checking consumer group '" + groupId + "'...");
        checkConsumerGroupStatus(bootstrapServers, groupId, topic);

        // Test 4: Try to consume messages
        System.out.println("\nTest 4: Attempting to consume messages from topic '" + topic + "'...");
        consumeMessages(bootstrapServers, groupId, topic);
    }

    private static boolean testBrokerConnection(String bootstrapServers) {
        try {
            Properties props = new Properties();
            props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000);

            AdminClient adminClient = AdminClient.create(props);
            adminClient.listTopics().names().get();
            adminClient.close();
            return true;
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
            return false;
        }
    }

    private static boolean checkTopicExists(String bootstrapServers, String topicName) {
        try {
            Properties props = new Properties();
            props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

            AdminClient adminClient = AdminClient.create(props);
            boolean exists = adminClient.listTopics().names().get().contains(topicName);
            adminClient.close();
            return exists;
        } catch (Exception e) {
            System.out.println("Error checking topic: " + e.getMessage());
            return false;
        }
    }

    private static void checkConsumerGroupStatus(String bootstrapServers, String groupId, String topic) {
        try {
            Properties props = new Properties();
            props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

            AdminClient adminClient = AdminClient.create(props);

            // Try to get offsets for this group
            try {
                var offsets = adminClient.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata().get();
                if (offsets.isEmpty()) {
                    System.out.println("⚠ Consumer group '" + groupId + "' has no offsets (not yet active)\n");
                } else {
                    System.out.println("✓ Consumer group '" + groupId + "' found with offsets:");
                    offsets.forEach((partition, offsetMetadata) ->
                        System.out.println("  - Partition " + partition.partition() + ": offset " + offsetMetadata.offset())
                    );
                    System.out.println();
                }
            } catch (Exception e) {
                System.out.println("⚠ Consumer group '" + groupId + "' not yet created\n");
            }
            adminClient.close();
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage() + "\n");
        }
    }

    private static void consumeMessages(String bootstrapServers, String groupId, String topic) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId + "-test-consumer");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 10000);

        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(Collections.singletonList(topic));

        System.out.println("Subscribed to topic. Waiting for messages (timeout: 5 seconds)...\n");

        long startTime = System.currentTimeMillis();
        long timeoutMs = 5000;
        final int[] messageCount = {0};

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            var records = consumer.poll(Duration.ofMillis(1000));

            if (!records.isEmpty()) {
                for (var record : records) {
                    System.out.println("✓ Received message:");
                    System.out.println("  - Key: " + record.key());
                    System.out.println("  - Value: " + record.value());
                    System.out.println("  - Partition: " + record.partition());
                    System.out.println("  - Offset: " + record.offset());
                    System.out.println();
                    messageCount[0]++;
                }
            }
        }

        if (messageCount[0] == 0) {
            System.out.println("✗ No messages received from topic '" + topic + "'");
            System.out.println("\nPossible causes:");
            System.out.println("  1. The topic exists but has no messages");
            System.out.println("  2. All messages have already been consumed");
            System.out.println("  3. Messages were produced with a different key");
        } else {
            System.out.println("✓ Successfully consumed " + messageCount[0] + " message(s)");
        }

        consumer.close();
    }
}
