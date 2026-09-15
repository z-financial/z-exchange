# Wallet Service Risks and Improvements

## Scope

This note is based on:

- `wallet-service/src/main/java`
- `wallet-service/src/main/resources` except log config
- `grpc-proto/wallet-proto/src/main/proto`
- shared `common-util` classes that wallet-service directly depends on for outbox retry, Kafka retry, Redis idempotency, and transaction handling

It excludes test code, log config, backup property files, and generated proto Java code.

## Risks Table

| ID | Category | Evidence from code | Impact | Risk Level |
| --- | --- | --- | --- | --- |
| WS-1 | Data inconsistency | `AfterPostLedgerProcessor.getTxnInfo(...)` loads `CONSUME`, `TRANSFER_OUT`, and `TRANSFER_IN`, but `TransactionInfo.create(...)` only puts `action.isTransfer()` actions into `walletIdToPostActions`. `updateReservationAfterPosting(...)` only reacts to `ActionType.CONSUME`, so that branch never runs with the current map content. | Successful ledger replies can leave reservations stuck in `pendingSettle` instead of moving to `consumed`, while snapshots and transaction status are still finalized. Reservation totals, outcomes, and future release/consume behavior can drift from the intended state machine. | High |
| WS-2 | Data inconsistency / Architecture gap | `ApplyReservationTransactionProcessor` always calls `beforePostingLedger(requestInfo, true)` and has no operation-type validator. `ObjectBuilder.createOutbox(...)` only emits ledger entries for `TRANSFER_OUT` / `TRANSFER_IN`, so release-only apply requests still commit local reservation/snapshot changes and create an outbox payload that can contain zero ledger entries. | Release-only apply flows can mutate local state first and then depend on an external async confirmation path that may be meaningless for the request. If no successful reply comes back, the wallet transaction can remain `PENDING` after local balances were already changed. | High |
| WS-3 | Data inconsistency / Cross-service contract fragility | `wallet-service` reply consumer `DefaultListener.handlePostLedgerReply(...)` ignores `reply.getError().getCode()` and finalizes purely from `reply.getReferenceId()`. | If the ledger side ever publishes a non-OK reply event, or if a failed reply is replayed manually, wallet will still run post-ledger finalization as if the post succeeded. The current behavior is only safe as long as ledger never publishes error replies. | High |
| WS-4 | Reliability / Consistency | Wallet commits local state and the outbox row before ledger confirmation in `BeforePostLedgerProcessor.updateDbToCreateTxn(...)`. Shared `OutboxRetryHandler` only retries rows where `attempt_count < maxRetries`; it does not transition exhausted rows into `DEAD`. | Failed ledger delivery can leave local holds and `PENDING` wallet transactions without a terminal wallet-side failure state. Funds can remain operationally locked until manual repair because the service has no compensation or reconciliation path after retry exhaustion. | High |
| WS-5 | Race condition / Data integrity | `IdGenerator` uses `System.currentTimeMillis() - START_TIME` plus a JVM-local `AtomicLong` for wallet ids, transaction ids, action ids, reservation ids, and event ids. There is no node identifier or centralized sequence source. | In a multi-instance deployment, two JVMs can generate the same IDs in the same millisecond. That can cause duplicate business ids, unexpected `insertIgnore` conflicts, or cross-record correlation errors. | High |
| WS-6 | Race condition / Idempotency weakness | `CreateWalletProcessor.createWallet(...)` treats `OPEN` wallets as already created, but an existing `INIT` wallet falls through to `createAccount(...)` and `enableWallet(...)`. A second concurrent caller can therefore race the first one and fail on `updateWalletStatus(INIT -> OPEN)` even though the wallet was created successfully by the first request. | Duplicate `createWallet` calls can produce noisy ledger-account retries and return `WALLET_UPDATE_FAILED` to one caller even though the final business state is valid. This is an idempotency gap on a user-visible bootstrap API. | Medium |
| WS-7 | Validation gap / Request-shape inconsistency | `WalletServiceImpl` comments say "at most one transaction line for one wallet id", but there is no validator enforcing that rule. `ObjectBuilder.createReservation(...)` derives `reservation.referenceId` as `request.referenceId + ":" + walletId`, so multiple debit/reserve lines for the same wallet in one request produce the same reservation reference. | A single malformed request can create duplicate reservation business keys, cause batch insert failures, or overwrite in-memory reservation maps during action construction. The service currently depends on callers respecting an undocumented invariant. | Medium |
| WS-8 | Scalability bottleneck | `DefaultPublisher.publish(...)` blocks on `kafkaTemplate.send(...).get(3, TimeUnit.SECONDS)`. `application.yml` sets outbox `page-limit: 2` and a 5-second retry interval. Shared command listener concurrency is only `3`. | Request threads and retry workers are serialized behind Kafka round-trips. Under backlog or broker slowness, wallet command throughput and recovery speed degrade quickly, and pending outbox growth is slow to drain. | Medium |
| WS-9 | Data integrity assumption | `WalletReservationMapper.batchInsert(...)` inserts `reservation_id, initiator, reference_id, wallet_id, wallet_reference_id, asset_id, total, remaining, consumed, pending_settle, reservation_status, reservation_outcome, reserve_txn_id` but omits the `released` column, while later logic performs arithmetic on `reservation.getReleased()`. | The current behavior is safe only if the database schema defines `released NOT NULL DEFAULT 0`. If that default is missing or nullable, release flows can fail at runtime or behave inconsistently across environments. | Medium |
| WS-10 | Validation gap | `ValidateHelper.requestPrecheck(...)` validates enums and `amount > 0`, but does not reject blank `reference_id`, `wallet_ref`, `asset_code`, or `reservation_ref`. Those strings are later used as DB lookup keys, business identifiers, and idempotency inputs. | Empty or malformed identifiers can create ambiguous rows, false cache/idempotency sharing, or confusing not-found behavior that is hard to distinguish from normal business misses. | Medium |

