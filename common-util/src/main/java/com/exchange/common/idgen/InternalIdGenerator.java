package com.exchange.common.idgen;

/** Application-facing boundary for allocating service-scoped internal IDs. */
@FunctionalInterface
public interface InternalIdGenerator {
    long nextId();
}
