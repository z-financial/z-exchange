package com.exchange.common.idgen;

import com.zed.idgen.snowflake.core.IdGenerator;
import com.zed.idgen.snowflake.core.generator.IdGeneratorInstance;
import com.zed.idgen.snowflake.core.manager.mysql.MysqlGeneratorRuntimeManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;

import static com.exchange.common.idgen.IdGeneratorAutoConfiguration.DATA_SOURCE_BEAN;
import static com.exchange.common.idgen.IdGeneratorAutoConfiguration.GENERATOR_BEAN;
import static com.exchange.common.idgen.IdGeneratorAutoConfiguration.INTERNAL_GENERATOR_BEAN;
import static com.exchange.common.idgen.IdGeneratorAutoConfiguration.LEASE_GUARD_BEAN;
import static com.exchange.common.idgen.IdGeneratorAutoConfiguration.RUNTIME_MANAGER_BEAN;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IdGeneratorAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IdGeneratorAutoConfiguration.class));

    @Test
    void disabledByDefaultDoesNotCreateGeneratorInfrastructure() {
        contextRunner.run(context -> {
            org.assertj.core.api.Assertions.assertThat(context).doesNotHaveBean(DATA_SOURCE_BEAN);
            org.assertj.core.api.Assertions.assertThat(context).doesNotHaveBean(GENERATOR_BEAN);
        });
    }

    @Test
    void enabledConfigurationUsesCallerManagedDataSourceAndStartsNamedGenerator() {
        DataSource dataSource = mock(DataSource.class);
        MysqlGeneratorRuntimeManager runtimeManager = mock(MysqlGeneratorRuntimeManager.class);
        IdGeneratorInstance generator = mock(IdGeneratorInstance.class);
        when(runtimeManager.resolveGeneratorId("wallet")).thenReturn(11L);
        when(runtimeManager.startGeneratorInstance(11L)).thenReturn(generator);
        when(generator.canGenerate()).thenReturn(true);

        contextRunner
                .withPropertyValues(
                        "app.id-generator.enabled=true",
                        "app.id-generator.domain-name=z-exchange",
                        "app.id-generator.generator-name=wallet",
                        "app.id-generator.server-instance-id=wallet")
                .withBean(DATA_SOURCE_BEAN, DataSource.class, () -> dataSource)
                .withBean(RUNTIME_MANAGER_BEAN, MysqlGeneratorRuntimeManager.class, () -> runtimeManager)
                .run(context -> {
                    org.assertj.core.api.Assertions.assertThat(context.getBean(DATA_SOURCE_BEAN))
                            .isSameAs(dataSource);
                    org.assertj.core.api.Assertions.assertThat(context.getBean(GENERATOR_BEAN))
                            .isSameAs(generator);
                    org.assertj.core.api.Assertions.assertThat(context)
                            .hasSingleBean(InternalIdGenerator.class)
                            .hasBean(INTERNAL_GENERATOR_BEAN)
                            .hasBean(LEASE_GUARD_BEAN);
                });
    }

    @Test
    void bindsExplicitRuntimeSettings() {
        DataSource dataSource = mock(DataSource.class);
        MysqlGeneratorRuntimeManager runtimeManager = mock(MysqlGeneratorRuntimeManager.class);
        IdGeneratorInstance generator = mock(IdGeneratorInstance.class);
        when(runtimeManager.resolveGeneratorId("internal-id")).thenReturn(12L);
        when(runtimeManager.startGeneratorInstance(12L)).thenReturn(generator);
        when(generator.canGenerate()).thenReturn(true);

        contextRunner
                .withPropertyValues(
                        "app.id-generator.enabled=true",
                        "app.id-generator.domain-name=z-exchange-wallet",
                        "app.id-generator.generator-name=internal-id",
                        "app.id-generator.server-instance-id=wallet",
                        "app.id-generator.runtime.lease-term-seconds=12",
                        "app.id-generator.runtime.local-safe-term-millis=9000",
                        "app.id-generator.runtime.ownership-check-interval-millis=1500")
                .withBean(DATA_SOURCE_BEAN, DataSource.class, () -> dataSource)
                .withBean(RUNTIME_MANAGER_BEAN, MysqlGeneratorRuntimeManager.class, () -> runtimeManager)
                .run(context -> {
                    IdGeneratorProperties.Runtime runtime = context
                            .getBean(IdGeneratorProperties.class)
                            .getRuntime();
                    org.assertj.core.api.Assertions.assertThat(runtime.getLeaseTermSeconds()).isEqualTo(12);
                    org.assertj.core.api.Assertions.assertThat(runtime.getLocalSafeTermMillis()).isEqualTo(9000L);
                    org.assertj.core.api.Assertions.assertThat(runtime.getOwnershipCheckIntervalMillis()).isEqualTo(1500L);
                });
    }

    @Test
    void generatorJacksonStackLoadsOnSpringBootManagedClasspath() {
        assertDoesNotThrow(() -> Class.forName(
                "com.zed.idgen.snowflake.core.manager.mysql.MysqlRegistrationLoader"));
    }
}
