# Result and Error-Handling Evaluation

## Conclusion

The current `Result` strategy is useful for explicit business-flow control, but its type model is inconsistent and too tightly coupled to the proto boundary. `common-util` leaks transport concerns through `PbMappableErrorCode`, while `ledger-service` and `wallet-service` both re-wrap the shared `Result` with service-local `Results` helpers that assume the embedded error code is their own enum. That makes `Result` look shared, but in practice it is only safely composable inside one service at a time.

The strongest part of the current design is that expected business failures are modeled explicitly instead of being hidden behind exceptions. That fits this repo well because many paths are intentionally non-fatal:

- validation failures
- idempotency conflicts
- cache degradation
- best-effort publish/retry flows

The main weaknesses are structural:

- `common-util` is transport-coupled because `PbMappableErrorCode` stores proto mapping directly.
- `ledger-service` and `wallet-service` duplicate `Results` and `PbErrorBuilder` logic.
- Service `Results.fail(Result<?>)` and `Results.getErrorCode(Result<?>)` rely on concrete enum downcasts and can throw on foreign `Result` values.
- Boundary adapters already collapse upstream failures too aggressively. For example, wallet converts ledger/account replies into local wrapper errors and keeps only the upstream message, dropping upstream proto code and detail.
- `errorDetail` is semantically wrong as a field name because the repo already uses it for success details such as idempotent replay and "already exists" outcomes.
- Numeric service error codes are weakly governed. There is already a duplicate numeric code in wallet error definitions.

## Refactor Verdict

The proposed refactor is directionally correct and worth doing.

It solves real repo-specific problems:

- removes proto coupling from `common-util`
- eliminates most duplicate `Results` implementations
- stops utility code from checking concrete service error-code classes
- makes service boundary mapping explicit instead of hiding it inside shared enums
- creates room for future exception-at-boundary handling without forcing that change now

However, the proposal needs tightening before it becomes a better long-term contract.

## Required Adjustments

### 1. `Result<T>` must keep the value

The proposed `Result` interface is missing `getValue()`. This repo depends on typed result payloads throughout stores, processors, and cache helpers. A `Result<T>` abstraction without value access is not viable here.

Recommended shape:

- `isSuccess()`
- `getValue()`
- `getErrorCode()`
- `getDetail()`
- `getScope()`
- optional `isFailed()` default helper

### 2. `ErrorCode` needs a neutral classification, not only `code + message`

If `ErrorCode` only has service-local code and message, each service will need hand-maintained mapping tables from business code to `ErrorCodePb`. That removes one coupling point but increases mapper drift risk.

A better contract is:

- `getErrorCode()` or `getCode()` for stable service-local machine code
- `getMessage()` for stable symbolic message
- `getCategory()` for transport-neutral classification such as:
  - `INTERNAL`
  - `UNAVAILABLE`
  - `INVALID_ARGUMENT`
  - `NOT_FOUND`
  - `ALREADY_EXISTS`
  - `FAILED_PRECONDITION`
  - `CONFLICT`
  - `PROCESSING`

Then each boundary maps `ErrorCategory -> ErrorCodePb`, which is simpler and safer than per-code switch logic.

### 3. `scope` is useful, but not sufficient

Adding `scope` helps diagnostics and future result-chain work, but it should not become the main mechanism for error translation. A single scope string cannot reliably represent both origin and wrapping history once results cross module or service boundaries.

If you want future chain preservation, treat `scope` as metadata, not as the core mapping key.

### 4. Boundary mapping should be centralized and audited

Your boundary-only proto mapping rule is good, but only if each service has one obvious mapper and tests for it.

Required safeguards:

- one mapper per service boundary
- test that every service `ErrorCode` maps
- log and metric on fallback to `ERROR_INTERNAL`
- preserve upstream error context in `detail` when wrapping remote failures

## Recommended Direction

I would approve the refactor with these constraints:

1. Replace `PbMappableErrorCode` with a transport-neutral `ErrorCode` interface in `common-util`.
2. Keep one default `Result<T>` implementation and one shared `Results` utility in `common-util`.
3. Rename `errorDetail` to `detail`.
4. Keep service-local error catalogs, preferably as enums implementing the common interface.
5. Add an `ErrorCategory` field to avoid fragile per-service proto mapping tables.
6. Centralize service boundary mapping and add coverage tests.
7. Preserve upstream code and detail when wrapping cross-service failures.

## `common-util` Module Split

If `common-util` grows multiple real submodules such as cache, DB, idempotency, outbox, or Kafka support, they should usually use different enums implementing the shared `ErrorCode` interface rather than one large `CommonErrorCode`.

That is a better fit for this repo because the current shared enum already mixes unrelated concerns:

- DB failure
- idempotency parse/format errors
- cache parse errors
- Lua or Redis-side infrastructure failures

Using one shared catch-all enum makes ownership blurry and causes the shared catalog to grow without a clear module boundary.

Recommended rule:

- use one enum per meaningful subsystem contract, for example:
  - `CacheErrorCode`
  - `IdempErrorCode`
  - `MessagingErrorCode` or `OutboxErrorCode`
- do not create one enum per tiny helper or class
- only create `DbErrorCode` if callers truly need DB-specific branching
- otherwise keep generic DB/internal failures as exceptions or a small infra-level code set

This only works well if the shared `Result` and `Results` layer is fully enum-agnostic. It should not inspect concrete enum types at all.

To keep multiple enums safe, the shared `ErrorCode` contract should also include either:

- a transport-neutral `ErrorCategory`, plus an optional namespace/module marker
- or a globally unique code convention across all common-util enums

I strongly prefer `ErrorCategory` plus subsystem-local codes. That keeps module ownership clear while still making boundary mapping predictable.

## Bottom Line

This is a good refactor, but not because it makes the model more abstract. It is good because it removes the wrong abstraction boundary: proto transport concerns embedded inside shared business/infrastructure error objects.

If implemented carefully, it will reduce layering violations, remove unsafe downcasts, improve cross-module composability, and make later exception-at-boundary handling easier.

If implemented too narrowly as only `code + message + scope`, it will still leave you with mapper drift, weak remote-error preservation, and limited future evolvability.
