package com.exchange.common.kafka.listener;

import com.exchange.common.kafka.listener.action.ListenerAction;
import com.exchange.common.kafka.listener.failure.ListenerFailure;
import com.exchange.common.kafka.listener.handler.AckOnlyDefaultMessageHandler;
import com.exchange.common.kafka.listener.handler.FailureReplyFactory;
import com.exchange.common.kafka.listener.handler.OutboxReplyDefaultMessageHandler;
import com.exchange.common.outbox.po.Outbox;
import com.exchange.common.outbox.po.enums.OutboxStatus;
import com.exchange.common.result.error.KafkaListenerErrorCode;
import com.exchange.proto.common.event.EventEnvelopePb;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageHandlerAdapterTest {
    @Test
    void outboxReplyHandlerWrapsDelegateOutbox() {
        // KL-MH-001: request/reply handlers expose successful work as an OutboxReply action.
        Outbox outbox = outbox("ledger-reply-event-ref");
        var handler = new OutboxReplyDefaultMessageHandler(envelope -> outbox, failureFactory(outbox));

        ListenerAction action = handler.handle(envelope());

        ListenerAction.OutboxReply reply = assertInstanceOf(ListenerAction.OutboxReply.class, action);
        assertSame(outbox, reply.outbox());
    }

    @Test
    void outboxReplyHandlerUsesFailureReplyFactory() {
        // KL-MH-002: parseable failures are delegated to the service-owned failure reply factory.
        Outbox failedOutbox = outbox("ledger-retry-exhausted-event-ref");
        var handler = new OutboxReplyDefaultMessageHandler(envelope -> outbox("unused"), failureFactory(failedOutbox));
        ListenerFailure failure = ListenerFailure.retryExhausted(KafkaListenerErrorCode.RETRY_EXHAUSTED, "exhausted");

        ListenerAction action = handler.handleFailure(envelope(), failure);

        ListenerAction.OutboxReply reply = assertInstanceOf(ListenerAction.OutboxReply.class, action);
        assertSame(failedOutbox, reply.outbox());
    }

    @Test
    void ackOnlyHandlerReturnsAckOnlyAfterConsumerCompletes() {
        // KL-MH-003: ack-only adapters do not create reply outboxes.
        AtomicBoolean called = new AtomicBoolean(false);
        var handler = new AckOnlyDefaultMessageHandler(envelope -> called.set(true));

        ListenerAction action = handler.handle(envelope());

        assertTrue(called.get());
        assertInstanceOf(ListenerAction.AckOnly.class, action);
    }

    @Test
    void ackOnlyFailurePolicyIsExplicitAndNullActionRejected() {
        // KL-MH-004: common-util requires services to choose a concrete failure policy.
        ListenerFailure failure = ListenerFailure.unexpected(
                KafkaListenerErrorCode.LISTENER_INTERNAL_ERROR,
                "unexpected"
        );
        var ackPolicy = new AckOnlyDefaultMessageHandler(envelope -> {
        }, (envelope, listenerFailure) -> ListenerAction.ack());

        assertInstanceOf(ListenerAction.AckOnly.class, ackPolicy.handleFailure(envelope(), failure));

        var nullPolicy = new AckOnlyDefaultMessageHandler(envelope -> {
        }, (envelope, listenerFailure) -> null);

        assertThrows(NullPointerException.class, () -> nullPolicy.handleFailure(envelope(), failure));
    }

    private FailureReplyFactory failureFactory(Outbox outbox) {
        return (envelope, failure) -> outbox;
    }

    private EventEnvelopePb envelope() {
        return EventEnvelopePb.newBuilder()
                .setEventRef("event")
                .setCommandRef("command")
                .setEventType("POST_LEDGER")
                .build();
    }

    private Outbox outbox(String eventRef) {
        return Outbox.create(
                1L,
                1,
                eventRef,
                "command",
                OutboxStatus.PENDING,
                "topic",
                "command",
                eventRef.getBytes(),
                0,
                LocalDateTime.now(),
                LocalDateTime.now(),
                null,
                null
        );
    }
}
