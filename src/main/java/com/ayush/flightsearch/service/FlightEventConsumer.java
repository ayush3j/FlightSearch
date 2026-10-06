package com.ayush.flightsearch.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class FlightEventConsumer {

    @KafkaListener(
        topics = "flight-events-replicated",
        groupId = "flight-search-group",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        try {
            log.info("✓ Received Kafka message - Key: {}, Value: {}, Partition: {}, Offset: {}",
                record.key(), record.value(), record.partition(), record.offset());
            log.info("Processing message");

            // Simulate processing
            throw new RuntimeException("Simulated processing failure");

        } catch (Exception e) {
            log.error("Error processing message: {}", e.getMessage());
            throw e;  // Re-throw so error handler retries
        } finally {
            // Only acknowledge after successful processing
            // If exception is thrown, error handler will retry
            // After max retries exhausted, message will be skipped
        }
    }


}
