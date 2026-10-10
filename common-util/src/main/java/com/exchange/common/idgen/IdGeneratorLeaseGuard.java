package com.exchange.common.idgen;

import com.exchange.common.exception.IdGeneratorException;
import com.zed.idgen.snowflake.core.IdGenerator;
import com.zed.idgen.snowflake.core.generator.IdGeneratorInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.SmartLifecycle;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Converts permanent generator ownership loss into process termination. Docker
 * restarts exited containers, but it does not restart a merely unhealthy one.
 */
public final class IdGeneratorLeaseGuard implements SmartLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger(IdGeneratorLeaseGuard.class);

    private final IdGeneratorInstance generator;
    private final ConfigurableApplicationContext applicationContext;
    private final IdGeneratorContext generatorContext;
    private final long checkIntervalMillis;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean shutdownStarted = new AtomicBoolean();
    private volatile ScheduledExecutorService executor;

    public IdGeneratorLeaseGuard(
            IdGenerator generator,
            ConfigurableApplicationContext applicationContext,
            IdGeneratorContext generatorContext,
            long checkIntervalMillis) {
        if (generatorContext == null) {
            throw IdGeneratorException.configuration(null, "id generator context must not be null");
        }
        if (!(generator instanceof IdGeneratorInstance instance)) {
            throw IdGeneratorException.configuration(
                    generatorContext,
                    "managed id generator must expose runtime ownership state");
        }
        if (applicationContext == null) {
            throw IdGeneratorException.configuration(generatorContext, "application context must not be null");
        }
        if (checkIntervalMillis <= 0) {
            throw IdGeneratorException.configuration(
                    generatorContext,
                    "ownership check interval must be positive");
        }
        this.generator = instance;
        this.applicationContext = applicationContext;
        this.generatorContext = generatorContext;
        this.checkIntervalMillis = checkIntervalMillis;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(newGuardThreadFactory());
        executor.scheduleWithFixedDelay(
                this::checkOwnership,
                checkIntervalMillis,
                checkIntervalMillis,
                TimeUnit.MILLISECONDS);
    }

    void checkOwnership() {
        if (!running.get()) {
            return;
        }
        boolean canGenerate;
        try {
            canGenerate = generator.canGenerate();
        } catch (RuntimeException failure) {
            // An unreadable ownership state is unsafe; do not keep serving ID writes.
            failClosed(failure);
            return;
        }
        if (!canGenerate) {
            failClosed(null);
        }
    }

    private void failClosed(RuntimeException failure) {
        if (!shutdownStarted.compareAndSet(false, true)) {
            return;
        }
        if (failure == null) {
            LOGGER.error(
                    "ID generator ownership was lost; stopping for a safe restart: service={}, domain={}, generator={}, instance={}",
                    generatorContext.serviceName(),
                    generatorContext.domainName(),
                    generatorContext.generatorName(),
                    generatorContext.serverInstanceId());
        } else {
            LOGGER.error(
                    "ID generator ownership check failed; stopping for a safe restart: service={}, domain={}, generator={}, instance={}",
                    generatorContext.serviceName(),
                    generatorContext.domainName(),
                    generatorContext.generatorName(),
                    generatorContext.serverInstanceId(),
                    failure);
        }
        /*
         * Close the context instead of reporting health alone. The Compose
         * restart policy reacts to process exit, not to an unhealthy status.
         * SmartLifecycle shutdown stops this guard before the generator manager
         * and its DataSource are destroyed.
         */
        try {
            AvailabilityChangeEvent.publish(applicationContext, ReadinessState.REFUSING_TRAFFIC);
        } finally {
            // A failing availability listener must not prevent fail-closed shutdown.
            applicationContext.close();
        }
    }

    @Override
    public void stop() {
        running.set(false);
        ScheduledExecutorService current = executor;
        if (current != null) {
            current.shutdownNow();
        }
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        // Start last and stop first so normal shutdown cannot be mistaken for lease loss.
        return Integer.MAX_VALUE;
    }

    private static ThreadFactory newGuardThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "id-generator-ownership-guard");
            thread.setDaemon(true);
            return thread;
        };
    }
}
