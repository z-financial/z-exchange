package com.exchange.common.identity;

import java.util.OptionalLong;

/** Canonical implementation of the version-one entity reference contract. */
public final class DefaultEntityRefCodec implements EntityRefCodec {
    @Override
    public String encode(EntityRefType type, long internalId) {
        if (type == null) {
            throw EntityReferenceException.unknownType(null);
        }
        if (internalId <= 0) {
            throw EntityReferenceException.outOfRange();
        }

        return ReferenceFormatSupport.format(type.getCode(), internalId);
    }

    @Override
    public DecodedEntityRef decode(String reference) {
        if (reference != null && reference.length() > ReferenceFormatSupport.REFERENCE_LENGTH) {
            throw EntityReferenceException.overLength(
                    reference.length(), ReferenceFormatSupport.REFERENCE_LENGTH);
        }

        ReferenceFormatSupport.ParsedReferenceParts parts =
                ReferenceFormatSupport.parseCanonicalParts(reference);
        if (parts == null) {
            throw EntityReferenceException.malformed();
        }

        EntityRefType type = EntityRefType.fromCode(parts.typeCode());
        OptionalLong internalId = ReferenceFormatSupport.decodePositiveLong(parts.encodedId());
        if (internalId.isEmpty()) {
            throw EntityReferenceException.outOfRange();
        }

        if (!ReferenceFormatSupport.checksumMatches(parts)) {
            throw EntityReferenceException.checksumInvalid();
        }

        return new DecodedEntityRef(type, internalId.getAsLong());
    }
}
