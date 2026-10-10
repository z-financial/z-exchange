package com.exchange.app.ledger.result;

import com.exchange.common.result.error.ErrorCategory;
import com.exchange.common.result.error.ErrorCode;

public enum LedgerServiceErrorCode implements ErrorCode {
    INTERNAL_ERROR(2, ErrorCategory.INTERNAL, "internal_error"),
    SERVER_ERROR(3, ErrorCategory.INTERNAL, "server_error"),
    DB_ERROR(11, ErrorCategory.INTERNAL, "db_error"),
    SERIALIZE_ERROR(12, ErrorCategory.INTERNAL, "serialize_error"),
    INVALID_VALUE_ERROR(13, ErrorCategory.INTERNAL, "invalid_value_error"),
    INVALID_ENUM_ERROR(14, ErrorCategory.INTERNAL, "invalid_enum_error"),
    INVALID_REQUEST_PARAMETER(100, ErrorCategory.INVALID_ARGUMENT, "invalid_request_parameter"),
    REQUEST_HASH_CONFLICT(200, ErrorCategory.FAILED_PRECONDITION, "request_hash_conflict"),
    REQUEST_IN_PROCESSING(201, ErrorCategory.PROCESSING, "request_in_processing"),
    LEDGER_DUPLICATED(1000, ErrorCategory.ALREADY_EXISTS, "ledger_duplicated"),
    LEDGER_NOT_FOUND(1001, ErrorCategory.NOT_FOUND, "ledger_not_found"),
    ACCOUNT_NOT_FOUND(1100, ErrorCategory.NOT_FOUND, "account_not_found"),
    ACCOUNT_INFO_MISMATCH(1101, ErrorCategory.FAILED_PRECONDITION, "account_info_mismatch"),
    ASSET_NOT_FOUND(1200, ErrorCategory.NOT_FOUND, "asset_not_found"),
    LEDGER_OUTBOX_DUPLICATED(2000, ErrorCategory.ALREADY_EXISTS, "ledger_outbox_duplicated");

    private static final String NAMESPACE = "ledger";

    private final int code;
    private final ErrorCategory category;
    private final String message;

    LedgerServiceErrorCode(int code, ErrorCategory category, String message) {
        this.code = code;
        this.category = category;
        this.message = message;
    }

    @Override
    public int getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }

    @Override
    public ErrorCategory getCategory() {
        return category;
    }

    @Override
    public String getNamespace() {
        return NAMESPACE;
    }
}
