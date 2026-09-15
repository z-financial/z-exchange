# Cache Metrics Two-Layer Change Scope

## 1. Purpose

This document defines the agreed phase-one cache metrics design for `common-util`.

The goal is to measure cache behavior in two layers:

- strategy layer: semantic cache read result and normalized strategy error type;
- support layer: Redis/client-side-cache/Lua operation latency and infrastructure success/error.

This scope is application-side Micrometer instrumentation only. External Redis exporter metrics, Redisson internal metrics, dashboard provisioning, and alert rules are not included here.

## 2. Implementation Scope

### 2.1 Strategy Layer Metrics

Change points:

- add metric wrappers for `RawCacheStrategy<T>` and `VersionCacheStrategy<T>`;
- wrap the already suppressing strategy, so metrics can observe `Result.failure`;
- record read result only from `get`;
- record strategy error type for `get`, `after_db_hit`, `after_db_miss`, `after_update`, and `after_insert`;
- preserve the original `Result` and business flow.

Recommended wrapper order:

```text
MetricCacheStrategy(
  SuppressExceptionStrategy(
    DefaultCacheStrategy(...)
  )
)
```

Metrics:

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `zexchange.cache.ops` | Counter | `application`, `cache_type`, `result` | Strategy-level cache read result count. Only emitted by `get`. |
| `zexchange.cache.errors` | Counter | `application`, `cache_type`, `operation`, `error_type` | Strategy-level cache failure count with normalized reason. Emitted for read and write/update strategy operations. |

Allowed values:

```text
cache_type = ledger_ref | ledger_txn | wallet_balance_ref | wallet_balance_snapshot | ...
result = value_hit | miss | tombstone_hit | negative_hit | error
operation = get | after_db_hit | after_db_miss | after_update | after_insert
error_type = redis_error | timeout | access | script_error | configuration_error | decode_error | contract_violation | unknown
```

Notes:

- `cache.ops` is read-only. Do not emit it from `after_db_hit`, `after_db_miss`, `after_update`, or `after_insert`.
- `cache.errors` includes `get` because read errors need the error type, especially decode/malformed-value errors.
- `cache.errors` also includes write/update operations because their behavior depends on concrete strategy implementation.
- `miss` and `tombstone_hit` imply DB fallback pressure.
- `negative_hit` is a fast negative cache result and normally avoids DB access.
- Do not tag by Redis key, account ID, transaction ID, reference ID, exception message, or raw exception class.

### 2.2 Support Layer Redis Metrics

Change points:

- introduce a support interface for Redis value operations;
- keep `BaseRedisSupport<T>` as the default implementation;
- add a metric wrapper around the support interface;
- record operation duration and `success|error`;
- do not classify error type in this layer.

Metrics:

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `zexchange.redis.operation.duration` | Timer | `application`, `component`, `operation`, `outcome` | Client-observed latency for RedisTemplate value operations. |

Allowed values:

```text
component = redis_template
operation = get | set | delete | set_if_absent
outcome = success | error
```

Notes:

- Do not tag support-layer metrics with `cache_type`; shared Redis support does not know the logical cache.
- Boolean `false` from `set_if_absent` is still `outcome=success`; it means the Redis command succeeded but the key already existed.
- The wrapper must rethrow the same exception and must not downgrade to `Result`.

### 2.3 Client-Side Cache Support Metrics

Change points:

- introduce a read-support interface for client-side cache reads;
- keep `BaseClientSideCacheSupport<T>` as the default implementation;
- add a metric wrapper around the read-support interface.

Metrics:

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `zexchange.redis.operation.duration` | Timer | `application`, `component`, `operation`, `outcome` | Client-observed latency for Redisson client-side-cache reads. |

Allowed values:

```text
component = redisson_client_side
operation = get
outcome = success | error
```

Notes:

- Client-side-cache currently supports only `get`; do not model `set`, `delete`, or `set_if_absent` for it.
- Client-side-cache should not directly set negative cache values. Negative values are written to Redis and may then be read/cached locally.

### 2.4 Script Executor Metrics

Change points:

- introduce a script executor interface;
- keep the current `ScriptExecutor` behavior as the default implementation;
- add a metric wrapper around the script executor interface;
- update script exception classification to recognize the interface/wrapper, not only a concrete implementation.

