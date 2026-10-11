package com.exchange.common.result.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum EventReferenceErrorCode implements ErrorCode {
    MALFORMED(-1810, ErrorCategory.INVALID_ARGUMENT, "malformed event reference"),
    UNKNOWN_TYPE(-1811, ErrorCategory.INVALID_ARGUMENT, "unknown event reference type"),
    CHECKSUM_INVALID(-1812, ErrorCategory.INVALID_ARGUMENT, "invalid event reference checksum"),
    OUT_OF_RANGE(-1813, ErrorCategory.INVALID_ARGUMENT, "event reference source ID is out of range"),
    UNEXPECTED_TYPE(-1814, ErrorCategory.INVALID_ARGUMENT, "unexpected event reference type");

    private final int code;
    private final ErrorCategory category;
    private final String message;

    @Override
    public String getNamespace() {
        return ErrorNamespace.EVENT_REFERENCE;
    }
}
