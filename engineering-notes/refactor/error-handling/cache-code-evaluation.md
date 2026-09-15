# Cache Error Handling Evaluation

## Verdict

Your refactor direction is mostly correct:

- push exceptions down into the cache subsystem internals
- keep `Result<?>` at the strategy boundary that the stores already consume
- stop converting failures to `Result` deep inside `common-util/src/main/java/com/exchange/common/redis/cache/component/support`

That said, the design is not strong enough yet in two places:

- `CacheExceptionTranslateAop` on `BaseCacheReadSupport` implementations is too narrow to cover the actual failure surface in this project
- downgrading arbitrary non-`CacheException` to `Result` will hide real bugs and turn them into silent cache misses

I would keep the refactor, but tighten the boundaries and reject the global `ExceptionToResultAop` option.

## 1. Current Project Error Handling Assessment

### What is already good

- The store layer already treats cache as a soft dependency and falls back to DB instead of failing the main flow.
  - `ledger-service/src/main/java/com/exchange/app/ledger/dao/store/LedgerTxnStore.java:47-80`
  - `wallet-service/src/main/java/com/exchange/app/wallet/dao/store/BalanceSnapshotStore.java:66-92`
- Shared/common failures are already normalized at the service boundary instead of leaking raw `common-util` details to gRPC.
  - `ledger-service/src/main/java/com/exchange/app/ledger/result/LedgerBoundaryErrorMapper.java:32-59`
  - `wallet-service/src/main/java/com/exchange/app/wallet/result/WalletBoundaryErrorMapper.java:32-59`
- The cache strategy layer already has the right conceptual boundary for downgrade: stores consume `Result`, internals should not need to.
  - `common-util/src/main/java/com/exchange/common/redis/cache/strategy/impl/DefaultRawCacheStrategy.java:37-74`
  - `common-util/src/main/java/com/exchange/common/redis/cache/strategy/impl/DefaultVersionCacheStrategy.java:37-78`

### Main smells in the current implementation

#### 1. Error conversion happens too early and in too many places

Today the cache subsystem mixes three different styles:

- low-level Redis wrappers intend to use exception translation
  - `common-util/src/main/java/com/exchange/common/redis/BaseRedisSupport.java:10-33`
  - `common-util/src/main/java/com/exchange/common/redis/BaseClientSideCacheSupport.java:10-18`
- codecs throw checked parse exceptions
  - `common-util/src/main/java/com/exchange/common/redis/cache/component/codec/impl/ValueCodec.java:25-95`
  - `common-util/src/main/java/com/exchange/common/redis/cache/component/codec/impl/VersionCodec.java:24-68`
- support components already convert internal failures to `Result.failure(...)`
  - `common-util/src/main/java/com/exchange/common/redis/cache/component/support/DefaultCacheReader.java:18-29`
  - `common-util/src/main/java/com/exchange/common/redis/cache/component/support/RawCacheWriter.java:21-56`
  - `common-util/src/main/java/com/exchange/common/redis/cache/component/support/VersionCacheWriter.java:22-56`

This creates duplicate downgrade points, duplicate logging, and incomplete behavior. Some failures become `Result`, some still escape as exceptions.

#### 2. `PARSE_CACHE_ERROR` is too coarse for this codebase

The entire cache subsystem currently has one error code:

- `common-util/src/main/java/com/exchange/common/result/error/CacheErrorCode.java:8-18`

That is too weak for the current design because the project already distinguishes:

- malformed cache payloads
- JSON serialization/deserialization failures
- Redis/Redisson access failures
- Lua script execution failures
- startup/configuration failures

Right now all parse-like issues collapse into one code, and non-parse infra failures do not fit at all.

#### 3. Some malformed cache values do not become `PARSE_CACHE_ERROR`

This is the most important hidden bug in the current design.

`ValueCodec.decode(...)` does:

- parse marker via `CacheType.mapper.from(...)`
  - `common-util/src/main/java/com/exchange/common/redis/cache/component/codec/impl/ValueCodec.java:64-66`
- then immediately switch through `CacheContentValidator.validateContent(...)`
  - `common-util/src/main/java/com/exchange/common/redis/cache/component/codec/impl/ValueCodec.java:68-70`
  - `common-util/src/main/java/com/exchange/common/redis/cache/util/CacheContentValidator.java:7-11`

