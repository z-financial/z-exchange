package com.exchange.common.kafka.producer.metrics;

import com.exchange.common.kafka.producer.IPublisher;
import com.exchange.common.kafka.producer.PublishSource;
import com.exchange.common.metrics.CommonMetricTagValues;
import com.exchange.common.metrics.CommonMetricTags;
import com.exchange.common.metrics.CommonMetrics;
import com.exchange.common.outbox.po.Outbox;
import com.exchange.common.outbox.po.enums.OutboxStatus;
import com.exchange.common.result.Result;
import com.exchange.common.result.error.KafkaProducerErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeteredPublisherTest {
    private static final Instant NOW = Instant.parse("2026-08-22T08:00:00Z");

    @Mock
    private IPublisher delegate;

    private SimpleMeterRegistry registry;
    private MeteredPublisher publisher;
    private Outbox outbox;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        publisher = new MeteredPublisher(delegate, ignored -> "ledger_post", registry, clock);
        outbox = Outbox.create(
                1L,
                1,
                "event-1",
                "command-1",
                OutboxStatus.PENDING,
                "ledger.wallet.posting",
                "command-1",
                new byte[]{1},
                0,
                null,
                null,
                null,
                null
        );
        outbox.setCreatedAt(LocalDateTime.ofInstant(NOW.minusSeconds(2), ZoneOffset.UTC));
    }

    @Test
    void recordsSuccessfulRetryOnlyAfterBrokerCompletion() {
        // A publish is complete only when the delegate future reaches its broker outcome.
        CompletableFuture<Result<Void>> delegateFuture = new CompletableFuture<>();
        when(delegate.publishAsync(outbox, PublishSource.RETRY)).thenReturn(delegateFuture);

        CompletableFuture<Result<Void>> returned = publisher.publishAsync(outbox, PublishSource.RETRY);

        assertFalse(returned.isDone());
        assertEquals(0, publisherCounter(CommonMetricTagValues.KafkaSources.RETRY,
                CommonMetricTagValues.Outcomes.SUCCESS));

        delegateFuture.complete(Result.success());

        assertTrue(returned.join().isSuccess());
        assertEquals(1, publisherCounter(CommonMetricTagValues.KafkaSources.RETRY,
                CommonMetricTagValues.Outcomes.SUCCESS));
        assertEquals(1, registry.find(CommonMetrics.KAFKA_PUBLISHER_DURATION.name())
                .tag(CommonMetricTags.SOURCE, CommonMetricTagValues.KafkaSources.RETRY)
                .tag(CommonMetricTags.OUTCOME, CommonMetricTagValues.Outcomes.SUCCESS)
                .timer().count());
        assertEquals(2.0, registry.find(CommonMetrics.OUTBOX_DELIVERY_DELAY.name())
                .tag(CommonMetricTags.EVENT_TYPE, "ledger_post")
                .tag(CommonMetricTags.SOURCE, CommonMetricTagValues.KafkaSources.RETRY)
                .tag(CommonMetricTags.OUTCOME, CommonMetricTagValues.Outcomes.SUCCESS)
                .timer().totalTime(java.util.concurrent.TimeUnit.SECONDS));
        verify(delegate).publishAsync(outbox, PublishSource.RETRY);
    }

    @Test
    void failedResultIsRecordedAsErrorWithoutChangingResult() {
        Result<Void> failure = Result.failure(KafkaProducerErrorCode.PUBLISH_FAILED);
        when(delegate.publishAsync(outbox, PublishSource.IMMEDIATE))
                .thenReturn(CompletableFuture.completedFuture(failure));

        Result<Void> returned = publisher.publishAsync(outbox).join();

        assertEquals(failure, returned);
        assertEquals(1, publisherCounter(CommonMetricTagValues.KafkaSources.IMMEDIATE,
                CommonMetricTagValues.Outcomes.ERROR));
    }

    @Test
    void synchronousDelegateExceptionIsRecordedAndRethrown() {
        RuntimeException failure = new RuntimeException("publisher setup failed");
        when(delegate.publishAsync(outbox, PublishSource.IMMEDIATE)).thenThrow(failure);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> publisher.publishAsync(outbox));

        assertEquals(failure, thrown);
        assertEquals(1, publisherCounter(CommonMetricTagValues.KafkaSources.IMMEDIATE,
                CommonMetricTagValues.Outcomes.ERROR));
    }

    private double publisherCounter(String source, String outcome) {
        var counter = registry.find(CommonMetrics.KAFKA_PUBLISHER_REQUESTS.name())
                .tag(CommonMetricTags.TOPIC, "ledger.wallet.posting")
                .tag(CommonMetricTags.SOURCE, source)
                .tag(CommonMetricTags.OUTCOME, outcome)
                .counter();
        return counter == null ? 0 : counter.count();
    }
}
