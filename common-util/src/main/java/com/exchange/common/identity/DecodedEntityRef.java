package com.exchange.common.identity;

/** A validated entity reference decoded by the shared codec. */
public record DecodedEntityRef(EntityRefType type, long internalId) {
}
