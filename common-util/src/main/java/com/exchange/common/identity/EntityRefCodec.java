package com.exchange.common.identity;

import com.exchange.common.result.Result;

/**
 * The only supported encoder and parser for generated entity references.
 * Business code must treat the encoded value as opaque.
 */
public interface EntityRefCodec {
    String encode(EntityRefType type, long internalId);

    DecodedEntityRef decode(String reference);

    /** Validates type and integrity without exposing decoded storage identity to business code. */
    default Result<Void> validate(String reference, EntityRefType expectedType) {
        try {
            decode(reference, expectedType);
            return Result.success();
        } catch (EntityReferenceException invalidReference) {
            return Result.failure(invalidReference.getErrorCode(), invalidReference.getMessage());
        }
    }

    default long decode(String reference, EntityRefType expectedType) {
        DecodedEntityRef decoded = decode(reference);
        if (decoded.type() != expectedType) {
            throw EntityReferenceException.unexpectedType(expectedType, decoded.type());
        }
        return decoded.internalId();
    }
}
