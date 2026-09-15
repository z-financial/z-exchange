# System Design Evaluation

## Scope and calibration

- Evaluated modules:
  - `common-util`
  - `ledger-service`
  - `wallet-service`
- Basis:
  - active code already analyzed in `src/main`
  - proto contracts already analyzed in `grpc-proto/*/src/main/proto`
  - prior architecture, logic, and risk notes under `./codex`
- Current-state assumption:
  - wallet issues previously identified as `WS-1` and `WS-2` are treated as fixed in the current codebase
- Uncertainty:
  - MySQL DDL, indexes, production deployment shape, metrics, alerting, and reconciliation tooling are not in scope here. Those could move some reliability and correctness scores by about one point.
- Scoring bar:
  - `7/10` means clearly above average
  - `9/10` means unusually strong design quality

## `common-util`

`common-util` is a useful shared platform base. The strongest part is that it recognizes the right distributed-systems primitives for this project: idempotency, outbox, Redis cache patterns, and Kafka retry/DLQ wiring. The weakness is that the module is not yet hardened enough to be a truly safe platform layer; several critical behaviors are still brittle or policy-heavy for shared infrastructure.

| Dimension | Score | Short reasoning | What is strong in the design | What is weak in the design | One concrete design-level improvement |
| --- | --- | --- | --- | --- | --- |
| Problem decomposition | 6/10 | The package split is clear: `db`, `redis.cache`, `redis.idemp`, `kafka`, and `outbox` each map to a real infrastructure concern. | `DbTransactionHelper`, `IdempRedisClient`, and `OutboxRetryHandler` are reusable primitives instead of service-specific code. | Runtime policy leaks into the shared layer through `CommandContainerRegister` and `KafkaErrorHandlingRegister`, so the module is not just infrastructure primitives. | Split shared primitives from default runtime policy. Keep the core library policy-free, and move concurrency/backoff/listener defaults behind per-service configuration. |
| Domain modeling | 5/10 | The infra objects are modeled well enough to work, but several key protocols are stringly typed. | `Outbox`, `CacheValueInfo`, and `Result<T>` express real operational concepts cleanly. | Idempotency state (`P:...`, `A:...`) and cache markers (`S`, `J`, `T`, `N`) are compact but brittle contracts. | Replace colon-delimited idempotency/cache encodings with typed serialized value objects so parsing and evolution are safer. |
| Architecture quality | 6/10 | The module provides the right building blocks for this codebase: Redis-backed idempotency, outbox retry, cache-aside, and DB transaction helpers. | The stable-cache vs versioned-cache split is a real architectural decision, not accidental layering. | The shared layer also owns behavior that should vary by service, such as Kafka retry profile and listener concurrency. | Treat `common-util` as a platform kernel: expose extension points and configuration hooks, but keep service-specific policy out of the shared abstractions. |
| Correctness by design | 4/10 | Some correctness protections are strong, but others rely on conventions that are too fragile for shared infra. | The Lua compare-and-delete idempotency release and version-CAS cache writes are good design choices. | `DbTransactionHelper` mutates a shared `TransactionTemplate` per call, which is the opposite of a safe-by-default concurrency design. | Make transaction helpers stateless and immutable per call, created from `PlatformTransactionManager` rather than mutating a singleton template. |
| Consistency and concurrency design | 6/10 | The module shows clear awareness of concurrent claim/update problems. | `release-idemp-if-owned.lua`, `set-if-absent-or-newer.lua`, and `select ... for update skip locked` are principled concurrency tools. | Concurrency safety is uneven because the transaction helper is mutable while the Redis and outbox pieces are carefully atomic. | Standardize on immutable concurrency primitives across the module, especially for transaction boundary management. |
| Reliability and recovery design | 6/10 | The module includes the right recovery shapes: pending outbox, retry loop, DLQ, and accepted idempotency states. | `OutboxRetryHandler` plus durable `Outbox` rows is the right baseline reliability pattern. | Retry exhaustion is under-modeled: `OutboxStatus.DEAD` exists, but exhausted rows just stop being selected. | Add an explicit terminal `DEAD` transition with metrics and alerting so failed messages are visible and operable. |
| Scalability and performance thinking | 5/10 | Caching and async retry exist, but operational tuning is too rigid for a shared platform. | Redis and outbox reduce direct load on MySQL and Kafka in the normal path. | Hardcoded listener concurrency `3`, fixed retry profile, and unbounded retry-loop runtime can become cross-service bottlenecks. | Make concurrency, retry, and retry-loop budget configurable per service or per topic. |
| Simplicity vs over-engineering | 6/10 | Most abstractions correspond to real needs in this project, so the module is not gratuitously complex. | Outbox, idempotency, and cache helpers are legitimate shared concerns. | There are still too many thin wrappers and commented-out cache artifacts for such a foundational module. | Trim dead abstractions and make the surviving ones first-class documented patterns with contract tests. |
| Overall engineering judgment | 6/10 | The module shows good instincts about what a fintech backend needs, but not yet the discipline of a hardened internal platform. | The author clearly understands that retries, idempotency, and cache coherence must be shared concerns. | A few brittle choices sit directly in the critical path of every service that depends on the library. | Promote `common-util` from “helpful utility layer” to “platform module” by hardening its contracts, defaults, and concurrency model before adding more features. |

