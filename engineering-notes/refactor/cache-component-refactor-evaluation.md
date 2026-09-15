# Cache Component Refactor Evaluation

## 1. Scope reviewed
- Read first:
  - `ai/prompts/evaluate/cache-design-evaluation`
- Focus reviewed:
  - `common-util/src/main/java/com/exchange/common/redis/cache/component/support/DefaultReadableCache.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/component/support/ReadableCache.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/component/support/RawDeletableCache.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/component/support/SimpleCache.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/component/support/VersionCache.java`
- Also reviewed:
  - `common-util/src/main/java/com/exchange/common/redis/cache/ops/impl/ReadOpsImpl.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/ops/impl/RawWriteOpsImpl.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/ops/impl/NegativeCacheOpsImpl.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/ops/impl/VersionWriteOpsImpl.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/ops/impl/VersionNegativeCacheOpsImpl.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/ops/impl/VersionTombstoneOpsImpl.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/ops/impl/RawDeleteRecoverOpsImpl.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/register/ReadableCacheRegister.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/register/simple/SimpleCacheRegister.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/register/version/VersionCacheRegister.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/component/factory/ReadableCacheFactory.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/strategy/StableCacheStrategy.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/strategy/VersionCacheStrategy.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/strategy/impl/DefaultStableCacheStrategy.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/strategy/impl/StrategyFactory.java`
  - `wallet-service/src/main/java/com/exchange/app/wallet/dao/cache/WalletCacheRegister.java`
  - `ledger-service/src/main/java/com/exchange/app/ledger/dao/cache/LedgerRegister.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/component/codec/impl/ValueCodec.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/component/codec/impl/VersionCodec.java`
- Important note:
  - `ai/prompts/evaluate/cache-design-evaluation` is a task prompt, not the generated evaluation result. The conclusions below are based on the current source code.

## 2. Executive summary
- Yes, there is a real refactor opportunity in `cache/component/support`.
- The smell is not that the code is “too OO” or “has too many classes”. The real smell is that `SimpleCache` and especially `VersionCache` are capability bundles that combine unrelated responsibilities, while `ops/impl` classes are mostly thin wrappers around those bundles.
- Your proposal is directionally good. Splitting read / write / delete responsibilities is the right direction.
- But your current proposal is incomplete in one important way: it still does not guarantee raw-vs-versioned compatibility on the read side. That is the deeper design issue behind the hot snapshot wiring problem.
- The refactor is worth doing, but it should be a targeted capability split, not a large generic “facade” rewrite.

## 3. Is there a real design smell?
- Yes, but it is localized.
- `DefaultReadableCache` is not the problem. It is already small and focused: read raw string from `BaseCacheReadSupport<String>`, decode with a `CacheDecoder`, return `CacheValueInfo`.
- `SimpleCache` and `VersionCache` are the real smell.
- Evidence in `SimpleCache`:
  - it exposes read through `get(...)`
  - it exposes raw write through `set(...)`
  - it exposes negative-cache write through `setNegative(...)`
  - it exposes delete through `delete(...)`
  - it owns both codec knowledge and transport knowledge
- Evidence in `VersionCache`:
  - it exposes read through `get(...)`
  - it exposes versioned write through `setIfAbsentOrNewer(...)`
  - it exposes tombstone write through `setTombstone(...)`
  - it exposes versioned negative write through `setNegative(...)`
  - it exposes raw delete through `delete(...)`
  - it loads Lua script bytes in `loadScript()`
  - it executes Redis Lua script through `RedissonClient`
  - it also embeds a readable cache instance internally
- That is too much for one component.
- The ops layer makes this even clearer:
  - `VersionWriteOpsImpl`, `VersionTombstoneOpsImpl`, and `VersionNegativeCacheOpsImpl` are nearly pure adapters over methods on `VersionCache`
  - `RawWriteOpsImpl` and `NegativeCacheOpsImpl` are nearly pure adapters over methods on `SimpleCache`
- That means the support-layer classes are still the real capability owners, while the ops layer mostly forwards calls. This is usually a sign that the boundary is drawn at the wrong place.

## 4. Root causes
- Capability boundaries are not modeled explicitly.
  - Read, write, negative write, tombstone write, delete, and script execution are bundled into the same support classes.
- Raw and versioned families are not type-separated strongly enough.
  - `ReadableCache` is a single interface for both raw and versioned encoded values.
  - `ReadableCacheRegister.clientSideReadableCache()` uses `ValueCodec`
  - `WalletCacheRegister.hotSnapshotCache()` injects that reader into a versioned hot snapshot path
  - `VersionCache` itself uses `VersionCodec`
  - This mismatch is possible only because the type system does not distinguish raw-readable vs versioned-readable cache components.
