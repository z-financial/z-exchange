package com.exchange.common.kafka.producer;

import com.exchange.common.outbox.po.Outbox;
import com.exchange.common.outbox.po.enums.OutboxStatus;
import com.exchange.common.result.Result;
import com.exchange.common.result.error.KafkaProducerErrorCode;
import com.exchange.proto.common.event.EventEnvelopePb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.util.concurrent.SettableListenableFuture;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultPublisherTest {
    @Mock
    private KafkaTemplate<String, byte[]> kafkaTemplate;

    private Outbox outbox;
    private SettableListenableFuture<SendResult<String, byte[]>> kafkaFuture;

    @BeforeEach
    void setUp() {
        outbox = Outbox.create(
                1L,
                1,
                "event-1",
                "command-1",
                OutboxStatus.PENDING,
                "destination-1",
                "partition-key-1",
                new byte[]{1, 2, 3},
                0,
                LocalDateTime.now(),
                LocalDateTime.now(),
                null,
                null
        );
        kafkaFuture = new SettableListenableFuture<>();
    }

    @Test
    void completesSuccessfullyOnlyAfterBrokerAcknowledgment() throws Exception {
        when(kafkaTemplate.send(eq("destination-1"), eq("partition-key-1"), any(byte[].class)))
                .thenReturn(kafkaFuture);
        DefaultPublisher publisher = publisher(Duration.ofSeconds(1));

        CompletableFuture<Result<Void>> resultFuture = publisher.publishAsync(outbox);

        assertFalse(resultFuture.isDone());
        kafkaFuture.set(null);
        assertTrue(resultFuture.get(1, TimeUnit.SECONDS).isSuccess());

        var envelopeCaptor = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(kafkaTemplate).send(eq("destination-1"), eq("partition-key-1"), envelopeCaptor.capture());
        EventEnvelopePb envelope = EventEnvelopePb.parseFrom(envelopeCaptor.getValue());
        assertEquals("event-1", envelope.getEventRef());
        assertEquals("command-1", envelope.getCommandRef());
        assertEquals("event-name-1", envelope.getEventType());
        assertArrayEquals(outbox.getPayload(), envelope.getPayload().toByteArray());
    }

    @Test
    void producerFailureReturnsCommonKafkaError() throws Exception {
        when(kafkaTemplate.send(eq("destination-1"), eq("partition-key-1"), any(byte[].class)))
                .thenReturn(kafkaFuture);
        CompletableFuture<Result<Void>> resultFuture = publisher(Duration.ofSeconds(1)).publishAsync(outbox);

        kafkaFuture.setException(new RuntimeException("broker unavailable"));

        Result<Void> result = resultFuture.get(1, TimeUnit.SECONDS);
        assertTrue(result.isFailed());
        assertEquals(KafkaProducerErrorCode.PUBLISH_FAILED, result.getErrorCode());
    }

    @Test
    void missingBrokerAcknowledgmentReturnsCommonKafkaErrorAfterTimeout() throws Exception {
        when(kafkaTemplate.send(eq("destination-1"), eq("partition-key-1"), any(byte[].class)))
                .thenReturn(kafkaFuture);

        Result<Void> result = publisher(Duration.ofMillis(25))
                .publishAsync(outbox)
                .get(1, TimeUnit.SECONDS);

        assertTrue(result.isFailed());
        assertEquals(KafkaProducerErrorCode.PUBLISH_FAILED, result.getErrorCode());
    }

    @Test
    void unknownEventTypeFailsWithoutCallingKafka() throws Exception {
        DefaultPublisher publisher = new DefaultPublisher(
                kafkaTemplate,
                ignored -> null,
                Duration.ofSeconds(1)
        );

        Result<Void> result = publisher.publishAsync(outbox).get(1, TimeUnit.SECONDS);

        assertTrue(result.isFailed());
        assertEquals(KafkaProducerErrorCode.PUBLISH_FAILED, result.getErrorCode());
        verifyNoInteractions(kafkaTemplate);
    }

    private DefaultPublisher publisher(Duration timeout) {
        return new DefaultPublisher(
                kafkaTemplate,
                ignored -> "event-name-1",
                timeout
        );
    }
}
