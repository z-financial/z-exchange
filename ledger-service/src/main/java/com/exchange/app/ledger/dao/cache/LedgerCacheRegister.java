package com.exchange.app.ledger.dao.cache;

import com.exchange.app.ledger.constant.cache.CacheScope;
import com.exchange.app.ledger.constant.cache.CacheTtlConfig;
import com.exchange.app.ledger.metrics.LedgerMetricTagValues;
import com.exchange.app.ledger.po.ledger.LedgerTxn;
import com.exchange.common.redis.cache.component.codec.impl.ValueCodec;
import com.exchange.common.redis.cache.component.codec.impl.VersionCodec;
import com.exchange.common.redis.cache.component.support.DefaultCacheReader;
import com.exchange.common.redis.cache.component.support.RawCacheDeleter;
import com.exchange.common.redis.cache.component.support.RawCacheWriter;
import com.exchange.common.redis.cache.component.support.VersionCacheWriter;
import com.exchange.common.redis.cache.strategy.feature.NegativeCacheFeature;
import com.exchange.common.redis.cache.strategy.feature.SelfRecoverFeature;
import com.exchange.common.redis.cache.strategy.feature.impl.DefaultNegativeCacheFeature;
import com.exchange.common.redis.cache.strategy.feature.impl.RawDeleteSelfRecoverFeature;
import com.exchange.common.redis.cache.strategy.model.RawStrategyConfig;
import com.exchange.common.redis.cache.strategy.model.StrategyType;
import com.exchange.common.redis.cache.strategy.model.VersionStrategyConfig;
import com.exchange.common.redis.cache.strategy.RawCacheStrategy;
import com.exchange.common.redis.cache.strategy.VersionCacheStrategy;
import com.exchange.common.redis.cache.strategy.metrics.MetricRawCacheStrategy;
import com.exchange.common.redis.cache.strategy.metrics.MetricVersionCacheStrategy;
import com.exchange.common.redis.cache.strategy.model.CacheDescriptor;
import com.exchange.common.redis.cache.strategy.impl.StrategyFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LedgerCacheRegister {
    @Bean(name = "sourceTxnRefCache")
    public RawCacheStrategy<String> sourceTxnRefCache(
            @Qualifier("defaultRawCacheReader") DefaultCacheReader<ValueCodec> cacheReader,
            RawCacheWriter cacheWriter,
            RawCacheDeleter cacheDeleter,
            StrategyFactory factory,
            CacheTtlConfig config,
            MeterRegistry meterRegistry) {
        CacheDescriptor<String> descriptor = new CacheDescriptor<>(
                String.class, CacheScope::sourceTxnRefKey);
        SelfRecoverFeature recoverFeature = new RawDeleteSelfRecoverFeature(cacheDeleter);
        NegativeCacheFeature negativeCacheFeature = new DefaultNegativeCacheFeature(cacheWriter, cacheDeleter);
        var ttlConfig = new RawStrategyConfig.TtlConfig(
                config.getDefaultRefStrategy(),
                config.getNegativeStrategy(),
                config.getTombstoneStrategy());
        RawStrategyConfig strategyConfig = new RawStrategyConfig(
                StrategyType.CACHE_ASIDE,
                ttlConfig,
                recoverFeature,
                negativeCacheFeature);
        RawCacheStrategy<String> strategy = factory.buildRawCacheSuppressExceptionStrategy(
                descriptor, cacheReader, cacheWriter, strategyConfig);
        return new MetricRawCacheStrategy<>(
                strategy, meterRegistry, LedgerMetricTagValues.CacheTypes.SOURCE_TXN_REF);
    }

    @Bean(name = "ledgerTxnRefCache")
    public VersionCacheStrategy<LedgerTxn> ledgerTxnRefCache(
            @Qualifier("defaultVersionCacheReader") DefaultCacheReader<VersionCodec> cacheReader,
            VersionCacheWriter cacheWriter,
            RawCacheDeleter cacheDeleter,
            StrategyFactory factory,
            CacheTtlConfig config,
            MeterRegistry meterRegistry) {
        CacheDescriptor<LedgerTxn> descriptor = new CacheDescriptor<>(
                LedgerTxn.class, CacheScope::ledgerTxnRefKey);
        SelfRecoverFeature recoverFeature = new RawDeleteSelfRecoverFeature(cacheDeleter);
        var ttlConfig = new VersionStrategyConfig.TtlConfig(
                config.getDefaultEntityStrategy(),
                config.getNegativeStrategy(),
                config.getTombstoneStrategy());
        VersionStrategyConfig versionStrategyConfig = new VersionStrategyConfig(
                StrategyType.CACHE_ASIDE,
                ttlConfig,
                recoverFeature,
                NegativeCacheFeature.disable());
        VersionCacheStrategy<LedgerTxn> strategy = factory.buildVersionCacheSuppressExceptionStrategy(
                descriptor, cacheReader, cacheWriter, versionStrategyConfig);
        return new MetricVersionCacheStrategy<>(
                strategy, meterRegistry, LedgerMetricTagValues.CacheTypes.LEDGER_TXN_REF);
    }
}
