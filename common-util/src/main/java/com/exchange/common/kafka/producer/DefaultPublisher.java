package com.exchange.common.kafka.producer;

import com.exchange.common.outbox.po.Outbox;
import com.exchange.common.result.Result;
import com.exchange.common.result.error.KafkaProducerErrorCode;
import com.exchange.common.utils.time.LongTimeHelper;
import com.exchange.proto.common.event.EventEnvelopePb;
import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.util.concurrent.ListenableFuture;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Common Kafka transport for service-owned outbox events.
 *
 * <p>Each service supplies one {@link OutboxEventTypeResolver} covering its event-type namespace.
 * This publisher resolves the wire event name, builds the common envelope, and maps the Kafka
 * acknowledgment, timeout, or producer error to a {@link Result}. It deliberately does not update
 * the outbox row; retry and listener workflows finalize their own rows only after a successful
 * broker acknowledgment.</p>
 */
@Slf4j
public class DefaultPublisher implements IPublisher {
    private static final Duration DEFAULT_PUBLISH_TIMEOUT = Duration.ofSeconds(3);

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final OutboxEventTypeResolver eventTypeResolver;
    private final Duration publishTimeout;

    public DefaultPublisher(
            KafkaTemplate<String, byte[]> kafkaTemplate,
            OutboxEventTypeResolver eventTypeResolver
    ) {
        this(kafkaTemplate, eventTypeResolver, DEFAULT_PUBLISH_TIMEOUT);
    }

    DefaultPublisher(
            KafkaTemplate<String, byte[]> kafkaTemplate,
            OutboxEventTypeResolver eventTypeResolver,
            Duration publishTimeout
    ) {
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate");
        this.eventTypeResolver = Objects.requireNonNull(eventTypeResolver, "eventTypeResolver");
        this.publishTimeout = Objects.requireNonNull(publishTimeout, "publishTimeout");
        if (publishTimeout.isZero() || publishTimeout.isNegative()) {
            throw new IllegalArgumentException("publish timeout must be positive");
        }
    }

    @Override
    public CompletableFuture<Result<Void>> publishAsync(Outbox outbox, PublishSource source) {
        Objects.requireNonNull(outbox, "outbox");
        Objects.requireNonNull(source, "source");

        CompletableFuture<Void> sendCompletion = new CompletableFuture<>();
        try {
            String eventType = eventTypeResolver.resolve(outbox.getEventType());
            if (eventType == null) {
                throw new IllegalArgumentException("unknown outbox event type: " + outbox.getEventType());
            }
            if (eventType.isBlank()) {
                throw new IllegalStateException("outbox event type resolver returned a blank event type");
            }

            byte[] envelopeBytes = EventEnvelopePb.newBuilder()
                    .setEventRef(outbox.getEventRef())
                    .setCommandRef(outbox.getCommandRef())
                    .setEventType(eventType)
                    .setPayload(ByteString.copyFrom(outbox.getPayload()))
                    .setOccurredAt(LongTimeHelper.now())
                    .build()
                    .toByteArray();
            ListenableFuture<SendResult<String, byte[]>> sendFuture = kafkaTemplate.send(
                    outbox.getDestination(),
                    outbox.getPartitionKey(),
                    envelopeBytes
            );
            // KafkaTemplate completion represents the broker acknowledgment boundary.
            sendFuture.addCallback(
                    ignored -> sendCompletion.complete(null),
                    sendCompletion::completeExceptionally
            );
        } catch (Exception e) {
            sendCompletion.completeExceptionally(e);
        }

        long timeoutMillis = publishTimeout.toMillis();
        return sendCompletion
                .orTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .handle((ignored, throwable) -> toPublishResult(outbox, timeoutMillis, throwable));
    }

    private Result<Void> toPublishResult(Outbox outbox, long timeoutMillis, Throwable throwable) {
        if (throwable == null) {
            return Result.success();
        }

        Throwable cause = unwrap(throwable);
        if (cause instanceof TimeoutException) {
            log.error(
                    "publish event timed out after {} ms, eventRef={}, commandRef={}, destination={}",
                    timeoutMillis,
                    outbox.getEventRef(),
                    outbox.getCommandRef(),
                    outbox.getDestination(),
                    cause
            );
        } else {
            log.error(
                    "publish event failed, eventRef={}, commandRef={}, destination={}",
                    outbox.getEventRef(),
                    outbox.getCommandRef(),
                    outbox.getDestination(),
                    cause
            );
        }
        return Result.failure(KafkaProducerErrorCode.PUBLISH_FAILED, outbox.getCommandRef());
    }

    private Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }
}
