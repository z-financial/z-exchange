# Wallet Transaction Lifecycle and Asynchronous Settlement

**Design:** D-013  
**Status:** Verified against the current implementation (2026-09-09)

**Module context:** [Wallet Service Overview](wallet-service-overview.md)

## Design in one minute

Wallet is the business state machine. Each public transaction request is validated, deduplicated, resolved to Wallet rows, and expanded into a small action vocabulary. Wallet commits its local financial state, transaction record, actions, and—when accounting is required—a Ledger command outbox in one database transaction.

Reserve and pure-release requests complete locally. Atomic requests and Ledger-bearing reservation applications return a durable Wallet transaction in `PENDING`; a later successful Ledger reply settles the remaining reservation and balance effects and changes that transaction to `COMPLETED`.

![Wallet transaction and asynchronous settlement lifecycle](../assets/wallet-transaction-settlement-flow.svg)

## Responsibility boundary

Wallet owns whether funds may move, the available/reserved balance state, reservations, and the Wallet transaction lifecycle. Ledger receives only accounting transfer entries and records a balanced journal; its posting behavior is described in [Ledger posting and accounting correctness](../ledger/posting-and-accounting-correctness.md). Shared delivery and outbox mechanics are owned by [Kafka transport and outbox reliability](../infrastructure/kafka-outbox-reliability.md).

The active Wallet-to-Ledger path is asynchronous Kafka/outbox. `PostServiceClient` remains in source but has no active transaction call site.

## Public operations and completion semantics

| RPC | Accepted line intent | Ledger command | Initial terminal meaning |
|---|---|---|---|
| `executeAtomicTransaction` | shared transaction-line model; debit/credit form the intended transfer composition | Always | Success means Wallet accepted and committed a `PENDING` transaction. |
| `reserveTransaction` | reserve lines only | Never | Success means the hold and transaction committed as `COMPLETED`. |
| `applyReserveTransaction` | currently accepts the shared line model | Unless every line is release | Pure release completes locally; other accepted combinations remain `PENDING`. |

The atomic and apply APIs both reuse the broad line model. The apply name suggests a narrower earmark/release contract, and atomic transfer intent suggests debit/credit, but current operation-specific validation narrows only reserve requests. This document describes executable behavior rather than silently imposing those implied restrictions.

## Normalized action model

`ObjectBuilder.createActionsFromLine` converts the wire operation into persisted `WalletAction` rows:

| Line operation | Persisted actions | Phase of balance effect |
|---|---|---|
| reserve | `RESERVE` | available → reserved before return |
| release | `RELEASE` | reserved → available before return |
| debit | `RESERVE`, `CONSUME`, `TRANSFER_OUT` | hold before Ledger; reserved leaves after success |
| credit | `TRANSFER_IN` | available increases after Ledger success |
| earmark | `CONSUME`, `TRANSFER_OUT` | reservation becomes pending before Ledger; reserved leaves after success |

Only `TRANSFER_OUT` and `TRANSFER_IN` become Ledger entries. `CONSUME` exists to connect settlement to a reservation; `RESERVE` and `RELEASE` are Wallet-only state changes.

## Phase 1: accept and persist

Each operation builds a `RequestInfo`, runs `IdempPrecheckProcessor`, and calls `BeforePostLedgerProcessor` for a new request. The processor:

1. creates the Wallet transaction and resolves referenced balance snapshots;
2. creates or loads reservations and expands lines into actions;
3. validates action, wallet, asset, amount, and transfer balance relationships;
4. constructs the Ledger command when required; and
5. uses `DbTxnExecutor` to persist all affected reservations, snapshots, transaction, actions, and the claimed outbox atomically.

When no Ledger command is needed, the transaction is changed from its constructed `PENDING` state to `COMPLETED` before the database transaction. When a command is needed, the row remains `PENDING`.

After commit, cache maintenance, Redis idempotency completion, immediate Kafka publishing, and outbox finalization run outside the business transaction. Their normal translated/`Result`-based failures are logged without undoing the commit. A mapped publish failure does not turn an already committed Wallet acceptance into an RPC failure; the scheduled dispatcher retains the durable retry path. Unexpected runtime failures in this post-commit sequence can still alter the caller-visible response even though the database commit remains authoritative.

## Command identity and accounting handoff

For the generated posting request, Wallet uses its `txnId` as:

- Ledger `referenceId`;
- outbox `commandId`;
- Kafka partition key.

The command `eventId` is a separate transport identity generated as `{txnId}-{process-local sequence}`. The outbox persists that value so publishing retries of the same row retain it.

The client's original `referenceId` remains on the Wallet transaction and is not Ledger's posting reference. Wallet account mappings translate Wallet IDs into Ledger account IDs, and only transfer actions are emitted as debit/credit entries.

## Phase 2: finalize after Ledger

`WalletLedgerReplyMessageHandler` parses `POST_LEDGER_REPLY`. A successful reply must contain a nonblank Wallet reference and Ledger transaction ID. It calls `AfterPostLedgerProcessor.afterPostLedger(referenceId)`.