## `ledger-service`

`ledger-service` is the strongest designed module in the project. The core posting path is intentionally centralized, the journal model is coherent, and the cache split is thoughtful. The main reason the scores do not go higher is that several key invariants are still enforced by convention rather than by a stronger contract boundary.

| Dimension | Score | Short reasoning | What is strong in the design | What is weak in the design | One concrete design-level improvement |
| --- | --- | --- | --- | --- | --- |
| Problem decomposition | 7/10 | The service is shaped clearly around three main jobs: account creation, posting, and query, with async ingress/egress adapters around the same core processor. | `PostLedgerProcessor`, `CreateAccountProcessor`, `GetLedgerTxnProcessor`, and `LedgerTxnStore` have intentional boundaries. | `DefaultListener` still mixes business outcome handling with retry-policy decisions. | Separate command execution from async outcome policy so listener code does not decide business failure semantics. |
| Domain modeling | 7/10 | `LedgerTxn`, `LedgerEntry`, `Account`, and `Asset` form a coherent journal/account model. | Balanced debit/credit by asset is enforced in `PostLedgerProcessor.reqPrecheck(...)`, which is exactly where the core ledger invariant belongs. | The model loses the distinction between external `accountRef` and internal `accountId` on the read path. | Preserve both external and internal account identity in the model, or make the API explicitly internal-facing. |
| Architecture quality | 7/10 | Reusing the same posting logic for gRPC and Kafka is a strong architectural choice. | The outbox-first reply publication boundary is correct: durable reply state is written before Kafka ack. | Async business-failure semantics are incomplete; non-OK outcomes become retry behavior instead of explicit replies. | Make ledger async commands return an explicit terminal result event for both success and business failure. |
| Correctness by design | 6/10 | Several important invariants are enforced up front, but some invalid states are still representable. | Balanced posting, atomic insert of transaction + entries, and Redis-to-MySQL idempotency backstop are solid. | Posting resolves accounts by `referenceId` only and does not validate full account dimensions against each entry. | Resolve full account records at post time and validate `assetId`, service scope, and owner dimensions before any insert. |
| Consistency and concurrency design | 7/10 | The Redis fast gate plus MySQL backstop is one of the better-designed parts of the system. | The service handles duplicate requests, malformed Redis values, and post-commit idempotency gaps in a principled way. | Read-after-write by `referenceId` can still be temporarily inconsistent because only the negative cache is cleaned after commit. | Write the positive `refId -> txnId` cache immediately after successful commit instead of relying on later lazy reads. |
| Reliability and recovery design | 6/10 | The basic durability boundary is sound, but terminal behavior is not fully thought through for async errors. | Durable outbox rows and scheduled retry give the reply path a real recovery story. | A Kafka caller may never receive a terminal business reply because non-OK posting results are converted into retries or DLQ. | Treat business failure as a normal terminal outcome, and reserve retry/DLQ only for transport and infrastructure failures. |
| Scalability and performance thinking | 5/10 | The service uses caches and async reply delivery, but some key paths are still synchronously blocking. | Query-by-id and query-by-reference use different cache strategies based on access shape, which is good. | `DefaultPublisher.publish(...)` blocks on `send().get(...)`, listener concurrency is low, and entry read batch size is tiny. | Move reply publishing off the listener thread and make batch sizes/concurrency fit expected ledger traffic. |
| Simplicity vs over-engineering | 7/10 | The design is relatively lean for a ledger service. Most complexity is domain-driven rather than accidental. | The cache split (`LedgerTxnCache` vs `LedgerRefCache`) is elegant and purposeful. | Metadata version/tombstone logic exists but does not appear to be part of an active user-facing flow yet. | Either wire the metadata update path into a real product flow or remove it until it is needed. |
| Overall engineering judgment | 7/10 | This module shows solid architectural thinking and better discipline than the rest of the project. | The author understands centralization of critical logic, durability boundaries, and fast-path vs source-of-truth trade-offs. | The remaining weaknesses are mostly at contract boundaries, which are high impact in a ledger system. | Before adding more features, harden the contract boundaries: IDs, account identity, async result semantics, and query API meaning. |

