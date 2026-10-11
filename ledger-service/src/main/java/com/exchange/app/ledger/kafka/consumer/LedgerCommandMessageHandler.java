package com.exchange.app.ledger.kafka.consumer;

import com.exchange.app.ledger.constant.EventType;
import com.exchange.app.ledger.metrics.LedgerBusinessMetrics;
import com.exchange.app.ledger.metrics.LedgerMetricTagValues;
import com.exchange.app.ledger.processor.post.PostLedgerProcessor;
import com.exchange.app.ledger.result.LedgerServiceErrorCode;
import com.exchange.app.ledger.result.PostTransactionResult;
import com.exchange.app.ledger.utils.OutboxHelper;
import com.exchange.common.exception.AbnormalProtoDataException;
import com.exchange.common.exception.CustomizedException;
import com.exchange.common.exception.IdGeneratorException;
import com.exchange.common.identity.EventRefFactory;
import com.exchange.common.identity.EventRefType;
import com.exchange.common.idgen.InternalIdGenerator;
import com.exchange.common.kafka.listener.exception.KafkaListenerRetriableException;
import com.exchange.common.outbox.po.Outbox;
import com.exchange.proto.common.event.EventEnvelopePb;
import com.exchange.proto.ledger.post.PostTransactionRequestPb;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Function;

@Slf4j
@Component
@RequiredArgsConstructor
public class LedgerCommandMessageHandler implements Function<EventEnvelopePb, Outbox> {
    private final PostLedgerProcessor postLedgerProcessor;
    private final LedgerBusinessMetrics ledgerBusinessMetrics;
    private final InternalIdGenerator internalIdGenerator;
    private final EventRefFactory eventRefFactory;

    @Override
    public Outbox apply(EventEnvelopePb envelope) {
        var eventRefValidation = eventRefFactory.validate(
                envelope.getEventRef(), EventRefType.WALLET_REQUEST);
        if (eventRefValidation.isFailed()) {
            throw new AbnormalProtoDataException(
                    LedgerServiceErrorCode.INVALID_REQUEST_PARAMETER,
                    eventRefValidation.getDetail());
        }
        log.info(
                "handle ledger command, eventRef={}, commandRef={}, eventType={}",
                envelope.getEventRef(),
                envelope.getCommandRef(),
                envelope.getEventType()
        );
        return switch (envelope.getEventType()) {
            case EventType.POST_LEDGER -> handlePostLedger(envelope);
            default -> throw new AbnormalProtoDataException(
                    LedgerServiceErrorCode.INVALID_ENUM_ERROR,
                    "invalid event type: " + envelope.getEventType()
            );
        };
    }

    private Outbox handlePostLedger(EventEnvelopePb envelope) {
        PostTransactionRequestPb request = parsePostLedgerRequest(envelope);
        if (!envelope.getCommandRef().equals(request.getSourceTxnRef())) {
            throw new AbnormalProtoDataException(
                    LedgerServiceErrorCode.INVALID_REQUEST_PARAMETER,
                    "command_ref does not match source_txn_ref");
        }
        PostTransactionResult result;
        try {
            result = ledgerBusinessMetrics.recordPostTransaction(
                    LedgerMetricTagValues.Ingress.KAFKA,
                    () -> postLedgerProcessor.postTransaction(request)
            );
        } catch (IdGeneratorException failure) {
            // Generator availability is infrastructure state: retain the Kafka record for retry.
            throw new KafkaListenerRetriableException(
                    failure.getErrorCode(), "ledger ID allocation is unavailable", failure);
        }
        if (!result.isSuccess()) {
            handlePostLedgerFailure(result);
        }
        // One generated identity represents one durable reply event. Publisher retries reuse
        // this stored row; reprocessing the command may intentionally create another reply event.
        long outboxInternalId = nextOutboxInternalId();
        return OutboxHelper.fromPostTransactionResult(
                outboxInternalId,
                result,
                envelope.getCommandRef(),
                eventRefFactory);
    }

    private long nextOutboxInternalId() {
        try {
            return internalIdGenerator.nextId();
        } catch (IdGeneratorException failure) {
            // No reply can be durable without its storage identity; retain the request for retry.
            throw new KafkaListenerRetriableException(
                    failure.getErrorCode(), "ledger reply outbox ID allocation is unavailable", failure);
        }
    }

    /*
     * The envelope is already parseable here, so the request id and reply contract are
     * known. Payload parse failure can be returned as a normal failed ledger reply instead
     * of DLQ; DLQ is only reserved for messages where the listener cannot identify the request.
     */
    private PostTransactionRequestPb parsePostLedgerRequest(EventEnvelopePb envelope) {
        try {
            return PostTransactionRequestPb.parseFrom(envelope.getPayload());
        } catch (InvalidProtocolBufferException e) {
            throw new AbnormalProtoDataException(
                    LedgerServiceErrorCode.SERIALIZE_ERROR,
                    "cannot parse post ledger payload",
                    e
            );
        }
    }

    private void handlePostLedgerFailure(PostTransactionResult result) {
        LedgerServiceErrorCode errorCode = result.errorCode() == null
                ? LedgerServiceErrorCode.SERVER_ERROR
                : result.errorCode();
        if (isExplicitRetryable(errorCode)) {
            throw new KafkaListenerRetriableException(
                    errorCode,
                    "post ledger failed with retryable error, errorCode=" + errorCode + ", detail=" + result.detail()
            );
        }
        throw new CustomizedException(errorCode, result.detail());
    }

    private boolean isExplicitRetryable(LedgerServiceErrorCode errorCode) {
        return errorCode == LedgerServiceErrorCode.REQUEST_IN_PROCESSING;
    }
}
