# DB Exception Translation Design Evaluation

## Scope

This evaluates the proposed common-util DB exception translation design for:

- `DbBaseRepository`
- MyBatis mapper/repository calls
- `DbTxnExecutor`
- common `DbErrorCode` / `DbException`

No code implementation is included in this document.

## Overall Verdict

The design is sound and worth implementing.

It correctly separates DB infrastructure failures from business errors, keeps retryability as an internal decision, and avoids leaking raw Spring/MySQL exceptions into ledger or wallet service code. The target boundaries are also right: repository/mapper calls for SQL operation failures, and `DbTxnExecutor` for transaction lifecycle failures.

The design needs a few refinements before implementation:

- Use consistent public error names, especially around timeout.
- Treat lock wait timeout and deadlock as retriable transient DB failures.
- Keep transaction commit/rollback ambiguity conservative and non-retriable.
- Make the translation idempotent so AOP on both repository and transaction executor does not double-wrap.
- Preserve expected duplicate-key semantics used by `insertIgnore`.

## Exception Mapping Evaluation

| Case | Proposed mapping | Evaluation | Recommendation |
|---|---|---|---|
| Connection failure / pool unavailable | `db_unavailable`, retriable | Sound. This is an infra availability issue and retry is usually valid. | Keep. Include typed connection/pool failures such as `CannotGetJdbcConnectionException`, `DataAccessResourceFailureException`, `SQLTransientConnectionException`, `ConnectException`, and `NoRouteToHostException`. Avoid free-form message matching in phase one. |
| Query timeout / socket timeout | `db_connection_timeout`, retriable | Semantics are sound, but name is too narrow. Query timeout is not always a connection timeout. | Use `db_timeout` as the public code. Internally classify query timeout, socket timeout, and SQL timeout into it. |
| Deadlock / lock wait timeout | `db_transient`, retriable | Sound. Lock wait timeout should be transient, not internal. It usually means contention or ordering pressure. | Keep as `db_transient`. Include MySQL deadlock and lock wait timeout error codes/states. |
| Unexpected `DataIntegrityViolationException` | `db_internal`, non-retriable | Mostly sound. Unexpected integrity violations usually indicate schema/data/invariant bugs. | Keep, but preserve duplicate-key exceptions that are expected idempotency signals, especially `insertIgnore`. |
| Bad SQL / mapper/config bug | `db_internal`, non-retriable | Sound. This is code/config failure, not caller-correctable. | Keep. Include bad grammar and mapper binding/config exceptions. |
| Transaction cannot start due to connection issue | `db_unavailable`, retriable | Sound. No business effect has happened if transaction start failed. | Keep. Classify at `DbTxnExecutor` boundary. |
| Commit/rollback/system transaction ambiguity | `db_internal`, non-retriable | Sound and conservative. Automatic retry can duplicate effects unless idempotency verifies the outcome. | Keep. Log with high severity and enough context for manual investigation or idempotency-based recovery. |

## DbErrorCode Evaluation

The proposed business-visible codes are good:

- `db_internal`
- `db_timeout`
- `db_unavailable`
- `db_transient`

Recommended categories:

| Code | Category | Retryable | Meaning |
|---|---|---:|---|
| `db_unavailable` | `UNAVAILABLE` | true | DB/pool/network is unavailable before or during operation. |
| `db_timeout` | `UNAVAILABLE` | true | Query/socket/SQL operation timed out. |
| `db_transient` | `UNAVAILABLE` | true | Deadlock, lock wait timeout, or other contention-related transient failure. |
| `db_internal` | `INTERNAL` | false | Schema, mapper, integrity, transaction ambiguity, or unknown DB failure. |

I would keep these four only for phase one. More detailed DB classes can be added later as metric tags or logs, not public business codes. `db_transient` should use `UNAVAILABLE` because it means the DB could not complete the operation temporarily due to contention, not that the domain data conflicts with the request.

One nuance: `retryable` should not be added to the generic `ErrorCode` interface. Use a narrower marker interface such as `RetryableErrorCode extends ErrorCode`, then let `DbErrorCode` implement it. This keeps retry semantics available to infrastructure without forcing every error domain to define retry behavior.

## DbException Evaluation

The proposed `DbException extends CustomizedException` is correct.

Recommended shape:

