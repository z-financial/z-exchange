package com.exchange.common.idgen;

import com.exchange.common.exception.IdGeneratorException;
import com.zed.idgen.snowflake.core.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Thin adapter that keeps the generator library API out of service code. */
public final class ManagedInternalIdGenerator implements InternalIdGenerator {
    private static final Logger LOGGER = LoggerFactory.getLogger(ManagedInternalIdGenerator.class);

    private final IdGenerator delegate;
    private final IdGeneratorContext generatorContext;

    public ManagedInternalIdGenerator(IdGenerator delegate, IdGeneratorContext generatorContext) {
        if (generatorContext == null) {
            throw IdGeneratorException.configuration(null, "id generator context must not be null");
        }
        if (delegate == null) {
            throw IdGeneratorException.configuration(generatorContext, "id generator must not be null");
        }
        this.delegate = delegate;
        this.generatorContext = generatorContext;
    }

    @Override
    public long nextId() {
        try {
            long value = delegate.getUid();
            if (value <= 0) {
                throw IdGeneratorException.allocation(generatorContext, null);
            }
            return value;
        } catch (RuntimeException failure) {
            IdGeneratorException translated =
                    failure instanceof IdGeneratorException idGeneratorFailure
                            ? idGeneratorFailure
                            : IdGeneratorException.allocation(generatorContext, failure);
            logFailure(translated);
            throw translated;
        }
    }

    private void logFailure(IdGeneratorException failure) {
        LOGGER.error(
                "ID allocation failed: service={}, domain={}, generator={}, instance={}, errorCode={}, sourceErrorCode={}",
                generatorContext.serviceName(),
                generatorContext.domainName(),
                generatorContext.generatorName(),
                generatorContext.serverInstanceId(),
                failure.getErrorCode(),
                failure.getSourceErrorCode(),
                failure);
    }
}
