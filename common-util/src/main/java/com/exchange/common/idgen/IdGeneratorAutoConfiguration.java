package com.exchange.common.idgen;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zed.idgen.snowflake.core.IdGenerator;
import com.zed.idgen.snowflake.core.manager.mysql.MysqlGeneratorRuntimeManager;
import com.zed.idgen.snowflake.core.manager.mysql.dao.MysqlDaoFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@AutoConfigureAfter(name = {
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration",
        "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration"
})
@ConditionalOnProperty(prefix = "app.id-generator", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(IdGeneratorProperties.class)
public class IdGeneratorAutoConfiguration {
    public static final String DATA_SOURCE_BEAN = "idGeneratorDataSource";
    public static final String DAO_FACTORY_BEAN = "idGeneratorDaoFactory";
    public static final String RUNTIME_MANAGER_BEAN = "idGeneratorRuntimeManager";
    public static final String GENERATOR_BEAN = "zIdGenerator";

    @Bean(name = DATA_SOURCE_BEAN, destroyMethod = "close")
    @ConditionalOnMissingBean(name = DATA_SOURCE_BEAN)
    public HikariDataSource idGeneratorDataSource(IdGeneratorProperties properties) {
        IdGeneratorProperties.DataSource source = properties.getDataSource();
        requireText(source.getJdbcUrl(), "app.id-generator.data-source.jdbc-url");
        requireText(source.getUsername(), "app.id-generator.data-source.username");

        HikariConfig config = new HikariConfig();
        config.setPoolName("z-id-generator");
        config.setJdbcUrl(source.getJdbcUrl());
        config.setUsername(source.getUsername());
        config.setPassword(source.getPassword());
        config.setDriverClassName(source.getDriverClassName());
        config.setMaximumPoolSize(source.getMaximumPoolSize());
        config.setMinimumIdle(source.getMinimumIdle());
        config.setConnectionTimeout(source.getConnectionTimeoutMillis());
        config.setValidationTimeout(source.getValidationTimeoutMillis());
        config.setMaxLifetime(source.getMaxLifetimeMillis());
        config.setKeepaliveTime(source.getKeepaliveTimeMillis());
        return new HikariDataSource(config);
    }

    @Bean(name = DAO_FACTORY_BEAN)
    @ConditionalOnMissingBean(name = DAO_FACTORY_BEAN)
    public MysqlDaoFactory idGeneratorDaoFactory(
            @Qualifier(DATA_SOURCE_BEAN) DataSource dataSource,
            IdGeneratorProperties properties) {
        return new MysqlDaoFactory(
                dataSource,
                properties.getDefaultStatementTimeoutSeconds(),
                properties.getLeaseRequestTimeoutSeconds());
    }

    @Bean(name = RUNTIME_MANAGER_BEAN, destroyMethod = "close")
    @ConditionalOnMissingBean(name = RUNTIME_MANAGER_BEAN)
    public MysqlGeneratorRuntimeManager idGeneratorRuntimeManager(
            @Qualifier(DAO_FACTORY_BEAN) MysqlDaoFactory daoFactory,
            IdGeneratorProperties properties) {
        requireText(properties.getDomainName(), "app.id-generator.domain-name");
        requireText(properties.getServerInstanceId(), "app.id-generator.server-instance-id");
        if (properties.getServerInstanceId().trim().length() > 27) {
            throw new IllegalArgumentException("app.id-generator.server-instance-id must not exceed 27 characters");
        }
        return new MysqlGeneratorRuntimeManager(
                daoFactory,
                properties.getDomainName().trim(),
                properties.getServerInstanceId().trim());
    }

    @Bean(name = GENERATOR_BEAN)
    @ConditionalOnMissingBean(name = GENERATOR_BEAN)
    public IdGenerator zIdGenerator(
            @Qualifier(RUNTIME_MANAGER_BEAN) MysqlGeneratorRuntimeManager runtimeManager,
            IdGeneratorProperties properties) {
        requireText(properties.getGeneratorName(), "app.id-generator.generator-name");
        long generatorId = runtimeManager.resolveGeneratorId(properties.getGeneratorName().trim());
        return runtimeManager.startGeneratorInstance(generatorId);
    }

    private static void requireText(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(propertyName + " must not be blank when ID generation is enabled");
        }
    }
}