- Script lifecycle is embedded inside a data-operation class.
  - `VersionCache` loads Lua script resources and executes them.
  - That makes testing and reuse harder than necessary.
- Assembly responsibility is spread awkwardly.
  - `StrategyFactory` is very thin
  - service-side registers manually compose readers, writers, negative-cache ops, tombstone ops, and recovery ops
  - support classes still hide too much logic
  - Result: assembly is both verbose and unsafe
- Delete semantics are under-specified.
  - `VersionNegativeCacheOpsImpl.cleanNegative()` falls back to raw delete
  - This is already a signal that the framework has not cleanly decided when deletion must be guarded by version semantics and when raw deletion is acceptable

## 5. Evaluation of my refactor idea
- `ReadFacade`
  - Value: yes
  - Comment: this already mostly exists as `DefaultReadableCache` + `ReadableCache`
  - Problem: a single generic `ReadFacade` is not enough
  - Why: if both raw and versioned readers still implement the same type, the current miswiring risk remains
  - Recommendation: split this into distinct families such as `RawCacheReader` and `VersionedCacheReader`, or use typed markers so Spring wiring cannot silently mix them
- `RawWriteFacade`
  - Value: yes
  - Comment: this is a good split and aligns with current `RawWriteOpsImpl`
  - Recommendation: this facade should own only raw encode + raw Redis set logic
- `VersionWriteFacade`
  - Value: yes, high value
  - Comment: this is the most important extraction from `VersionCache`
  - Recommendation: it should own only versioned encode + CAS write logic, not read and not script loading
- `RawDeleteFacade`
  - Value: yes
  - Comment: useful for self-recovery and plain cleanup paths
  - Recommendation: keep it simple and raw
- `VersionDeleteFacade`
  - Value: maybe, but not as currently described
  - Main issue:
    - “delete through Redisson Lua CAS” is not a complete contract
    - versioned deletion is only meaningful if the rule is explicit, for example:
      - delete only if cached version <= X
      - delete only if cached entry is a negative marker
      - delete only if cached version == expectedVersion
  - Recommendation:
    - do not introduce a generic `VersionDeleteFacade` yet
    - introduce a guarded delete abstraction only after the delete invariant is defined
- “strategy factory should receive one declared Codec + BaseRedisSupport / BaseClientSideCacheSupport / RedissonClient / Options”
  - Value: partial
  - Good part:
    - centralizing assembly can reduce miswiring in service registers
  - Risk:
    - if one `StrategyFactory` receives every low-level dependency, it becomes a god-factory and just moves the smell
  - Recommendation:
    - separate component-family assembly from strategy assembly
    - use one factory or builder for raw cache components and one for versioned cache components
    - let strategy assembly consume those already-typed components

## 6. Would it resolve issues from ai/prompts/evaluate/cache-design-evaluation?
- It would resolve some of them, but not automatically all of them.
- Likely resolved if done well:
  - oversized responsibility in `VersionCache`
  - oversized responsibility in `SimpleCache`
  - ops layer depending on broad support classes instead of narrow capabilities
  - easier testing of read/write/delete/script pieces independently
  - lower chance of accidental dependency coupling
- Only resolved if you make read families type-safe:
  - the raw-vs-versioned reader mismatch identified in the earlier evaluation
  - A plain `ReadFacade` does not solve this by itself
- Not resolved by this refactor alone:
  - logic bugs in strategy classes such as inverted `requireNegativeCache` behavior
  - unclear equal-version overwrite semantics in the Lua CAS script
  - missing framework tests
  - business-flow gaps in snapshot write/update behavior
- Resolved only if you also add guarded delete semantics:
  - the raw delete leak in `VersionNegativeCacheOpsImpl.cleanNegative()`

## 7. Is it worth doing now?
- Yes, but only as a focused refactor.
- Reasons it is worth doing now:
  - the current hot snapshot path already shows that the existing abstraction can be miswired in a correctness-relevant way
  - `SimpleCache` and `VersionCache` have small usage surfaces, mainly in registers and ops implementations, so the blast radius is manageable
  - the ops layer is already moving toward finer-grained capabilities with `RawWriteOpsImpl`
  - future client-side-cache expansion will be riskier if the raw/versioned boundaries stay this loose
- Reasons not to overdo it:
  - the system does not yet have many cache families
  - a large rename-heavy rewrite would add churn without automatically improving invariants
  - generic “facade” abstractions can become vague if introduced without strict capability semantics
- Practical answer:
  - do the refactor now
  - keep it staged
  - optimize for stronger boundaries, not for maximum number of new abstractions

## 8. Recommended refactor design
- Refactor goal:
  - move from “bucket classes with many methods” to “small capability components with explicit semantics”
