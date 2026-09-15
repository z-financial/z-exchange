# Cache Metrics Two-Layer Design Evaluation

## 1. Verdict

The design is mostly sound for phase one.

Splitting cache metrics into two layers is the right direction:

- strategy layer: semantic cache result and normalized cache error type;
- support layer: Redis/client-side/Lua operation latency and success/error outcome.

This separation avoids forcing low-level Redis components to understand business cache meaning, while still giving enough latency visibility to diagnose Redis-side pressure.

The design needs a few corrections before implementation:

- `BaseClientSideCacheSupport` currently supports only `get`; do not model `set`, `delete`, or `setIfAbsent` for that component unless the interface is expanded.
- low-level support wrappers should not try to tag by `cache_type`, because `BaseRedisSupport` is a shared singleton and does not know which logical cache is using it.
- strategy metrics should be inserted outside the current suppress-exception wrapper, or they must explicitly count `Result.failure`; otherwise downgraded cache failures may be missed.
- `ScriptExecutor` should be wrapped through an interface, and the exception translation AOP should recognize that interface instead of depending on a concrete class.
- direct Redisson calls in `VersionCacheWriter.setNegative(...)` bypass `BaseRedisSupport` and `ScriptExecutor`, so they need either their own wrapper coverage or a small refactor if complete Redis latency coverage is required.

## 2. What Is Sound

### Strategy Layer Metrics

Recording semantic read results at the strategy layer is correct:

```text
hit | miss | tombstone | negative | error
```

Only the strategy layer can correctly distinguish these outcomes because it has decoded `CacheReadResult`.

This is especially useful because:

- `miss` and `tombstone` imply DB fallback pressure;
- `negative` means a fast negative cache result, usually avoiding DB;
- `error` means cache degradation, not necessarily business failure;
- result labels stay low-cardinality and stable.

### Support Layer Metrics

Recording low-level operation duration at the Redis support layer is also correct:

```text
operation = get | set | delete | set_if_absent
outcome = success | error
```

This helps answer a different question from strategy metrics:

- strategy metric: "Did cache help or fall back?"
- support timer: "Was Redis/client-side cache slow or failing?"

Do not add `error_type` here. Error classification belongs at the strategy/idempotency wrapper layer where exceptions have domain meaning.

### ScriptExecutor Wrapper

Adding an executor interface and a metrics wrapper is sound.

Lua scripts are a distinct Redis path:

- versioned cache CAS uses `setIfAbsentOrNewer`;
- idempotency release uses `releaseIdempIfOwned`;
- script latency can be very different from simple `GET`/`SET`.

The wrapper should record operation duration and `success|error`, then rethrow the same exception. It should not swallow or translate exceptions.

## 3. Corrections And Improvements

### 3.1 Metric Names Should Avoid Ambiguity

The existing phase-one doc uses:

```text
zexchange.cache.ops
zexchange.cache.errors
zexchange.redis.operation.duration
```

If support-layer metrics are added, `zexchange.cache.ops` becomes slightly ambiguous. It should mean strategy read result only.

Recommended definitions:

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `zexchange.cache.ops` | Counter | `application`, `cache_type`, `result` | Strategy-level cache read result count. |
| `zexchange.cache.errors` | Counter | `application`, `cache_type`, `operation`, `error_type` | Strategy-level cache error count. |
| `zexchange.redis.operation.duration` | Timer | `application`, `component`, `operation`, `outcome` | Low-level Redis/client-side/script operation latency. |

Allowed values:

```text
cache_type = ledger_ref | ledger_txn | wallet_balance_ref | wallet_balance_snapshot | ...
result = value_hit | miss | tombstone_hit | negative_hit | error
operation = get | after_db_hit | after_db_miss | after_update | after_insert
error_type = redis_error | timeout | access | script_error | configuration_error | decode_error | contract_violation | unknown

component = redis_template | redisson_client_side | script_executor
redis operation = get | set | delete | set_if_absent
script operation = set_if_absent_or_newer | release_idemp_if_owned
outcome = success | error
```

Why add `operation` to `zexchange.cache.errors`:

- read errors are not the only possible strategy-layer errors;
- encode/contract errors can occur before low-level Redis support is called;
- write/update errors may otherwise only appear as support-level `error` without semantic cache context.

### 3.2 Do Not Put `cache_type` On Shared Support Metrics

`BaseRedisSupport` is registered as one shared bean. It receives only Redis keys and values.

Tagging support metrics with `cache_type` would require either:

- parsing Redis keys, which is brittle and risks high cardinality;
- creating per-cache support beans, which is too invasive for this phase;
- passing context through method parameters, which pollutes the low-level API.

Recommendation:

```text
zexchange.redis.operation.duration{application,component,operation,outcome}
```

Use strategy metrics for logical cache type. Use support metrics for Redis operation latency.

### 3.3 Client-Side Cache Supports Only Read

Current `BaseClientSideCacheSupport<T>` only implements:

```java
T get(String key)
```

So its valid support-layer operation set is only:

```text
operation = get
component = redisson_client_side
```

`set`, `delete`, and `set_if_absent` apply to `BaseRedisSupport`, not client-side cache support.

### 3.4 Preserve Existing Failure Semantics

Support wrappers should behave transparently:

```text
try operation
  record success duration
  return original result
catch exception
  record error duration
  throw same exception
```

They should not:

