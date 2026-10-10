package com.exchange.common.identity;

import com.exchange.common.idgen.InternalIdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class EntityReferenceAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EntityReferenceAutoConfiguration.class));

    @Test
    void providesSharedCodecByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(EntityRefCodec.class);
            assertThat(context.getBean(EntityRefCodec.class)).isInstanceOf(DefaultEntityRefCodec.class);
        });
    }

    @Test
    void preservesApplicationProvidedCodec() {
        EntityRefCodec custom = new DefaultEntityRefCodec();

        contextRunner
                .withBean(EntityRefCodec.class, () -> custom)
                .run(context -> assertThat(context.getBean(EntityRefCodec.class)).isSameAs(custom));
    }

    @Test
    void providesIdentityFactoryWhenManagedGeneratorExists() {
        contextRunner
                .withBean(InternalIdGenerator.class, () -> () -> 42L)
                .run(context -> {
                    assertThat(context).hasSingleBean(EntityIdentityFactory.class);
                    GeneratedEntityIdentity identity = context
                            .getBean(EntityIdentityFactory.class)
                            .next(EntityRefType.LEDGER_ACCOUNT);
                    assertThat(identity.internalId()).isEqualTo(42L);
                    assertThat(identity.reference()).startsWith("LAC-");
                });
    }
}
