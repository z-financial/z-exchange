package com.exchange.common.identity;

import com.exchange.common.result.Result;

import java.util.OptionalLong;

/** Version-one event-reference contract. Delivery boundaries never expose a raw numeric storage ID. */
public final class DefaultEventRefFactory implements EventRefFactory {
    @Override
    public String create(EventRefType type, long internalId) {
        if (type == null) {
            throw EventReferenceException.unknownType(null);
        }
        if (internalId <= 0) {
            throw EventReferenceException.outOfRange();
        }
        return ReferenceFormatSupport.format(type.code(), internalId);
    }

    @Override
    public Result<Void> validate(String eventRef, EventRefType expectedType) {
        try {
            validateOrThrow(eventRef, expectedType);
            return Result.success();
        } catch (EventReferenceException invalidReference) {
            return Result.failure(invalidReference.getErrorCode(), invalidReference.getMessage());
        }
    }

    private void validateOrThrow(String eventRef, EventRefType expectedType) {
        EventRefType actualType = validateOrThrow(eventRef);
        if (expectedType == null || actualType != expectedType) {
            throw EventReferenceException.unexpectedType(expectedType, actualType);
        }
    }

    private EventRefType validateOrThrow(String eventRef) {
        ReferenceFormatSupport.ParsedReferenceParts parts =
                ReferenceFormatSupport.parseCanonicalParts(eventRef);
        if (parts == null) {
            throw EventReferenceException.malformed();
        }

        EventRefType actualType = EventRefType.fromCode(parts.typeCode());
        OptionalLong internalId = ReferenceFormatSupport.decodePositiveLong(parts.encodedId());
        if (internalId.isEmpty()) {
            throw EventReferenceException.outOfRange();
        }

        if (!ReferenceFormatSupport.checksumMatches(parts)) {
            throw EventReferenceException.checksumInvalid();
        }
        return actualType;
    }
}
