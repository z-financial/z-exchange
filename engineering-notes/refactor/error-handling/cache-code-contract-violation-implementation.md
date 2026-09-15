# Cache Contract Violation Refactor

## Scope

This follow-up refactor stays inside the cache error-handling path:

- `common-util/src/main/java/com/exchange/common/result/error`
- `common-util/src/main/java/com/exchange/common/redis/cache`
- wallet and ledger cache registrations only

It does not change wallet/ledger business flow outside cache wiring, and it does not broaden exception handling into other `common-util` subsystems.

## What Changed

### 1. Added a cache-specific contract code

`CacheErrorCode` now includes:

- `CONTRACT_VIOLATION`

This is used for cache-owned programmer-misuse / contract-failure paths instead of overloading `UNEXPECTED_INTERNAL` or throwing raw `IllegalArgumentException`.

### 2. Encode-side misuse now throws `CacheException(CONTRACT_VIOLATION)`

The following encode/write paths now throw cache-specific exceptions:

- `ValueCodec.encode(...)`
- `VersionCodec.encode(...)`
- `RawCacheWriter.set(...)`
- `VersionCacheWriter.setIfAbsentOrNewer(...)`

This keeps the error model cache-scoped and lets the soft-fail boundary stay narrow.

### 3. Decode-time behavior was preserved

Read/decode failures remain unchanged:

- malformed stored value -> `MALFORMED_VALUE`
- serialization / deserialization failure -> `SERIALIZATION`

No self-recover behavior was widened.

### 4. Suppressing strategy behavior is now explicit in `StrategyFactory`

`StrategyFactory` no longer depends on proxy/AOP wiring for suppress-exception strategy construction.

Instead, the suppressing builders now return explicit wrapper strategies that:

- delegate to the normal strategy
- catch `CacheException` only
- downgrade to `Result.failure(...)`

This keeps the soft-fail boundary explicit and avoids broad handling of generic JDK runtime exceptions.

### 5. Wallet and ledger now use suppressing strategy variants

Updated registrations:

- `ledger-service/.../LedgerCacheRegister`
- `wallet-service/.../BalanceSnapshotCacheRegister`

These services now use:

- `buildRawCacheSuppressExceptionStrategy(...)`
- `buildVersionCacheSuppressExceptionStrategy(...)`

That matches the store-layer expectation that cache failures are logged and do not block main flow.

## Evaluation

## Alignment with the discussed design

This refactor follows the agreed direction in the key places:

- cache-owned contract failures stay in `CacheException`
- suppressing strategy boundary catches `CacheException` only
- self-recover still applies only to `MALFORMED_VALUE` / `SERIALIZATION`
- wallet/ledger explicitly choose soft-fail strategy beans
- fail-fast behavior remains available through the plain strategy builders

## Why this is better than broad `IllegalArgumentException` downgrade

This change avoids introducing a second generic downgrade rule for:

- `IllegalArgumentException`
- `IllegalStateException`

That matters in this repo because broad JDK runtime downgrade would make it too easy to hide unrelated future bugs as cache misses.

By normalizing cache-owned misuse into `CacheException(CONTRACT_VIOLATION)` near the source, the downgrade boundary stays precise and cache-specific.

## Trade-off

Wallet and ledger will now soft-fail not only on operational cache errors but also on cache contract-violation errors.

That is intentional and consistent with the stated requirement that cache must not block main flow there. The trade-off is that these failures should remain visible via logs/monitoring, since they still indicate implementation misuse rather than an expected operational condition.

## Notes

- `CacheExceptionToResultAop` was left untouched to keep the change focused. The suppressing strategy path no longer depends on it.
- No tests were run in this follow-up; verification was source-level only.

## Verification

Source-level verification completed:

- `ValueCodec.encode(...)` / `VersionCodec.encode(...)` now use `CONTRACT_VIOLATION`
- `RawCacheWriter.set(...)` / `VersionCacheWriter.setIfAbsentOrNewer(...)` now use `CONTRACT_VIOLATION`
- decode-time `MALFORMED_VALUE` / `SERIALIZATION` handling remains in place
- `StrategyFactory` suppressing builders now wrap strategies explicitly and catch `CacheException` only
- wallet/ledger cache registrations now use the suppressing builders
