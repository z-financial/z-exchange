package com.exchange.common.result.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum IdGeneratorErrorCode implements ErrorCode {
    CONFIGURATION(-1700, ErrorCategory.INTERNAL, "id_generator_configuration"),
    REGISTRATION_FAILED(-1701, ErrorCategory.UNAVAILABLE, "id_generator_registration_failed"),
    ACQUISITION_FAILED(-1702, ErrorCategory.UNAVAILABLE, "id_generator_acquisition_failed"),
    UNAVAILABLE(-1703, ErrorCategory.UNAVAILABLE, "id_generator_unavailable"),
    CLOCK_ROLLBACK(-1704, ErrorCategory.UNAVAILABLE, "id_generator_clock_rollback"),
    SEQUENCE_EXHAUSTED(-1705, ErrorCategory.UNAVAILABLE, "id_generator_sequence_exhausted"),
    ALLOCATION_FAILED(-1706, ErrorCategory.INTERNAL, "id_generator_allocation_failed");

    private final int code;
    private final ErrorCategory category;
    private final String message;

    @Override
    public String getNamespace() {
        return ErrorNamespace.ID_GENERATOR;
    }
}
