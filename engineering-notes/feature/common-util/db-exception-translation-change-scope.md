# DB Exception Translation Change Scope

## Goal

Add common DB exception translation so ledger and wallet services no longer receive raw Spring/MyBatis/JDBC exceptions from repository and transaction boundaries.

## Implemented Scope

### Retryable Error Contract

Added `RetryableErrorCode extends ErrorCode`.

Purpose:

- keep retry semantics out of the base `ErrorCode` contract;
- allow infrastructure code to detect retryable errors generically;
- let DB errors expose retryability without forcing all error domains to do so.

### DB Error Codes

Added `DbErrorCode`.

| Code | Message | Category | Retryable | Meaning |
|---|---|---|---:|---|
| `DB_INTERNAL` | `db_internal` | `INTERNAL` | false | Schema, mapper, data-integrity, transaction ambiguity, or unknown DB failure. |
| `DB_TIMEOUT` | `db_timeout` | `UNAVAILABLE` | true | Query/socket/SQL timeout. |
| `DB_UNAVAILABLE` | `db_unavailable` | `UNAVAILABLE` | true | DB connection, pool, or network unavailable. |
| `DB_TRANSIENT` | `db_transient` | `UNAVAILABLE` | true | Deadlock, lock wait timeout, or temporary contention. |

`DB_TRANSIENT` intentionally uses `UNAVAILABLE` because it represents temporary DB unavailability caused by contention, not a domain conflict.

### DB Exception

Added `DbException extends CustomizedException`.

Factory methods:

- `internal(operation, cause)`
- `timeout(operation, cause)`
- `unavailable(operation, cause)`
- `transientFailure(operation, cause)`
- `create(errorCode, operation, cause)`

Meaning:

- carries the normalized `DbErrorCode`;
- preserves the original cause;
- exposes `isRetryable()` through the DB error code.

### DB Exception Translator

Added `DbExceptionTranslator`.

Classification:

- connection/pool/network failures -> `DB_UNAVAILABLE`;
- query/socket/SQL timeout -> `DB_TIMEOUT`;
- MySQL deadlock `1213`, SQL state `40001`, lock wait timeout `1205` -> `DB_TRANSIENT`;
- bad SQL, data access errors, transaction system ambiguity, unknown SQL failures -> `DB_INTERNAL`.

The translator returns `null` for non-DB exceptions so service/domain exceptions are not incorrectly wrapped.

### AOP Boundary

Added `DbExceptionTranslateAop`.

Applied to:

- `DbBaseRepository` subclasses;
- public `DbTxnExecutor` methods.

Behavior:

- raw DB exceptions are translated into `DbException`;
- existing `DbException` is passed through unchanged;
- non-DB exceptions are passed through unchanged;
- translated failures are logged once at the translation boundary.

### Transaction Executor Adjustment

Updated `DbTxnExecutor` to avoid logging already translated `DbException` again inside the transaction callback.

Reason:

- repository AOP logs the translated DB failure;
- `DbTxnExecutor` still marks rollback-only;
- duplicate stack traces are avoided.

### Service Boundary Normalization

Updated ledger and wallet boundary error mappers to treat common DB namespace errors as common infra failures.

Current behavior:

- DB common errors normalize to existing service `INTERNAL_ERROR`.

Future option:

- services can add explicit unavailable/server error mapping if public API should expose `ERROR_UNAVAILABLE` for retryable DB failures.

## Verification

Commands run:

```powershell
mvn -pl common-util,ledger-service,wallet-service -am -DskipTests test-compile
mvn -pl common-util -Dtest=DbExceptionTranslatorTest test
```

Result:

- compile passed;
- `DbExceptionTranslatorTest` passed with 5 tests.

## Notes

- `insertIgnore` duplicate-key semantics are preserved: expected duplicates still return `0`.
- Transaction commit/rollback ambiguity stays `DB_INTERNAL` and non-retryable.
- This phase does not add DB metrics; it only provides normalized exception/error types for later metrics and retry policy work.
