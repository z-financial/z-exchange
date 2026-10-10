package com.exchange.common.identity;

import com.exchange.common.idgen.InternalIdGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultEntityIdentityFactoryTest {
    @Test
    void allocationAndEncodingUseTheSameGeneratedValue() {
        InternalIdGenerator generator = () -> 123_456_789L;
        EntityRefCodec codec = new DefaultEntityRefCodec();
        EntityIdentityFactory factory = new DefaultEntityIdentityFactory(generator, codec);

        GeneratedEntityIdentity identity = factory.next(EntityRefType.WALLET_TRANSACTION);

        assertEquals(123_456_789L, identity.internalId());
        assertEquals("WTX-0000008M0kX-1WJJKr", identity.reference());
        assertEquals(123_456_789L,
                codec.decode(identity.reference(), EntityRefType.WALLET_TRANSACTION));
    }
}
