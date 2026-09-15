# Idempotency Redis Wrapper Change Scope

## Goal

Add a shared `Result`-returning idempotency Redis wrapper in `common-util` and use it in `ledger-service` first.

The wrapper centralizes:

- Redis/cache exception downgrade;
- idempotency value parsing;
- request-hash verification;
- normalized idempotency error metrics;
- sanitized idempotency error logs.

Wallet remains on the raw `IdempRedisClient` path for now.

## Code Scope

### Common Redis Script Executor

Moved the Lua script executor from cache-specific infrastructure to common Redis infrastructure:

```text
com.exchange.common.redis.component.ScriptExecutor
```

The executor now owns:

- `set-if-absent-or-newer.lua`
- `release-idemp-if-owned.lua`

`CacheExceptionTranslateAop` now checks the common Redis `ScriptExecutor`, so Lua failures continue to map to Redis script/configuration errors.

### Raw Idempotency Redis Client

`IdempRedisClient` remains available for backward compatibility.

It still exposes the existing methods used by wallet, but delegates `releaseIdempIfOwned` to the shared `ScriptExecutor`.

### Result-Returning Wrapper

Added:

```text
com.exchange.common.redis.idemp.IdempotencyClient
com.exchange.common.redis.idemp.DefaultIdempotencyClient
```

Wrapper operations:

| Operation | Method | Normal negative outcome |
|---|---|---|
| `claim` | `claimIdempIfAbsent` | `Result.success(false)` |
| `mark_done` | `markIdempDone` | none |
| `get` | `getIdemp` | `Result.success(null)` when key is absent |
| `hash_compare` | `getIdempAndVerifyHash` | `Result.failure(HASH_CONFLICT)` on mismatch |
| `release` | `releaseIdempIfOwned` | `Result.success("")` when key is absent or not owned |
| `force_delete` | `forceDeleteIdemp` | `Result.success(false)` when key is absent |

### Bean Registration

`RedisIdempRegister` now registers:

- raw `IdempRedisClient`;
- wrapper `IdempotencyClient`.

Services can migrate from raw Redis idempotency access to the wrapper incrementally.

### Ledger-Service Usage

`PostLedgerProcessor` now uses `IdempotencyClient`.

Behavior:

- Redis idempotency claim/read failures fall back to DB idempotency.
- malformed Redis idempotency values are metriced, then force-deleted for self-recovery.
- hash conflicts still fail the request.
- mark-done failures after DB commit are logged and metriced but do not undo the committed ledger transaction.
- release failures are logged and metriced; empty release result remains a normal outcome.

## Metric Definition

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `zexchange.idempotency.errors` | Counter | `service`, `scope`, `operation`, `error_type` | Abnormal idempotency infrastructure or semantic failures. |

## Metric Tags

| Tag | Meaning | Expected values |
|---|---|---|
| `service` | Bounded service owner of the idempotency key. | `ledger`, `wallet` |
| `scope` | Bounded idempotency scope. | `post_ledger`, future wallet scopes |
| `operation` | Wrapper operation that observed the error. | `claim`, `mark_done`, `get`, `hash_compare`, `release`, `force_delete` |
| `error_type` | Normalized error category. | `hash_conflict`, `parse_error`, `redis_error`, `timeout`, `access`, `script_error`, `configuration_error`, `contract_violation`, `unknown` |

## Error Type Mapping

| Source error | `error_type` |
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
| unmapped error | `unknown` |

## Measurement Rules

- Increment the counter only for `Result.failure` or downgraded exceptions.
- Do not increment the counter for normal negative Redis outcomes.
- Do not use idempotency key, reference ID, token, hash, raw Redis value, host, pod, or instance ID as metric tags.
- Logs emitted by the wrapper include only service, scope, operation, and normalized error type.

## Out Of Scope

- Wallet-service migration to `IdempotencyClient`.
- Redis client-side latency timers.
- Cache metric wrappers.
- External exporters.
- Business-flow refactors beyond ledger idempotency access.
