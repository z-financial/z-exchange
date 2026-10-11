package com.exchange.app.ledger.dao.store;

import com.exchange.app.ledger.dao.repository.LedgerTxnRepository;
import com.exchange.app.ledger.po.ledger.LedgerTxn;
import com.exchange.common.redis.cache.model.CacheReadResult;
import com.exchange.common.redis.cache.strategy.RawCacheStrategy;
import com.exchange.common.redis.cache.strategy.VersionCacheStrategy;
import com.exchange.common.result.Result;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LedgerTxnStoreUnitTest {
    @Test
    void getBySourceTxnRefShouldNotRenewNegativeCacheOnNegativeHit() {
        LedgerTxnRepository repository = mock(LedgerTxnRepository.class);
        VersionCacheStrategy<LedgerTxn> txnCache = mock(VersionCacheStrategy.class);
        RawCacheStrategy<String> refCache = mock(RawCacheStrategy.class);
        LedgerTxnStore store = new LedgerTxnStore(repository, txnCache, refCache);

        when(refCache.get("ref-1")).thenReturn(Result.success(CacheReadResult.<String>negativeHit()));

        Result<LedgerTxn> result = store.getBySourceTxnRef("ref-1");

        assertTrue(result.isSuccess());
        assertNull(result.getValue());
        verify(refCache, never()).afterDbMiss(anyString());
        verifyNoInteractions(repository);
    }

    @Test
    void getBySourceTxnRefShouldSetNegativeOnlyAfterDbMiss() {
        LedgerTxnRepository repository = mock(LedgerTxnRepository.class);
        VersionCacheStrategy<LedgerTxn> txnCache = mock(VersionCacheStrategy.class);
        RawCacheStrategy<String> refCache = mock(RawCacheStrategy.class);
        LedgerTxnStore store = new LedgerTxnStore(repository, txnCache, refCache);

        when(refCache.get("ref-1")).thenReturn(Result.success(CacheReadResult.<String>miss()));
        when(repository.getBySourceTxnRef("ref-1")).thenReturn(null);
        when(refCache.afterDbMiss("ref-1")).thenReturn(Result.success(Boolean.TRUE));

        Result<LedgerTxn> result = store.getBySourceTxnRef("ref-1");

        assertTrue(result.isSuccess());
        assertNull(result.getValue());
        verify(repository).getBySourceTxnRef("ref-1");
        verify(refCache).afterDbMiss("ref-1");
    }

    @Test
    void getBySourceTxnRefShouldFallbackToDbWhenCachedLedgerRefIsStale() {
        LedgerTxnRepository repository = mock(LedgerTxnRepository.class);
        VersionCacheStrategy<LedgerTxn> txnCache = mock(VersionCacheStrategy.class);
        RawCacheStrategy<String> refCache = mock(RawCacheStrategy.class);
        LedgerTxnStore store = new LedgerTxnStore(repository, txnCache, refCache);
        LedgerTxn dbTxn = LedgerTxn.create(2L, "txn-2", "ref-1");

        when(refCache.get("ref-1")).thenReturn(Result.success(CacheReadResult.valueHit("txn-stale")));
        when(txnCache.get("txn-stale")).thenReturn(Result.success(CacheReadResult.<LedgerTxn>miss()));
        when(repository.getByLedgerTxnRef("txn-stale")).thenReturn(null);
        when(txnCache.afterDbMiss("txn-stale")).thenReturn(Result.success(Boolean.TRUE));
        when(repository.getBySourceTxnRef("ref-1")).thenReturn(dbTxn);
        when(refCache.afterDbHit("ref-1", "txn-2")).thenReturn(Result.success(Boolean.TRUE));
        when(txnCache.afterDbHit("txn-2", dbTxn, dbTxn.getVersion())).thenReturn(Result.success(Boolean.TRUE));

        Result<LedgerTxn> result = store.getBySourceTxnRef("ref-1");

        assertTrue(result.isSuccess());
        assertSame(dbTxn, result.getValue());
        verify(repository).getBySourceTxnRef("ref-1");
        verify(refCache, never()).afterDbMiss("ref-1");
    }
}
