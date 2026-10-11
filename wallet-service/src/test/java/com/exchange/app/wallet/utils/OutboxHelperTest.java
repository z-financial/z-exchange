package com.exchange.app.wallet.utils;

import com.exchange.app.wallet.po.transaction.WalletTransaction;
import com.exchange.common.identity.DefaultEventRefFactory;
import com.exchange.common.identity.EventRefType;
import com.exchange.proto.ledger.post.PostTransactionRequestPb;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OutboxHelperTest {
    @Test
    void preservesAllocatedEventRefAndTransactionRoutingIdentity() throws Exception {
        WalletTransaction transaction = new WalletTransaction();
        transaction.setWalletTxnRef("wallet-txn-ref-1");
        PostTransactionRequestPb request = PostTransactionRequestPb.newBuilder()
                .setSourceTxnRef(transaction.getWalletTxnRef())
                .build();

        String eventRef = new DefaultEventRefFactory().create(EventRefType.WALLET_REQUEST, 701L);
        var outbox = OutboxHelper.fromPostTransactionRequest(701L, eventRef, request, transaction);

        assertEquals(701L, outbox.getInternalId());
        assertEquals(eventRef, outbox.getEventRef());
        assertEquals(transaction.getWalletTxnRef(), outbox.getCommandRef());
        assertEquals(transaction.getWalletTxnRef(), outbox.getPartitionKey());
        assertEquals(request, PostTransactionRequestPb.parseFrom(outbox.getPayload()));
    }
}
