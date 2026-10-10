package com.exchange.common.exception;

import com.exchange.common.idgen.IdGeneratorContext;
import com.exchange.common.result.error.IdGeneratorErrorCode;
import com.zed.idgen.snowflake.core.exception.SnowflakeErrorCode;
import com.zed.idgen.snowflake.core.exception.SnowflakeException;
import lombok.Getter;

/**
 * Project-level ID generation failure. The original library failure is retained
 * as the cause while callers see a stable application error contract.
 */
@Getter
public final class IdGeneratorException extends CustomizedException {
    private final IdGeneratorContext generatorContext;
    private final SnowflakeErrorCode sourceErrorCode;

    private IdGeneratorException(
            IdGeneratorErrorCode errorCode,
            IdGeneratorContext generatorContext,
            String message,
            Throwable cause) {
        super(errorCode, message, cause);
        this.generatorContext = generatorContext;
        this.sourceErrorCode = sourceErrorCode(cause);
    }

    public static IdGeneratorException configuration(IdGeneratorContext context, String message) {
        return new IdGeneratorException(IdGeneratorErrorCode.CONFIGURATION, context, message, null);
    }

    public static IdGeneratorException configuration(
            IdGeneratorContext context,
            String message,
            Throwable cause) {
        return new IdGeneratorException(IdGeneratorErrorCode.CONFIGURATION, context, message, cause);
    }

    public static IdGeneratorException registration(IdGeneratorContext context, Throwable cause) {
        return new IdGeneratorException(
                IdGeneratorErrorCode.REGISTRATION_FAILED,
                context,
                IdGeneratorErrorCode.REGISTRATION_FAILED.getMessage(),
                cause);
    }

    public static IdGeneratorException acquisition(IdGeneratorContext context, Throwable cause) {
        return new IdGeneratorException(
                IdGeneratorErrorCode.ACQUISITION_FAILED,
                context,
                IdGeneratorErrorCode.ACQUISITION_FAILED.getMessage(),
                cause);
    }

    public static IdGeneratorException allocation(IdGeneratorContext context, Throwable cause) {
        if (cause instanceof SnowflakeException snowflakeException) {
            return fromSnowflake(context, snowflakeException);
        }
        return new IdGeneratorException(
                IdGeneratorErrorCode.ALLOCATION_FAILED,
                context,
                IdGeneratorErrorCode.ALLOCATION_FAILED.getMessage(),
                cause);
    }

    private static IdGeneratorException fromSnowflake(
            IdGeneratorContext context,
            SnowflakeException cause) {
        IdGeneratorErrorCode errorCode = switch (cause.getErrorCode()) {
            case ClockRollback -> IdGeneratorErrorCode.CLOCK_ROLLBACK;
            case IdRunOut -> IdGeneratorErrorCode.SEQUENCE_EXHAUSTED;
            case LeaseExpired, GeneratorNotStarted, GeneratorStopped, RuntimeManagerClosed ->
                    IdGeneratorErrorCode.UNAVAILABLE;
            default -> IdGeneratorErrorCode.ALLOCATION_FAILED;
        };
        return new IdGeneratorException(errorCode, context, errorCode.getMessage(), cause);
    }

    private static SnowflakeErrorCode sourceErrorCode(Throwable cause) {
        return cause instanceof SnowflakeException snowflakeException
                ? snowflakeException.getErrorCode()
                : null;
    }

}
