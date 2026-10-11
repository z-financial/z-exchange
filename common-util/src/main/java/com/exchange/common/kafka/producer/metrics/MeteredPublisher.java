package com.exchange.common.kafka.producer.metrics;

import com.exchange.common.kafka.producer.IPublisher;
import com.exchange.common.kafka.producer.OutboxEventTypeResolver;
import com.exchange.common.kafka.producer.PublishSource;
import com.exchange.common.metrics.CommonMetricTagValues;
import com.exchange.common.metrics.CommonMetricTags;
import com.exchange.common.metrics.CommonMetrics;
import com.exchange.common.metrics.MetricTagSanitizer;
import com.exchange.common.outbox.po.Outbox;
import com.exchange.common.result.Result;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * Adds broker-outcome and outbox-age measurements without changing publisher behavior.
 */
@Slf4j
public class MeteredPublisher implements IPublisher {
    private final IPublisher delegate;
    private final OutboxEventTypeResolver eventTypeResolver;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final ConcurrentMap<PublisherMetricKey, PublisherMeters> successMeters = new ConcurrentHashMap<>();

    public MeteredPublisher(
            IPublisher delegate,
            OutboxEventTypeResolver eventTypeResolver,
            MeterRegistry meterRegistry
    ) {
        this(delegate, eventTypeResolver, meterRegistry, Clock.systemDefaultZone());
    }

    MeteredPublisher(
            IPublisher delegate,
            OutboxEventTypeResolver eventTypeResolver,
            MeterRegistry meterRegistry,
            Clock clock
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.eventTypeResolver = Objects.requireNonNull(eventTypeResolver, "eventTypeResolver");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CompletableFuture<Result<Void>> publishAsync(Outbox outbox, PublishSource source) {
        Objects.requireNonNull(outbox, "outbox");
        Objects.requireNonNull(source, "source");
        long startedAtNanos = System.nanoTime();

        final CompletableFuture<Result<Void>> future;
        try {
            future = delegate.publishAsync(outbox, source);
        } catch (RuntimeException e) {
            recordSafely(outbox, source, CommonMetricTagValues.Outcomes.ERROR, startedAtNanos);
            throw e;
        }

        if (future == null) {
            recordSafely(outbox, source, CommonMetricTagValues.Outcomes.ERROR, startedAtNanos);
            return null;
        }

        // Observe the original future without wrapping it, so metrics cannot alter completion semantics.
        try {
            future.whenComplete((result, throwable) -> {
                String outcome = throwable == null && result != null && result.isSuccess()
                        ? CommonMetricTagValues.Outcomes.SUCCESS
                        : CommonMetricTagValues.Outcomes.ERROR;
                recordSafely(outbox, source, outcome, startedAtNanos);
            });
        } catch (RuntimeException e) {
            log.error(
                    "failed to attach Kafka publisher metric callback, eventRef={}, destination={}, source={}",
                    outbox.getEventRef(),
                    outbox.getDestination(),
                    source.metricValue(),
                    e
            );
            // Metrics are best-effort and must never turn a valid publish attempt into a failure.
        }
        return future;
    }

    private void recordSafely(Outbox outbox, PublishSource source, String outcome, long startedAtNanos) {
        try {
            PublisherMetricKey key = new PublisherMetricKey(
                    MetricTagSanitizer.safeValue(outbox.getDestination()),
                    resolveEventTypeSafely(outbox),
                    source.metricValue(),
                    outcome
            );
            PublisherMeters meters = CommonMetricTagValues.Outcomes.SUCCESS.equals(outcome)
                    ? successMeters.computeIfAbsent(key, this::registerMeters)
                    : registerMeters(key);
            meters.requests().increment();
            meters.duration().record(System.nanoTime() - startedAtNanos, TimeUnit.NANOSECONDS);

            LocalDateTime createdAt = outbox.getCreatedAt();
            if (createdAt != null) {
                Duration delay = Duration.between(createdAt, LocalDateTime.now(clock));
                meters.deliveryDelay().record(delay.isNegative() ? Duration.ZERO : delay);
            }
        } catch (RuntimeException e) {
            log.error(
                    "failed to record Kafka publisher metric, eventRef={}, destination={}, source={}, outcome={}",
                    outbox.getEventRef(),
                    outbox.getDestination(),
                    source.metricValue(),
                    outcome,
                    e
            );
            // Metric registration/recording is isolated from the outbox delivery contract.
        }
    }

    private String resolveEventTypeSafely(Outbox outbox) {
        try {
            return MetricTagSanitizer.safeValue(eventTypeResolver.resolve(outbox.getEventType()));
        } catch (RuntimeException e) {
            log.error(
                    "failed to resolve Kafka publisher event type metric tag, eventRef={}, eventType={}",
                    outbox.getEventRef(),
                    outbox.getEventType(),
                    e
            );
            return CommonMetricTagValues.UNKNOWN;
        }
    }

    private PublisherMeters registerMeters(PublisherMetricKey key) {
        Counter requests = Counter.builder(CommonMetrics.KAFKA_PUBLISHER_REQUESTS.name())
                .description(CommonMetrics.KAFKA_PUBLISHER_REQUESTS.description())
                .tag(CommonMetricTags.TOPIC, key.topic())
                .tag(CommonMetricTags.SOURCE, key.source())
                .tag(CommonMetricTags.OUTCOME, key.outcome())
                .register(meterRegistry);
        Timer duration = Timer.builder(CommonMetrics.KAFKA_PUBLISHER_DURATION.name())
                .description(CommonMetrics.KAFKA_PUBLISHER_DURATION.description())
                .tag(CommonMetricTags.TOPIC, key.topic())
                .tag(CommonMetricTags.SOURCE, key.source())
                .tag(CommonMetricTags.OUTCOME, key.outcome())
                .register(meterRegistry);
        Timer deliveryDelay = Timer.builder(CommonMetrics.OUTBOX_DELIVERY_DELAY.name())
                .description(CommonMetrics.OUTBOX_DELIVERY_DELAY.description())
                .tag(CommonMetricTags.EVENT_TYPE, key.eventType())
                .tag(CommonMetricTags.SOURCE, key.source())
                .tag(CommonMetricTags.OUTCOME, key.outcome())
                .register(meterRegistry);
        return new PublisherMeters(requests, duration, deliveryDelay);
    }

    /*
     * Successful sends are the hot path, so their meter instances are cached. Failure meters are
     * looked up only on the rare error path to avoid an unnecessary general-purpose metric cache.
     */
    private record PublisherMetricKey(String topic, String eventType, String source, String outcome) {
    }

    private record PublisherMeters(Counter requests, Timer duration, Timer deliveryDelay) {
    }
}
