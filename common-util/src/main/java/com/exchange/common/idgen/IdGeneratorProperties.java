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
}
