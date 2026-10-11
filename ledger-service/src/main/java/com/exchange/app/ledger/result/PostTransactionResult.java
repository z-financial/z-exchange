package com.exchange.app.ledger.result;

import com.exchange.common.utils.ValidateHelper;

import java.util.Objects;

// Transport-neutral result for the post ledger use case.
// Keeps exact ledger error codes and metric facts before conversion to protobuf.
public record PostTransactionResult(
        String sourceTxnRef,
        String ledgerTxnRef,
        LedgerServiceErrorCode errorCode,
        String detail,
        Kind kind,
        int entryCount,
        IdempotentSource idempotentSource
) {
    public PostTransactionResult {
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.ERROR) {
            Objects.requireNonNull(errorCode, "errorCode");
        } else {
            if (errorCode != null) {
                throw new IllegalArgumentException("successful post result should not have errorCode");
            }
            if (ValidateHelper.isBlank(sourceTxnRef)) {
                throw new IllegalArgumentException("successful post result requires sourceTxnRef");
            }
            if (ValidateHelper.isBlank(ledgerTxnRef)) {
                throw new IllegalArgumentException("successful post result requires ledgerTxnRef");
            }
        }
        if (kind == Kind.CREATED && entryCount <= 0) {
            throw new IllegalArgumentException("created post result requires positive entryCount");
        }
        if (kind == Kind.IDEMPOTENT_REPLAY) {
            Objects.requireNonNull(idempotentSource, "idempotentSource");
        }
    }

    public static PostTransactionResult created(
            String sourceTxnRef, String ledgerTxnRef, String detail, int entryCount) {
        return new PostTransactionResult(
                sourceTxnRef,
                ledgerTxnRef,
                null,
                detail,
                Kind.CREATED,
                entryCount,
                null
        );
    }

    public static PostTransactionResult idempotentReplay(
            String sourceTxnRef,
            String ledgerTxnRef,
            String detail,
            IdempotentSource source
    ) {
        return new PostTransactionResult(
                sourceTxnRef,
                ledgerTxnRef,
                null,
                detail,
                Kind.IDEMPOTENT_REPLAY,
                0,
                source
        );
    }

    public static PostTransactionResult failure(LedgerServiceErrorCode errorCode, String detail) {
        return new PostTransactionResult(
                "",
                "",
                errorCode,
                detail,
                Kind.ERROR,
                0,
                null
        );
    }

    public boolean isSuccess() {
        return kind != Kind.ERROR;
    }

    public boolean isCreated() {
        return kind == Kind.CREATED;
    }

    public boolean isIdempotentReplay() {
        return kind == Kind.IDEMPOTENT_REPLAY;
    }

    public enum Kind {
        CREATED,
        IDEMPOTENT_REPLAY,
        ERROR
    }

    public enum IdempotentSource {
        REDIS,
        DB
    }
}
