package com.exchange.common.kafka.listener;

import com.exchange.common.exception.CustomizedException;
import com.exchange.common.exception.DbException;
import com.exchange.common.kafka.listener.action.ListenerAction;
import com.exchange.common.kafka.listener.exception.DlqException;
import com.exchange.common.kafka.listener.exception.KafkaListenerRetriableException;
import com.exchange.common.kafka.listener.failure.ListenerFailure;
import com.exchange.common.kafka.listener.failure.ListenerFailureType;
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
import com.exchange.common.result.error.DbErrorCode;
import com.exchange.common.result.error.KafkaListenerErrorCode;
import com.exchange.proto.common.event.EventEnvelopePb;
import com.google.protobuf.ByteString;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultListenerTest {
    private static final String REQUEST_EVENT_REF = "wallet-request-event-ref";
    private static final String COMMAND_REF = "wallet-transaction-ref";
    private static final String ACK_FAILED_HEADER = "zx-ack-failed";
    private static final int REPLY_FAILURE_AFTER_ATTEMPTS = 5;

    @Mock
    private MessageHandler messageHandler;
    @Mock
    private OutboxRepository outboxRepository;
    @Mock
    private IPublisher replyPublisher;
    @Mock
    private ListenerAttemptResolver attemptResolver;
    @Mock
    private KafkaListenerMetrics listenerMetrics;
    @Mock
    private Acknowledgment ack;

    private DefaultListener listener;
    private RecordHeaders headers;
    private EventEnvelopePb envelope;
    private byte[] envelopeBytes;
    private Outbox replyOutbox;

    @BeforeEach
    void setUp() {
        listener = new DefaultListener(
                messageHandler,
                outboxRepository,
                replyPublisher,
                attemptResolver,
                new ListenerRetryPolicy(1000, REPLY_FAILURE_AFTER_ATTEMPTS),
                KafkaListenerErrorCode.LISTENER_INTERNAL_ERROR,
                Runnable::run,
                listenerMetrics
        );
        headers = new RecordHeaders();
        envelope = envelope();
        envelopeBytes = envelope.toByteArray();
        replyOutbox = pendingOutbox("ledger-reply-event-ref");
    }

    @Test
    void cannotParseEnvelopeGoesToDlqWithoutAckOrReply() {
        // KL-DL-001: without a parseable envelope, the listener cannot identify the request.
        DlqException thrown = assertThrows(
                DlqException.class,
                () -> listener.onMessage(new byte[]{1, 2, 3}, ack, headers)
        );

        assertEquals(KafkaListenerErrorCode.ENVELOPE_PARSE_ERROR, thrown.getErrorCode());
        verifyNoInteractions(messageHandler, outboxRepository, replyPublisher, ack);
        verify(listenerMetrics).recordCompletion(eq(KafkaListenerRequestOutcome.NON_RETRYABLE), anyLong());
    }

    @Test
    void businessSuccessPersistsReplyBeforeAckAndPublishesAfterAck() {
        // KL-DL-002: the durable reply outbox row must exist before the source record is acked.
        when(messageHandler.handle(envelope)).thenReturn(ListenerAction.reply(replyOutbox));
        when(outboxRepository.insertWithClaimAndVerify(eq(replyOutbox), any(LocalDateTime.class)))
                .thenReturn(OutboxInsertDecision.inserted(replyOutbox));
        when(replyPublisher.publishAsync(replyOutbox)).thenReturn(CompletableFuture.completedFuture(
                Result.<Void>failure(KafkaListenerErrorCode.LISTENER_INTERNAL_ERROR)
        ));

        listener.onMessage(envelopeBytes, ack, headers);

        InOrder order = inOrder(outboxRepository, ack, listenerMetrics, replyPublisher);
        order.verify(outboxRepository).insertWithClaimAndVerify(eq(replyOutbox), any(LocalDateTime.class));
        order.verify(ack).acknowledge();
        order.verify(listenerMetrics).recordCompletion(eq(KafkaListenerRequestOutcome.SUCCESS), anyLong());
        order.verify(replyPublisher).publishAsync(replyOutbox);
    }

    @Test
    void ackOnlySuccessDoesNotTouchOutboxOrPublisher() {
        // KL-DL-003: ack-only handlers complete durable work inside the handler itself.
        when(messageHandler.handle(envelope)).thenReturn(ListenerAction.ack());

        listener.onMessage(envelopeBytes, ack, headers);

        verify(ack).acknowledge();
        verifyNoInteractions(outboxRepository, replyPublisher);
    }

    @Test
    void metricFailureDoesNotChangeListenerBehavior() {
        // Observability is best-effort and cannot block a valid acknowledgment.
        when(messageHandler.handle(envelope)).thenReturn(ListenerAction.ack());
        doThrow(new RuntimeException("registry unavailable"))
                .when(listenerMetrics)
                .recordProcessingDelay(anyLong(), eq(headers));
        doThrow(new RuntimeException("registry unavailable"))
                .when(listenerMetrics)
                .recordCompletion(eq(KafkaListenerRequestOutcome.SUCCESS), anyLong());

        assertDoesNotThrow(() -> listener.onMessage(envelopeBytes, ack, headers));

        verify(ack).acknowledge();
    }

    @Test
    void customizedFailureCreatesNonRetryableFailureReply() {
        // KL-DL-004/KL-DL-005: parseable business failures become failed replies, not Kafka retries.
        CustomizedException failure = new CustomizedException(
                KafkaListenerErrorCode.LISTENER_INTERNAL_ERROR,
                "payload is invalid"
        );
        Outbox failedOutbox = pendingOutbox("ledger-failure-event-ref");
        when(messageHandler.handle(envelope)).thenThrow(failure);
        when(messageHandler.handleFailure(eq(envelope), any(ListenerFailure.class)))
                .thenReturn(ListenerAction.reply(failedOutbox));
        when(outboxRepository.insertWithClaimAndVerify(eq(failedOutbox), any(LocalDateTime.class)))
                .thenReturn(OutboxInsertDecision.inserted(failedOutbox));
        when(replyPublisher.publishAsync(failedOutbox)).thenReturn(CompletableFuture.completedFuture(
                Result.<Void>failure(KafkaListenerErrorCode.LISTENER_INTERNAL_ERROR)
        ));

        listener.onMessage(envelopeBytes, ack, headers);

        ArgumentCaptor<ListenerFailure> captor = ArgumentCaptor.forClass(ListenerFailure.class);
        verify(messageHandler).handleFailure(eq(envelope), captor.capture());
        assertEquals(ListenerFailureType.NON_RETRYABLE, captor.getValue().type());
        assertEquals(KafkaListenerErrorCode.LISTENER_INTERNAL_ERROR, captor.getValue().errorCode());
        verify(ack).acknowledge();
        verify(listenerMetrics).recordCompletion(eq(KafkaListenerRequestOutcome.FAILURE_REPLIED), anyLong());
        verify(replyPublisher).publishAsync(failedOutbox);
    }

    @Test
    void unexpectedExceptionCreatesInternalFailureReply() {
        // KL-DL-006: unconverted server bugs are reported upstream as bounded internal failures.
        Outbox failedOutbox = pendingOutbox("ledger-failure-event-ref");
        when(messageHandler.handle(envelope)).thenThrow(new NullPointerException("bug"));
        when(messageHandler.handleFailure(eq(envelope), any(ListenerFailure.class)))
                .thenReturn(ListenerAction.reply(failedOutbox));
        when(outboxRepository.insertWithClaimAndVerify(eq(failedOutbox), any(LocalDateTime.class)))
                .thenReturn(OutboxInsertDecision.inserted(failedOutbox));
        when(replyPublisher.publishAsync(failedOutbox)).thenReturn(CompletableFuture.completedFuture(
                Result.<Void>failure(KafkaListenerErrorCode.LISTENER_INTERNAL_ERROR)
        ));

        listener.onMessage(envelopeBytes, ack, headers);

        ArgumentCaptor<ListenerFailure> captor = ArgumentCaptor.forClass(ListenerFailure.class);
        verify(messageHandler).handleFailure(eq(envelope), captor.capture());
        assertEquals(ListenerFailureType.UNEXPECTED, captor.getValue().type());
        assertEquals(KafkaListenerErrorCode.LISTENER_INTERNAL_ERROR, captor.getValue().errorCode());
        verify(ack).acknowledge();
        verify(listenerMetrics).recordCompletion(eq(KafkaListenerRequestOutcome.FAILURE_REPLIED), anyLong());
    }

    @Test
    void escapedUnclassifiedExceptionIsRecordedAsNonRetryable() {
        // No durable failure reply exists when the failure handler itself escapes unexpectedly.
        when(messageHandler.handle(envelope)).thenThrow(new NullPointerException("business bug"));
        when(messageHandler.handleFailure(eq(envelope), any(ListenerFailure.class)))
                .thenThrow(new IllegalStateException("failure conversion bug"));

        assertThrows(
                IllegalStateException.class,
                () -> listener.onMessage(envelopeBytes, ack, headers)
        );

        verify(listenerMetrics).recordCompletion(eq(KafkaListenerRequestOutcome.NON_RETRYABLE), anyLong());
        verifyNoInteractions(outboxRepository, replyPublisher, ack);
    }

    @Test
    void retryableFailureBeforeReplyThresholdRethrowsWithoutAckOrReply() {
        // KL-DL-007: before the configured threshold, retryable failures remain Kafka retries.
        KafkaListenerRetriableException retryable = new KafkaListenerRetriableException(
                KafkaListenerErrorCode.REPLY_OUTBOX_AMBIGUOUS,
                "retry later"
        );
        when(messageHandler.handle(envelope)).thenThrow(retryable);
        when(attemptResolver.resolve(headers)).thenReturn(4);

        KafkaListenerRetriableException thrown = assertThrows(
                KafkaListenerRetriableException.class,
                () -> listener.onMessage(envelopeBytes, ack, headers)
        );

        assertSame(retryable, thrown);
        verify(listenerMetrics).recordCompletion(eq(KafkaListenerRequestOutcome.RETRYABLE), anyLong());
        verify(messageHandler, never()).handleFailure(eq(envelope), any());
        verifyNoInteractions(outboxRepository, replyPublisher, ack);
    }

    @Test
    void retryableFailureAtReplyThresholdCreatesRetryExhaustedReply() {
        // KL-DL-008: at the threshold, upstream receives a durable retry-exhausted reply.
        KafkaListenerRetriableException retryable = new KafkaListenerRetriableException(
                KafkaListenerErrorCode.REPLY_OUTBOX_AMBIGUOUS,
                "retry later"
        );
        Outbox failedOutbox = pendingOutbox("ledger-retry-exhausted-event-ref");
        when(messageHandler.handle(envelope)).thenThrow(retryable);
        when(attemptResolver.resolve(headers)).thenReturn(REPLY_FAILURE_AFTER_ATTEMPTS);
        when(messageHandler.handleFailure(eq(envelope), any(ListenerFailure.class)))
                .thenReturn(ListenerAction.reply(failedOutbox));
        when(outboxRepository.insertWithClaimAndVerify(eq(failedOutbox), any(LocalDateTime.class)))
                .thenReturn(OutboxInsertDecision.inserted(failedOutbox));
        when(replyPublisher.publishAsync(failedOutbox)).thenReturn(CompletableFuture.completedFuture(
                Result.<Void>failure(KafkaListenerErrorCode.LISTENER_INTERNAL_ERROR)
        ));

        listener.onMessage(envelopeBytes, ack, headers);

        ArgumentCaptor<ListenerFailure> captor = ArgumentCaptor.forClass(ListenerFailure.class);
        verify(messageHandler).handleFailure(eq(envelope), captor.capture());
        assertEquals(ListenerFailureType.RETRY_EXHAUSTED, captor.getValue().type());
        assertEquals(KafkaListenerErrorCode.RETRY_EXHAUSTED, captor.getValue().errorCode());
        verify(ack).acknowledge();
        verify(listenerMetrics).recordCompletion(eq(KafkaListenerRequestOutcome.FAILURE_REPLIED), anyLong());
        verify(replyPublisher).publishAsync(failedOutbox);
    }

    @Test
    void retryableFailureAboveReplyThresholdStillCreatesFailureReply() {
        // KL-DL-009: no listener-level DLQ ceiling exists; RetryableTopic owns final DLT routing.
        KafkaListenerRetriableException retryable = new KafkaListenerRetriableException(
                KafkaListenerErrorCode.REPLY_OUTBOX_AMBIGUOUS,
                "retry later"
        );
        Outbox failedOutbox = pendingOutbox("ledger-retry-exhausted-event-ref");
        when(messageHandler.handle(envelope)).thenThrow(retryable);
        when(attemptResolver.resolve(headers)).thenReturn(6);
        when(messageHandler.handleFailure(eq(envelope), any(ListenerFailure.class)))
                .thenReturn(ListenerAction.reply(failedOutbox));
        when(outboxRepository.insertWithClaimAndVerify(eq(failedOutbox), any(LocalDateTime.class)))
                .thenReturn(OutboxInsertDecision.inserted(failedOutbox));
        when(replyPublisher.publishAsync(failedOutbox)).thenReturn(CompletableFuture.completedFuture(
                Result.<Void>failure(KafkaListenerErrorCode.LISTENER_INTERNAL_ERROR)
        ));

        listener.onMessage(envelopeBytes, ack, headers);

        verify(ack).acknowledge();
        verify(replyPublisher).publishAsync(failedOutbox);
    }

    @Test
    void retryExhaustedReplyPersistenceRetryableDbFailureKeepsRetrying() {
        // KL-DL-010/KL-DL-014: retryable DB failure means no durable reply exists yet.
        KafkaListenerRetriableException retryable = new KafkaListenerRetriableException(
                KafkaListenerErrorCode.REPLY_OUTBOX_AMBIGUOUS,
                "retry later"
        );
        when(messageHandler.handle(envelope)).thenThrow(retryable);
        when(attemptResolver.resolve(headers)).thenReturn(REPLY_FAILURE_AFTER_ATTEMPTS);
        when(messageHandler.handleFailure(eq(envelope), any(ListenerFailure.class)))
                .thenReturn(ListenerAction.reply(replyOutbox));
        when(outboxRepository.insertWithClaimAndVerify(eq(replyOutbox), any(LocalDateTime.class)))
                .thenThrow(DbException.unavailable("insert reply outbox", new RuntimeException("down")));

        KafkaListenerRetriableException thrown = assertThrows(
                KafkaListenerRetriableException.class,
                () -> listener.onMessage(envelopeBytes, ack, headers)
        );

        assertEquals(DbErrorCode.DB_UNAVAILABLE, thrown.getErrorCode());
        verifyNoInteractions(replyPublisher, ack);
    }

    @Test
    void duplicateSameIntentPendingOutboxIsIdempotentAndPublished() {
        // KL-DL-011: same-intent duplicate is a successful replay of the durable reply.
        Outbox existing = pendingOutbox(replyOutbox.getEventRef());
        when(messageHandler.handle(envelope)).thenReturn(ListenerAction.reply(replyOutbox));
        when(outboxRepository.insertWithClaimAndVerify(eq(replyOutbox), any(LocalDateTime.class)))
                .thenReturn(OutboxInsertDecision.existingSameIntent(replyOutbox, existing));
        when(replyPublisher.publishAsync(existing)).thenReturn(CompletableFuture.completedFuture(
                Result.<Void>failure(KafkaListenerErrorCode.LISTENER_INTERNAL_ERROR)
        ));

        listener.onMessage(envelopeBytes, ack, headers);

        verify(ack).acknowledge();
        verify(replyPublisher).publishAsync(existing);
    }

    @Test
    void duplicateSameIntentSentOutboxIsAckedAndImmediatePublishIsSkipped() {
        // KL-DL-011: non-pending duplicate has already left the immediate-publish path.
        Outbox existing = pendingOutbox(replyOutbox.getEventRef());
        existing.setOutboxStatus(OutboxStatus.SENT);
        when(messageHandler.handle(envelope)).thenReturn(ListenerAction.reply(replyOutbox));
        when(outboxRepository.insertWithClaimAndVerify(eq(replyOutbox), any(LocalDateTime.class)))
                .thenReturn(OutboxInsertDecision.existingSameIntent(replyOutbox, existing));

        listener.onMessage(envelopeBytes, ack, headers);

        verify(ack).acknowledge();
        verifyNoInteractions(replyPublisher);
    }

    @Test
    void duplicateConflictingOutboxGoesToDlq() {
        // KL-DL-012: same event id with different intent is a data/contract conflict.
        Outbox existing = pendingOutbox(replyOutbox.getEventRef());
        existing.setPayload("different".getBytes());
        when(messageHandler.handle(envelope)).thenReturn(ListenerAction.reply(replyOutbox));
        when(outboxRepository.insertWithClaimAndVerify(eq(replyOutbox), any(LocalDateTime.class)))
                .thenReturn(OutboxInsertDecision.existingConflict(replyOutbox, existing));

        DlqException thrown = assertThrows(DlqException.class, () -> listener.onMessage(envelopeBytes, ack, headers));

        assertEquals(KafkaListenerErrorCode.REPLY_OUTBOX_CONFLICT, thrown.getErrorCode());
        verifyNoInteractions(replyPublisher, ack);
    }

    @Test
    void ambiguousOutboxInsertRetries() {
        // KL-DL-013: insert affected zero rows but lookup missed the row, so DB state is ambiguous.
        when(messageHandler.handle(envelope)).thenReturn(ListenerAction.reply(replyOutbox));
        when(outboxRepository.insertWithClaimAndVerify(eq(replyOutbox), any(LocalDateTime.class)))
                .thenReturn(OutboxInsertDecision.existingMissingAmbiguous(replyOutbox));

        KafkaListenerRetriableException thrown = assertThrows(
                KafkaListenerRetriableException.class,
                () -> listener.onMessage(envelopeBytes, ack, headers)
        );

        assertEquals(KafkaListenerErrorCode.REPLY_OUTBOX_AMBIGUOUS, thrown.getErrorCode());
        verifyNoInteractions(replyPublisher, ack);
    }

    @Test
    void nonRetryableDbFailureDuringOutboxInsertGoesToDlq() {
        // KL-DL-015: non-retryable DB errors should not spin Kafka retries.
        when(messageHandler.handle(envelope)).thenReturn(ListenerAction.reply(replyOutbox));
        when(outboxRepository.insertWithClaimAndVerify(eq(replyOutbox), any(LocalDateTime.class)))
                .thenThrow(DbException.internal("insert reply outbox", new RuntimeException("bad schema")));

        DlqException thrown = assertThrows(DlqException.class, () -> listener.onMessage(envelopeBytes, ack, headers));

        assertEquals(DbErrorCode.DB_INTERNAL, thrown.getErrorCode());
        verifyNoInteractions(replyPublisher, ack);
    }

    @Test
    void ackFailureAfterDurableOutcomeMarksHeaderAndRetriesWithoutPublish() {
        // KL-DL-016: ack failure is offset recovery, not a business failure reply.
        when(messageHandler.handle(envelope)).thenReturn(ListenerAction.reply(replyOutbox));
        when(outboxRepository.insertWithClaimAndVerify(eq(replyOutbox), any(LocalDateTime.class)))
                .thenReturn(OutboxInsertDecision.inserted(replyOutbox));
        doThrow(new RuntimeException("commit failed")).when(ack).acknowledge();

        KafkaListenerRetriableException thrown = assertThrows(
                KafkaListenerRetriableException.class,
                () -> listener.onMessage(envelopeBytes, ack, headers)
        );

        assertEquals(KafkaListenerErrorCode.ACK_FAILED, thrown.getErrorCode());
        verify(listenerMetrics).recordCompletion(eq(KafkaListenerRequestOutcome.ACK_FAILURE), anyLong());
        Header header = headers.lastHeader(ACK_FAILED_HEADER);
        assertNotNull(header);
        assertEquals("true", new String(header.value()));
        verifyNoInteractions(replyPublisher);
    }

    @Test
    void ackFailedRetrySkipsBusinessAndOutboxThenAcknowledges() {
        // KL-DL-017: the durable outcome already exists, so the retry only recovers the offset.
        headers.add(ACK_FAILED_HEADER, "true".getBytes());

        listener.onMessage(new byte[]{1, 2, 3}, ack, headers);

        verify(ack).acknowledge();
        verifyNoInteractions(messageHandler, outboxRepository, replyPublisher);
    }

    @Test
    void ackFailedRetryRetriesAgainIfAckFailsAgain() {
        // KL-DL-018: repeated ack failure remains retryable and must not duplicate business work.
        headers.add(ACK_FAILED_HEADER, "true".getBytes());
        doThrow(new RuntimeException("commit failed again")).when(ack).acknowledge();

        KafkaListenerRetriableException thrown = assertThrows(
                KafkaListenerRetriableException.class,
                () -> listener.onMessage(new byte[]{1, 2, 3}, ack, headers)
        );

        assertEquals(KafkaListenerErrorCode.ACK_FAILED, thrown.getErrorCode());
        long headerCount = StreamSupport.stream(headers.headers(ACK_FAILED_HEADER).spliterator(), false).count();
        assertEquals(1, headerCount);
        verifyNoInteractions(messageHandler, outboxRepository, replyPublisher);
    }

    @Test
    void immediatePublishFailureResultIsSuppressed() {
        // KL-DL-019: once the source is acked, outbox retry owns publish recovery.
        stubInsertedReply();
        when(replyPublisher.publishAsync(replyOutbox)).thenReturn(CompletableFuture.completedFuture(
                Result.<Void>failure(KafkaListenerErrorCode.LISTENER_INTERNAL_ERROR)
        ));

        assertDoesNotThrow(() -> listener.onMessage(envelopeBytes, ack, headers));

        verify(outboxRepository, never()).updateStatusToFinalize(any(), eq(OutboxStatus.SENT), any(LocalDateTime.class));
    }

    @Test
    void immediatePublishDoesNotWaitForBrokerAcknowledgment() {
        // The Kafka listener thread must return while the producer send is still in flight.
        stubInsertedReply();
        CompletableFuture<Result<Void>> publishFuture = new CompletableFuture<>();
        when(replyPublisher.publishAsync(replyOutbox)).thenReturn(publishFuture);

        listener.onMessage(envelopeBytes, ack, headers);

        verify(ack).acknowledge();
        verify(outboxRepository, never())
                .updateStatusToFinalize(any(), eq(OutboxStatus.SENT), any(LocalDateTime.class));

        publishFuture.complete(Result.success());

        verify(outboxRepository)
                .updateStatusToFinalize(eq(replyOutbox), eq(OutboxStatus.SENT), any(LocalDateTime.class));
    }

    @Test
    void immediatePublishExceptionalCompletionLeavesOutboxPending() {
        // Timeout/producer exceptions are observed asynchronously; the outbox retry worker owns recovery.
        stubInsertedReply();
        when(replyPublisher.publishAsync(replyOutbox)).thenReturn(
                CompletableFuture.failedFuture(new TimeoutException("broker acknowledgment timed out"))
        );

        assertDoesNotThrow(() -> listener.onMessage(envelopeBytes, ack, headers));

        verify(outboxRepository, never())
                .updateStatusToFinalize(any(), eq(OutboxStatus.SENT), any(LocalDateTime.class));
    }

    @Test
    void immediatePublishExceptionIsSuppressed() {
        // KL-DL-020: publisher exceptions after ack are logged and left to outbox retry.
        stubInsertedReply();
        doThrow(new RuntimeException("producer down")).when(replyPublisher).publishAsync(replyOutbox);

        assertDoesNotThrow(() -> listener.onMessage(envelopeBytes, ack, headers));

        verify(outboxRepository, never()).updateStatusToFinalize(any(), eq(OutboxStatus.SENT), any(LocalDateTime.class));
    }

    @Test
    void immediatePublishSuccessFinalizesOutbox() {
        // KL-DL-021: successful immediate publish should mark the durable reply as sent.
        stubInsertedReply();
        when(replyPublisher.publishAsync(replyOutbox)).thenReturn(CompletableFuture.completedFuture(Result.success()));

        listener.onMessage(envelopeBytes, ack, headers);

        verify(outboxRepository).updateStatusToFinalize(eq(replyOutbox), eq(OutboxStatus.SENT), any(LocalDateTime.class));
    }

    @Test
    void finalizeSentFailureIsSuppressed() {
        // KL-DL-022: finalization failure must not turn an already-published reply into source retry.
        stubInsertedReply();
        when(replyPublisher.publishAsync(replyOutbox)).thenReturn(CompletableFuture.completedFuture(Result.success()));
        doThrow(new RuntimeException("db down"))
                .when(outboxRepository)
                .updateStatusToFinalize(eq(replyOutbox), eq(OutboxStatus.SENT), any(LocalDateTime.class));

        assertDoesNotThrow(() -> listener.onMessage(envelopeBytes, ack, headers));
    }

    private void stubInsertedReply() {
        when(messageHandler.handle(envelope)).thenReturn(ListenerAction.reply(replyOutbox));
        when(outboxRepository.insertWithClaimAndVerify(eq(replyOutbox), any(LocalDateTime.class)))
                .thenReturn(OutboxInsertDecision.inserted(replyOutbox));
    }

    private EventEnvelopePb envelope() {
        return EventEnvelopePb.newBuilder()
                .setEventRef(REQUEST_EVENT_REF)
                .setCommandRef(COMMAND_REF)
                .setEventType("POST_LEDGER")
                .setPayload(ByteString.copyFromUtf8("payload"))
                .build();
    }

    private Outbox pendingOutbox(String eventRef) {
        return Outbox.create(
                1L,
                1,
                eventRef,
                COMMAND_REF,
                OutboxStatus.PENDING,
                "reply-topic",
                COMMAND_REF,
                ("payload-" + eventRef).getBytes(),
                0,
                LocalDateTime.now(),
                LocalDateTime.now(),
                null,
                null
        );
    }
}