- downgrade exceptions to `Result`;
- convert error codes;
- log raw Redis keys or values;
- reinterpret a Boolean `false` as an error.

For Redis operations, `false` from `setIfAbsent` or Lua CAS is a successful command with a negative control result. It is not an infrastructure error.

### 3.5 Wrapper Ordering Matters

Current cache strategies are often built through `StrategyFactory.build*SuppressExceptionStrategy(...)`, which downgrades `CacheException` into `Result.failure`.

The metric strategy wrapper should be outside this suppressing wrapper:

```text
MetricCacheStrategy(
  SuppressExceptionStrategy(
    DefaultCacheStrategy(...)
  )
)
```

That lets the metric wrapper observe both:

- successful `Result<CacheReadResult<T>>`;
- failed `Result` after exception downgrade.

If the metric wrapper is inside the suppressing wrapper, thrown exceptions may be counted, but downgraded failures can be lost depending on where the wrapper is placed.

### 3.6 ScriptExecutor Should Use An Interface

The current `CacheExceptionTranslateAop` checks the concrete `ScriptExecutor` target.

If a wrapper is added, prefer:

```text
RedisScriptExecutor interface
DefaultRedisScriptExecutor implements RedisScriptExecutor
MetricRedisScriptExecutor implements RedisScriptExecutor
```

Then update AOP classification to recognize the interface, not just the concrete implementation.

This keeps script exception classification stable after decoration.

### 3.7 Cover Direct Redisson Calls

`VersionCacheWriter.setNegative(...)` currently calls Redisson directly:

```java
redissonClient.getBucket(key).setIfAbsent(...)
```

This bypasses both:

- `BaseRedisSupport`;
- `ScriptExecutor`.

If complete low-level Redis latency coverage is required, either:

- route this operation through `BaseRedisSupport.setIfAbsent(...)`; or
- add a small Redisson bucket support abstraction and wrap that too.

For phase one, this can be accepted as a known gap if negative writes are not central to the test.

## 4. Missing Metrics

### Required In Phase One

The following are necessary to make the design complete:

| Metric | Why |
|---|---|
| `zexchange.cache.errors{application,cache_type,operation,error_type}` | Strategy read/write errors can happen before support-layer Redis calls, especially decode, encode, and contract errors. |
| `zexchange.redis.operation.duration{application,component,operation,outcome}` | Gives client-observed latency for RedisTemplate, client-side cache, and Lua scripts without relying on external exporters. |

### Optional Later

These are valuable but not mandatory for phase one:

| Metric | Why |
|---|---|
| `zexchange.cache.write.results{application,cache_type,operation,result}` | Helpful for versioned CAS visibility, especially `applied|skipped`, but can wait until cache write behavior becomes a test focus. |
| `zexchange.cache.self_recovery{application,cache_type,outcome}` | Helpful if malformed/tombstone recovery becomes frequent. For now, `cache.errors` plus Redis delete latency is enough. |
| Redis connection pool / Netty pending metrics | Better provided by Redisson/Micrometer or exporter integration later. Not necessary for this code-only phase. |

## 5. Performance Concerns

The metrics themselves are low overhead if implemented carefully.

Recommended guardrails:

- use only bounded labels;
- never tag Redis keys, IDs, account IDs, reference IDs, exception messages, or raw class names;
- avoid parsing Redis keys to derive labels;
- avoid registering new meters from unbounded runtime values;
- for hot paths, cache meter lookups or use Micrometer meter providers instead of repeatedly building meters with dynamic tags;
- record timers around the smallest useful operation, not around JSON encode/decode unless that is intentionally part of the measured strategy operation;
- do not log every cache miss or negative hit.

Expected double visibility is acceptable:

- one strategy counter records semantic result;
- one support timer records Redis call latency.

This is not harmful double counting because the two metrics answer different questions.

## 6. Recommended Implementation Shape

### Interfaces

Introduce interfaces before wrappers:

```text
RedisValueSupport<T>
ClientSideCacheReadSupport<T>
RedisScriptExecutor
```

Current classes can become default implementations:

```text
BaseRedisSupport implements RedisValueSupport
BaseClientSideCacheSupport implements ClientSideCacheReadSupport
DefaultScriptExecutor implements RedisScriptExecutor
```

Metric wrappers then decorate the interfaces.

### Strategy Wrappers

Add wrappers for:

```text
MetricRawCacheStrategy<T>
MetricVersionCacheStrategy<T>
```

They should:

- record `zexchange.cache.ops` for `get`;
- record `zexchange.cache.errors` for failed strategy results;
- preserve original `Result` values;
- avoid logging normal misses/negative hits.

### Support Wrappers

Add wrappers for:

```text
MetricRedisValueSupport<T>
MetricClientSideCacheReadSupport<T>
MetricRedisScriptExecutor
```

They should:

- record `zexchange.redis.operation.duration`;
- use `component` to distinguish RedisTemplate, Redisson client-side cache, and scripts;
- record only `success|error`;
- preserve exceptions exactly.

## 7. Final Assessment

The two-layer design is correct, but it should be implemented with a clean boundary:

- strategy metrics know cache semantics and error type;
- support metrics know operation latency and infrastructure success/error;
- script metrics are just another support-level Redis operation;
- low-level wrappers should not be forced to understand logical cache types.

With the corrections above, the design is complete enough for phase one and fits the current `common-util` cache architecture.
