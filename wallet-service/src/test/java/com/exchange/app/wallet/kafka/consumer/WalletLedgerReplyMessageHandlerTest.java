package com.exchange.app.wallet.kafka.consumer;

import com.exchange.app.wallet.constant.EventType;
import com.exchange.app.wallet.metrics.WalletBusinessMetrics;
import com.exchange.app.wallet.po.transaction.WalletTransaction;
import com.exchange.app.wallet.processor.transaction.step.AfterPostLedgerProcessor;
import com.exchange.app.wallet.result.WalletServiceErrorCode;
import com.exchange.common.exception.AbnormalProtoDataException;
import com.exchange.common.exception.CustomizedException;
import com.exchange.common.exception.DbException;
import com.exchange.common.kafka.listener.exception.KafkaListenerRetriableException;
import com.exchange.common.identity.DefaultEntityRefCodec;
import com.exchange.common.identity.EntityRefType;
import com.exchange.common.result.Result;
import com.exchange.common.result.error.DbErrorCode;
import com.exchange.proto.common.error.ErrorCodePb;
import com.exchange.proto.common.error.ErrorPb;
import com.exchange.proto.common.event.EventEnvelopePb;
import com.exchange.proto.ledger.post.PostTransactionReplyPb;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletLedgerReplyMessageHandlerTest {
    private static final String WALLET_TXN_REF = new DefaultEntityRefCodec()
            .encode(EntityRefType.WALLET_TRANSACTION, 1L);

    @Mock
    private AfterPostLedgerProcessor afterPostLedgerProcessor;

    private WalletLedgerReplyMessageHandler handler;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        handler = new WalletLedgerReplyMessageHandler(
                afterPostLedgerProcessor,
                new WalletBusinessMetrics(meterRegistry),
                new DefaultEntityRefCodec()
        );
    }

    @Test
    void successfulLedgerReplyCompletesWalletTransaction() {
        // A successful reply is the only outcome allowed to enter wallet finalization.
        when(afterPostLedgerProcessor.afterPostLedger(WALLET_TXN_REF))
                .thenReturn(Result.success(mock(WalletTransaction.class)));

        handler.accept(envelope(successReply().toByteString()));

        verify(afterPostLedgerProcessor).afterPostLedger(WALLET_TXN_REF);
    }

    @Test
    void failedLedgerReplyNeverCompletesWalletTransaction() {
        // A final ledger failure is preserved for DLT/reconciliation, not retried as completion.
        PostTransactionReplyPb reply = PostTransactionReplyPb.newBuilder()
                .setError(error(ErrorCodePb.ERROR_INTERNAL, "ledger failed"))
                .build();

        CustomizedException thrown = assertThrows(
                CustomizedException.class,
                () -> handler.accept(envelope(reply.toByteString()))
        );

        assertEquals(WalletServiceErrorCode.POST_TRANSACTION_FAILED, thrown.getErrorCode());
        verify(afterPostLedgerProcessor, never()).afterPostLedger(WALLET_TXN_REF);
    }

    @Test
    void malformedReplyPayloadIsNonRetryableProtocolFailure() {
        AbnormalProtoDataException thrown = assertThrows(
                AbnormalProtoDataException.class,
                () -> handler.accept(envelope(ByteString.copyFromUtf8("bad-payload")))
        );

        assertEquals(WalletServiceErrorCode.SERIALIZE_ERROR, thrown.getErrorCode());
        verify(afterPostLedgerProcessor, never()).afterPostLedger(WALLET_TXN_REF);
    }

    @Test
    void unsupportedEventTypeIsNonRetryableProtocolFailure() {
        EventEnvelopePb envelope = EventEnvelopePb.newBuilder()
                .setEventId("reply-event-1")
                .setCommandId(WALLET_TXN_REF)
                .setEventType("UNKNOWN")
                .setPayload(successReply().toByteString())
                .build();

        AbnormalProtoDataException thrown = assertThrows(
                AbnormalProtoDataException.class,
                () -> handler.accept(envelope)
        );

        assertEquals(WalletServiceErrorCode.INVALID_ENUM_ERROR, thrown.getErrorCode());
        verify(afterPostLedgerProcessor, never()).afterPostLedger(WALLET_TXN_REF);
    }

    @Test
    void retryableDbFailureBecomesKafkaRetrySignal() {
        DbException dbFailure = DbException.unavailable("afterPostLedger", new RuntimeException("db down"));
        when(afterPostLedgerProcessor.afterPostLedger(WALLET_TXN_REF)).thenThrow(dbFailure);

        KafkaListenerRetriableException thrown = assertThrows(
                KafkaListenerRetriableException.class,
                () -> handler.accept(envelope(successReply().toByteString()))
        );

        assertEquals(DbErrorCode.DB_UNAVAILABLE, thrown.getErrorCode());
    }

    @Test
    void nonRetryableProcessorResultPreservesItsErrorCode() {
        when(afterPostLedgerProcessor.afterPostLedger(WALLET_TXN_REF)).thenReturn(Result.failure(
                WalletServiceErrorCode.WALLET_TRANSACTION_UPDATE_FAILED,
                "state conflict"
        ));

        CustomizedException thrown = assertThrows(
                CustomizedException.class,
                () -> handler.accept(envelope(successReply().toByteString()))
        );

        assertEquals(WalletServiceErrorCode.WALLET_TRANSACTION_UPDATE_FAILED, thrown.getErrorCode());
    }

    private EventEnvelopePb envelope(ByteString payload) {
        return EventEnvelopePb.newBuilder()
                .setEventId("reply-event-1")
                .setCommandId(WALLET_TXN_REF)
                .setEventType(EventType.POST_LEDGER_REPLY)
                .setPayload(payload)
                .build();
    }

    private PostTransactionReplyPb successReply() {
        return PostTransactionReplyPb.newBuilder()
                .setWalletTxnRef(WALLET_TXN_REF)
                .setLedgerTxnId("ledger-txn-1")
                .setError(error(ErrorCodePb.ERROR_OK, "success"))
                .build();
    }

    private ErrorPb error(ErrorCodePb code, String message) {
        return ErrorPb.newBuilder().setCode(code).setMessage(message).build();
    }
}
