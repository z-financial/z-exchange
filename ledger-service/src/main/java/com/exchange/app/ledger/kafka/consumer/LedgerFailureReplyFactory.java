package com.exchange.app.ledger.kafka.consumer;

import com.exchange.app.ledger.result.LedgerBoundaryErrorMapper;
import com.exchange.app.ledger.result.LedgerServiceErrorCode;
import com.exchange.app.ledger.result.PostTransactionResult;
import com.exchange.app.ledger.utils.OutboxHelper;
import com.exchange.common.kafka.listener.failure.ListenerFailure;
import com.exchange.common.identity.EventRefFactory;
import com.exchange.common.idgen.InternalIdGenerator;
import com.exchange.common.exception.IdGeneratorException;
import com.exchange.common.kafka.listener.exception.KafkaListenerRetriableException;
import com.exchange.common.kafka.listener.handler.FailureReplyFactory;
import com.exchange.common.outbox.po.Outbox;
import com.exchange.proto.common.event.EventEnvelopePb;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class LedgerFailureReplyFactory implements FailureReplyFactory {
    private final InternalIdGenerator internalIdGenerator;
    private final EventRefFactory eventRefFactory;

    @Override
    public Outbox createFailureReply(EventEnvelopePb envelope, ListenerFailure failure) {
        LedgerServiceErrorCode ledgerError = LedgerBoundaryErrorMapper.toLedgerErrorCode(failure.errorCode());
        PostTransactionResult result = PostTransactionResult.failure(ledgerError, failure.detail());
        long outboxInternalId;
        try {
            outboxInternalId = internalIdGenerator.nextId();
        } catch (IdGeneratorException allocationFailure) {
            // A failed reply without a durable outbox row must remain a Kafka retry.
            throw new KafkaListenerRetriableException(
                    allocationFailure.getErrorCode(),
                    "ledger failure-reply outbox ID allocation is unavailable",
                    allocationFailure);
        }
        return OutboxHelper.fromPostTransactionResult(
                outboxInternalId,
                result,
                envelope.getCommandRef(),
                eventRefFactory);
    }
}