- Recommended shape:
  - keep `DefaultReadableCache` or rename it to something clearer like `DecodedCacheReader`
  - split `SimpleCache` into:
    - `RawCacheWriter`
    - `RawCacheDeleter`
    - keep reading separate
  - split `VersionCache` into:
    - `VersionedCacheReader`
    - `VersionedCasWriter`
    - `RawCacheDeleter` or `GuardedVersionDelete` depending on the concrete use case
    - `VersionScriptExecutor` for Lua loading and execution
- Important optimization over your proposal:
  - do not center the design on the word `Facade`
  - use names that express the real capability and invariant:
    - `RawCacheReader`
    - `VersionedCacheReader`
    - `RawCacheWriter`
    - `VersionedCasWriter`
    - `RawCacheDeleter`
    - `GuardedVersionDelete`
    - `VersionScriptExecutor`
- Recommended assembly approach:
  - raw family builder:
    - takes `ValueCodec`, `BaseRedisSupport<String>`, optional `BaseClientSideCacheSupport<String>`
  - versioned family builder:
    - takes `VersionCodec`, `BaseRedisSupport<String>`, optional `BaseClientSideCacheSupport<String>`, `RedissonClient`, script loader
  - strategy assembler:
    - takes `CacheDescriptor`, already-typed read/write/delete components, and `StrategyOption`
- This keeps `StrategyFactory` focused on behavior composition instead of making it responsible for low-level infrastructure assembly.

## 9. Suggested component boundaries
- `RawCacheReader`
  - depends on `BaseCacheReadSupport<String>` + `ValueCodec`
  - responsibility: read raw Redis string and decode raw format
- `VersionedCacheReader`
  - depends on `BaseCacheReadSupport<String>` + `VersionCodec`
  - responsibility: read raw Redis string and decode versioned format
- `RawCacheWriter`
  - depends on `BaseRedisSupport<String>` + `ValueCodec`
  - responsibility: encode normal or negative raw values and write with TTL
- `VersionedCasWriter`
  - depends on `VersionCodec` + `VersionScriptExecutor`
  - responsibility: encode normal / tombstone / negative versioned values and write via CAS
- `RawCacheDeleter`
  - depends on `BaseRedisSupport<String>`
  - responsibility: raw delete only
- `GuardedVersionDelete`
  - optional, only if needed
  - depends on `VersionScriptExecutor`
  - responsibility: conditional delete with explicit version contract
- `VersionScriptExecutor`
  - depends on `RedissonClient` + loaded Lua script content
  - responsibility: execute version-aware Redis scripts
- `ScriptSource` or `LuaScriptProvider`
  - depends on `ResourceLoader`
  - responsibility: load script text once
- `ReadOpsImpl`
  - should depend on a typed reader family, not the overly generic `ReadableCache`
- `VersionWriteOpsImpl`, `VersionTombstoneOpsImpl`, `VersionNegativeCacheOpsImpl`
  - should depend on `VersionedCasWriter` or narrower interfaces, not `VersionCache`
- `NegativeCacheOpsImpl` and `RawWriteOpsImpl`
  - should depend on `RawCacheWriter` and `RawCacheDeleter`, not `SimpleCache`

## 10. Code examples
- Example 1: split read families by type, not just by method name

```java
public interface RawCacheReader {
    <T> Result<CacheValueInfo<T>> get(String key, Class<T> clazz);
}

public interface VersionedCacheReader {
    <T> Result<CacheValueInfo<T>> get(String key, Class<T> clazz);
}

public final class DefaultRawCacheReader implements RawCacheReader {
    private final BaseCacheReadSupport<String> readSupport;
    private final ValueCodec codec;

    public <T> Result<CacheValueInfo<T>> get(String key, Class<T> clazz) {
        String raw = readSupport.get(key);
        if (raw == null) return Results.success();
        try {
            return Results.success(codec.decode(raw, clazz));
        } catch (CacheParseException e) {
            return Results.fail(CommonErrorCode.PARSE_CACHE_ERROR, "parse cache error");
        }
    }
}

public final class DefaultVersionedCacheReader implements VersionedCacheReader {
    private final BaseCacheReadSupport<String> readSupport;
    private final VersionCodec codec;

    public <T> Result<CacheValueInfo<T>> get(String key, Class<T> clazz) {
        String raw = readSupport.get(key);
        if (raw == null) return Results.success();
        try {
            return Results.success(codec.decode(raw, clazz));
        } catch (CacheParseException e) {
            return Results.fail(CommonErrorCode.PARSE_CACHE_ERROR, "parse versioned cache error");
        }
    }
}
```

- Example 2: extract script loading and execution out of `VersionCache`

