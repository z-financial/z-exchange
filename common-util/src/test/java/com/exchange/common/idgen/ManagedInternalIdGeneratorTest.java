package com.exchange.common.idgen;

import com.zed.idgen.snowflake.core.IdGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ManagedInternalIdGeneratorTest {

    @Test
    void returnsPositiveGeneratedId() {
        IdGenerator delegate = mock(IdGenerator.class);
        when(delegate.getUid()).thenReturn(42L);

        assertEquals(42L, new ManagedInternalIdGenerator(delegate).nextId());
    }

    @Test
    void rejectsValueOutsideServiceIdContract() {
        IdGenerator delegate = mock(IdGenerator.class);
        when(delegate.getUid()).thenReturn(0L);

        assertThrows(
                IllegalStateException.class,
                () -> new ManagedInternalIdGenerator(delegate).nextId());
    }
}
