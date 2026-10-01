package com.exchange.common.idgen;

import com.zed.idgen.snowflake.core.IdGenerator;
import com.zed.idgen.snowflake.core.manager.mysql.MysqlGeneratorRuntimeManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;

import static com.exchange.common.idgen.IdGeneratorAutoConfiguration.DATA_SOURCE_BEAN;
import static com.exchange.common.idgen.IdGeneratorAutoConfiguration.GENERATOR_BEAN;
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
        IdGenerator generator = mock(IdGenerator.class);
        when(runtimeManager.resolveGeneratorId("wallet")).thenReturn(11L);
        when(runtimeManager.startGeneratorInstance(11L)).thenReturn(generator);

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
                });
    }

    @Test
    void generatorJacksonStackLoadsOnSpringBootManagedClasspath() {
        assertDoesNotThrow(() -> Class.forName(
                "com.zed.idgen.snowflake.core.manager.mysql.MysqlRegistrationLoader"));
    }
}
