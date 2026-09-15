# Common-Util Result Migration

## Scope

This change migrates active `common-util` callers from the old `com.exchange.common.utils.result` model to the new `com.exchange.common.result` model.

It does not touch `wallet-service` or `ledger-service`.

## What Changed

### `Result` helper surface

Updated [`Result.java`](D:/TestProgram/Java/JavaEE/z-exchange/common-util/src/main/java/com/exchange/common/result/Result.java):

- kept the `final class` + private constructor structure
- implemented the current `IResult` contract explicitly:
  - `isSuccess()`
  - `getErrorCode()`
  - `getValue()`
  - `getDetail()`
  - `getScope()`
- added `Result.failure(IResult<?>)`
- added `Result.is(IResult<?>, ErrorCode)`

`common-util` callers were updated to use `isSuccess()` instead of `success()`. This is necessary because Java cannot support both:

- static factory `Result.success(...)`
- instance method `result.success()`

These two helpers replace the old common helper usage patterns in `common-util`:

- `Results.fail(result)` -> `Result.failure(result)`
- `Results.is(result, code)` -> `Result.is(result, code)`
- `Results.result(newValue, result)` -> `Result.from(newValue, result)`

The temporary alias methods such as `value()` and `errorCode()` were removed to keep the refactored `Result` surface clean and force one access style.

### Subsystem error enums

Completed [`IdempErrorCode.java`](D:/TestProgram/Java/JavaEE/z-exchange/common-util/src/main/java/com/exchange/common/result/error/IdempErrorCode.java):

- `INVALID_IDEMP_KEY`
- `INVALID_IDEMP_VALUE`

Added [`CacheErrorCode.java`](D:/TestProgram/Java/JavaEE/z-exchange/common-util/src/main/java/com/exchange/common/result/error/CacheErrorCode.java):

- `PARSE_CACHE_ERROR`

Added [`OutboxErrorCode.java`](D:/TestProgram/Java/JavaEE/z-exchange/common-util/src/main/java/com/exchange/common/result/error/OutboxErrorCode.java):

- `UNEXPECTED_DB_ERROR`

This removes active `common-util` runtime usage of the old catch-all `CommonErrorCode` for:

- idempotency parsing
- cache encode/decode failures
- outbox retry claim failure

### Common-util caller migration

Migrated active `common-util` code to import the new `Result` type in:

- [`DbTxnExecutor.java`](D:/TestProgram/Java/JavaEE/z-exchange/common-util/src/main/java/com/exchange/common/db/utils/DbTxnExecutor.java)
- [`IPublisher.java`](D:/TestProgram/Java/JavaEE/z-exchange/common-util/src/main/java/com/exchange/common/kafka/producer/IPublisher.java)
- [`OutboxRetryHandler.java`](D:/TestProgram/Java/JavaEE/z-exchange/common-util/src/main/java/com/exchange/common/outbox/retry/OutboxRetryHandler.java)
- [`IdempRedisClient.java`](D:/TestProgram/Java/JavaEE/z-exchange/common-util/src/main/java/com/exchange/common/redis/idemp/IdempRedisClient.java)
- [`CommonIdempHelper.java`](D:/TestProgram/Java/JavaEE/z-exchange/common-util/src/main/java/com/exchange/common/redis/idemp/utils/CommonIdempHelper.java)
- cache component support classes
- cache strategy interfaces and implementations
- negative cache feature classes

Behavioral changes are intentionally small:

- success/failure creation now uses `Result.success(...)` and `Result.failure(...)`
- failure propagation now normalizes malformed old-style failure-with-value states into proper failure-without-value states
- cache parse recovery matching now compares against `CacheErrorCode.PARSE_CACHE_ERROR`

## What Still Remains

### Old package still exists

The legacy package still exists:

- [`common-util/src/main/java/com/exchange/common/utils/result`](D:/TestProgram/Java/JavaEE/z-exchange/common-util/src/main/java/com/exchange/common/utils/result)

But after this change it is no longer used by active `common-util` callers.

I left it in place because removing it would expand scope into service migration and cleanup.

### Proto mapper architecture is still transitional

[`ProtoErrorMapper.java`](D:/TestProgram/Java/JavaEE/z-exchange/common-util/src/main/java/com/exchange/common/result/error/ProtoErrorMapper.java) still lives in `common-util`, which is weaker than the checklist’s final boundary-only architecture.

This change does not revisit that decision.

### One stale commented import remains

[`VersionNegativeCacheFeature.java`](D:/TestProgram/Java/JavaEE/z-exchange/common-util/src/main/java/com/exchange/common/redis/cache/strategy/feature/VersionNegativeCacheFeature.java) still contains a commented old import. It is non-functional and was left untouched.

## Expected Temporary Fallout

Because `common-util` public signatures now point at `com.exchange.common.result.Result` instead of the old package in several interfaces, downstream `wallet-service` / `ledger-service` code may be temporarily broken until they migrate too.

That is expected for this step and matches the requested scope.

## Verification

Repository scan after the migration shows:

- no active `common-util` imports of `com.exchange.common.utils.result`
- only the legacy package itself still references old result types

I did not complete a Maven compile because the local environment still fails on Java toolchain setup:

- `mvn -pl common-util -am -DskipTests compile`
- failure: `invalid target release: 21`
