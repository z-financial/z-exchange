package com.exchange.common.idgen;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@ConfigurationProperties(prefix = "app.id-generator")
public class IdGeneratorProperties {
    @Setter
    private boolean enabled;
    @Setter
    private String domainName = "z-exchange";
    @Setter
    private String generatorName;
    @Setter
    private String serverInstanceId;
    @Setter
    private int defaultStatementTimeoutSeconds = 5;
    @Setter
    private int leaseRequestTimeoutSeconds = 2;
    private final DataSource dataSource = new DataSource();
    private final Runtime runtime = new Runtime();

    @Setter
    @Getter
    public static class DataSource {
        private String jdbcUrl;
        private String username;
        private String password = "";
        private String driverClassName = "com.mysql.cj.jdbc.Driver";
        private int maximumPoolSize = 4;
        private int minimumIdle = 1;
        private long connectionTimeoutMillis = 2_000L;
        private long validationTimeoutMillis = 1_000L;
        private long maxLifetimeMillis = 1_500_000L;
        private long keepaliveTimeMillis = 300_000L;

    }

    @Setter
    @Getter
    public static class Runtime {
        private int leaseTermSeconds = 10;
        private long localSafeTermMillis = 8_000L;
        private long clockSkewWindowMillis = 1_000L;
        private long reuseSafetyMarginMillis = 100L;
        private long renewIntervalMillis = 5_000L;
        private long cleanerIntervalMillis = 1_000L;
        private int renewCorePoolSize = 2;
        private int renewMaxPoolSize = 4;
        private int renewQueueSize = 1_024;
        private long ownershipCheckIntervalMillis = 1_000L;
    }
}