Metrics:

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `zexchange.redis.operation.duration` | Timer | `application`, `component`, `operation`, `outcome` | Client-observed latency for Lua script execution. |

Allowed values:

```text
component = script_executor
operation = set_if_absent_or_newer | release_idemp_if_owned
outcome = success | error
```

Notes:

- Do not add `error_type` to script duration metrics.
- Script errors should still be translated to `RedisErrorCode.SCRIPT` by the existing exception translation path.
- Boolean `false` from version CAS is not an error; it means the script succeeded and kept the newer existing value.

### 2.5 Version Negative Cache Write Alignment

Change points:

- route `VersionCacheWriter.setNegative(...)` through `BaseRedisSupport.setIfAbsent(...)`;
- keep versioned negative value format unchanged: version `0` plus negative marker;
- keep versioned CAS writes through Lua script.

Reason:

- raw cache negative writes already use `BaseRedisSupport.setIfAbsent(...)`;
- routing versioned negative writes through the same support path gives support-layer timing coverage;
- it avoids direct Redisson bucket writes for this path;
- client-side readers still observe the negative value from Redis.

Notes:

- This does not change cache semantics: it is still atomic set-if-absent with TTL.
- This does not degrade client-side-cache read performance; write path and read path remain separate.

## 3. Error Type Mapping

Recommended normalized mapping for `zexchange.cache.errors`:

| Source error | `error_type` |
|---|---|
| `RedisErrorCode.TIMEOUT` | `timeout` |
| `RedisErrorCode.ACCESS` | `access` |
| `RedisErrorCode.SCRIPT` | `script_error` |
| `RedisErrorCode.CONFIGURATION` | `configuration_error` |
| `RedisErrorCode.CONNECTION` | `redis_error` |
| `RedisErrorCode.MALFORMED_VALUE` | `decode_error` |
| `RedisErrorCode.SERIALIZATION` | `decode_error` |
| `CacheErrorCode.CONTRACT_VIOLATION` | `contract_violation` |
| Any other failure | `unknown` |

Do not expose raw error messages or raw exception class names as metric labels.

## 4. Excluded From This Scope

The following are intentionally excluded:

- Redis exporter setup;
- Redisson pool/internal metric integration;
- dashboards and alert rules;
- per-key metrics;
- DB fallback counter separate from `cache.ops`;
- cache write result metric such as `applied|skipped`;
- cache self-recovery metric;
- changing cache strategy behavior or TTL semantics.

## 5. Cardinality Rules

Allowed labels:

- `application`;
- `cache_type`;
- `result`;
- `operation`;
- `error_type`;
- `component`;
- `outcome`.

Forbidden labels:

- Redis key;
- account ID;
- wallet ID;
- ledger transaction ID;
- reference ID;
- command ID;
- request ID;
- raw exception message;
- raw exception class name;
- serialized cache value.

## 6. Validation Checklist

Before relying on these metrics in a load test, validate:

1. Cache value hit increments `zexchange.cache.ops{result="value_hit"}`.
2. Cache miss increments `zexchange.cache.ops{result="miss"}`.
3. Tombstone hit increments `zexchange.cache.ops{result="tombstone_hit"}`.
4. Negative hit increments `zexchange.cache.ops{result="negative_hit"}`.
5. Cache get failure increments both `zexchange.cache.ops{result="error"}` and `zexchange.cache.errors{operation="get",error_type=...}`.
6. `after_db_hit`, `after_db_miss`, `after_update`, and `after_insert` failures increment `zexchange.cache.errors` with the correct `operation`.
7. RedisTemplate support operations record `zexchange.redis.operation.duration{component="redis_template"}`.
8. Client-side-cache reads record `zexchange.redis.operation.duration{component="redisson_client_side",operation="get"}`.
9. Lua script calls record `zexchange.redis.operation.duration{component="script_executor"}`.
10. Boolean negative outcomes such as `setIfAbsent=false` or CAS skipped are recorded as support-level `outcome="success"`, not `error`.
11. No emitted metric contains Redis keys, IDs, raw exception messages, or unbounded labels.