But `EnumMapper.from(...)` returns `null` for unknown markers:

- `common-util/src/main/java/com/exchange/common/utils/enums/EnumMapper.java:23-24`

So malformed values like an unknown marker can produce a `NullPointerException` instead of `CacheParseException`. That bypasses:

- `DefaultCacheReader` parse handling
- the `PARSE_CACHE_ERROR` path
- the self-recover delete path in the strategy layer

This is exactly the kind of project-specific hole your refactor should close.

#### 4. The current AOP design is only a sketch, not a reliable contract

Both AOPs are stubs:

- `common-util/src/main/java/com/exchange/common/redis/aop/CacheExceptionTranslateAop.java:15-22`
- `common-util/src/main/java/com/exchange/common/redis/aop/CacheExceptionToResultAop.java:16-25`

And `CacheExceptionToResultAop` imports JUnit `Order`, not Spring `Order`:

- `common-util/src/main/java/com/exchange/common/redis/aop/CacheExceptionToResultAop.java:8`

That matters because your design depends heavily on proxy behavior and advice ordering. In this repo, an invisible AOP boundary is only safe if it is very small, very explicit, and covered by tests.

#### 5. `CacheException` is too weak for translation

`CacheException` currently only carries `ErrorCode` and message:

- `common-util/src/main/java/com/exchange/common/exception/CacheException.java:5-8`
- `common-util/src/main/java/com/exchange/common/exception/CustomizedException.java:6-13`

There is no constructor with `cause`. If you translate a low-level Redis/Redisson exception into `CacheException`, you lose the actual cause chain unless you log at the translation site. That is a bad trade in an infra subsystem.

#### 6. The current code misclassifies some internal misuse as parse error

Examples:

- `RawCacheWriter.set(...)` returns `PARSE_CACHE_ERROR` when `value == null`
  - `common-util/src/main/java/com/exchange/common/redis/cache/component/support/RawCacheWriter.java:21-25`
- `VersionCacheWriter.setIfAbsentOrNewer(...)` does the same
  - `common-util/src/main/java/com/exchange/common/redis/cache/component/support/VersionCacheWriter.java:22-26`

That is not a cache parse failure. It is either:

- a caller bug
- an invalid internal contract
- or a separate cache write validation category

If you keep calling everything parse, your error model will stay muddy.

#### 7. Cross-service mapping will drift if cache codes expand without boundary work

Both service boundary mappers explicitly special-case only `CacheErrorCode.PARSE_CACHE_ERROR`:

- `ledger-service/src/main/java/com/exchange/app/ledger/result/LedgerBoundaryErrorMapper.java:43-47`
- `wallet-service/src/main/java/com/exchange/app/wallet/result/WalletBoundaryErrorMapper.java:43-47`

If you add more cache codes, wallet and ledger will not automatically stay consistent. They will just fall back to generic internal behavior with a warning. That is acceptable only if it is intentional.

## 2. Evaluation of Your Refactor

## 2.1 Catch low-level exceptions and translate to `CacheException`

### Good part

This is the right direction for the Redis access boundary.

It fixes a real layering problem in the current code:

- low-level access should not know about `Result`
- low-level access should expose infra failure as exception
- strategy boundary should decide whether the failure is softened into `Result`

That matches how the store layer already uses cache: best effort, log, continue to DB.

### Weak part

Your stated translation point is too narrow for the actual code.

If translation only happens in `BaseCacheReadSupport` implementations:

- `BaseRedisSupport`
- `BaseClientSideCacheSupport`

then it still misses:

- Redisson write calls in `VersionCacheWriter`
  - `common-util/src/main/java/com/exchange/common/redis/cache/component/support/VersionCacheWriter.java:43`
- Lua eval in `ScriptExecutor`
  - `common-util/src/main/java/com/exchange/common/redis/cache/component/support/ScriptExecutor.java:39-48`
- codec failures in `ValueCodec` and `VersionCodec`

So the actual design should be:

- Redis/Redisson wrapper beans translate driver exceptions to `CacheException`
- codec and cache-format code throws `CacheException` directly with cache-specific codes
- strategy boundary downgrades `CacheException` to `Result`

Not all translation belongs in one AOP.

### Recommendation

Do not rely on `CacheExceptionTranslateAop` alone to create the whole cache error model.

Use it only for low-level client exceptions. Let cache-format code construct `CacheException` directly.

