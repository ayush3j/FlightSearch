package com.ayush.flightsearch.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
public class FlightEventDltConsumer {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public FlightEventDltConsumer(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(
        topics = "flight-events-replicated-dlt",
        groupId = "flight-dlt-reprocessing-group",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {

        log.warn(
            "⚠️ DLT message received - Key: {}, Value: {}, Partition: {}, Offset: {}",
            record.key(),
            record.value(),
            record.partition(),
            record.offset()
        );

        log.info("📤 Reprocessing DLT message...");

        try {
            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(
                            "flight-events-replicated",
                            record.key(),
                            record.value()
                    );

            SendResult<String, String> result = future.get();
            log.info(
                    "✓ Message successfully published to main topic - Partition: {}, Offset: {}",
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset()
            );
            acknowledgment.acknowledge();
            log.info("✓ DLT offset acknowledged successfully");
        } catch (Exception e) {
            log.error(
                    "❌ Failed to reprocess DLT message - Key: {}, Offset: {}",
                    record.key(),
                    record.offset(),
                    e
            );

            // Do NOT acknowledge.
            // Exception causes the listener/error handling mechanism
            // to handle the failed DLT message.
            throw new RuntimeException(
                    "DLT reprocessing failed",
                    e
            );
        }
    }
}
