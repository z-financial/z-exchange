package com.exchange.app.wallet.kafka.consumer;

import com.exchange.app.wallet.constant.EventType;
import com.exchange.app.wallet.metrics.WalletBusinessMetrics;
import com.exchange.app.wallet.metrics.WalletMetricTagValues;
import com.exchange.app.wallet.po.transaction.WalletTransaction;
import com.exchange.app.wallet.processor.transaction.step.AfterPostLedgerProcessor;
import com.exchange.app.wallet.result.WalletServiceErrorCode;
import com.exchange.common.exception.AbnormalProtoDataException;
import com.exchange.common.exception.CustomizedException;
import com.exchange.common.kafka.listener.exception.KafkaListenerRetriableException;
import com.exchange.common.identity.EntityRefCodec;
import com.exchange.common.identity.EntityRefType;
import com.exchange.common.result.Result;
import com.exchange.common.result.error.ErrorCode;
import com.exchange.common.result.error.RetryableErrorCode;
import com.exchange.proto.common.error.ErrorCodePb;
import com.exchange.proto.common.event.EventEnvelopePb;
import com.exchange.proto.ledger.post.PostTransactionReplyPb;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

@Slf4j
@Component
@RequiredArgsConstructor
public class WalletLedgerReplyMessageHandler implements Consumer<EventEnvelopePb> {
    private final AfterPostLedgerProcessor afterPostLedgerProcessor;
    private final WalletBusinessMetrics walletBusinessMetrics;
    private final EntityRefCodec entityRefCodec;

    @Override
    public void accept(EventEnvelopePb envelope) {
        log.debug(
                "handle ledger reply, eventId={}, commandId={}, eventType={}",
                envelope.getEventId(),
                envelope.getCommandId(),
                envelope.getEventType()
        );
        if (!EventType.POST_LEDGER_REPLY.equals(envelope.getEventType())) {
            throw new AbnormalProtoDataException(
                    WalletServiceErrorCode.INVALID_ENUM_ERROR,
                    "invalid event type: " + envelope.getEventType()
            );
        }
        handlePostLedgerReply(parseReply(envelope));
    }

    private PostTransactionReplyPb parseReply(EventEnvelopePb envelope) {
        try {
            return PostTransactionReplyPb.parseFrom(envelope.getPayload());
        } catch (InvalidProtocolBufferException e) {
            throw new AbnormalProtoDataException(
                    WalletServiceErrorCode.SERIALIZE_ERROR,
                    "cannot parse post ledger reply payload",
                    e
            );
        }
    }

    private void handlePostLedgerReply(PostTransactionReplyPb reply) {
        if (!reply.hasError()) {
            throw new AbnormalProtoDataException(
                    WalletServiceErrorCode.SERIALIZE_ERROR,
                    "post ledger reply is missing error outcome"
            );
        }

        /*
         * A failed ledger reply is already a final response to the original command. Reprocessing
         * that same reply cannot turn it into success, so preserve it through DLT/reconciliation
         * instead of retrying this consumer or incorrectly completing the wallet transaction.
         */
        if (reply.getError().getCode() != ErrorCodePb.ERROR_OK) {
            throw new CustomizedException(
                    WalletServiceErrorCode.POST_TRANSACTION_FAILED,
                    "ledger post failed, code=" + reply.getError().getCode()
                            + ", message=" + reply.getError().getMessage()
                            + ", detail=" + reply.getError().getDetail()
            );
        }
        if (reply.getSourceTxnRef().isBlank() || reply.getLedgerTxnRef().isBlank()) {
            throw new AbnormalProtoDataException(
                    WalletServiceErrorCode.SERIALIZE_ERROR,
                    "successful post ledger reply is missing transaction identity"
            );
        }
        // Reply correlation is a typed boundary value; validation never exposes or uses
        // the decoded internal storage ID.
        Result<Void> referenceValidation =
                entityRefCodec.validate(reply.getSourceTxnRef(), EntityRefType.WALLET_TRANSACTION);
        if (referenceValidation.isFailed()) {
            throw classifyProcessingFailure(
                    referenceValidation.getErrorCode(), referenceValidation.getDetail(), null);
        }
        referenceValidation =
                entityRefCodec.validate(reply.getLedgerTxnRef(), EntityRefType.LEDGER_TRANSACTION);
        if (referenceValidation.isFailed()) {
            throw classifyProcessingFailure(
                    referenceValidation.getErrorCode(), referenceValidation.getDetail(), null);
        }

        Result<WalletTransaction> result;
        try {
            result = walletBusinessMetrics.recordResult(
                    WalletMetricTagValues.Operations.COMPLETE_LEDGER_TRANSACTION,
                    WalletMetricTagValues.Ingress.KAFKA,
                    () -> afterPostLedgerProcessor.afterPostLedger(reply.getSourceTxnRef())
            );
        } catch (CustomizedException e) {
            throw classifyProcessingFailure(e.getErrorCode(), e.getMessage(), e);
        }

        if (result == null) {
            throw new CustomizedException(
                    WalletServiceErrorCode.NULL_RESULT_ERROR,
                    "after post ledger returned null"
            );
        }
        if (result.isFailed()) {
            throw classifyProcessingFailure(result.getErrorCode(), result.getDetail(), null);
        }
    }

    private CustomizedException classifyProcessingFailure(ErrorCode errorCode, String detail, Throwable cause) {
        ErrorCode normalizedError = errorCode == null ? WalletServiceErrorCode.SERVER_ERROR : errorCode;
        String normalizedDetail = detail == null || detail.isBlank()
                ? "wallet failed to apply ledger reply"
                : detail;
        if (normalizedError instanceof RetryableErrorCode retryableError && retryableError.isRetryable()) {
            return new KafkaListenerRetriableException(normalizedError, normalizedDetail, cause);
        }
        return new CustomizedException(normalizedError, normalizedDetail, cause);
    }
}
