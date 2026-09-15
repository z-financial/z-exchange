# Idempotency Metrics Wrapper Design Evaluation

## Verdict

The design is directionally sound. Moving ledger and wallet idempotency access behind a metric/exception-enhanced wrapper is a good fit for `common-util`, because idempotency is shared infrastructure and the services should not each reimplement Redis exception handling, parsing, hash verification, and metric emission.

It is not complete as written. The design needs a few corrections before implementation:

- define exact wrapper semantics for normal negative Redis outcomes, such as `claim=false`, `get=null`, and release returning empty string;
- fix operation naming, especially `mark_down` -> `mark_done`;
- decide whether `hash_compare` is a logical idempotency operation only, not a Redis client-side operation;
- map Redis malformed key/value errors to parse-related metric error types;
- keep idempotency-specific errors limited to idempotency semantics, currently `hash_conflict`;
- update the moved `ScriptExecutor` references in the cache AOP, otherwise script errors may stop being classified as script errors.

## Sound Parts

### Shared Wrapper In Common-Util

This is sound.

`IdempRedisClient` is used by both ledger and wallet. A wrapper that returns `Result` and records common metrics keeps the business code cleaner and preserves the service boundary: common-util handles idempotency infrastructure, while ledger/wallet still decide business outcomes.

Recommended responsibility split:

| Layer | Responsibility |
|---|---|
| Raw `IdempRedisClient` | Build keys/values and execute Redis operations. May throw translated Redis/cache exceptions. |
| Metric wrapper | Downgrade exceptions to `Result`, parse idempotency values, verify request hash, emit idempotency error metrics, log normalized errors. |
| Ledger/wallet business code | Interpret `PENDING`, `ACCEPTED`, hash conflict, DB fallback, and business replay semantics. |

### Moving ScriptExecutor Up To Redis Package

This is sound.

`ScriptExecutor` is not cache-specific anymore if it manages both cache CAS scripts and idempotency release scripts. A package such as this is cleaner:

```text
com.exchange.common.redis.component.ScriptExecutor
```

or:

```text
com.exchange.common.redis.script.ScriptExecutor
```

The moved executor should own:

- `set-if-absent-or-newer.lua`
- `release-idemp-if-owned.lua`

This also lets the Redisson script call be covered by the same exception translation pattern as cache scripts.

Important correction: `CacheExceptionTranslateAop.isScriptOperation(...)` currently checks the concrete cache `ScriptExecutor` class. If `ScriptExecutor` moves packages, update this check. Better options:

- check a marker interface such as `RedisScriptExecutor`;
- check an annotation on script methods;
- or update the import to the new common Redis `ScriptExecutor`.

### Wrapper.getIdemp Decoding

This is sound and should reduce duplicate parsing logic in ledger/wallet.

Current business code performs:

```java
String idempValue = idempRedisClient.getIdemp(...);
Result<IdempValue> parsed = CommonIdempHelper.parseIdempValue(idempValue);
```

Moving this into the wrapper makes the contract clearer:

```java
Result<IdempValue> getIdemp(service, scope, idempId)
```

Required semantic rule:

```text
Redis key absent -> Result.success(null)
Malformed value -> Result.failure(RedisErrorCode.MALFORMED_VALUE)
Redis exception -> Result.failure(redis/timeout/script/etc.)
```

Do not pass `null` into `CommonIdempHelper.parseIdempValue(...)`; handle absent key first.

### getIdempAndVerifyHash

This is sound.

The helper is more readable than repeating parse/hash logic in each service:

```java
Result<IdempValue> getIdempAndVerifyHash(service, scope, idempId, expectedHash)
```

Recommended semantics:

| Condition | Result |
|---|---|
| Key does not exist | `Result.success(null)` |
| Value malformed | `Result.failure(RedisErrorCode.MALFORMED_VALUE)` |
| Hash mismatch | `Result.failure(IdempErrorCode.HASH_CONFLICT)` |
| Hash matches | `Result.success(idempValue)` |
| Redis failure | `Result.failure(redis_error/timeout/script/etc.)` |

## Metrics Evaluation

Proposed metric:

```text
zexchange.idempotency.errors{service,scope,operation,error_type}
```

This is sound if all labels are bounded.

### Tags

| Tag | Evaluation |
|---|---|
| `service` | Reasonable if derived from `GlobalServiceId.code`, such as `ledger` or `wallet`. Do not use instance ID, host, pod, or arbitrary caller input. |
| `scope` | Reasonable if derived from the bounded idempotency `scope` argument, such as `post_ledger` or `post_transaction`. Do not derive from request fields. |
| `operation` | Useful and low-cardinality if restricted to a fixed enum. |
| `error_type` | Useful, but must be normalized through an allowlist. Do not use raw exception class or message. |

### Operation Values

Recommended operation names:

```text
claim | mark_done | get | hash_compare | release | force_delete
```

Corrections:

- use `mark_done`, not `mark_down`;
- `hash_compare` is a logical idempotency operation, not a Redis command;
- if Redis client-side latency metrics are added later, do not record `hash_compare` under Redis latency.

### Error Type Mapping

