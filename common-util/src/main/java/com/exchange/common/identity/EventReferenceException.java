package com.exchange.common.identity;

import com.exchange.common.exception.CustomizedException;
import com.exchange.common.result.error.EventReferenceErrorCode;

/** Controlled validation failure for durable event references. */
public final class EventReferenceException extends CustomizedException {
    private EventReferenceException(EventReferenceErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public static EventReferenceException malformed() {
        return new EventReferenceException(
                EventReferenceErrorCode.MALFORMED,
                EventReferenceErrorCode.MALFORMED.getMessage());
    }

    public static EventReferenceException unknownType(String typeCode) {
        return new EventReferenceException(
                EventReferenceErrorCode.UNKNOWN_TYPE,
                "unknown event reference type: " + String.valueOf(typeCode));
    }

    public static EventReferenceException checksumInvalid() {
        return new EventReferenceException(
                EventReferenceErrorCode.CHECKSUM_INVALID,
                EventReferenceErrorCode.CHECKSUM_INVALID.getMessage());
    }

    public static EventReferenceException outOfRange() {
        return new EventReferenceException(
                EventReferenceErrorCode.OUT_OF_RANGE,
                EventReferenceErrorCode.OUT_OF_RANGE.getMessage());
    }

    public static EventReferenceException unexpectedType(EventRefType expected, EventRefType actual) {
        String expectedCode = expected == null ? "null" : expected.code();
        String actualCode = actual == null ? "null" : actual.code();
        return new EventReferenceException(
                EventReferenceErrorCode.UNEXPECTED_TYPE,
                "expected event reference type " + expectedCode + " but received " + actualCode);
    }

}
