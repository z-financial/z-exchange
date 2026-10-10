package com.exchange.common.idgen;

import com.zed.idgen.snowflake.core.generator.IdGeneratorInstance;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdGeneratorLeaseGuardTest {

    @Test
    void keepsApplicationRunningWhileOwnershipIsSafe() {
        IdGeneratorInstance generator = mock(IdGeneratorInstance.class);
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        when(generator.canGenerate()).thenReturn(true);

        IdGeneratorLeaseGuard guard = new IdGeneratorLeaseGuard(generator, context, 1_000L);
        guard.start();
        guard.checkOwnership();
        guard.stop();

        verify(context, never()).close();
    }

    @Test
    void closesApplicationWhenOwnershipIsLost() {
        IdGeneratorInstance generator = mock(IdGeneratorInstance.class);
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        when(generator.canGenerate()).thenReturn(false);

        IdGeneratorLeaseGuard guard = new IdGeneratorLeaseGuard(generator, context, 1_000L);
        guard.start();
        guard.checkOwnership();
        guard.stop();

        verify(context).close();
    }

    @Test
    void closesApplicationWhenReadinessPublicationFails() {
        IdGeneratorInstance generator = mock(IdGeneratorInstance.class);
        GenericApplicationContext context = new GenericApplicationContext();
        context.addApplicationListener((ApplicationListener<AvailabilityChangeEvent<?>>) event -> {
            throw new IllegalStateException("listener failed");
        });
        context.refresh();
        when(generator.canGenerate()).thenReturn(false);
        IdGeneratorLeaseGuard guard = new IdGeneratorLeaseGuard(generator, context, 1_000L);
        guard.start();

        assertThrows(IllegalStateException.class, guard::checkOwnership);
        guard.stop();

        assertFalse(context.isActive());
    }
}
