package com.exchange.common.idgen;

/** Non-secret identity attached to ID-generator operational signals. */
public record IdGeneratorContext(
        String serviceName,
        String domainName,
        String generatorName,
        String serverInstanceId) {
}
