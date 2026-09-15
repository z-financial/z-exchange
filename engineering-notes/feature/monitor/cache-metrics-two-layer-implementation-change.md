# Cache Metrics Two-Layer Implementation Change

## 1. Purpose

This document records the implementation scope for the phase-one cache metrics work based on:

- `ai/monitor/feature/cache-metrics-two-layer-change-scope.md`
- the follow-up decision that `cache.ops` is read-only, while `cache.errors` records both `get` and write/update strategy failures.

The implementation keeps wallet-service source files untouched. Shared support wrappers are implemented in `common-util`, and ledger-service opts into strategy-level cache metrics for its two cache beans.

## 2. Implemented Change Points

### 2.1 Common Metric Definitions

Added common metric definitions for:

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `zexchange.cache.ops` | Counter | `application`, `cache_type`, `result` | Strategy-level cache read result count. |
| `zexchange.cache.errors` | Counter | `application`, `cache_type`, `operation`, `error_type` | Strategy-level cache failure count with normalized reason. |
| `zexchange.redis.operation.duration` | Timer | `application`, `component`, `operation`, `outcome` | Client-observed Redis/client-side-cache/script operation duration. |

Common tag constants were added for:

```text
cache_type
result
component
outcome
```

### 2.2 Strategy-Level Cache Metrics

Added strategy wrappers:

```text
MetricRawCacheStrategy<T>
MetricVersionCacheStrategy<T>
```

Behavior:

- `get` records `zexchange.cache.ops`;
- failed `get` also records `zexchange.cache.errors`;
- failed `after_db_hit`, `after_db_miss`, `after_update`, and `after_insert` record `zexchange.cache.errors`;
- successful write/update strategy calls do not record `cache.ops`;
- wrappers preserve original `Result` values and rethrow unexpected runtime exceptions.

Ledger activation:

| Ledger cache bean | Strategy wrapper | `cache_type` |
|---|---|---|
| `ledgerRefCache` | `MetricRawCacheStrategy<String>` | `ledger_ref` |
| `ledgerTxnCache` | `MetricVersionCacheStrategy<LedgerTxn>` | `ledger_txn` |

### 2.3 RedisTemplate Support Metrics

Added interface and wrapper:

```text
RedisValueSupport<T>
MeteredRedisValueSupport<T>
```

Measured operations:

```text
component = redis_template
operation = get | set | delete | set_if_absent
outcome = success | error
```

Notes:

- `BaseRedisSupport<T>` remains the raw implementation.
- `MeteredRedisValueSupport<T>` is registered as the primary `RedisValueSupport<T>`.
- Boolean negative outcomes such as `setIfAbsent=false` are still `outcome=success`.

### 2.4 Client-Side Cache Support Metrics

Added interface and wrapper:

```text
ClientSideCacheReadSupport<T>
MeteredClientSideCacheReadSupport<T>
```

Measured operations:

```text
component = redisson_client_side
operation = get
outcome = success | error
```

Notes:

- Client-side-cache remains read-only.
- It does not directly set negative values.

### 2.5 Script Executor Metrics

Added interface and wrapper:

```text
RedisScriptExecutor
MeteredRedisScriptExecutor
```

Measured operations:

```text
component = script_executor
operation = set_if_absent_or_newer | release_idemp_if_owned
outcome = success | error
```

The cache exception translation AOP now classifies script operations through `RedisScriptExecutor`, so the metric wrapper does not break Lua error classification.

### 2.6 Version Negative Cache Write Alignment

Already implemented before this change set:

- `VersionCacheWriter.setNegative(...)` now uses `BaseRedisSupport.setIfAbsent(...)` through the support interface.
- Versioned negative value format remains unchanged.
- CAS/tombstone writes still use Lua.

## 3. Error Type Mapping

`zexchange.cache.errors` uses this normalized mapping:

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

## 4. Scope Boundary

Changed areas:

- common cache/Redis support infrastructure in `common-util`;
- ledger cache registration in `ledger-service`;
- cache metrics documentation under `ai/monitor/feature`.

Not changed:

- wallet-service source files;
- wallet cache strategy registration;
- business cache behavior;
- cache TTL values;
- Redis key format;
- DB fallback behavior.

## 5. Validation

Compile validation:

```text
mvn -pl common-util,ledger-service -am -DskipTests compile
mvn -pl common-util,wallet-service -am -DskipTests compile
```

Result:

```text
BUILD SUCCESS
```

The wallet compile was a shared-infrastructure compatibility check only; wallet-service source files were not modified.

Suggested runtime validation:

1. Ledger cache hit increments `zexchange.cache.ops{application="ledger-service",cache_type="ledger_txn",result="value_hit"}` or `cache_type="ledger_ref"`.
2. Ledger cache miss increments `zexchange.cache.ops{result="miss"}`.
3. Malformed cached value increments both `zexchange.cache.ops{result="error"}` and `zexchange.cache.errors{operation="get",error_type="decode_error"}`.
4. RedisTemplate operations emit `zexchange.redis.operation.duration{component="redis_template"}`.
5. Version CAS script emits `zexchange.redis.operation.duration{component="script_executor",operation="set_if_absent_or_newer"}`.
6. No emitted metric includes Redis keys, ledger transaction IDs, reference IDs, account IDs, raw exception messages, or raw exception class names.
