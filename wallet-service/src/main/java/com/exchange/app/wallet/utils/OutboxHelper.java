package com.exchange.app.wallet.utils;

import com.exchange.app.wallet.constant.EventType;
import com.exchange.app.wallet.kafka.constant.WalletTopic;
import com.exchange.app.wallet.po.enums.outbox.OutboxEventType;
import com.exchange.app.wallet.po.transaction.WalletTransaction;
import com.exchange.common.outbox.po.enums.OutboxStatus;
import com.exchange.common.utils.enums.EnumMapper;
import com.exchange.common.outbox.po.Outbox;
import com.exchange.proto.ledger.post.PostTransactionRequestPb;

import java.time.LocalDateTime;
import java.util.HashMap;

public class OutboxHelper {
    public static String resolveEventType(int eventType) {
        return outboxEventTypeStringMapper.to(eventType);
    }

    final static public EnumMapper<Integer, OutboxEventType> outboxEventTypeMapper = new EnumMapper<>(
            new HashMap<>() {{
                put(OutboxEventType.LEDGER_POST.code, OutboxEventType.LEDGER_POST);
            }}
    );

    final static public EnumMapper<Integer, String> outboxEventTypeStringMapper = new EnumMapper<>(
            new HashMap<>() {{
                put(OutboxEventType.LEDGER_POST.code, EventType.POST_LEDGER);
            }}
    );

    public static Outbox fromPostTransactionRequest(
            long internalId,
            String eventRef,
            PostTransactionRequestPb requestPb,
            WalletTransaction txn) {
        return Outbox.create(
                internalId,
                OutboxEventType.LEDGER_POST.code,
                eventRef,
                txn.getWalletTxnRef(),
                OutboxStatus.PENDING,
                WalletTopic.POST_LEDGER,
                txn.getWalletTxnRef(),
                requestPb.toByteArray(),
                0,
                LocalDateTime.now(),
                LocalDateTime.now(),
                null,
                null
        );
    }
}
