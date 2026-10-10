package com.exchange.common.idgen;

import com.exchange.common.exception.IdGeneratorException;
import com.exchange.common.result.error.IdGeneratorErrorCode;
import com.zed.idgen.snowflake.core.IdGenerator;
import com.zed.idgen.snowflake.core.exception.ClockRollbackException;
import com.zed.idgen.snowflake.core.exception.SnowflakeErrorCode;
import com.zed.idgen.snowflake.core.exception.SnowflakeException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ManagedInternalIdGeneratorTest {
    private static final IdGeneratorContext CONTEXT = new IdGeneratorContext(
            "wallet-service",
            "z-exchange-wallet",
            "internal-id",
            "wallet");

    @Test
    void returnsPositiveGeneratedId() {
        IdGenerator delegate = mock(IdGenerator.class);
        when(delegate.getUid()).thenReturn(42L);

        assertEquals(42L, new ManagedInternalIdGenerator(delegate, CONTEXT).nextId());
    }

    @Test
    void rejectsValueOutsideServiceIdContract() {
        IdGenerator delegate = mock(IdGenerator.class);
        when(delegate.getUid()).thenReturn(0L);

        IdGeneratorException failure = assertThrows(
                IdGeneratorException.class,
                () -> new ManagedInternalIdGenerator(delegate, CONTEXT).nextId());

        assertEquals(IdGeneratorErrorCode.ALLOCATION_FAILED, failure.getErrorCode());
        assertEquals(CONTEXT, failure.getGeneratorContext());
    }

    @Test
    void translatesClockRollbackAndPreservesCause() {
        IdGenerator delegate = mock(IdGenerator.class);
        ClockRollbackException source = new ClockRollbackException("clock moved backwards");
        when(delegate.getUid()).thenThrow(source);

        IdGeneratorException failure = assertThrows(
                IdGeneratorException.class,
                () -> new ManagedInternalIdGenerator(delegate, CONTEXT).nextId());

        assertEquals(IdGeneratorErrorCode.CLOCK_ROLLBACK, failure.getErrorCode());
        assertEquals(SnowflakeErrorCode.ClockRollback, failure.getSourceErrorCode());
        assertEquals(source, failure.getCause());
    }

    @Test
    void translatesSequenceExhaustion() {
        IdGenerator delegate = mock(IdGenerator.class);
        SnowflakeException source = mock(SnowflakeException.class);
        when(source.getErrorCode()).thenReturn(SnowflakeErrorCode.IdRunOut);
        when(delegate.getUid()).thenThrow(source);

        IdGeneratorException failure = assertThrows(
                IdGeneratorException.class,
                () -> new ManagedInternalIdGenerator(delegate, CONTEXT).nextId());

        assertEquals(IdGeneratorErrorCode.SEQUENCE_EXHAUSTED, failure.getErrorCode());
        assertEquals(SnowflakeErrorCode.IdRunOut, failure.getSourceErrorCode());
    }
}