## 2.2 Remove `CacheParseException` and `VersionedCacheParseException`

### Good part

I agree with removing both.

`VersionedCacheParseException` adds almost no value in this project:

- it does not change handling
- it does not affect strategy behavior
- it does not affect boundary mapping

`CacheParseException` also becomes redundant if you move to:

- one cache-owned runtime exception type
- one cache-owned error code catalog

### Hidden requirement

This only works if `CacheException` becomes richer:

- add `cause`
- keep a precise code
- include enough context in detail/message to debug key, operation, and cache type

Without that, replacing parse exceptions with plain `CacheException` is a downgrade in diagnosability.

## 2.3 Add richer `CacheErrorCode`

### Good part

This solves a real problem. The current single-code model is too weak for:

- self-recovery decisions
- metrics and alerting
- distinguishing bad cache data from transient infra failures
- future wallet/ledger boundary behavior

### What I would change

Your proposed set is directionally correct, but I would tighten naming and category semantics.

Recommended shape:

- `CONNECTION`
- `TIMEOUT`
- `ACCESS`
- `SERIALIZATION`
- `MALFORMED_VALUE`
- `SCRIPT_EXECUTION`
- `CONFIGURATION`
- optional `UNEXPECTED_INTERNAL`

Recommended categories:

- `CONNECTION`, `TIMEOUT` -> `ErrorCategory.UNAVAILABLE`
- `ACCESS` -> usually `ErrorCategory.INTERNAL` in this project unless you truly model auth/ACL issues
- `SERIALIZATION`, `MALFORMED_VALUE`, `CONFIGURATION`, `UNEXPECTED_INTERNAL` -> `ErrorCategory.INTERNAL`
- `SCRIPT_EXECUTION` -> probably `ErrorCategory.INTERNAL` unless you want retry semantics for transient Redis script failures

### Important project-specific consequence

Once you add these codes, both:

- `LedgerBoundaryErrorMapper`
- `WalletBoundaryErrorMapper`

must be reviewed at the same time. Otherwise the extra granularity exists only inside `common-util` and does not produce consistent service behavior.

## 2.4 Stop returning `Result<?>` in `cache/component/support`

This is a good refactor.

Today these classes are internal infrastructure helpers:

- `DefaultCacheReader`
- `RawCacheWriter`
- `VersionCacheWriter`
- `RawCacheDeleter`
- `ScriptExecutor`

They should not downgrade to `Result` themselves. Doing so:

- duplicates conversion logic
- forces `Result.from(...)` plumbing in strategies
- makes internal callers reason about business-style failure objects too early

This refactor directly improves:

- `common-util/src/main/java/com/exchange/common/redis/cache/component/support/DefaultCacheReader.java:18-29`
- `common-util/src/main/java/com/exchange/common/redis/cache/component/support/RawCacheWriter.java:21-56`
- `common-util/src/main/java/com/exchange/common/redis/cache/component/support/VersionCacheWriter.java:22-56`
- `common-util/src/main/java/com/exchange/common/redis/cache/component/support/ScriptExecutor.java:39-48`

That is one of the strongest parts of your design.

## 2.5 Downgrade `CacheException` to `Result` at the strategy boundary

### Good part

Conceptually this is the right outward contract for the current repo.

The store layer already expects:

- cache strategy methods return `Result`
- cache failure is often non-blocking
- DB remains the main source of truth

So the downgrade point should be the strategy boundary, not the codec/writer boundary.

### Weak part

Catching non-`CacheException` and converting it to `Result` is too broad.

Why this is risky here:

- stores treat unsuccessful cache `Result` as soft failure and continue
- processors then often return success or generic server error based on store `Result`
- so a real programming bug can be silently converted into a cache miss path

Examples of bugs you do not want to soften blindly:

- `NullPointerException` from malformed internal state
- configuration bugs in strategy wiring
- programmer bugs in self-recover feature code
- mistakes in descriptor/key building

In this project, hiding those behind a generic cache failure will make the system look healthy while it is actually broken.

### Recommendation

Preferred policy:

- downgrade `CacheException` only
- let all other exceptions propagate

If you absolutely want containment, then:

- catch broad exceptions only around direct cache collaborator calls
- log stack traces at high severity
- map them to `CacheErrorCode.UNEXPECTED_INTERNAL`

Do not introduce a cross-subsystem shared `UncaughtInternalError` just for this. It works against subsystem ownership.