## `wallet-service`

`wallet-service` has the richest domain model in the project and also the highest architectural risk. The service clearly understands wallet-specific concerns such as reservation lifecycle, local operational balances, and asynchronous settlement. The problem is that the design is ambitious but not yet equally strong at closing failure loops and protecting invariants across the wallet-ledger boundary.

| Dimension | Score | Short reasoning | What is strong in the design | What is weak in the design | One concrete design-level improvement |
| --- | --- | --- | --- | --- | --- |
| Problem decomposition | 7/10 | The service is intentionally structured around a transaction pipeline rather than large RPC handlers. | `IdempPrecheckProcessor`, `BeforePostLedgerProcessor`, `AfterPostLedgerProcessor`, and the three entry processors show clear workflow decomposition. | `BeforePostLedgerProcessor` still carries too many responsibilities: planning, validation, DB mutation, outbox creation, and immediate publish. | Split transaction planning from persistence/orchestration so the state machine is clearer without changing the current flow shape. |
| Domain modeling | 8/10 | This is the best-modeled domain in the project. `WalletTransaction`, `WalletAction`, `WalletReservation`, and `BalanceSnapshot` map cleanly to reserve/apply/settle behavior. | `ObjectBuilder.createActionsFromLine(...)` makes the local action decomposition explicit instead of hiding it in ad hoc logic. | Important invariants are spread across several tables and processors instead of being governed by one explicit lifecycle model. | Introduce a first-class reservation/transaction state-transition model that owns the legal state moves across reserve, release, consume, and settle. |
| Architecture quality | 6/10 | The architecture makes sense for a wallet coordinator that owns operational state and settles through ledger asynchronously. | Separating local reserve flows from ledger-backed settlement flows is the right high-level shape. | The service mutates local state before external ledger confirmation, so correctness depends heavily on reply semantics and later repair. | Add an explicit saga or reconciliation model for wallet transactions so local mutation and external settlement are joined by a formal lifecycle, not just callback timing. |
| Correctness by design | 5/10 | The service has meaningful safeguards, but incorrect end states are still too easy to reach under cross-service failure. | Snapshot SQL predicates, reservation row locks, optimistic lock checks, and idempotency backstop are all good. | The design still relies on cross-service behavior, especially around when ledger emits replies and what wallet should do when it does not receive one. | Make wallet transaction terminal states explicit for `SUCCESS`, `BUSINESS_FAIL`, `DELIVERY_FAIL`, and `TIMEOUT`, then drive processor behavior from that state machine. |
| Consistency and concurrency design | 6/10 | There is real thought around concurrent mutation ordering and duplicate handling. | Sorted snapshot updates reduce deadlock risk, and reservation updates use both `FOR UPDATE` and optimistic predicates. | The service can still leave funds operationally locked in `PENDING` states after local commit if ledger-side progress stalls or fails. | Add a reconciliation worker that scans long-running pending transactions against outbox state and drives them to a terminal outcome. |
| Reliability and recovery design | 5/10 | The outbox gives delivery durability, but the recovery story is incomplete once local state is already mutated. | The system does not pretend Kafka publication is the true success boundary; it durably persists wallet state plus outbox first. | There is no equally strong compensation/closure strategy when the ledger side fails, never replies, or replies with a terminal business error. | Add timeout-based recovery plus compensating transitions for reservations and snapshots when settlement cannot complete. |
| Scalability and performance thinking | 5/10 | The design is functional for current scope, but it will feel heavy under sustained write volume. | The service avoids premature read-cache complexity and keeps the main concern on transactional correctness. | Cross-table writes are frequent, Kafka publish blocks synchronously, and outbox retry throughput is configured conservatively. | Decouple publishing from request threads and raise outbox throughput budgets before expanding transaction volume. |
| Simplicity vs over-engineering | 5/10 | The complexity is partly justified by the domain, but the current shape still carries more moving parts than it controls cleanly. | The action/reservation/snapshot split is not arbitrary; it reflects real fintech semantics. | The service has both a rich local operational model and an async ledger settlement model, but not yet a strong unifying transaction lifecycle abstraction. | Collapse the orchestration around an explicit transaction state machine instead of letting multiple processors implicitly coordinate it. |
| Overall engineering judgment | 6/10 | The module shows strong domain intuition, but only moderate closure on hard distributed-systems trade-offs. | The designer clearly understands that holds, releases, and settlement should not be modeled as one flat balance update. | The project reaches for senior-level domain complexity before fully solving senior-level failure closure. | Stabilize the transaction lifecycle design before adding more wallet product surface area. |

