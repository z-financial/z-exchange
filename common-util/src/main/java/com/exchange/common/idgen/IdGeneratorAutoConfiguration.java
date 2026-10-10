package com.exchange.common.idgen;

import com.exchange.common.exception.IdGeneratorException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zed.idgen.snowflake.core.IdGenerator;
import com.zed.idgen.snowflake.core.manager.mysql.MysqlGeneratorRuntimeManager;
import com.zed.idgen.snowflake.core.manager.mysql.MysqlGeneratorRuntimeConfig;
import com.zed.idgen.snowflake.core.manager.mysql.dao.MysqlDaoFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

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
    private static final Logger LOGGER = LoggerFactory.getLogger(IdGeneratorAutoConfiguration.class);

    public static final String CONTEXT_BEAN = "idGeneratorContext";
    public static final String DATA_SOURCE_BEAN = "idGeneratorDataSource";
    public static final String DAO_FACTORY_BEAN = "idGeneratorDaoFactory";
    public static final String RUNTIME_MANAGER_BEAN = "idGeneratorRuntimeManager";
    public static final String GENERATOR_BEAN = "zIdGenerator";
    public static final String INTERNAL_GENERATOR_BEAN = "internalIdGenerator";
    public static final String LEASE_GUARD_BEAN = "idGeneratorLeaseGuard";

    @Bean(name = CONTEXT_BEAN)
    @ConditionalOnMissingBean(name = CONTEXT_BEAN)
    public IdGeneratorContext idGeneratorContext(
            IdGeneratorProperties properties,
            Environment environment) {
        IdGeneratorContext context = new IdGeneratorContext(
                environment.getProperty("spring.application.name"),
                properties.getDomainName(),
                properties.getGeneratorName(),
                properties.getServerInstanceId());
        requireText(context.serviceName(), "spring.application.name", context);
        requireText(context.domainName(), "app.id-generator.domain-name", context);
        requireText(context.generatorName(), "app.id-generator.generator-name", context);
        requireText(context.serverInstanceId(), "app.id-generator.server-instance-id", context);
        if (context.serverInstanceId().trim().length() > 27) {
            throw IdGeneratorException.configuration(
                    context,
                    "app.id-generator.server-instance-id must not exceed 27 characters");
        }
        return new IdGeneratorContext(
                context.serviceName().trim(),
                context.domainName().trim(),
                context.generatorName().trim(),
                context.serverInstanceId().trim());
    }

    @Bean(name = DATA_SOURCE_BEAN, destroyMethod = "close")
    @ConditionalOnMissingBean(name = DATA_SOURCE_BEAN)
    public HikariDataSource idGeneratorDataSource(
            IdGeneratorProperties properties,
            @Qualifier(CONTEXT_BEAN) IdGeneratorContext context) {
        IdGeneratorProperties.DataSource source = properties.getDataSource();
        requireText(source.getJdbcUrl(), "app.id-generator.data-source.jdbc-url", context);
        requireText(source.getUsername(), "app.id-generator.data-source.username", context);

        try {
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
        } catch (RuntimeException failure) {
            IdGeneratorException translated = failure instanceof IllegalArgumentException
                    ? IdGeneratorException.configuration(context, "invalid ID generator data-source configuration", failure)
                    : IdGeneratorException.registration(context, failure);
            logFailure("data-source", context, translated);
            throw translated;
        }
    }

    @Bean(name = DAO_FACTORY_BEAN)
    @ConditionalOnMissingBean(name = DAO_FACTORY_BEAN)
    public MysqlDaoFactory idGeneratorDaoFactory(
            @Qualifier(DATA_SOURCE_BEAN) DataSource dataSource,
            IdGeneratorProperties properties,
            @Qualifier(CONTEXT_BEAN) IdGeneratorContext context) {
        try {
            return new MysqlDaoFactory(
                    dataSource,
                    properties.getDefaultStatementTimeoutSeconds(),
                    properties.getLeaseRequestTimeoutSeconds());
        } catch (RuntimeException failure) {
            IdGeneratorException translated = IdGeneratorException.configuration(
                    context,
                    "invalid ID generator DAO configuration",
                    failure);
            logFailure("configuration", context, translated);
            throw translated;
        }
    }

    @Bean(name = RUNTIME_MANAGER_BEAN, destroyMethod = "close")
    @ConditionalOnMissingBean(name = RUNTIME_MANAGER_BEAN)
    public MysqlGeneratorRuntimeManager idGeneratorRuntimeManager(
            @Qualifier(DAO_FACTORY_BEAN) MysqlDaoFactory daoFactory,
            IdGeneratorProperties properties,
            @Qualifier(CONTEXT_BEAN) IdGeneratorContext context) {
        IdGeneratorProperties.Runtime runtime = properties.getRuntime();
        try {
            MysqlGeneratorRuntimeConfig runtimeConfig = new MysqlGeneratorRuntimeConfig(
                    runtime.getLeaseTermSeconds(),
                    runtime.getLocalSafeTermMillis(),
                    runtime.getClockSkewWindowMillis(),
                    runtime.getReuseSafetyMarginMillis(),
                    runtime.getRenewIntervalMillis(),
                    runtime.getCleanerIntervalMillis(),
                    runtime.getRenewCorePoolSize(),
                    runtime.getRenewMaxPoolSize(),
                    runtime.getRenewQueueSize());
            return new MysqlGeneratorRuntimeManager(
                    daoFactory,
                    context.domainName(),
                    context.serverInstanceId(),
                    runtimeConfig);
        } catch (RuntimeException failure) {
            IdGeneratorException translated = IdGeneratorException.configuration(
                    context,
                    "invalid ID generator runtime configuration",
                    failure);
            logFailure("configuration", context, translated);
            throw translated;
        }
    }

    @Bean(name = GENERATOR_BEAN)
    @ConditionalOnMissingBean(name = GENERATOR_BEAN)
    public IdGenerator zIdGenerator(
            @Qualifier(RUNTIME_MANAGER_BEAN) MysqlGeneratorRuntimeManager runtimeManager,
            @Qualifier(CONTEXT_BEAN) IdGeneratorContext context) {
        long generatorId;
        try {
            generatorId = runtimeManager.resolveGeneratorId(context.generatorName());
        } catch (RuntimeException failure) {
            IdGeneratorException translated = IdGeneratorException.registration(context, failure);
            logFailure("registration", context, translated);
            throw translated;
        }
        try {
            return runtimeManager.startGeneratorInstance(generatorId);
        } catch (RuntimeException failure) {
            IdGeneratorException translated = IdGeneratorException.acquisition(context, failure);
            logFailure("acquisition", context, translated);
            throw translated;
        }
    }

    @Bean(name = INTERNAL_GENERATOR_BEAN)
    @ConditionalOnMissingBean(InternalIdGenerator.class)
    public InternalIdGenerator internalIdGenerator(
            @Qualifier(GENERATOR_BEAN) IdGenerator generator,
            @Qualifier(CONTEXT_BEAN) IdGeneratorContext context) {
        return new ManagedInternalIdGenerator(generator, context);
    }

    @Bean(name = LEASE_GUARD_BEAN)
    @ConditionalOnMissingBean(IdGeneratorLeaseGuard.class)
    public IdGeneratorLeaseGuard idGeneratorLeaseGuard(
            @Qualifier(GENERATOR_BEAN) IdGenerator generator,
            ConfigurableApplicationContext applicationContext,
            IdGeneratorProperties properties,
            @Qualifier(CONTEXT_BEAN) IdGeneratorContext context) {
        return new IdGeneratorLeaseGuard(
                generator,
                applicationContext,
                context,
                properties.getRuntime().getOwnershipCheckIntervalMillis());
    }

    private static void requireText(
            String value,
            String propertyName,
            IdGeneratorContext context) {
        if (value == null || value.isBlank()) {
            throw IdGeneratorException.configuration(
                    context,
                    propertyName + " must not be blank when ID generation is enabled");
        }
    }

    private static void logFailure(
            String stage,
            IdGeneratorContext context,
            IdGeneratorException failure) {
        LOGGER.error(
                "ID generator {} failure: service={}, domain={}, generator={}, instance={}, errorCode={}, sourceErrorCode={}",
                stage,
                context.serviceName(),
                context.domainName(),
                context.generatorName(),
                context.serverInstanceId(),
                failure.getErrorCode(),
                failure.getSourceErrorCode(),
                failure);
    }
}
