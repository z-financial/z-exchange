package com.exchange.common.result.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum EntityReferenceErrorCode implements ErrorCode {
    MALFORMED(-1800, ErrorCategory.INVALID_ARGUMENT, "malformed entity reference"),
    UNKNOWN_TYPE(-1801, ErrorCategory.INVALID_ARGUMENT, "unknown entity reference type"),
    CHECKSUM_INVALID(-1802, ErrorCategory.INVALID_ARGUMENT, "invalid entity reference checksum"),
    OVER_LENGTH(-1803, ErrorCategory.INVALID_ARGUMENT, "entity reference exceeds maximum length"),
    OUT_OF_RANGE(-1804, ErrorCategory.INVALID_ARGUMENT, "entity reference internal ID is out of range"),
    UNEXPECTED_TYPE(-1805, ErrorCategory.INVALID_ARGUMENT, "unexpected entity reference type");

    private final int code;
    private final ErrorCategory category;
    private final String message;

    @Override
    public String getNamespace() {
        return ErrorNamespace.ENTITY_REFERENCE;
    }
}