## A. Overall score for the whole project

`6.5/10`

This is a reasonably designed backend system with real architectural thinking behind it. The project is stronger on decomposition, domain modeling, and distributed-systems awareness than on invariant protection and failure closure.

## B. Overall verdict

`decent (interview-ready)`

The project is clearly above beginner quality. It shows intentional system design, not just endpoint-by-endpoint coding. It is not yet a strong production architecture because too many critical outcomes still depend on convention, implicit cross-service behavior, or unfinished platform hardening.

## C. Engineer level suggested by this project

`strong mid`

The work shows someone who is already thinking in terms of outbox boundaries, idempotency, reservation state, and asynchronous coordination. What is missing for `senior potential` is stronger invariant-driven design at service boundaries and a more complete failure-recovery model.

## 1. Strongest design decisions

- Reusing `PostLedgerProcessor` for both gRPC and Kafka ingress in `ledger-service` is one of the best choices in the codebase. It keeps the hardest business logic centralized.
- Modeling wallet state with `WalletTransaction`, `WalletAction`, `WalletReservation`, and `BalanceSnapshot` is a strong domain decision. It matches real reserve/apply/settle behavior instead of flattening everything into one mutable balance row.
- Using durable outbox rows before Kafka ack/publication is the right reliability boundary in both business services.
- Treating Redis as a fast gate and MySQL as the final source of truth is the correct stance for idempotency and cache-backed reads.
- The split between `LedgerTxnCache` and `LedgerRefCache` shows good performance and consistency judgment rather than generic caching.

## 2. Weakest design decisions

- `common-util` mixes shared primitives with hardcoded runtime policy, and one of those primitives (`DbTransactionHelper` mutating `TransactionTemplate`) is not safe enough for a platform layer.
- `wallet-service` commits local balance and reservation effects before it has a fully explicit end-to-end settlement/failure model.
- `ledger-service` does not clearly separate business failure from transport failure on the async path.
- Internal and external identity boundaries are blurred in a few critical places, especially `accountRef` vs `accountId`.
- Local time-plus-sequence ID generation is not a serious long-term choice for horizontally scaled financial services.

## 3. Signs of mature engineering judgment

- The project consistently recognizes idempotency as a first-class design concern rather than bolting it on at the controller layer.
- Both services use durable outbox persistence, not best-effort event publishing, which is the right instinct.
- The wallet reservation flow uses row ordering, `FOR UPDATE`, SQL balance predicates, and optimistic checks together. That is thoughtful concurrency design.
- The ledger module distinguishes cache shapes by access pattern and consistency need instead of using one generic cache abstraction everywhere.
- Business services are mostly thin adapters around processors, which shows intentional layering.

## 4. Signs of over-design or unnecessary complexity

- `wallet-service` has reached a fairly complex local state model before it has an equally mature reconciliation model.
- `common-util` contains several thin abstractions and some dead/commented paths that make the shared platform feel less disciplined than it should.
- The ledger metadata version/tombstone path looks like a future-facing extension point more than a justified current requirement.
- Some reliability behavior is spread across multiple layers and conventions instead of being owned by one explicit lifecycle model.

## 5. The 3 highest-leverage design improvements

1. Make async transaction outcomes explicit end to end.
   - Ledger should emit terminal result events for both success and business failure.
   - Wallet should own a first-class transaction lifecycle with success, failure, timeout, and reconciliation paths.

2. Harden the shared platform layer before building more product logic on top of it.
   - Replace local time-plus-sequence IDs.
   - Make transaction helpers immutable.
   - Replace brittle string-encoded idempotency/cache state.
   - Add explicit `DEAD` handling and metrics for exhausted outbox rows.

3. Tighten identity and invariant boundaries.
   - In ledger, validate full account dimensions before posting and stop conflating `accountRef` with `accountId`.
   - In wallet, centralize reservation and transaction state transitions behind an explicit state machine rather than distributed processor assumptions.
