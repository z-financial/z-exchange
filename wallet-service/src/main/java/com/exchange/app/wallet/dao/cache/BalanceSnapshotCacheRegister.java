package com.exchange.app.wallet.dao.cache;

import com.exchange.app.wallet.constant.cache.CacheScope;
import com.exchange.app.wallet.constant.cache.CacheTtlConfig;
import com.exchange.app.wallet.metrics.WalletMetricTagValues;
import com.exchange.app.wallet.po.wallet.BalanceSnapshot;
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
import com.exchange.common.redis.cache.strategy.model.CacheDescriptor;
import com.exchange.common.redis.cache.strategy.RawCacheStrategy;
import com.exchange.common.redis.cache.strategy.VersionCacheStrategy;
import com.exchange.common.redis.cache.strategy.impl.StrategyFactory;
import com.exchange.common.redis.cache.strategy.metrics.MetricRawCacheStrategy;
import com.exchange.common.redis.cache.strategy.metrics.MetricVersionCacheStrategy;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BalanceSnapshotCacheRegister {
    @Bean(name = "balanceRefCache")
    public RawCacheStrategy<String> balanceRefCache(
            @Qualifier("defaultRawCacheReader") DefaultCacheReader<ValueCodec> cacheReader,
            RawCacheWriter cacheWriter,
            RawCacheDeleter cacheDeleter,
            StrategyFactory factory,
            CacheTtlConfig config,
            MeterRegistry meterRegistry) {
        CacheDescriptor<String> descriptor = new CacheDescriptor<>(String.class, CacheScope::balanceSnapshotBusinessRefKey);
        var ttlConfig = new RawStrategyConfig.TtlConfig(
                config.getDefaultRefStrategy(),
                config.getNegativeStrategy(),
                config.getTombstoneStrategy());
        SelfRecoverFeature selfRecoverFeature = new RawDeleteSelfRecoverFeature(cacheDeleter);
        NegativeCacheFeature negativeCacheFeature = new DefaultNegativeCacheFeature(cacheWriter, cacheDeleter);
        RawStrategyConfig strategyConfig = new RawStrategyConfig(
                StrategyType.CACHE_ASIDE,
                ttlConfig,
                selfRecoverFeature,
                negativeCacheFeature);

        RawCacheStrategy<String> strategy = factory.buildRawCacheSuppressExceptionStrategy(
                descriptor, cacheReader, cacheWriter, strategyConfig);
        return new MetricRawCacheStrategy<>(
                strategy, meterRegistry, WalletMetricTagValues.CacheTypes.BALANCE_REF_NORMAL);
    }

    @Bean("hotBalanceRefCache")
    public RawCacheStrategy<String> hotBalanceRefCache(
            @Qualifier("clientSideRawCacheReader") DefaultCacheReader<ValueCodec> cacheReader,
            RawCacheWriter cacheWriter,
            RawCacheDeleter cacheDeleter,
            StrategyFactory factory,
            CacheTtlConfig config,
            MeterRegistry meterRegistry) {
        CacheDescriptor<String> descriptor = new CacheDescriptor<>(String.class, CacheScope::balanceSnapshotBusinessRefKey);
        var ttlConfig = new RawStrategyConfig.TtlConfig(
                config.getDefaultRefStrategy(),
                config.getNegativeStrategy(),
                config.getTombstoneStrategy());
        SelfRecoverFeature selfRecoverFeature = new RawDeleteSelfRecoverFeature(cacheDeleter);
        NegativeCacheFeature negativeCacheFeature = new DefaultNegativeCacheFeature(cacheWriter, cacheDeleter);
        RawStrategyConfig strategyConfig = new RawStrategyConfig(
                StrategyType.POST_REFRESH,
                ttlConfig,
                selfRecoverFeature,
                negativeCacheFeature);

        RawCacheStrategy<String> strategy = factory.buildRawCacheSuppressExceptionStrategy(
                descriptor, cacheReader, cacheWriter, strategyConfig);
        return new MetricRawCacheStrategy<>(
                strategy, meterRegistry, WalletMetricTagValues.CacheTypes.BALANCE_REF_HOT);
    }

    @Bean("normalBalanceCache")
    public VersionCacheStrategy<BalanceSnapshot> normalBalanceCache(
            @Qualifier("defaultVersionCacheReader") DefaultCacheReader<VersionCodec> cacheReader,
            VersionCacheWriter cacheWriter,
            RawCacheDeleter cacheDeleter,
            StrategyFactory factory,
            CacheTtlConfig config,
            MeterRegistry meterRegistry) {
        CacheDescriptor<BalanceSnapshot> descriptor = new CacheDescriptor<>(BalanceSnapshot.class, CacheScope::balanceSnapshotWalletRefKey);
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
        VersionCacheStrategy<BalanceSnapshot> strategy = factory.buildVersionCacheSuppressExceptionStrategy(
                descriptor, cacheReader, cacheWriter, versionStrategyConfig);
        return new MetricVersionCacheStrategy<>(
                strategy, meterRegistry, WalletMetricTagValues.CacheTypes.BALANCE_SNAPSHOT_NORMAL);
    }

    @Bean("hotBalanceCache")
    public VersionCacheStrategy<BalanceSnapshot> hotBalanceCache(
            @Qualifier("clientSideVersionCacheReader") DefaultCacheReader<VersionCodec> cacheReader,
            VersionCacheWriter cacheWriter,
            RawCacheDeleter cacheDeleter,
            StrategyFactory factory,
            CacheTtlConfig config,
            MeterRegistry meterRegistry) {
        CacheDescriptor<BalanceSnapshot> descriptor = new CacheDescriptor<>(BalanceSnapshot.class, CacheScope::balanceSnapshotWalletRefKey);
        SelfRecoverFeature recoverFeature = new RawDeleteSelfRecoverFeature(cacheDeleter);
        // enable negative cache for hot snapshot
        NegativeCacheFeature negativeCacheFeature = new DefaultNegativeCacheFeature(cacheWriter, cacheDeleter);
        var ttlConfig = new VersionStrategyConfig.TtlConfig(
                config.getDefaultEntityStrategy(),
                config.getNegativeStrategy(),
                config.getTombstoneStrategy());
        VersionStrategyConfig versionStrategyConfig = new VersionStrategyConfig(
                StrategyType.POST_REFRESH,
                ttlConfig,
                recoverFeature,
                negativeCacheFeature);
        VersionCacheStrategy<BalanceSnapshot> strategy = factory.buildVersionCacheSuppressExceptionStrategy(
                descriptor, cacheReader, cacheWriter, versionStrategyConfig);
        return new MetricVersionCacheStrategy<>(
                strategy, meterRegistry, WalletMetricTagValues.CacheTypes.BALANCE_SNAPSHOT_HOT);
    }
}
