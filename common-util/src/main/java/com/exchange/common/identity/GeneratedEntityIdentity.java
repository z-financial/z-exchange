package com.exchange.common.identity;

/**
 * The two values assigned together when constructing a new owner row.
 * The numeric member remains private to owner persistence boundaries.
 */
public record GeneratedEntityIdentity(long internalId, String reference) {
}
