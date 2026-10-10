package com.exchange.app.wallet.utils;

import com.exchange.app.wallet.po.transaction.WalletTransaction;
import com.exchange.proto.ledger.post.PostTransactionRequestPb;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OutboxHelperTest {
    @Test
    void preservesAllocatedEventIdAndTransactionRoutingIdentity() throws Exception {
        WalletTransaction transaction = new WalletTransaction();
        transaction.setTxnId("wallet-txn-1");
        PostTransactionRequestPb request = PostTransactionRequestPb.newBuilder()
                .setReferenceId(transaction.getTxnId())
                .build();

        var outbox = OutboxHelper.fromPostTransactionRequest("701", request, transaction);

        assertEquals("701", outbox.getEventId());
        assertEquals(transaction.getTxnId(), outbox.getCommandId());
        assertEquals(transaction.getTxnId(), outbox.getPartitionKey());
        assertEquals(request, PostTransactionRequestPb.parseFrom(outbox.getPayload()));
    }
}