The design says `error_type` converts from `Result.errorCode`. That is fine, but the mapping must be explicit.

Recommended normalized values:

```text
hash_conflict
parse_error
redis_error
timeout
access
script_error
configuration_error
contract_violation
unknown
```

Example mapping:

| Source error | Metric `error_type` |
|---|---|
| `IdempErrorCode.HASH_CONFLICT` | `hash_conflict` |
| `RedisErrorCode.MALFORMED_KEY` / `MALFORMED_VALUE` | `parse_error` |
| `RedisErrorCode.TIMEOUT` | `timeout` |
| `RedisErrorCode.ACCESS` | `access` |
| `RedisErrorCode.SCRIPT` | `script_error` |
| `RedisErrorCode.CONFIGURATION` | `configuration_error` |
| `RedisErrorCode.CONNECTION` | `redis_error` |
| `RedisErrorCode.UNEXPECTED_INTERNAL` | `unknown` |
| `CacheErrorCode.CONTRACT_VIOLATION` | `contract_violation` |

Do not blindly use raw enum names unless they are guaranteed stable and bounded.

The agreed error-code ownership is:

| Error family | Ownership |
|---|---|
| `RedisErrorCode` | Redis/client/script/codec/storage-shape failures, including malformed idempotency key/value. |
| `CacheErrorCode` | Cache contract failures only, currently `CONTRACT_VIOLATION`. |
| `IdempErrorCode` | Idempotency semantic failures only, currently `HASH_CONFLICT`. |

## Semantic Corrections Needed

### Keep Normal Negative Outcomes Out Of Error Metrics

The wrapper should increment `zexchange.idempotency.errors` only for abnormal failures.

These should normally not be counted as errors:

| Operation | Normal non-error outcome |
|---|---|
| `claim` | `false`, because another request owns the key |
| `get` | `null`, because no idempotency key exists |
| `release` | empty Lua return, because key is already gone or no longer owned |
| `force_delete` | `false`, because key does not exist |

Hash conflict is different: it is a correctness/idempotency failure and should be counted.

### Release Script Return Needs Care

Current `release-idemp-if-owned.lua` returns empty string when:

- the key is already gone;
- the caller does not own the key;
- the key is no longer in the expected pending state.

That means an empty return is not necessarily a Redis failure. Treat script execution failure as an infra error, but do not add a separate release-specific idempotency error type unless the business contract explicitly wants to observe release misses as abnormal.

Do not introduce a release-specific idempotency error code. If release fails by exception, emit the metric by converting the underlying `Result.errorCode`, usually from `RedisErrorCode`.

### Avoid Sensitive Logging

The wrapper should log:

```text
service, scope, operation, normalized error code
```

It should not log:

```text
idempotency key, reference ID, token, hash, raw Redis value, request payload
```

This matters because `IdempValue` contains token/hash-like data and may be high-cardinality or sensitive.

### Result Contract Should Be Consistent

The raw `IdempRedisClient` currently mixes primitive returns and `Result<String>` for `releaseIdempIfOwned`. For a cleaner design:

- raw client should consistently throw translated exceptions and return primitive/raw values;
- wrapper should consistently return `Result`.

That makes the downgrade boundary obvious and easier to test.

## Completeness Gaps

Before implementation, define these items:

1. A wrapper interface, for example `IdempotencyClient`, so services depend on the Result-returning API rather than the raw Redis client.
2. Bean registration rules so ledger/wallet inject the wrapper, while the raw `IdempRedisClient` remains an internal dependency.
3. Metric constants in `common-util`, including metric name, description, tags, operation values, and error type values.
4. Error-type conversion from `RedisErrorCode`, `CacheErrorCode`, and `IdempErrorCode`.
5. Whether `release` empty return is success, warning, or failure.
6. How wrapper logging avoids raw key/value/token/hash details.
7. Unit tests using a fake `MeterRegistry` or `SimpleMeterRegistry`.

## Recommended Final Shape

```java
public interface IdempotencyClient {
    Result<Boolean> claimIdempIfAbsent(String service, String scope, String idempId,
                                       String hash, String token, Duration ttl);

    Result<Void> markIdempDone(String service, String scope, String idempId,
                               String hash, String token, String content, Duration ttl);

    Result<IdempValue> getIdemp(String service, String scope, String idempId);

    Result<IdempValue> getIdempAndVerifyHash(String service, String scope,
                                             String idempId, String expectedHash);

    Result<String> releaseIdempIfOwned(String service, String scope,
                                       String idempId, String expectedValue);

    Result<Boolean> forceDeleteIdemp(String service, String scope, String idempId);
}
```

Metric behavior:

```text
on Result.failure:
  zexchange.idempotency.errors{service,scope,operation,error_type} += 1
```

Do not increment the error counter for normal negative outcomes.

## Overall Assessment

The design is worth implementing, but only after tightening the contracts above. The strongest part is moving idempotency parsing, hash verification, exception downgrade, and error metrics into one common wrapper. The biggest risk is accidentally changing idempotency behavior by treating normal Redis negative results as errors, or by leaking high-cardinality values through labels/logs.