## Suggested Improvements

### Architecture

- Split `applyReserveTransaction` into two explicit paths instead of routing everything through `beforePostingLedger(..., true)`.
  - Local-only path: pure `RELEASE` requests should update reservations/snapshots and finalize synchronously without creating an outbox row.
  - Ledger-backed path: only requests containing `TRANSFER_OUT` / `TRANSFER_IN` should enter the async ledger saga.
  - This addresses WS-2 directly and removes an unnecessary cross-service dependency for release-only flows.

- Make the wallet transaction state machine explicit in storage.
  - Suggested states: `LOCAL_COMMITTED`, `LEDGER_DISPATCHED`, `LEDGER_CONFIRMED`, `FAILED`, `COMPENSATION_REQUIRED`.
  - Today `PENDING` overloads multiple meanings: local state mutated, outbox pending, ledger not confirmed, or retry exhausted.
  - This would make WS-4 operationally manageable instead of leaving repairs implicit.

- Replace timestamp-plus-local-sequence IDs with a distributed-safe generator.
  - Use Snowflake-style IDs, DB sequences, or UUID/ULID with sortable semantics.
  - Apply the same strategy to wallet ids, transaction ids, reservation ids, action ids, and outbox event ids.
  - This addresses WS-5.

- Enforce request-shape invariants before action construction.
  - Reject duplicate `(walletRef, operationType)` debit/reserve lines for the same request.
  - Validate which operation-type combinations are legal for `atomic`, `reserve`, and `apply` APIs.
  - This addresses WS-2, WS-7, and WS-10.

### Performance

- Remove synchronous request-thread waiting on Kafka producer futures.
  - `DefaultPublisher.publish(...)` should use async completion callbacks and let the outbox row remain the source of truth until producer acknowledgment arrives.
  - Immediate publish can stay as an optimization, but it should not block application threads for up to 3 seconds per send.
  - This addresses WS-8.

- Increase outbox retry throughput and add backlog controls.
  - `page-limit: 2` is too small for a state-mutating service that depends on async settlement.
  - Raise the page limit, add a retry execution time budget, and emit metrics for pending outbox count, oldest pending age, retry exhaustion, and publish latency.
  - This addresses WS-4 and WS-8.

- Collapse duplicate per-wallet work before persistence.
  - When requests contain multiple lines for the same wallet and asset, either reject them or aggregate them before snapshot/reservation mutation.
  - That reduces lock churn, write amplification, and request-shape fragility.
  - This addresses WS-7 and helps with throughput under hot wallets.

### Reliability

- Fix post-ledger reservation settlement so `CONSUME` actions actually reach `updateReservationAfterPosting(...)`.
  - The minimal repair is to include `CONSUME` in the post-finalization action map instead of filtering only `action.isTransfer()`.
  - Add an invariant test for atomic debit -> ledger reply -> reservation `pendingSettle == 0` and `consumed == amount`.
  - This addresses WS-1.

- Check ledger reply status before finalizing wallet state.
  - `DefaultListener.handlePostLedgerReply(...)` should reject non-OK replies and move the wallet transaction into a failure/reconciliation state instead of calling `afterPostLedger(...)` blindly.
  - This addresses WS-3 and reduces cross-service contract brittleness.

- Add a terminal path for exhausted outbox retries.
  - Shared outbox rows should move to `DEAD` or an equivalent terminal status, and wallet transactions tied to those rows should become visible to reconciliation or compensation jobs.
  - Without that, WS-4 remains an operational blind spot.

- Make `createWallet` idempotent around `INIT` state.
  - If a wallet already exists in `INIT`, reload and continue toward `OPEN` instead of treating `updateWalletStatus(...) == 0` as a hard business failure.
  - Consider a dedicated wallet-creation idempotency key keyed by `(serviceId, referenceId)`.
  - This addresses WS-6.

- Persist all reservation amount fields explicitly and enforce schema defaults.
  - Include `released` in `WalletReservationMapper.batchInsert(...)`.
  - Back it with `NOT NULL DEFAULT 0` at the schema level.
  - This addresses WS-9.

- Tighten request validation on non-empty business identifiers.
  - Require nonblank `reference_id`, `wallet_ref`, `asset_code`, and reservation refs where the operation type needs one.
  - Return `INVALID_REQUEST_PARAMETER` before idempotency claim or DB lookups.
  - This addresses WS-10.

## Assumptions

- The statement that ledger async reply events are currently produced only for successful posts is based on the already analyzed `ledger-service` consumer path, not on `wallet-service` alone.

- MySQL DDL is not in the analyzed scope, so unique constraints and default values are inferred from repository behavior and mapper SQL.

- WS-9 assumes the `wallet_reservations.released` column is not guaranteed by schema to default to `0`; if the schema already enforces `NOT NULL DEFAULT 0`, the runtime risk becomes lower but the mapper remains unnecessarily implicit.
