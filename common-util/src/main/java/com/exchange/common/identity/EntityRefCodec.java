package com.exchange.common.identity;

/**
 * The only supported encoder and parser for generated entity references.
 * Business code must treat the encoded value as opaque.
 */
public interface EntityRefCodec {
    String encode(EntityRefType type, long internalId);

    DecodedEntityRef decode(String reference);

    default long decode(String reference, EntityRefType expectedType) {
        DecodedEntityRef decoded = decode(reference);
        if (decoded.type() != expectedType) {
            throw EntityReferenceException.unexpectedType(expectedType, decoded.type());
        }
        return decoded.internalId();
    }
}