```java
public final class VersionScriptExecutor {
    private final RedissonClient redissonClient;
    private final String setIfAbsentOrNewerScript;

    public Result<Boolean> setIfAbsentOrNewer(
            String key,
            String encodedValue,
            long newVersion,
            Duration ttl) {

        Boolean applied = redissonClient.getScript(StringCodec.INSTANCE)
                .eval(
                        RScript.Mode.READ_WRITE,
                        setIfAbsentOrNewerScript,
                        RScript.ReturnType.BOOLEAN,
                        Collections.singletonList(key),
                        encodedValue,
                        newVersion,
                        ttl.toMillis());
        return Results.success(applied);
    }
}
```

- Example 3: versioned writer with explicit responsibility

```java
public final class VersionedCasWriter {
    private final VersionCodec codec;
    private final VersionScriptExecutor executor;

    public <T> Result<Boolean> set(String key, T value, long version, TtlStrategy ttl) {
        return write(key, value, CacheType.fromSource(value), version, ttl);
    }

    public Result<Boolean> setTombstone(String key, long version, TtlStrategy ttl) {
        return write(key, "", CacheType.TOMBSTONE, version, ttl);
    }

    public Result<Boolean> setNegative(String key, long version, TtlStrategy ttl) {
        return write(key, "", CacheType.NEGATIVE, version, ttl);
    }

    private Result<Boolean> write(String key, Object value, CacheType type, long version, TtlStrategy ttl) {
        String encoded = codec.encode(value, type, version);
        return executor.setIfAbsentOrNewer(key, encoded, version, ttl.afterJitter());
    }
}
```

- Example 4: strategy assembly should consume typed components, not raw infrastructure

```java
public final class VersionedStrategyAssembler {
    public <T> VersionCacheStrategy<T> buildPostRefresh(
            CacheDescriptor<T> descriptor,
            VersionedCacheReader reader,
            VersionedCasWriter writer,
            RawCacheDeleter deleter,
            StrategyOption option) {

        ReadOps<T> readOps = new ReadOpsImpl<>(reader, descriptor);
        VersionWriteOps<T> writeOps = new VersionWriteOpsImpl<>(writer, descriptor);
        VersionNegativeCacheOps negativeOps = new VersionNegativeCacheOpsImpl(writer, deleter, descriptor);
        SelfRecoverOps recoverOps = new RawDeleteRecoverOpsImpl(deleter, descriptor);
        return new DefaultVersionPostRefreshStrategy<>(readOps, writeOps, negativeOps, recoverOps, option);
    }
}
```

## 11. Benefits
- Stronger invariants
  - raw and versioned read families can be made incompatible by type
- Lower coupling
  - ops classes stop depending on broad support buckets
- Better testability
  - reader decode, raw write, CAS write, delete, and script execution can be tested independently
- Better names
  - `VersionedCasWriter` says more than `VersionCache`
- Cleaner runtime model
  - script loading and script execution are infrastructure concerns, not general cache-component concerns
- Easier future extension
  - hot path, client-side cache, and guarded delete can evolve independently

## 12. Risks / downsides
- More classes
  - if naming is weak, this becomes abstraction noise instead of clarity
- Over-generic factories
  - if one `StrategyFactory` starts accepting codec + Redis + Redisson + read supports + options for every case, it will become another overloaded assembly object
- Premature versioned delete abstraction
  - introducing `VersionDeleteFacade` before defining the delete invariant will create confusion
- Migration churn
  - register classes and ops constructors will all need updates
- Partial refactor risk
  - if you split write/delete but keep a single generic `ReadableCache`, the deepest problem will remain

## 13. Final recommendation
- Refactor is justified.
- The main reason is not aesthetics. The current `SimpleCache` and `VersionCache` boundaries are too broad, and that weakens correctness boundaries, especially between raw and versioned cache families.
- Your proposal is mostly on the right path.
- The main changes I would make to your proposal are:
  - split read components into raw and versioned families explicitly
  - extract Lua loading/execution into a dedicated executor
  - do not introduce a generic `VersionDeleteFacade` until the delete contract is defined
  - do not turn `StrategyFactory` into a god-factory; use smaller family-specific assemblers/builders
- Recommended practical sequence:
  - 1. Extract `VersionScriptExecutor`
  - 2. Split `VersionCache` into `VersionedCacheReader` + `VersionedCasWriter`
  - 3. Split `SimpleCache` into `RawCacheWriter` + `RawCacheDeleter`, keep read separate
  - 4. Change ops impls to depend on those narrower components
  - 5. Make raw-vs-versioned readers distinct bean types so miswiring is impossible
- If you do only one thing now, do step 5 together with the support-layer split. That is the highest-leverage improvement.
