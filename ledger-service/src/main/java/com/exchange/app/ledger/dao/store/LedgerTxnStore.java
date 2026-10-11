package com.exchange.app.ledger.dao.store;

import com.exchange.app.ledger.dao.repository.LedgerTxnRepository;
import com.exchange.app.ledger.po.ledger.LedgerTxn;
import com.exchange.common.redis.cache.model.CacheReadResult;
import com.exchange.common.redis.cache.strategy.RawCacheStrategy;
import com.exchange.common.redis.cache.strategy.VersionCacheStrategy;
import com.exchange.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LedgerTxnStore {
    private final LedgerTxnRepository ledgerTxnRepository;
    private final VersionCacheStrategy<LedgerTxn> ledgerTxnRefCache;
    private final RawCacheStrategy<String> sourceTxnRefCache;

    public LedgerTxnStore(
            LedgerTxnRepository ledgerTxnRepository,
            @Qualifier("ledgerTxnRefCache") VersionCacheStrategy<LedgerTxn> ledgerTxnRefCache,
            @Qualifier("sourceTxnRefCache") RawCacheStrategy<String> sourceTxnRefCache) {
        this.ledgerTxnRepository = ledgerTxnRepository;
        this.ledgerTxnRefCache = ledgerTxnRefCache;
        this.sourceTxnRefCache = sourceTxnRefCache;
    }

    public void postInsert(LedgerTxn ledgerTxn) {
        Result<Boolean> result = sourceTxnRefCache.afterInsert(
                ledgerTxn.getSourceTxnRef(), ledgerTxn.getLedgerTxnRef());
        if (!result.isSuccess()) {
            log.error("clean negative cache failed, sourceTxnRef={}, result={}",
                    ledgerTxn.getSourceTxnRef(), result);
        }

        result = ledgerTxnRefCache.afterInsert(
                ledgerTxn.getLedgerTxnRef(), ledgerTxn, ledgerTxn.getVersion());
        if (!result.isSuccess()) {
            log.error("ledger transaction cache insert failed, ledgerTxnRef={}, result={}",
                    ledgerTxn.getLedgerTxnRef(), result);
        }
    }

    // 1. ledger txn is rarely modified, using a version CAS cache here is like overkill
    //    in fact, I'm testing version CAS cache in the ledger txn query,
    //    since ledger service is a dummy ledger and its business flow is simple, which is a good place to do tech drill
    // 2. no need for negative cache, since querying by ledger transaction reference is used internally,
    //    the cache miss is rare, and attack rick is low
    public Result<LedgerTxn> getByLedgerTxnRef(String ledgerTxnRef) {
        // check cache
        Result<CacheReadResult<LedgerTxn>> cacheResult = ledgerTxnRefCache.get(ledgerTxnRef);

        if (!cacheResult.isSuccess()) {
            // cache error does not block the main flow
            log.error("ledger transaction cache get failed, ledgerTxnRef={}, result={}",
                    ledgerTxnRef, cacheResult);
        } else {
            CacheReadResult<LedgerTxn> cacheInfo = cacheResult.getValue();
            if (cacheInfo.isValueHit() || cacheInfo.isNegativeHit()) {
                // return target ledger txn if hit a value cache
                // return null to end the query fast if hit a negative cache
                return Result.success(cacheInfo.value());
            }
        }

        // cache miss or tombstone, query db
        LedgerTxn ledgerTxn = ledgerTxnRepository.getByLedgerTxnRef(ledgerTxnRef);

        // update cache
        if (ledgerTxn != null) {
            Result<Boolean> setResult = ledgerTxnRefCache.afterDbHit(
                    ledgerTxnRef, ledgerTxn, ledgerTxn.getVersion());
            if (!setResult.isSuccess()) {
                // cache error should not block the main flow
                log.error("ledger transaction cache DB-hit write failed, ledgerTxnRef={}, txn={}, result={}",
                        ledgerTxnRef, ledgerTxn, setResult);
            }
        } else {
            Result<Boolean> result = ledgerTxnRefCache.afterDbMiss(ledgerTxnRef);
            if (!result.isSuccess()) {
                log.error("ledger transaction cache DB-miss write failed, ledgerTxnRef={}, result={}",
                        ledgerTxnRef, result);
            }
        }

        return Result.success(ledgerTxn);
    }

    // negative value for cache miss, key -> NULL
    // Querying by the source transaction reference is an external API operation,
    // need to protect external api from random query attack and massive query miss
    // and source_txn_ref -> ledger_txn_ref is an immutable mapping.
    public Result<LedgerTxn> getBySourceTxnRef(String sourceTxnRef) {
        Result<CacheReadResult<String>> refCacheResult = sourceTxnRefCache.get(sourceTxnRef);

        if (!refCacheResult.isSuccess()) {
            // cache error does not block the main flow
            log.error("source transaction reference cache get failed, sourceTxnRef={}, result={}",
                    sourceTxnRef, refCacheResult);
        } else {
            CacheReadResult<String> cacheInfo = refCacheResult.getValue();
            if (cacheInfo.isNegativeHit()) {
                // Do not renew negative cache TTL on a negative-cache hit.
                return Result.success(null);
            }
            if (cacheInfo.isValueHit()) {
                String ledgerTxnRef = cacheInfo.value();
                Result<LedgerTxn> txnResult = getByLedgerTxnRef(ledgerTxnRef);
                if (txnResult.isFailed() || txnResult.getValue() != null) {
                    return txnResult;
                }

                log.warn("source reference cache points to missing transaction, sourceTxnRef={}, ledgerTxnRef={}",
                        sourceTxnRef, ledgerTxnRef);
                return loadBySourceTxnRefFromDb(sourceTxnRef);
            }
        }

        return loadBySourceTxnRefFromDb(sourceTxnRef);
    }

    private Result<LedgerTxn> loadBySourceTxnRefFromDb(String sourceTxnRef) {
        LedgerTxn txn = ledgerTxnRepository.getBySourceTxnRef(sourceTxnRef);
        if (txn != null) {
            Result<Boolean> setRefCacheResult = sourceTxnRefCache.afterDbHit(
                    sourceTxnRef, txn.getLedgerTxnRef());
            if (!setRefCacheResult.isSuccess()) {
                log.error("source reference cache DB-hit write failed, sourceTxnRef={}, result={}",
                        sourceTxnRef, setRefCacheResult);
            }

            Result<Boolean> setCacheResult = ledgerTxnRefCache.afterDbHit(
                    txn.getLedgerTxnRef(), txn, txn.getVersion());
            if (!setCacheResult.isSuccess()) {
                log.error("ledger transaction cache DB-hit write failed, sourceTxnRef={}, ledgerTxnRef={}, txn={}, result={}",
                        sourceTxnRef, txn.getLedgerTxnRef(), txn, setCacheResult);
            }
        } else {
            Result<Boolean> result = sourceTxnRefCache.afterDbMiss(sourceTxnRef);
            if (!result.isSuccess()) {
                log.error("source reference negative-cache write failed, sourceTxnRef={}, result={}",
                        sourceTxnRef, result);
            }
        }
        return Result.success(txn);
    }

    public int updateMetadataByPk(LedgerTxn txn) {
        int affected = ledgerTxnRepository.updateMetadataByPk(txn);
        if (affected == 0) {
            log.debug("update metadata failed, ledgerTxnRef={}, txn={}",
                    txn.getLedgerTxnRef(), txn);
            return 0;
        }
        Result<Boolean> result = ledgerTxnRefCache.afterUpdate(
                txn.getLedgerTxnRef(), txn, txn.getVersion());
        if (!result.isSuccess()) {
            log.error("ledger transaction cache update failed, ledgerTxnRef={}, result={}",
                    txn.getLedgerTxnRef(), result);
        }

        return affected;
    }
}
