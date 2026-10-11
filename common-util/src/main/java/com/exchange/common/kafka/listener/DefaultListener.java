package com.exchange.common.kafka.listener;

import com.exchange.common.exception.CustomizedException;
import com.exchange.common.exception.DbException;
import com.exchange.common.kafka.listener.action.ListenerAction;
import com.exchange.common.kafka.listener.exception.DlqException;
import com.exchange.common.kafka.listener.exception.KafkaListenerRetriableException;
import com.exchange.common.kafka.listener.failure.ListenerFailure;
import com.exchange.common.kafka.listener.handler.MessageHandler;
import com.exchange.common.kafka.listener.metrics.KafkaListenerMetrics;
import com.exchange.common.kafka.listener.metrics.KafkaListenerRequestOutcome;
import com.exchange.common.kafka.listener.outbox.OutboxInsertDecision;
import com.exchange.common.kafka.listener.retry.ListenerAttemptResolver;
import com.exchange.common.kafka.producer.IPublisher;
import com.exchange.common.outbox.dao.repository.OutboxRepository;
import com.exchange.common.outbox.po.Outbox;
import com.exchange.common.outbox.po.enums.OutboxStatus;
import com.exchange.common.result.Result;
import com.exchange.common.result.error.ErrorCode;
import com.exchange.common.result.error.KafkaListenerErrorCode;
import com.exchange.common.utils.time.LocalDateTimeHelper;
import com.exchange.proto.common.event.EventEnvelopePb;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.support.Acknowledgment;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Slf4j
public class DefaultListener {
    private static final String ACK_FAILED_HEADER = "zx-ack-failed";
    private static final byte[] ACK_FAILED_HEADER_VALUE = "true".getBytes(StandardCharsets.UTF_8);

    // Converts service-specific command handling into a normal listener action.
    private final MessageHandler messageHandler;
    private final OutboxRepository outboxRepository;
    private final IPublisher replyPublisher;
    // Hides Spring Kafka retry-topic header details from the listener workflow.
    private final ListenerAttemptResolver attemptResolver;
    // Controls when retryable listener failures become durable failure replies.
    private final ListenerRetryPolicy retryPolicy;
    // Public error used when an unexpected exception can still be converted into a reply.
    private final ErrorCode unexpectedErrorCode;
    // Keeps callback-side DB finalization off Kafka producer network threads.
    private final Executor publishCallbackExecutor;
    private final KafkaListenerMetrics listenerMetrics;

    public DefaultListener(
            MessageHandler messageHandler,
            OutboxRepository outboxRepository,
            IPublisher replyPublisher,
            ListenerAttemptResolver attemptResolver,
            ListenerRetryPolicy retryPolicy,
            ErrorCode unexpectedErrorCode,
            Executor publishCallbackExecutor
    ) {
        this(
                messageHandler,
                outboxRepository,
                replyPublisher,
                attemptResolver,
                retryPolicy,
                unexpectedErrorCode,
                publishCallbackExecutor,
                KafkaListenerMetrics.noop()
        );
    }

    public DefaultListener(
            MessageHandler messageHandler,
            OutboxRepository outboxRepository,
            IPublisher replyPublisher,
            ListenerAttemptResolver attemptResolver,
            ListenerRetryPolicy retryPolicy,
            ErrorCode unexpectedErrorCode,
            Executor publishCallbackExecutor,
            KafkaListenerMetrics listenerMetrics
    ) {
        this.messageHandler = Objects.requireNonNull(messageHandler, "messageHandler");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository");
        this.replyPublisher = Objects.requireNonNull(replyPublisher, "replyPublisher");
        this.attemptResolver = Objects.requireNonNull(attemptResolver, "attemptResolver");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.unexpectedErrorCode = Objects.requireNonNull(unexpectedErrorCode, "unexpectedErrorCode");
        this.publishCallbackExecutor = Objects.requireNonNull(publishCallbackExecutor, "publishCallbackExecutor");
        this.listenerMetrics = Objects.requireNonNull(listenerMetrics, "listenerMetrics");
    }

    /*
     * The listener acknowledges only after a durable outcome exists: either the handler
     * completed an ack-only action or a reply outbox row was inserted/verified. Parseable
     * failures are converted through MessageHandler.handleFailure so callers still receive
     * a reply. Retry/DLQ exceptions are reserved for cases where no durable outcome can be
     * produced yet, or where the message cannot identify a request.
     */
    public void onMessage(byte[] envelopeBytes, Acknowledgment ack, Headers headers) {
        onMessage(envelopeBytes, ack, headers, System.currentTimeMillis());
    }

