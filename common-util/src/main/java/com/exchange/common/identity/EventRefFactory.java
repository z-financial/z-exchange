package com.exchange.common.identity;

import com.exchange.common.result.Result;

/** Creates and validates opaque durable message references. */
public interface EventRefFactory {
    String create(EventRefType type, long internalId);

    Result<Void> validate(String eventRef, EventRefType expectedType);
}
