package com.exchange.common.identity;

/** Allocates and encodes the identity pair for one new entity owner row. */
public interface EntityIdentityFactory {
    GeneratedEntityIdentity next(EntityRefType type);
}