    public void onMessage(byte[] envelopeBytes, Acknowledgment ack, Headers headers, long recordTimestamp) {
        long startedAtNanos = System.nanoTime();
        KafkaListenerRequestOutcome outcome = null;
        ListenerProcessingResult processingResult;
        recordProcessingDelaySafely(recordTimestamp, headers);
        try {
            processingResult = processMessage(envelopeBytes, ack, headers);
            outcome = processingResult.outcome();
        } catch (DlqException e) {
            /*
             * DLQ is a later Spring Kafka routing result, not an outcome known here. This
             * invocation only knows that the failure cannot be retried by the listener; the
             * service DLT handler records the separate DLT metric after delivery is confirmed.
             */
            outcome = KafkaListenerRequestOutcome.NON_RETRYABLE;
            log.error("listener dlq, errorCode={}, detail={}", e.getErrorCode(), e.getMessage(), e);
            throw e;
        } catch (KafkaListenerRetriableException e) {
            outcome = e.getErrorCode() == KafkaListenerErrorCode.ACK_FAILED
                    ? KafkaListenerRequestOutcome.ACK_FAILURE
                    : KafkaListenerRequestOutcome.RETRYABLE;
            throw e;
        } catch (RuntimeException e) {
            // An unclassified escaped failure is non-retryable under the listener contract.
            outcome = KafkaListenerRequestOutcome.NON_RETRYABLE;
            throw e;
        } finally {
            recordCompletionSafely(outcome, System.nanoTime() - startedAtNanos);
        }

        /*
         * Immediate publishing is a best-effort latency optimization after the durable listener
         * outcome has been acknowledged and measured. Keeping it outside the listener metric
         * boundary prevents publisher latency or future publisher failures from contaminating
         * listener duration and outcome metrics.
         */
        tryImmediatePublishSuppressingErrors(processingResult.outboxToPublish());
    }

    private void recordProcessingDelaySafely(long recordTimestamp, Headers headers) {
        try {
            listenerMetrics.recordProcessingDelay(recordTimestamp, headers);
        } catch (RuntimeException e) {
            log.error("failed to record Kafka listener processing delay metric", e);
            // Observability failures must not affect listener processing or acknowledgment.
        }
    }

    private void recordCompletionSafely(KafkaListenerRequestOutcome outcome, long durationNanos) {
        try {
            listenerMetrics.recordCompletion(outcome, durationNanos);
        } catch (RuntimeException e) {
            log.error("failed to record Kafka listener completion metric, outcome={}", outcome, e);
            // In particular, never mask the original retry/DLQ exception from the listener.
        }
    }

    private ListenerProcessingResult processMessage(
            byte[] envelopeBytes,
            Acknowledgment ack,
            Headers headers
    ) {
        EventEnvelopePb envelope = null;
        Outbox outboxToPublish;
        KafkaListenerRequestOutcome outcome = KafkaListenerRequestOutcome.SUCCESS;

        if (isAckFailureRetry(headers)) {
            /*
             * The previous attempt already made business/reply state durable and failed only
             * while acknowledging Kafka. On retry, acknowledge the retry record directly instead
             * of re-running idempotent business and outbox logic just to recover the offset.
             */
            acknowledgeOrThrowRetriable(ack, headers);
            return new ListenerProcessingResult(KafkaListenerRequestOutcome.SUCCESS, null);
        }

        try {
            envelope = parseEnvelopeOrThrowDlq(envelopeBytes);
            ListenerAction action = messageHandler.handle(envelope);
            outboxToPublish = persistActionOrThrow(action);
        } catch (DlqException e) {
            throw e;
        } catch (KafkaListenerRetriableException e) {
            outboxToPublish = handleRetriableFailureOrThrow(envelope, e, headers);
            outcome = KafkaListenerRequestOutcome.FAILURE_REPLIED;
        } catch (CustomizedException e) {
            ListenerFailure failure = ListenerFailure.nonRetryable(e.getErrorCode(), e.getMessage(), e);
            ListenerAction action = messageHandler.handleFailure(requireEnvelope(envelope), failure);
            outboxToPublish = persistActionOrThrow(action);
            outcome = KafkaListenerRequestOutcome.FAILURE_REPLIED;
        } catch (Exception e) {
            ListenerFailure failure = ListenerFailure.unexpected(
                    unexpectedErrorCode,
                    "unexpected listener failure",
                    e
            );
            ListenerAction action = messageHandler.handleFailure(requireEnvelope(envelope), failure);
            outboxToPublish = persistActionOrThrow(action);
            outcome = KafkaListenerRequestOutcome.FAILURE_REPLIED;
        }

        /*
         * Business/reply state is durable before acknowledging the consumed record. The caller
         * performs best-effort immediate publishing only after listener metrics are finalized.
         */
        acknowledgeOrThrowRetriable(ack, headers);
        return new ListenerProcessingResult(outcome, outboxToPublish);
    }

