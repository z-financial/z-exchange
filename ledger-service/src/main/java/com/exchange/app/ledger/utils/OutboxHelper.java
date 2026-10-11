package com.exchange.app.ledger.utils;

import com.exchange.app.ledger.constant.EventType;
import com.exchange.app.ledger.kafka.constant.LedgerTopic;
import com.exchange.app.ledger.po.enums.outbox.OutboxEventType;
import com.exchange.app.ledger.result.PostTransactionResult;
import com.exchange.app.ledger.result.PostTransactionResultConverter;
import com.exchange.common.outbox.po.Outbox;
import com.exchange.common.outbox.po.enums.OutboxStatus;
import com.exchange.common.identity.EventRefFactory;
import com.exchange.common.identity.EventRefType;
import com.exchange.common.utils.enums.EnumMapper;
import com.exchange.proto.ledger.post.PostTransactionReplyPb;

import java.time.LocalDateTime;
import java.util.HashMap;

public class OutboxHelper {
    public static String resolveEventType(int eventType) {
        return outboxEventTypeStringMapper.to(eventType);
    }

    final static public EnumMapper<Integer, OutboxEventType> outboxEventTypeMapper = new EnumMapper<>(
            new HashMap<>() {{
                put(OutboxEventType.LEDGER_POST_REPLY.code, OutboxEventType.LEDGER_POST_REPLY);
            }}
    );

    final static public EnumMapper<Integer, String> outboxEventTypeStringMapper = new EnumMapper<>(
            new HashMap<>() {{
                put(OutboxEventType.LEDGER_POST_REPLY.code, EventType.POST_LEDGER_REPLY);
            }}
    );

    public static Outbox fromPostTransactionResult(
            long internalId,
            PostTransactionResult result,
            String commandRef,
            EventRefFactory eventRefFactory) {
        return fromPostTransactionReply(
                internalId,
                PostTransactionResultConverter.toProto(result),
                eventRefFactory.create(EventRefType.LEDGER_REPLY, internalId),
                commandRef
        );
    }

    private static Outbox fromPostTransactionReply(
            long internalId,
            PostTransactionReplyPb replyPb,
            String replyEventRef,
            String commandRef) {
        return Outbox.create(
                internalId,
                OutboxEventType.LEDGER_POST_REPLY.code,
                replyEventRef,
                commandRef,
                OutboxStatus.PENDING,
                LedgerTopic.REPLY_WALLET,
                commandRef,
                replyPb.toByteArray(),
                0,
                LocalDateTime.now(),
                LocalDateTime.now(),
                null,
                null
        );
    }

}