## 3. 3.A vs 3.B

## Prefer 3.A: subsystem-specific downgrade

3.A is better for this repo.

Reasons:

- the project already split common/shared error ownership by subsystem
  - `CacheErrorCode`
  - `IdempErrorCode`
  - `OutboxErrorCode`
- wallet and ledger already normalize shared/common failures at their own boundaries
- cache and outbox do not have identical failure semantics

Cache is usually a soft dependency here. Outbox is not the same kind of dependency:

- cache read/write failure often means "log and continue"
- outbox retry/claim failure may mean retry loop failure or consistency risk

One global `ExceptionToResultAop` inside `common-util` would blur those policies together.

### Why 3.B is weaker

Global `ExceptionToResultAop` would create these problems:

- one invisible global downgrade rule for unrelated subsystems
- harder reasoning about where exceptions are supposed to stop
- pressure to invent common catch-all error codes
- higher risk of accidentally swallowing bugs in places that should fail fast

It would save boilerplate, but it would weaken ownership and evolvability.

### Even better than 3.A

If you are willing to avoid AOP, the cleanest design is:

- internal cache code throws `CacheException`
- strategy methods explicitly convert `CacheException` to `Result`

That is more obvious, easier to test, and less proxy-fragile than AOP.

Given your current design direction, though, 3.A is still much better than 3.B.

## 4. Concrete Improvements I Recommend

1. Keep one error model per layer.

- below strategy: throw `CacheException`
- strategy outward: return `Result`
- service boundary: map common/shared error to service-owned error

2. Make `CacheException` cause-preserving.

- add constructor with `Throwable cause`
- do not lose Redis/Redisson/JSON exception chains

3. Fix malformed-value handling before any broader refactor.

- unknown cache marker must become `MALFORMED_VALUE`
- it must not become `NullPointerException`

4. Restrict self-recover to data-corruption-like codes.

Safe candidates:

- `MALFORMED_VALUE`
- possibly `SERIALIZATION` on read if it clearly means the stored value is bad

Do not auto-delete on:

- `CONNECTION`
- `TIMEOUT`
- `ACCESS`
- `SCRIPT_EXECUTION`
- `CONFIGURATION`

5. Do not treat caller misuse as parse failure.

Examples like `value == null` should be either:

- `IllegalArgumentException` if it is a programming contract violation
- or a distinct cache write validation code if you intentionally want it modeled

6. Keep startup configuration failures fail-fast.

`ScriptExecutor.loadScript()` currently throws `IllegalStateException` on startup failure:

- `common-util/src/main/java/com/exchange/common/redis/cache/component/support/ScriptExecutor.java:26-36`

That is correct. Do not soften startup misconfiguration into runtime cache `Result`.

7. If you keep AOP, make it narrow and test it.

Test cases should prove:

- RedisTemplate exception -> translated to `CacheException`
- Redisson exception -> translated to `CacheException`
- malformed cached value -> `CacheException(MALFORMED_VALUE)`
- strategy boundary -> `Result.failure(...)`
- unexpected non-cache bug -> not silently swallowed, unless intentionally configured

8. Update wallet and ledger boundary mapping together with new cache codes.

If you introduce `UNAVAILABLE`-class cache failures, decide now whether they should map to:

- service `SERVER_ERROR`
- service `INTERNAL_ERROR`
- or future retry/transport semantics

Do not add codes first and postpone boundary policy. That will create drift.

## 5. Recommended Final Shape

I would implement the design like this:

- internal cache codecs/support/writers/readers throw `CacheException`
- `CacheExceptionTranslateAop` only wraps actual Redis/Redisson client exceptions
- codec and cache-format code throws `CacheException` directly
- strategy layer is the only place that returns `Result`
- strategy downgrade catches `CacheException` only
- service boundary mappers stay service-local and map new cache codes intentionally

## Bottom Line

This is a good refactor because it removes the wrong boundary: internal cache helpers should not be in the business of manufacturing `Result`.

The weak points are:

- translation scope is too narrow if it only targets `BaseCacheReadSupport`
- broad non-`CacheException` downgrade will hide bugs
- cross-service mapping must be updated with the new cache code set

So:

- keep the refactor
- prefer 3.A over 3.B
- narrow the catch policy
- make cache error codes richer
- keep fail-fast behavior for real configuration bugs
