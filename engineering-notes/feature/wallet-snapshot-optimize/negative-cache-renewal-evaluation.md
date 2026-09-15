# LedgerTxnStore Negative Cache Renewal Evaluation

## Context

`LedgerTxnStore.getByRefId()` currently treats every successful `null` returned from `doGetByRefId()` as a DB miss:

```java
Result<LedgerTxn> txnResult = doGetByRefId(refId);
if (txnResult.isSuccess() && txnResult.getValue() == null) {
    ledgerRefCache.afterDbMiss(refId);
}
```

That is not precise enough. `doGetByRefId()` can return `Result.success(null)` from at least two materially different paths:

- A `ledgerRefCache` negative-cache hit (`CacheType.NEGATIVE`).
- A real DB miss from `ledgerTxnRepository.getByRefId(refId)`.

Because `ledgerRefCache` is a raw cache and `RawCacheWriter.setNegative()` uses unconditional `set(key, value, ttl)`, calling `afterDbMiss()` after a negative hit refreshes the negative TTL. With repeated miss traffic, the negative entry can be kept alive much longer than the configured `negative-ttl` of 30 seconds.

## Evaluation

The optimization target is valid and should be fixed before relying on the ref-id cache path. The current behavior makes negative TTL a sliding TTL under repeated traffic, even though the configured TTL reads like a bounded suppression window. For a user-facing ref-id lookup, that means a ref-id that is later created can remain hidden by a repeatedly renewed negative entry unless `postInsert()` clears it successfully and wins all races.

Design A is the better fit for the immediate ledger-service change. Returning `Result<QueryInfo<LedgerTxn>>` from `doGetByRefId()` keeps the fix local to `LedgerTxnStore`, preserves the existing cache abstraction, and directly records whether the final result came from a DB miss or a cache negative hit. `getByRefId()` can then call `ledgerRefCache.afterDbMiss(refId)` only when `QueryInfo.resultType == DB_MISS`.

Design B is too broad as stated. `RawCacheStrategy.get()` and `VersionCacheStrategy.get()` already return `Result<CacheValueInfo<T>>`, and `CacheValueInfo` already carries `CacheType.NEGATIVE`, `CacheType.TOMBSTONE`, and `version`. Replacing that with `Result<T>` would either lose cache metadata or force expected cache states into generic `Result` failures. That would conflict with the current store convention where cache infrastructure failures are logged and downgraded, while cache miss, negative hit, and tombstone are normal read states.

## Project-Specific Risks

The important bug is not Redis decoding. `DefaultCacheReader` and `ValueCodec` already preserve negative/tombstone information as `CacheValueInfo`. The bug is that `LedgerTxnStore` collapses several paths into `Result.success(null)` and makes a write decision outside the method that knows the source of the null.

`CacheValueInfo.ifCacheHit()` is also a source of ambiguity. It returns true for valid values and negative hits, but false for tombstones and physical cache misses. That helper is convenient for fast return, but it is not sufficient when the caller needs to decide whether to write a new negative cache entry.

There is a second hidden issue in the current ref-id flow. If `ledgerRefCache` has a positive `refId -> txnId` entry but `getByTxnId(txnId)` returns `null`, the outer `getByRefId()` currently treats that as a ref-id miss and may set a negative ref cache. That can overwrite a positive ref mapping even though no DB lookup by `reference_id` was performed. Ledger transactions are intended to be stable, so this should be rare, but it is exactly the kind of cache inconsistency path that should not create a negative entry.

Tombstone should not be modeled as a final "not found" result for this flow. In the current versioned entity cache, tombstone means "do not trust cached entity; go to DB", not "the row does not exist". The raw ref-id cache does not normally create tombstones, but if a tombstone is observed, the safer behavior is to treat it as cache miss and query DB.

`postInsert()` currently calls `ledgerRefCache.afterInsert(referenceId, txnId)`, and for `CACHE_ASIDE` raw strategy that clears negative cache rather than populating the positive ref mapping. That is consistent with cache-aside, but it leaves a stale-negative race: a DB miss request that started before insert commit can still set a negative after `postInsert()` clears it. Fixing renewal does not solve that race; it only prevents repeated negative hits from extending the stale period.

## Recommendation

Use a refined Design A now.

Suggested local model:

```java
private record RefLookupInfo(LedgerTxn txn, RefLookupSource source) {}

private enum RefLookupSource {
    CACHE_VALUE_HIT,
    CACHE_NEGATIVE_HIT,
    DB_HIT,
    DB_MISS
}
```

Then `getByRefId()` should set negative only for `DB_MISS`. It should not set negative for `CACHE_NEGATIVE_HIT`, `CACHE_VALUE_HIT` with downstream entity miss, cache errors, or tombstone-observed fallback paths unless a real `ledgerTxnRepository.getByRefId(refId)` was executed and returned null.

Keep operation semantics explicit:

- Cache negative hit: return not found quickly, do not renew negative TTL.
- Cache physical miss: query DB; set negative only if DB returns null.
- Cache tombstone/malformed recovered path: query DB; set negative only if DB returns null.
- Positive ref hit: resolve by txn id; if txn id resolution fails, prefer deleting/refetching the ref mapping or falling back to `getByRefId(refId)` before considering a negative write.

Do not implement Design B as `Result<T>`. If this pattern repeats in wallet-service and ledger-service, introduce a cache-specific read abstraction instead:

```java
record CacheReadResult<T>(T value, CacheReadStatus status, long version) {}

enum CacheReadStatus {
    VALUE,
    MISS,
    NEGATIVE,
    TOMBSTONE
}
```

That would be a later common-util refactor, not the minimal fix. It should preserve version metadata for `VersionCacheStrategy` and should not encode cache miss/negative/tombstone as generic `Result.failure()`.

## Concrete Improvements

For the immediate change:

- Add a small private record/enum inside `LedgerTxnStore` instead of changing shared cache interfaces.
- Rename `Miss` to `DB_MISS`; plain `Miss` is ambiguous because it can mean cache miss or DB miss.
- Avoid `TombStone` as a final result type for `doGetByRefId()` unless the caller has a specific retry/backoff behavior. In this store, tombstone should fall through to DB.
- Add tests that verify a negative-cache hit does not call `ledgerRefCache.afterDbMiss(refId)`.
- Add tests that verify a real DB miss does call `ledgerRefCache.afterDbMiss(refId)`.
- Add a regression test for stale positive ref cache resolving to missing txn id; the store should not overwrite the ref key with a negative without checking DB by ref id.

For future common cache cleanup:

- Consider changing raw negative writes from unconditional `set()` to `setIfAbsent()` so an accidental repeated `afterDbMiss()` cannot renew a negative TTL or overwrite a positive key.
- If `setIfAbsent()` is adopted, evaluate insert races separately. It reduces renewal/overwrite risk, but it does not fully prevent stale negative creation after a concurrent insert unless `postInsert()` populates the positive ref cache or a stronger version/token guard is introduced.
- If wallet-service keeps the same ref-id negative pattern, apply the same query-source distinction there as well; `BalanceSnapshotStore.getByRefId()` has the same TODO and the same renewal risk.

## Verdict

The design direction is reasonable, but Design A should be narrowed and named more precisely. The immediate problem is a store-layer loss of source information, not a missing Redis cache type. Fixing that locally gives the best trade-off: minimal blast radius, clear behavior, and no shared API churn.

Design B should be postponed unless multiple stores need the same semantics and the project is ready to introduce a cache-specific result type. As proposed, returning `Result<T>` from cache strategies is weaker than the current `Result<CacheValueInfo<T>>` contract and risks mixing normal cache states with real cache errors.