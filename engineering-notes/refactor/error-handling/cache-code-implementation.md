# Cache Exception Refactor Implementation

## Scope

This change completes the cache exception refactor in the Redis/cache slice and keeps the outward strategy contract as `Result`.

Touched areas:

- `common-util/src/main/java/com/exchange/common/redis`
- `common-util/src/main/java/com/exchange/common/exception`
- `common-util/src/main/java/com/exchange/common/result/error`
- cache boundary normalization in:
  - `ledger-service/src/main/java/com/exchange/app/ledger/result/LedgerBoundaryErrorMapper.java`
  - `wallet-service/src/main/java/com/exchange/app/wallet/result/WalletBoundaryErrorMapper.java`

## What Changed

### 1. Low-level cache exception translation is now implemented

- `CacheExceptionTranslateAop` now translates recognized Spring Redis / Redisson low-level exceptions into `CacheException`.
- Translation uses the new cache-specific error codes:
  - `CONNECTION`
  - `TIMEOUT`
  - `ACCESS`
  - `SCRIPT`
  - `UNEXPECTED_INTERNAL`
- Existing `CacheException` instances are rethrown unchanged.
- Non-cache/programming exceptions are not broadly swallowed by the boundary AOP.

### 2. Codec parse exceptions were removed

- deleted:
  - `CacheParseException`
  - `VersionedCacheParseException`
- `ValueCodec` and `VersionCodec` now throw `CacheException` directly.
- read-path bad cache payloads now use:
  - `MALFORMED_VALUE`
  - `SERIALIZATION`
- version/content encode contract failures now use `UNEXPECTED_INTERNAL`.

### 3. Cache support components now return raw values instead of `Result`

Refactored support classes:

- `DefaultCacheReader`
- `RawCacheWriter`
- `VersionCacheWriter`
- `RawCacheDeleter`
- `ScriptExecutor`
- `NegativeWriter`

These classes now:

- return objects / primitives / `void`
- throw `CacheException` on cache failures
- no longer manufacture `Result.failure(...)` internally

### 4. Strategy boundary downgrade is implemented

- `CacheExceptionToResultAop` now converts `CacheException` to `Result.failure(...)`.
- non-`CacheException` is not converted.

### 5. Self-recover is now limited to read-time corruption-like failures

`DefaultRawCacheStrategy.get()` and `DefaultVersionCacheStrategy.get()` now:

- catch `CacheException` internally
- trigger self-recover only for:
  - `MALFORMED_VALUE`
  - `SERIALIZATION`
- rethrow the same `CacheException` so the strategy boundary AOP downgrades it to `Result`

`RawDeleteSelfRecoverFeature` was also adjusted so recover-delete failures are logged and do not mask the original cache failure.

### 6. Script loading now throws `CacheException`

- `ScriptExecutor.loadScript()` now throws `CacheException(CONFIGURATION, ...)` on load failure.
- `ScriptExecutor.setIfAbsentOrNewer(...)` now returns `Boolean`.

### 7. Cache namespace mapping was broadened at service boundaries

`LedgerBoundaryErrorMapper` and `WalletBoundaryErrorMapper` now normalize any `cache` namespace error to service-local internal error handling, instead of only the old `PARSE_CACHE_ERROR`.

## Alignment With `evaluation.md`

This implementation follows the earlier evaluation in the main points:

- internal cache helpers no longer return `Result`
- cache-specific error codes replace the old parse-only code
- self-recover is restricted to malformed/serialization read failures
- strategy boundary remains the downgrade point
- non-`CacheException` is not broadly transformed by `CacheExceptionToResultAop`
- wallet/ledger boundary mapping was updated together with the cache code expansion

## Notable Adjustment From The Evaluation

The earlier evaluation preferred keeping clear programmer-misuse cases out of cache exceptions where possible.

Current implementation keeps that only partially:

- writer null-value misuse now throws `IllegalArgumentException`
- some codec encode-contract failures are still represented as `CacheException(UNEXPECTED_INTERNAL)`

That keeps the refactor minimal while still removing the old parse-exception model.

## Service Impact

No cache-path business logic was refactored in ledger/wallet stores.

That was intentional:

- the existing store logic already treats cache failure as non-blocking
- after this refactor, cache failures still surface to those stores as unsuccessful `Result`
- stores continue logging cache failures and falling back to DB/main flow

## Verification

Source-level checks completed:

- no remaining `CacheParseException` / `VersionedCacheParseException` references in the affected code
- no remaining `PARSE_CACHE_ERROR` references in the affected code
- support/strategy call sites were updated to the new raw-return signatures

Build verification was attempted with:

```powershell
mvn -pl common-util,ledger-service,wallet-service -am -DskipTests compile
```

It did not complete because the local Java toolchain cannot compile for the project target release:

- Maven error: `invalid target release: 21`

So the remaining verification gap is environment-level, not a discovered code-level regression.
