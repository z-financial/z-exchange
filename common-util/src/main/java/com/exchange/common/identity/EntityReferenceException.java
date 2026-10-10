package com.exchange.common.identity;

import com.exchange.common.exception.CustomizedException;
import com.exchange.common.result.error.EntityReferenceErrorCode;

/** Controlled validation failure for generated entity references. */
public final class EntityReferenceException extends CustomizedException {
    private EntityReferenceException(EntityReferenceErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public static EntityReferenceException malformed() {
        return new EntityReferenceException(
                EntityReferenceErrorCode.MALFORMED,
                EntityReferenceErrorCode.MALFORMED.getMessage());
    }

    public static EntityReferenceException unknownType(String typeCode) {
        return new EntityReferenceException(
                EntityReferenceErrorCode.UNKNOWN_TYPE,
                "unknown entity reference type: " + String.valueOf(typeCode));
    }

    public static EntityReferenceException checksumInvalid() {
        return new EntityReferenceException(
                EntityReferenceErrorCode.CHECKSUM_INVALID,
                EntityReferenceErrorCode.CHECKSUM_INVALID.getMessage());
    }

    public static EntityReferenceException overLength(int actualLength, int maximumLength) {
        return new EntityReferenceException(
                EntityReferenceErrorCode.OVER_LENGTH,
                "entity reference length " + actualLength + " exceeds " + maximumLength);
    }

    public static EntityReferenceException outOfRange() {
        return new EntityReferenceException(
                EntityReferenceErrorCode.OUT_OF_RANGE,
                EntityReferenceErrorCode.OUT_OF_RANGE.getMessage());
    }

    public static EntityReferenceException unexpectedType(
            EntityRefType expected,
            EntityRefType actual) {
        return new EntityReferenceException(
                EntityReferenceErrorCode.UNEXPECTED_TYPE,
                "expected entity reference type " + expected.getCode()
                        + " but received " + actual.getCode());
    }
}