    private record ListenerProcessingResult(
            KafkaListenerRequestOutcome outcome,
            Outbox outboxToPublish
    ) {
    }

    /*
     * If the outer envelope cannot be parsed, the listener cannot know the event reference,
     * command reference, or reply contract. There is no safe failure reply to create, so this is
     * routed to DLQ instead of being downgraded into a business failure.
     */
    private EventEnvelopePb parseEnvelopeOrThrowDlq(byte[] envelopeBytes) {
        try {
            return EventEnvelopePb.parseFrom(envelopeBytes);
        } catch (InvalidProtocolBufferException e) {
            throw new DlqException(
                    KafkaListenerErrorCode.ENVELOPE_PARSE_ERROR,
                    "cannot parse event envelope",
                    e
            );
        }
    }

    /*
     * Retryable failures remain Kafka retries until the reply threshold. After that
     * threshold, a durable retry-exhausted reply is persisted so the upstream is not
     * left waiting forever. If that durable reply still cannot be produced, this method
     * rethrows and lets Spring RetryableTopic own the final DLT routing.
     */
    private Outbox handleRetriableFailureOrThrow(
            EventEnvelopePb envelope,
            KafkaListenerRetriableException e,
            Headers headers
    ) {
        EventEnvelopePb requiredEnvelope = requireEnvelope(envelope);
        int attempt = attemptResolver.resolve(headers);

        if (attempt < retryPolicy.replyFailureAfterAttempts()) {
            throw e;
        }

        ListenerFailure failure = ListenerFailure.retryExhausted(
                KafkaListenerErrorCode.RETRY_EXHAUSTED,
                e.getMessage(),
                e
        );
        ListenerAction action = messageHandler.handleFailure(requiredEnvelope, failure);
        return persistActionOrThrow(action);
    }

    private EventEnvelopePb requireEnvelope(EventEnvelopePb envelope) {
        if (envelope == null) {
            throw new DlqException(
                    KafkaListenerErrorCode.ENVELOPE_PARSE_ERROR,
                    "cannot identify request because event envelope is unavailable"
            );
        }
        return envelope;
    }

    private Outbox persistActionOrThrow(ListenerAction action) {
        Objects.requireNonNull(action, "action");
        if (action instanceof ListenerAction.AckOnly) {
            return null;
        }
        if (action instanceof ListenerAction.OutboxReply(Outbox outbox)) {
            return insertReplyOutboxOrThrowListenerException(outbox);
        }
        throw new DlqException(KafkaListenerErrorCode.LISTENER_INTERNAL_ERROR, "unknown listener action: " + action);
    }

    /*
     * Reply outbox persistence is the durable boundary before ack. Duplicate delivery is
     * accepted only when the existing row has the same intent; missing rows after an
     * insert-ignore conflict are ambiguous and retryable, while conflicting intent is a
     * contract/data error for DLQ.
     */
    private Outbox insertReplyOutboxOrThrowListenerException(Outbox outbox) {
        try {
            OutboxInsertDecision decision = outboxRepository.insertWithClaimAndVerify(
                    outbox,
                    LocalDateTimeHelper.nowAfterMs(retryPolicy.baseAttemptIntervalMs())
            );
            return switch (decision.type()) {
                case INSERTED -> outbox;
                case EXISTING_SAME_INTENT -> decision.existing();
                case EXISTING_MISSING_AMBIGUOUS -> throw new KafkaListenerRetriableException(
                        KafkaListenerErrorCode.REPLY_OUTBOX_AMBIGUOUS,
                        "reply outbox insert affected 0 rows but existing row is missing, eventRef=" + outbox.getEventRef()
                );
                case EXISTING_CONFLICT -> throw new DlqException(
                        KafkaListenerErrorCode.REPLY_OUTBOX_CONFLICT,
                        "reply outbox event reference conflicts with different intent, eventRef=" + outbox.getEventRef()
                );
            };
        } catch (KafkaListenerRetriableException | DlqException e) {
            throw e;
        } catch (DbException e) {
            if (e.isRetryable()) {
                throw new KafkaListenerRetriableException(
                        e.getErrorCode(),
                        "retryable DB failure while inserting reply outbox",
                        e
                );
            }
            throw new DlqException(e.getErrorCode(), "non-retryable DB failure while inserting reply outbox", e);
        }
    }

