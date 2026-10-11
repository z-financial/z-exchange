package com.exchange.common.identity;

import com.exchange.common.idgen.InternalIdGenerator;
import com.exchange.common.idgen.IdGeneratorAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Makes the stateless shared codec available to Wallet and Ledger. */
@Configuration(proxyBeanMethods = false)
@AutoConfigureAfter(IdGeneratorAutoConfiguration.class)
public class EntityReferenceAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(EntityRefCodec.class)
    public EntityRefCodec entityRefCodec() {
        return new DefaultEntityRefCodec();
    }

    @Bean
    @ConditionalOnMissingBean(EventRefFactory.class)
    public EventRefFactory eventRefFactory() {
        return new DefaultEventRefFactory();
    }

    @Bean
    @ConditionalOnBean(InternalIdGenerator.class)
    @ConditionalOnMissingBean(EntityIdentityFactory.class)
    public EntityIdentityFactory entityIdentityFactory(
            InternalIdGenerator idGenerator,
            EntityRefCodec refCodec) {
        return new DefaultEntityIdentityFactory(idGenerator, refCodec);
    }
}
