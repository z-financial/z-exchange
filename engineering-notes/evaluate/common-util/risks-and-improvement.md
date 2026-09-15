# Common Util Risks and Improvements

## Scope

This note is based on active code in:

- `common-util/src/main/java`
- `common-util/src/main/resources/script/lua`
- `grpc-proto/common-proto/src/main/proto`

It excludes tests, log config, and generated proto Java code.

## Risks Table

| ID | Risk | Evidence in code | Impact | Level | Suggested improvement |
|---|---|---|---|---|---|
| CU-1 | Shared `TransactionTemplate` is mutated per call, which can leak propagation settings across concurrent threads. | `DbTransactionHelper.executeWithResult(...)` and `executeWithIfSuccess(...)` both call `template.setPropagationBehavior(propagation)` before `template.execute(...)`. In this codebase the template is injected as a Spring bean into `PostLedgerProcessor` and `OutboxRetryHandler`, so it is very likely singleton-scoped. | A call expecting one propagation mode can race with another call setting a different mode, producing inconsistent transaction boundaries under load. | High | Stop mutating a shared `TransactionTemplate`. Create a fresh `TransactionTemplate` per call from `PlatformTransactionManager`, or expose dedicated immutable templates per propagation mode. |
| CU-2 | The idempotency value format is brittle and not null-safe. | `CommonIdempHelper.parseIdempValue(...)` calls `value.split(":")` with no null guard. The accepted-state format also requires exactly 4 parts, while `idempValue(status, hash, token, value)` stores raw `value` after `:`. | A transient race where Redis `GET` returns null can throw `NullPointerException`. Future services storing accepted content that contains `:` will break parsing and idempotency recovery. | High | Make parsing null-safe, and replace the ad hoc colon-delimited format with structured JSON or at least `split(":", 4)` plus escaping/base64 for the content field. |
| CU-3 | Outbox rows can become silently stranded after max retries. | `OutboxStatus.DEAD` exists, but `OutboxRetryHandler.retry()` only finalizes successful rows to `SENT`. `OutboxRepository.selectForClaimSkipLock(...)` filters `attempt_count < maxRetries`, so exhausted rows simply stop being selected. | Failed messages stop retrying without moving to an explicit terminal state or generating a clear operator signal. | High | Add an explicit terminal transition to `DEAD` when retries are exhausted, and emit metrics/alerts keyed by `destination`, `commandId`, and `lastError`. |
| CU-4 | Shared Kafka retry behavior is hardcoded and service-agnostic. | `CommandContainerRegister` hardcodes concurrency `3`. `KafkaErrorHandlingRegister` hardcodes three 1-second delays and one DLQ policy for all listeners using this factory. | Fast services can be artificially throttled, while slower services may need wider backoff windows. A shared library default becomes a cross-service bottleneck. | Medium | Move concurrency and retry policy to configuration properties, and allow per-listener or per-topic overrides instead of one fixed profile. |
| CU-5 | Outbox retry has no execution budget and can overlap its own schedule under backlog. | `OutboxRetryHandler.retry()` loops until a short page is seen; there is a TODO saying "maybe add a execute time limit". `OutboxDispatcher` schedules it by cron. | On backlog, one dispatch cycle can run long enough to overlap the next schedule, increasing DB pressure and producing uneven retry latency. | Medium | Add a max runtime or max claimed-row budget per dispatch cycle, then continue from the next schedule tick. Persist progress only if needed. |
| CU-6 | The generic string splitting helper is only correct for single-character delimiters. | `StringHelper.strictDivideIntoTwoParts(...)` uses `value.substring(i + 1)` instead of `i + divider.length()`. Current active callers use `:` and `|`, both one character. | Today the impact is limited, but it is a latent correctness bug in a shared utility and can silently mis-parse future multi-character formats. | Low | Fix the helper to advance by `divider.length()`, and keep existing behavior covered by a small unit test. |

## Suggested Improvements

### Architecture

- Replace the current string-form idempotency payload with a versioned structured payload.
  - Evidence: `CommonIdempHelper` manually formats `P:{hash}:{token}` and `A:{hash}:{token}:{content}`.
  - Why: it is already causing null-safety and delimiter-collision risk. A small JSON document or compact protobuf payload would remove format ambiguity and make future fields cheap to add.

- Make transaction-boundary control immutable.
  - Evidence: `DbTransactionHelper` mutates `TransactionTemplate` state at runtime.
  - Why: transaction semantics should not depend on shared mutable bean state in a concurrent server.

- Turn the outbox into an explicit state machine.
  - Evidence: `OutboxStatus.DEAD` exists, but active code only uses `PENDING` and `SENT`.
  - Why: retry exhaustion should be visible in data, not hidden behind a query filter.

### Performance

- Externalize Kafka listener concurrency and retry delays.
  - Evidence: concurrency `3` and retry delays `1s,1s,1s` are hardcoded in shared config.
  - Why: these values are workload-sensitive and should scale with the service using the library.

- Add a bounded retry work budget in `OutboxRetryHandler`.
  - Evidence: the handler loops until the page is short and currently has no time cap.
  - Why: it prevents long retry sweeps from monopolizing DB and publisher capacity.

### Reliability

- Add null guards and parse-limit handling in `CommonIdempHelper.parseIdempValue(...)` and `parseIdempKey(...)`.
  - Evidence: both assume non-null well-formed strings.
  - Why: these methods sit on a race-prone Redis boundary and should fail closed into a typed `Result`, not a runtime exception.

- Emit metrics around outbox retry outcomes and exhausted rows.
  - Evidence: current behavior logs success/failure counts, but exhausted rows are not surfaced as a durable terminal state.
  - Why: retries in fintech flows need observable operator signals, not only application logs.

## Assumptions

- `TransactionTemplate` is assumed to be singleton-scoped because it is injected as a normal Spring dependency and not constructed per call in the analyzed code.
- The outbox schema is not in scope, so uniqueness/index assumptions are inferred from repository usage rather than verified from DDL.
- `StringHelper.strictDivideIntoTwoParts(...)` is currently safe for active callers only because the analyzed code uses one-character delimiters.
