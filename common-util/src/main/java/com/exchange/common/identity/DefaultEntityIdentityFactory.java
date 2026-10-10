package com.exchange.common.identity;

import com.exchange.common.idgen.InternalIdGenerator;

import java.util.Objects;

public final class DefaultEntityIdentityFactory implements EntityIdentityFactory {
    private final InternalIdGenerator idGenerator;
    private final EntityRefCodec refCodec;

    public DefaultEntityIdentityFactory(InternalIdGenerator idGenerator, EntityRefCodec refCodec) {
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.refCodec = Objects.requireNonNull(refCodec, "refCodec");
    }

    @Override
    public GeneratedEntityIdentity next(EntityRefType type) {
        // Callers invoke this before their DB transaction. Keeping allocation
        // and encoding together prevents a row ID and its reference diverging.
        long internalId = idGenerator.nextId();
        return new GeneratedEntityIdentity(internalId, refCodec.encode(type, internalId));
    }
}
