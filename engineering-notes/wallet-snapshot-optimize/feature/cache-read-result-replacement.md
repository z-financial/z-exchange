# CacheReadResult Replacement Change Doc

## Summary

This change replaces `CacheValueInfo<T>` with `CacheReadResult<T>` in `common-util` and migrates current store callers in ledger-service and wallet-service.

The goal is to make cache read state explicit and reusable across stores:

- `VALUE_HIT`: cache contains a usable value.
- `CACHE_MISS`: Redis key is physically absent.
- `NEGATIVE_HIT`: cache contains a negative value.
- `TOMBSTONE_HIT`: cache contains a versioned tombstone and the caller should query DB.

`CacheType` remains as the low-level codec/wire marker (`STRING`, `JSON`, `NEGATIVE`, `TOMBSTONE`). Store code no longer branches on `CacheType`; it branches on cache read semantics.

## Files Changed

- Added `common-util/src/main/java/com/exchange/common/redis/cache/model/CacheReadResult.java`.
- Added `common-util/src/main/java/com/exchange/common/redis/cache/model/CacheReadStatus.java`.
- Removed `common-util/src/main/java/com/exchange/common/redis/cache/model/CacheValueInfo.java`.
- Updated `CacheDecoder`, `DefaultCacheReader`, `ValueCodec`, and `VersionCodec` to return `CacheReadResult`.
- Updated `RawCacheStrategy` and `VersionCacheStrategy` `get()` contracts to return `Result<CacheReadResult<T>>`.
- Updated `DefaultRawCacheStrategy`, `DefaultVersionCacheStrategy`, and `StrategyFactory` wrapper signatures.
- Migrated `LedgerTxnStore` and `BalanceSnapshotStore` to explicit cache read states.
- Added focused `LedgerTxnStoreUnitTest` coverage for negative-hit non-renewal, DB-miss negative write, and stale positive ref fallback.

## Behavior Change

Before this change, `DefaultCacheReader.get()` returned `null` for a physical Redis miss, and encoded negative/tombstone state inside `CacheValueInfo.cacheType` when a key existed.

After this change, `DefaultCacheReader.get()` always returns a `CacheReadResult<T>` on successful cache access:

- Physical Redis miss returns `CacheReadResult.miss()`.
- Raw negative marker returns `NEGATIVE_HIT`.
- Raw tombstone marker returns `TOMBSTONE_HIT`.
- Versioned values preserve the decoded cache status and version.

This avoids the previous ambiguity where store code had to combine `null`, `value`, and `CacheType` checks manually.

## Negative Cache Renewal Fix

`LedgerTxnStore.getByRefId()` no longer sets a negative ref cache just because the returned `LedgerTxn` is `null`.

The new flow is:

- Ref cache `NEGATIVE_HIT`: return `Result.success(null)` and do not call `afterDbMiss()`.
- Ref cache `VALUE_HIT`: resolve by txn id. If that resolution returns null, fall back to DB lookup by ref id before deciding cache mutation.
- Ref cache `CACHE_MISS` or `TOMBSTONE_HIT`: query DB by ref id.
- DB ref-id miss: call `ledgerRefCache.afterDbMiss(refId)`.
- DB ref-id hit: refresh `refId -> txnId` and `txnId -> LedgerTxn` caches.

`BalanceSnapshotStore.getByRefId()` now follows the same rule for wallet snapshot ref cache: negative-cache hits do not renew negative TTL; only real DB misses call `afterDbMiss()`.

## Why Replace Instead Of Wrap

Replacing `CacheValueInfo` is better than adding another parallel model because the old API encouraged imprecise checks:

- `CacheValueInfo.ifCacheHit()` treated negative cache as a hit but tombstone as a miss.
- Physical cache miss was represented as `null`, while negative hit was represented as a non-null object with null value.
- Store code had to know codec-level `CacheType` values.

`CacheReadResult` makes the store decision explicit and reusable without exposing the cache wire format.

## Trade-Offs

This is a shared API change, so it has larger blast radius than a local `LedgerTxnStore` fix. The current project impact is still controlled because `CacheValueInfo` usage was limited to common cache internals, `LedgerTxnStore`, and `BalanceSnapshotStore`.

The change does not make cache freshness guaranteed. A request can still observe stale cache state or set a negative after a concurrent insert if the timing is unlucky. The fix specifically prevents repeated negative-cache hits from extending the negative TTL indefinitely.

The change does not alter `RawCacheWriter.setNegative()`, which still writes negative values according to existing strategy behavior. If we later want stronger protection against accidental renewal/overwrite at the writer layer, that should be evaluated separately because it changes stale-positive-cache behavior.

## Follow-Up Suggestions

- Add unit tests around `LedgerTxnStore.getByRefId()` for `NEGATIVE_HIT`, `CACHE_MISS`, and stale positive ref cache resolving to missing txn id.
- Add equivalent `BalanceSnapshotStore.getByRefId()` tests when that store has active cache unit tests again.
- Consider adding codec-level tests that assert physical miss maps to `CACHE_MISS`, negative marker maps to `NEGATIVE_HIT`, and versioned tombstone preserves version.
- Consider a later writer-layer change from raw negative `set()` to `setIfAbsent()` only after deciding how to handle stale positive ref mappings.

## Compatibility Notes

`CacheReadResult` preserves version metadata for versioned cache values, negative markers, and tombstones. Store code should treat `TOMBSTONE_HIT` as a DB-query signal, not as a final not-found result.

`Result.failure()` remains reserved for cache infrastructure failures such as Redis errors, serialization errors, and malformed cache values. `CACHE_MISS`, `NEGATIVE_HIT`, and `TOMBSTONE_HIT` are normal read states.