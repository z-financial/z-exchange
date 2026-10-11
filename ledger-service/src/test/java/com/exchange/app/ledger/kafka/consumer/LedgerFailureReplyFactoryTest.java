package com.exchange.app.ledger.kafka.consumer;

import com.exchange.app.ledger.result.LedgerServiceErrorCode;
import com.exchange.common.kafka.listener.failure.ListenerFailure;
import com.exchange.common.identity.DefaultEntityRefCodec;
import com.exchange.common.identity.DefaultEventRefFactory;
import com.exchange.common.identity.EntityRefType;
import com.exchange.common.identity.EventRefFactory;
import com.exchange.common.identity.EventRefType;
import com.exchange.common.result.error.KafkaListenerErrorCode;
import com.exchange.proto.common.error.ErrorCodePb;
import com.exchange.proto.common.event.EventEnvelopePb;
import com.exchange.proto.ledger.post.PostTransactionReplyPb;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LedgerFailureReplyFactoryTest {
    private static final EventRefFactory EVENT_REF_FACTORY = new DefaultEventRefFactory();
    private static final String REQUEST_EVENT_REF = EVENT_REF_FACTORY.create(EventRefType.WALLET_REQUEST, 123L);
    private static final String COMMAND_REF = new DefaultEntityRefCodec()
            .encode(EntityRefType.WALLET_TRANSACTION, 456L);

    private final LedgerFailureReplyFactory factory = new LedgerFailureReplyFactory(
            () -> 701L,
            EVENT_REF_FACTORY);

    @Test
    void retryExhaustedKafkaFailureMapsToLedgerInternalReply() throws Exception {
        // KL-LH-008: common Kafka errors must not leak through the ledger public reply contract.
        ListenerFailure failure = ListenerFailure.retryExhausted(
                KafkaListenerErrorCode.RETRY_EXHAUSTED,
                "retry exhausted"
        );

        var outbox = factory.createFailureReply(envelope(), failure);

        assertEquals(EVENT_REF_FACTORY.create(EventRefType.LEDGER_REPLY, 701L), outbox.getEventRef());
        assertEquals(701L, outbox.getInternalId());
        PostTransactionReplyPb reply = PostTransactionReplyPb.parseFrom(outbox.getPayload());
        assertEquals(ErrorCodePb.ERROR_INTERNAL, reply.getError().getCode());
        assertEquals(LedgerServiceErrorCode.INTERNAL_ERROR.getMessage(), reply.getError().getMessage());
        assertEquals("retry exhausted", reply.getError().getDetail());
    }

    @Test
    void configuredLedgerUnexpectedErrorCreatesLedgerFailureReply() throws Exception {
        // KL-LH-009: unexpected listener failures use the ledger error supplied by DefaultListener config.
        ListenerFailure failure = ListenerFailure.unexpected(
                LedgerServiceErrorCode.SERVER_ERROR,
                "unexpected listener failure",
                new NullPointerException("bug")
        );

        var outbox = factory.createFailureReply(envelope(), failure);

        PostTransactionReplyPb reply = PostTransactionReplyPb.parseFrom(outbox.getPayload());
        assertEquals(EVENT_REF_FACTORY.create(EventRefType.LEDGER_REPLY, 701L), outbox.getEventRef());
        assertEquals(ErrorCodePb.ERROR_INTERNAL, reply.getError().getCode());
        assertEquals(LedgerServiceErrorCode.SERVER_ERROR.getMessage(), reply.getError().getMessage());
    }

    private EventEnvelopePb envelope() {
        return EventEnvelopePb.newBuilder()
                .setEventRef(REQUEST_EVENT_REF)
                .setCommandRef(COMMAND_REF)
                .setEventType("POST_LEDGER")
                .build();
    }
}
