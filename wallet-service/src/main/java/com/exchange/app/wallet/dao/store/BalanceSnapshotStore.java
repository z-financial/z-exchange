package com.exchange.app.wallet.dao.store;

import com.exchange.app.wallet.dao.repository.BalanceSnapshotRepository;
import com.exchange.app.wallet.po.enums.OwnerType;
import com.exchange.app.wallet.po.enums.ServiceId;
import com.exchange.app.wallet.po.wallet.BalanceSnapshot;
import com.exchange.common.component.PromotionClassifier;
import com.exchange.common.result.Result;
import com.exchange.common.redis.cache.model.CacheReadResult;
import com.exchange.common.redis.cache.strategy.RawCacheStrategy;
import com.exchange.common.redis.cache.strategy.VersionCacheStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class BalanceSnapshotStore {
    private final BalanceSnapshotRepository balanceRepository;
    private final RawCacheStrategy<String> hotBalanceRefCache;
    private final RawCacheStrategy<String> balanceRefCache;
    private final VersionCacheStrategy<BalanceSnapshot> hotBalanceCache;
    private final VersionCacheStrategy<BalanceSnapshot> normalBalanceCache;
    private final PromotionClassifier hotBalanceRefClassifier;
    private final PromotionClassifier hotBalanceClassifier;

    public BalanceSnapshotStore(
            BalanceSnapshotRepository balanceRepository,
            @Qualifier("hotBalanceRefCache") RawCacheStrategy<String> hotBalanceRefCache,
            @Qualifier("balanceRefCache") RawCacheStrategy<String> balanceRefCache,
            @Qualifier("hotBalanceCache") VersionCacheStrategy<BalanceSnapshot> hotBalanceCache,
            @Qualifier("normalBalanceCache") VersionCacheStrategy<BalanceSnapshot> normalBalanceCache,
            @Qualifier("hotBalanceClassifier") PromotionClassifier hotBalanceClassifier,
            @Qualifier("hotBalanceRefClassifier") PromotionClassifier hotBalanceRefClassifier) {
        this.balanceRepository = balanceRepository;
        this.hotBalanceRefCache = hotBalanceRefCache;
        this.balanceRefCache = balanceRefCache;
        this.hotBalanceCache = hotBalanceCache;
        this.normalBalanceCache = normalBalanceCache;
        this.hotBalanceClassifier = hotBalanceClassifier;
        this.hotBalanceRefClassifier = hotBalanceRefClassifier;
    }

    public void postInsert(BalanceSnapshot balanceSnapshot) {
        postInsert(balanceSnapshot.getWalletRef(), balanceSnapshot.getServiceId(),
                balanceSnapshot.getWalletBusinessRef(), balanceSnapshot.getOwnerType());

        Result<Boolean> result = determineBalanceStrategy(balanceSnapshot.getWalletRef())
                .afterInsert(balanceSnapshot.getWalletRef(), balanceSnapshot, balanceSnapshot.getVersion());
        if (!result.isSuccess()) {
            log.error("after_insert, cache strategy failed: {}, snapshot: {}", result, balanceSnapshot);
        }
    }

    public void postInsert(String walletRef, ServiceId serviceId, String businessRef, OwnerType ownerType) {
        String scopedBusinessRef = buildBusinessRefCacheId(serviceId, businessRef);
        promoteHotSnapshot(walletRef, scopedBusinessRef, ownerType);

        Result<Boolean> refResult = determineRefStrategy(scopedBusinessRef)
                .afterInsert(scopedBusinessRef, walletRef);
        if (!refResult.isSuccess()) {
            log.error("clean negative cache failed, service_id={}, wallet_business_ref={}, result={}",
                    serviceId, businessRef, refResult);
        }
    }

    public void postDbUpdate(BalanceSnapshot balanceSnapshot) {
        Result<Boolean> result = determineBalanceStrategy(balanceSnapshot.getWalletRef())
                .afterUpdate(balanceSnapshot.getWalletRef(), balanceSnapshot, balanceSnapshot.getVersion());
        if (!result.isSuccess()) {
            log.error("after_update, cache strategy failed: {}, snapshot: {}", result, balanceSnapshot);
        }
    }

    public Result<BalanceSnapshot> getByWalletRef(String walletRef) {
        var result = doGetByWalletRef(walletRef);
        if (result.isSuccess() && result.getValue() != null) {
            BalanceSnapshot balanceSnapshot = result.getValue();
            String scopedBusinessRef = buildBusinessRefCacheId(balanceSnapshot.getServiceId(),
                    balanceSnapshot.getWalletBusinessRef());
            promoteHotSnapshot(balanceSnapshot.getWalletRef(), scopedBusinessRef, balanceSnapshot.getOwnerType());
        }
        return result;
    }

    private Result<BalanceSnapshot> doGetByWalletRef(String walletRef) {
        var snapshotCache = determineBalanceStrategy(walletRef);

        Result<CacheReadResult<BalanceSnapshot>> cacheResult = snapshotCache.get(walletRef);
        if (!cacheResult.isSuccess()) {
            log.error("get by wallet_ref cache failed, wallet_ref={}, result={}", walletRef, cacheResult);
        } else {
            CacheReadResult<BalanceSnapshot> valueInfo = cacheResult.getValue();
            if (valueInfo.isValueHit() || valueInfo.isNegativeHit()) {
                return Result.success(valueInfo.value());
            }
        }

        BalanceSnapshot balanceSnapshot = balanceRepository.getByWalletRef(walletRef);
        if (balanceSnapshot != null) {
            Result<Boolean> setResult = snapshotCache.afterDbHit(balanceSnapshot.getWalletRef(), balanceSnapshot, balanceSnapshot.getVersion());
            if (!setResult.isSuccess()) {
                log.error("getByWalletRef|afterDbHit cache failed, snapshot: {}, result: {}", balanceSnapshot, setResult);
            }
        } else {
            Result<Boolean> setResult = snapshotCache.afterDbMiss(walletRef);
            if (!setResult.isSuccess()) {
                log.error("afterDbMiss cache failed, wallet_ref={}, result={}", walletRef, setResult);
            }
        }

        return Result.success(balanceSnapshot);
    }

    // negative value for cache miss, key -> NULL
    // querying by ref id is a user-facing path, so keep a stable negative cache here
    public Result<BalanceSnapshot> getByBusinessRef(ServiceId serviceId, String businessRef) {
        String scopedBusinessRef = buildBusinessRefCacheId(serviceId, businessRef);
        Result<BalanceSnapshot> snapshotResult = doGetByBusinessRef(serviceId, businessRef, scopedBusinessRef);
        if (snapshotResult.isSuccess() && snapshotResult.getValue() != null) {
            // try to promote hot snapshot
            BalanceSnapshot balanceSnapshot = snapshotResult.getValue();
            String scopedSnapshotRefId = buildBusinessRefCacheId(balanceSnapshot.getServiceId(), balanceSnapshot.getWalletBusinessRef());
            promoteHotSnapshot(balanceSnapshot.getWalletRef(), scopedSnapshotRefId, balanceSnapshot.getOwnerType());
        }
        return snapshotResult;
    }

    private Result<BalanceSnapshot> doGetByBusinessRef(ServiceId serviceId, String businessRef,
                                                       String scopedBusinessRef) {
        Result<CacheReadResult<String>> refCacheResult = determineRefStrategy(scopedBusinessRef)
                .get(scopedBusinessRef);
        if (!refCacheResult.isSuccess()) {
            log.error("get cache failed, service_id={}, wallet_business_ref={}, result={}",
                    serviceId, businessRef, refCacheResult);
        } else {
            CacheReadResult<String> cacheInfo = refCacheResult.getValue();
            if (cacheInfo.isNegativeHit()) {
                // Do not renew negative cache TTL on a negative-cache hit.
                return Result.success(null);
            }
            if (cacheInfo.isValueHit()) {
                String walletRef = cacheInfo.value();
                Result<BalanceSnapshot> snapshotResult = getByWalletRef(walletRef);
                if (snapshotResult.isFailed() || snapshotResult.getValue() != null) {
                    return snapshotResult;
                }

                log.error("business-ref cache hit but snapshot not found, service_id={}, wallet_business_ref={}, wallet_ref={}",
                        serviceId, businessRef, walletRef);
                return loadByBusinessRefFromDb(serviceId, businessRef, scopedBusinessRef);
            }
        }

        return loadByBusinessRefFromDb(serviceId, businessRef, scopedBusinessRef);
    }

    private Result<BalanceSnapshot> loadByBusinessRefFromDb(ServiceId serviceId, String businessRef,
                                                            String scopedBusinessRef) {
        BalanceSnapshot snapshot = balanceRepository.getByWalletBusinessRef(serviceId, businessRef);
        if (snapshot != null) {
            Result<Boolean> setRefCacheResult = determineRefStrategy(scopedBusinessRef)
                    .afterDbHit(scopedBusinessRef, snapshot.getWalletRef());
            if (!setRefCacheResult.isSuccess()) {
                log.error("set business-ref cache failed, service_id={}, wallet_business_ref={}, result={}",
                        serviceId, businessRef, setRefCacheResult);
            }

            Result<Boolean> result = determineBalanceStrategy(snapshot.getWalletRef())
                    .afterDbHit(snapshot.getWalletRef(), snapshot, snapshot.getVersion());
            if (!result.isSuccess()) {
                log.error("doGetByBusinessRef|afterDbHit cache failed, snapshot: {}, result: {}",
                        snapshot, result);
            }
        } else {
            Result<Boolean> result = determineRefStrategy(scopedBusinessRef).afterDbMiss(scopedBusinessRef);
            if (!result.isSuccess()) {
                log.error("set negative result failed, service_id={}, wallet_business_ref={}, result={}",
                        serviceId, businessRef, result);
            }
        }
        return Result.success(snapshot);
    }

    private void promoteHotSnapshot(String walletRef, String scopedRefId, OwnerType ownerType) {
        if (ownerType == OwnerType.SYSTEM) {
            hotBalanceClassifier.promote(walletRef);
            hotBalanceRefClassifier.promote(scopedRefId);
        }
    }

    private RawCacheStrategy<String> determineRefStrategy(String scopedRefId) {
        return hotBalanceRefClassifier.isPromoted(scopedRefId) ? hotBalanceRefCache : balanceRefCache;
    }

    private VersionCacheStrategy<BalanceSnapshot> determineBalanceStrategy(String walletRef) {
        return hotBalanceClassifier.isPromoted(walletRef) ? hotBalanceCache : normalBalanceCache;
    }

    private String buildBusinessRefCacheId(ServiceId serviceId, String businessRef) {
        return serviceId.code + ":" + businessRef;
    }
}
