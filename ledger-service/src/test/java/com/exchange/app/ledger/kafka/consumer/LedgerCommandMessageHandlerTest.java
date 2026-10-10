package com.exchange.app.ledger.kafka.consumer;

import com.exchange.app.ledger.constant.EventType;
import com.exchange.app.ledger.kafka.constant.LedgerTopic;
import com.exchange.app.ledger.metrics.LedgerBusinessMetrics;
import com.exchange.app.ledger.processor.post.PostLedgerProcessor;
import com.exchange.app.ledger.result.LedgerServiceErrorCode;
import com.exchange.app.ledger.result.PostTransactionResult;
import com.exchange.common.exception.AbnormalProtoDataException;
import com.exchange.common.exception.CustomizedException;
import com.exchange.common.exception.IdGeneratorException;
import com.exchange.common.idgen.IdGeneratorContext;
import com.exchange.common.kafka.listener.exception.KafkaListenerRetriableException;
import com.exchange.common.result.error.IdGeneratorErrorCode;
import com.exchange.proto.common.error.ErrorCodePb;
import com.exchange.proto.common.event.EventEnvelopePb;
import com.exchange.proto.ledger.common.LedgerDirectionPb;
import com.exchange.proto.ledger.post.LedgerEntryPb;
import com.exchange.proto.ledger.post.PostTransactionReplyPb;
import com.exchange.proto.ledger.post.PostTransactionRequestPb;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LedgerCommandMessageHandlerTest {
    private static final String REQUEST_EVENT_ID = "wallet-event-123";
    private static final String COMMAND_ID = "wallet-command-123";

    @Mock
    private PostLedgerProcessor postLedgerProcessor;

    private LedgerCommandMessageHandler handler;

    @BeforeEach
    void setUp() {
        handler = new LedgerCommandMessageHandler(
                postLedgerProcessor,
                new LedgerBusinessMetrics(new SimpleMeterRegistry())
        );
    }

    @Test
    void postLedgerSuccessReturnsSuccessReplyOutbox() throws Exception {
        // KL-LH-001: successful ledger processing becomes a deterministic success reply outbox.
        PostTransactionRequestPb request = request();
        when(postLedgerProcessor.postTransaction(any(PostTransactionRequestPb.class)))
                .thenReturn(PostTransactionResult.created("ref-1", "ledger-txn-1", "created", 2));

        var outbox = handler.apply(envelope(EventType.POST_LEDGER, request.toByteString()));

        assertEquals("reply:" + REQUEST_EVENT_ID + ":success", outbox.getEventId());
        assertEquals(COMMAND_ID, outbox.getCommandId());
        assertEquals(LedgerTopic.REPLY_WALLET, outbox.getDestination());
        assertEquals(COMMAND_ID, outbox.getPartitionKey());
        PostTransactionReplyPb reply = PostTransactionReplyPb.parseFrom(outbox.getPayload());
        assertEquals(ErrorCodePb.ERROR_OK, reply.getError().getCode());
        assertEquals("ledger-txn-1", reply.getLedgerTxnId());
        verify(postLedgerProcessor).postTransaction(any(PostTransactionRequestPb.class));
    }

    @Test
    void idempotentReplayReconstructsCanonicalSuccessReply() {
        // KL-LH-002: creation and replay must resolve to the same durable reply intent.
        PostTransactionRequestPb request = request();
        when(postLedgerProcessor.postTransaction(any(PostTransactionRequestPb.class)))
                .thenReturn(
                        PostTransactionResult.created("ref-1", "ledger-txn-1", "created", 2),
                        PostTransactionResult.idempotentReplay(
                                "ref-1",
                                "ledger-txn-1",
                                "already exists",
                                PostTransactionResult.IdempotentSource.DB
                        )
                );

        var createdOutbox = handler.apply(envelope(EventType.POST_LEDGER, request.toByteString()));
        var replayOutbox = handler.apply(envelope(EventType.POST_LEDGER, request.toByteString()));

        assertEquals(createdOutbox.getEventId(), replayOutbox.getEventId());
        assertArrayEquals(createdOutbox.getPayload(), replayOutbox.getPayload());
        assertFalse(replayOutbox.getEventId().contains("ledger-txn-1"));
    }

    @Test
    void invalidPayloadThrowsSerializeErrorForFailureReplyFlow() {
        // KL-LH-003: payload parse failure is parseable at envelope level, so it can become a reply.
        AbnormalProtoDataException thrown = assertThrows(
                AbnormalProtoDataException.class,
                () -> handler.apply(envelope(EventType.POST_LEDGER, ByteString.copyFromUtf8("bad-payload")))
        );

        assertEquals(LedgerServiceErrorCode.SERIALIZE_ERROR, thrown.getErrorCode());
        verify(postLedgerProcessor, never()).postTransaction(any());
    }

    @Test
    void invalidEventTypeThrowsInvalidEnumForFailureReplyFlow() {
        // KL-LH-004: unsupported event types keep the request identity and become failed replies.
        AbnormalProtoDataException thrown = assertThrows(
                AbnormalProtoDataException.class,
                () -> handler.apply(envelope("UNKNOWN_EVENT", ByteString.EMPTY))
        );

        assertEquals(LedgerServiceErrorCode.INVALID_ENUM_ERROR, thrown.getErrorCode());
        verify(postLedgerProcessor, never()).postTransaction(any());
    }

    @Test
    void nonRetryablePostResultThrowsCustomizedException() {
        // KL-LH-005: non-retryable ledger errors should be converted to failed replies by DefaultListener.
        when(postLedgerProcessor.postTransaction(any(PostTransactionRequestPb.class)))
                .thenReturn(PostTransactionResult.failure(LedgerServiceErrorCode.ACCOUNT_NOT_FOUND, "missing"));

        CustomizedException thrown = assertThrows(
                CustomizedException.class,
                () -> handler.apply(envelope(EventType.POST_LEDGER, request().toByteString()))
        );

        assertEquals(LedgerServiceErrorCode.ACCOUNT_NOT_FOUND, thrown.getErrorCode());
    }

    @Test
    void requestInProcessingIsExplicitlyRetryable() {
        // KL-LH-006: in-flight idempotency is a deliberate Kafka retry signal in this phase.
        when(postLedgerProcessor.postTransaction(any(PostTransactionRequestPb.class)))
                .thenReturn(PostTransactionResult.failure(LedgerServiceErrorCode.REQUEST_IN_PROCESSING, "pending"));

        KafkaListenerRetriableException thrown = assertThrows(
                KafkaListenerRetriableException.class,
                () -> handler.apply(envelope(EventType.POST_LEDGER, request().toByteString()))
        );

        assertEquals(LedgerServiceErrorCode.REQUEST_IN_PROCESSING, thrown.getErrorCode());
    }

    @Test
    void idGeneratorFailureIsRetryable() {
        IdGeneratorException generatorFailure = IdGeneratorException.allocation(
                new IdGeneratorContext("ledger-service", "z-exchange-ledger", "internal-id", "ledger-1"),
                new RuntimeException("generator unavailable"));
        when(postLedgerProcessor.postTransaction(any(PostTransactionRequestPb.class)))
                .thenThrow(generatorFailure);

        KafkaListenerRetriableException thrown = assertThrows(
                KafkaListenerRetriableException.class,
                () -> handler.apply(envelope(EventType.POST_LEDGER, request().toByteString()))
        );

        assertEquals(IdGeneratorErrorCode.ALLOCATION_FAILED, thrown.getErrorCode());
        assertEquals(generatorFailure, thrown.getCause());
    }

    private EventEnvelopePb envelope(String eventType, ByteString payload) {
        return EventEnvelopePb.newBuilder()
                .setEventId(REQUEST_EVENT_ID)
                .setCommandId(COMMAND_ID)
                .setEventType(eventType)
                .setPayload(payload)
                .build();
    }

    private PostTransactionRequestPb request() {
        return PostTransactionRequestPb.newBuilder()
                .setReferenceId("ref-1")
                .setDescription("test")
                .addEntries(entry(LedgerDirectionPb.LedgerDirection_Debit))
                .addEntries(entry(LedgerDirectionPb.LedgerDirection_Credit))
                .build();
    }

    private LedgerEntryPb entry(LedgerDirectionPb direction) {
        return LedgerEntryPb.newBuilder()
                .setAccountRef("account-ref")
                .setDirection(direction)
                .setAmount(100)
                .setAssetId("asset-1")
                .build();
    }
}