    private boolean isAckFailureRetry(Headers headers) {
        return headers != null && headers.lastHeader(ACK_FAILED_HEADER) != null;
    }

    private void acknowledgeOrThrowRetriable(Acknowledgment ack, Headers headers) {
        try {
            ack.acknowledge();
        } catch (Exception e) {
            markAckFailure(headers);
            /*
             * Acknowledgment is the Kafka offset boundary. By the time this method is called,
             * the normal reply or failure reply has already been made durable, so an ack failure
             * must not be converted into a business failure. Retrying lets Kafka redeliver the
             * record, while idempotent business logic and outbox verification prevent duplicate
             * effects.
             */
            throw new KafkaListenerRetriableException(
                    KafkaListenerErrorCode.ACK_FAILED,
                    "failed to acknowledge Kafka record after durable listener processing",
                    e
            );
        }
    }

    private void markAckFailure(Headers headers) {
        if (headers == null || isAckFailureRetry(headers)) {
            return;
        }
        headers.add(ACK_FAILED_HEADER, ACK_FAILED_HEADER_VALUE);
    }

    /*
     * Immediate publish is best-effort because the source record has already been acknowledged
     * and the reply outbox row is durable. The asynchronous callback runs on a dedicated executor
     * so database work never blocks Kafka producer network threads. A broker-acknowledged send is
     * finalized as SENT; every timeout, producer error, callback error, or finalization error is
     * logged and left for the outbox retry worker.
     *
     * At-least-once delivery still applies: a broker acknowledgment followed by a failed status
     * update can cause a later retry and duplicate delivery. Reply consumers must be idempotent.
     */
    private void tryImmediatePublishSuppressingErrors(Outbox outbox) {
        // TODO Carry the insert decision here if existing rows should skip this immediate attempt.
        if (outbox == null) {
            return;
        }
        if (outbox.getOutboxStatus() != OutboxStatus.PENDING) {
            log.info(
                    "skip immediate publish for non-pending outbox, eventRef={}, status={}",
                    outbox.getEventRef(),
                    outbox.getOutboxStatus()
            );
            return;
        }

        try {
            CompletableFuture<Result<Void>> publishFuture = replyPublisher.publishAsync(outbox);
            if (publishFuture == null) {
                log.error(
                        "immediate reply publish returned null future, eventRef={}, commandRef={}",
                        outbox.getEventRef(),
                        outbox.getCommandRef()
                );
                return;
            }
            publishFuture.whenCompleteAsync(
                    (result, throwable) -> handleImmediatePublishCompletion(outbox, result, throwable),
                    publishCallbackExecutor
            ).exceptionally(throwable -> {
                log.error(
                        "immediate reply publish callback failed, eventRef={}, commandRef={}",
                        outbox.getEventRef(),
                        outbox.getCommandRef(),
                        throwable
                );
                return null;
            });
        } catch (Exception e) {
            log.error(
                    "immediate reply publish setup threw exception, eventRef={}, commandRef={}",
                    outbox.getEventRef(),
                    outbox.getCommandRef(),
                    e
            );
        }
    }

    private void handleImmediatePublishCompletion(Outbox outbox, Result<Void> result, Throwable throwable) {
        if (throwable != null) {
            log.error(
                    "immediate reply publish completed exceptionally, eventRef={}, commandRef={}",
                    outbox.getEventRef(),
                    outbox.getCommandRef(),
                    throwable
            );
            return;
        }
        if (result == null) {
            log.error(
                    "immediate reply publish completed with null result, eventRef={}, commandRef={}",
                    outbox.getEventRef(),
                    outbox.getCommandRef()
            );
            return;
        }
        if (result.isSuccess()) {
            finalizeSentSuppressingErrors(outbox);
            return;
        }
        log.error(
                "immediate reply publish failed, eventRef={}, commandRef={}, errorCode={}, detail={}",
                outbox.getEventRef(),
                outbox.getCommandRef(),
                result.getErrorCode(),
                result.getDetail()
        );
    }

    private void finalizeSentSuppressingErrors(Outbox outbox) {
        try {
            int updated = outboxRepository.updateStatusToFinalize(outbox, OutboxStatus.SENT, LocalDateTime.now());
            if (updated != 1) {
                log.error("finalize reply outbox failed, eventRef={}, updated={}", outbox.getEventRef(), updated);
            }
        } catch (Exception e) {
            log.error("finalize reply outbox threw exception, eventRef={}", outbox.getEventRef(), e);
        }
    }
}