The finalizer loads the Wallet transaction, relevant actions, snapshots, and consume reservations. If the transaction is already finalized, sequential replay succeeds without another effect. Otherwise a second Wallet database transaction:

1. moves each consume amount from reservation `pendingSettle` to `consumed`;
2. subtracts `TRANSFER_OUT` from reserved balances and adds `TRANSFER_IN` to available balances;
3. performs guarded row updates; and
4. changes the Wallet transaction from `PENDING` to `COMPLETED`.

Any failed `Result` rolls back that whole finalization transaction. Cache maintenance runs only after commit. Detailed bucket and reservation enforcement belong to [balance and concurrency correctness](balance-and-concurrency-correctness.md) and [reservation lifecycle](reservation-lifecycle.md).

## Current guarantees

- Phase-1 Wallet state and the optional command outbox commit atomically.
- Phase-2 reservation, balance, and transaction-status changes commit atomically.
- A Ledger-backed RPC reports durable Wallet acceptance, not completed cross-service settlement.
- Delivery may repeat, so correctness depends on Wallet and Ledger business idempotency as well as outbox durability; exhausted rows have no automatic re-drive.
- Sequential successful reply replay is a no-op after the Wallet transaction is final.

These guarantees are bounded to the active application paths and database transactions; the schema does not independently encode all domain relationships.

Reservation-backed actions have an additional current shape constraint: the mutation callbacks select reservations by action reference, but then apply `CONSUME`/`RELEASE` actions by `walletId` rather than filtering each action by its `reservationId`. A request that applies multiple reservations belonging to the same Wallet can therefore mutate the wrong reservation or fail and roll back. The usual one-reservation-per-Wallet flow is supported; the broader multi-reservation shape is not currently guaranteed.

## Failure and recovery behavior

| Scenario | Current behavior |
|---|---|
| immediate command publish fails | RPC can still succeed; the committed outbox is retried later. |
| command or reply is duplicated | Ledger posting and Wallet finalization use business state/idempotency barriers; concurrent Wallet reply convergence has the limitation below. |
| Ledger returns explicit failure | Wallet does not finalize. The listener treats it as terminal and preserves it through DLT handling; held/pending state remains unresolved. |
| no usable Ledger reply arrives | Wallet remains `PENDING`; no current scheduled business re-drive closes it. |
| Wallet finalization returns a retryable error | retry-topic processing retries it. |
| Wallet finalization returns a non-retryable error | the record reaches the configured DLT policy. |
| concurrent success replies race | row locks and guarded updates prevent two committed effects, but the losing consumer may report failure instead of converging to success. |

DLT and retry-exhaustion signals are operational evidence, not the business recovery mechanism. The owner-selected future direction is a Wallet job that finds stuck transactions and reposts the same logical Ledger command; Ledger's business idempotency makes that re-drive safe when identity and payload are preserved.

## Trade-offs

The split lifecycle avoids holding a synchronous Wallet request open across Kafka and Ledger, and makes the command durable with local state. It also creates an explicit `PENDING` business state that needs recovery and operational visibility. Keeping local reserve/release operations synchronous avoids an unnecessary remote journal round trip, while Ledger-backed transfers preserve the service boundary.

## Observability and performance

Wallet business metrics distinguish gRPC operations from Kafka `complete_ledger_transaction`, so acceptance rate and settlement rate can be compared. Outbox, listener, database, Redis, and gRPC instrumentation are described in [shared observability](../operations/observability.md); Wallet-specific evidence belongs to [Wallet observability and performance](../operations/wallet-performance.md).

Configuration values for retry timing, schedules, concurrency, and pools are pre-launch snapshots rather than validated operating limits.

## Unresolved requirements and TODOs

- **Recovery design:** implement claiming, age/state selection, retry limits, and stop conditions for Wallet-owned stuck-transaction re-drive.
- **Compensation design:** define how an explicit valid Ledger rejection releases or reverses phase-1 state and which terminal status records the outcome.
- **Concurrent replay:** make a losing duplicate success reply converge to the already-final result instead of DLT.
- **Correlation validation:** validate envelope command identity against reply reference identity rather than trusting only payload fields.
- **Replay response:** return the transaction's latest stored status; current ID-only replay builders leave protobuf status `Unknown`.
- **API contract:** decide and enforce which line combinations atomic and `applyReserveTransaction` intentionally support.
- **Wire cleanup:** classify or remove inactive two-step transaction messages/enums before presenting them as supported API.
- **Reservation/action correlation:** filter every reservation mutation by `reservationId` and add same-Wallet, multi-reservation phase-1 and settlement tests.

## Verification record

Verified on 2026-09-09 against the current processors, transaction callbacks, repositories, schema, protobuf contracts, Kafka handler/configuration, outbox call sites, focused tests, and stress/reconciliation tooling. The default Wallet Maven suite passed 20 JUnit 5 tests; legacy JUnit 4 transaction tests were compiled but not discovered. No live MySQL/Redis/Kafka failure-injection or concurrent settlement run was performed.
