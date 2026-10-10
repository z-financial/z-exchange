package com.exchange.common.idgen;

import com.zed.idgen.snowflake.core.IdGenerator;

import java.util.Objects;

/** Thin adapter that keeps the generator library API out of service code. */
public final class ManagedInternalIdGenerator implements InternalIdGenerator {
    private final IdGenerator delegate;

    public ManagedInternalIdGenerator(IdGenerator delegate) {
        this.delegate = Objects.requireNonNull(delegate, "id generator must not be null");
    }

    @Override
    public long nextId() {
        long value = delegate.getUid();
        if (value <= 0) {
            throw new IllegalStateException("id generator returned a non-positive value");
        }
        return value;
    }
}