- `DbException` should store the translated `DbErrorCode`.
- It should preserve the original cause.
- Factory methods should be explicit:
  - `internal(String operation, Throwable cause)`
  - `timeout(String operation, Throwable cause)`
  - `unavailable(String operation, Throwable cause)`
  - `transientFailure(String operation, Throwable cause)`
- Message should include a bounded operation name, not raw SQL or sensitive parameters.

This keeps business code dependent on one common exception type and one common DB error namespace.

## AOP Boundary Evaluation

Using AOP is reasonable, but the pointcuts must be narrow.

Recommended boundaries:

1. Repository level
   - Translate raw Spring/MyBatis/JDBC exceptions thrown by repository methods.
   - This covers service-owned repositories and common repositories such as outbox.
   - Prefer an annotation like `@DbExceptionTranslate` or a package pointcut scoped to repository classes.

2. `DbTxnExecutor`
   - Translate transaction begin/commit/rollback failures.
   - Also pass through existing `DbException` unchanged.

3. Mapper level
   - Avoid direct mapper access in business code where possible.
   - If mapper AOP is added later, make it a fallback for missed repository coverage.
   - Repository wrappers are still cleaner because they preserve ownership and allow method-specific semantics.

Important implementation rules:

- If the exception is already `DbException`, rethrow it unchanged.
- `insertIgnore` must continue to return `0` for expected `DuplicateKeyException`.
- Do not translate service-domain exceptions such as `InvalidValueException`.
- Do not log the same failure twice at both repository and transaction layers.
- Put the DB translation aspect before broad service-level exception handlers.

## Completeness Gaps

The design is close, but should explicitly cover these before coding:

1. Root-cause classification
   - Spring often wraps JDBC/MySQL exceptions.
   - The classifier should inspect the root cause, SQL state, vendor error code, and class names.

2. MySQL-specific transient signals
   - Deadlock: MySQL error `1213`, SQL state `40001`.
   - Lock wait timeout: MySQL error `1205`, usually SQL state `HY000`.
   - Both should map to `db_transient`.

3. Transaction ambiguity
   - Commit failure after the DB may have committed is not safely retryable.
   - The design already says this; implementation should make this case visibly `db_internal` and non-retriable.

4. Boundary error mapping
   - Ledger and wallet boundary mappers currently normalize common infra errors.
   - They should map common DB errors to service-level internal/unavailable semantics intentionally, not through fallback logging noise.

5. Metrics
   - DB error metrics should tag by low-cardinality values:
     - `db_error_code`
     - `operation`
     - `retryable`
   - Avoid exception class as a high-cardinality metric tag. Keep it in logs.

## Performance Considerations

The runtime cost of exception translation is negligible on success paths if AOP only wraps method calls and does not inspect causes unless an exception is thrown.

Watch for:

- overly broad pointcuts wrapping non-DB business methods;
- repeated logging at repository and transaction layers;
- expensive stack/root-cause analysis on success path;
- high-cardinality operation names generated from full method signatures with parameter values.

Use stable operation names such as `LedgerTxnRepository.insertIgnore` or `DbTxnExecutor.executeWithDefault`.

## Recommended Final Design

Use this refined mapping:

| Source failure | `DbErrorCode` | Retryable |
|---|---|---:|
| Cannot get connection, pool exhausted, typed connection/network root cause | `DB_UNAVAILABLE` / `db_unavailable` | true |
| Query timeout, SQL timeout, socket timeout | `DB_TIMEOUT` / `db_timeout` | true |
| Deadlock, lock wait timeout, transient contention | `DB_TRANSIENT` / `db_transient` | true |
| Unexpected integrity violation, bad SQL, mapper bug, unknown DB error | `DB_INTERNAL` / `db_internal` | false |
| Commit/rollback ambiguity | `DB_INTERNAL` / `db_internal` | false |

Implementation should be:

- common `DbErrorCode` in `common-util`;
- common `DbException extends CustomizedException`;
- common classifier/translator utility;
- repository-level translation;
- transaction-executor translation;
- idempotent pass-through for already translated `DbException`;
- explicit preservation of `insertIgnore` duplicate-key behavior.

## Conclusion

The design is complete enough for implementation after the naming and boundary refinements above.

The most important correction is to use `db_timeout`, not `db_connection_timeout`, and to keep deadlock/lock wait timeout under `db_transient`. The most important implementation constraint is avoiding duplicate conversion/logging when both repository and `DbTxnExecutor` boundaries are active.
