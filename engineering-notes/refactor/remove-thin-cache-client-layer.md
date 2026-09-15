# Thin Cache Client Layer Refactor

## What I inspected

- `common-util/src/main/java/com/exchange/common/redis/cache/client`
  - `SimpleCacheClient`
  - `VersionCacheClient`
  - `VersionAppSideCacheReadClient`
  - `CacheReader`
  - `JsonVersionedCacheRedisClient` (commented legacy file)
- Support layer
  - `common-util/src/main/java/com/exchange/common/redis/cache/component/support/SimpleRedisSupport.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/component/support/VersionedRedisSupport.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/component/support/CacheReadSupport.java`
  - `common-util/src/main/java/com/exchange/common/redis/BaseRedisSupport.java`
  - `common-util/src/main/java/com/exchange/common/redis/BaseAppSideCacheSupport.java`
- Strategy / ops layer
  - `DefaultVersionCacheAsideStrategy`
  - `SimpleBaseOpsImpl`
  - `NegativeCacheOpsImpl`
  - `VersionBaseOpsImpl`
  - `VersionTombstoneOpsImpl`
  - `VersionNegativeCacheOpsImpl`
- Spring registration / injection
  - `SimpleCacheRegister`
  - `VersionCacheRegister`
  - `AppSideCacheRegister`
  - `ledger-service/.../LedgerRegister`
- Tests
  - Searched `common-util/src/test`, `ledger-service/src/test`, `wallet-service/src/test`
  - No focused tests were found for this cache client/support/strategy path

## What was removed

- Removed `SimpleCacheClient`
- Removed `VersionCacheClient`

These two classes were pass-through facades over `SimpleRedisSupport` and `VersionedRedisSupport` respectively. Their only non-trivial behavior was applying `ttl.afterJitter()` before delegating write-like operations.

## Why removal was better

- The extra client layer did not provide a meaningful abstraction boundary
- Higher layers already depend on cache-specific support/ops concepts, so the client layer mostly added indirection
- It made TTL behavior look like a caller concern even though jitter is infrastructure behavior tied to cache writes
- Removing it simplifies wiring without changing cache semantics

## Where `ttl.afterJitter()` moved

- `SimpleRedisSupport.set(...)`
- `SimpleRedisSupport.setNegative(...)`
- `VersionedRedisSupport.setIfAbsentOrNewer(...)`
- `VersionedRedisSupport.setTombstone(...)`
- `VersionedRedisSupport.setNegative(...)`

Callers still pass `TtlStrategy`, but the support layer now applies jitter internally before writing to Redis or invoking the Lua CAS script.

## Registration changes

- Removed the `SimpleCacheClient` bean from `SimpleCacheRegister`
- Removed the `VersionCacheClient` bean from `VersionCacheRegister`
- Updated ops implementations to depend directly on `SimpleRedisSupport` / `VersionedRedisSupport`
- Updated `ledger-service` cache bean construction to inject the support beans directly

## Why behavior should remain unchanged

- Read paths still use the same `CacheReadSupport` decoding flow
- Delete paths still call the same `BaseRedisSupport.delete(...)`
- Versioned CAS writes still go through the same Lua script and encoded payload format
- Tombstone and negative-cache writes still use the same cache types and version checks
- Parse-error recovery and logging behavior in ops classes was not changed
- The only moved behavior was TTL jitter application, from the removed client wrappers into the support write methods

## What I intentionally did not change

- `VersionAppSideCacheReadClient` was inspected but left in place because it is outside the write-path refactor target and changing that bean/type would alter unrelated app-side cache API surface for little benefit
- No business logic, cache key construction, codec behavior, tombstone semantics, negative-cache semantics, or delete semantics were changed
