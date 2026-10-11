package com.exchange.app.ledger.result;

import com.exchange.common.utils.ValidateHelper;
import com.exchange.proto.ledger.post.PostTransactionReplyPb;

public final class PostTransactionResultConverter {
    private PostTransactionResultConverter() {
    }

    // Protobuf is a transport/event payload detail; keep conversion at service and outbox boundaries.
    public static PostTransactionReplyPb toProto(PostTransactionResult result) {
        if (result == null) {
            result = PostTransactionResult.failure(LedgerServiceErrorCode.SERVER_ERROR, "");
        }

        PostTransactionReplyPb.Builder builder = PostTransactionReplyPb.newBuilder();
        if (ValidateHelper.hasText(result.sourceTxnRef())) {
            builder.setSourceTxnRef(result.sourceTxnRef());
        }
        if (ValidateHelper.hasText(result.ledgerTxnRef())) {
            builder.setLedgerTxnRef(result.ledgerTxnRef());
        }

        if (result.isSuccess()) {
            // Creation and idempotent replay are the same wire outcome. Diagnostic differences
            // must not leak into the public success contract consumed by Wallet.
            builder.setError(PbErrorBuilder.success());
        } else {
            builder.setError(PbErrorBuilder.build(result.errorCode(), result.detail()));
        }
        return builder.build();
    }
}
