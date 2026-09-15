# Wallet Service Critical Logic

## Scope

This analysis is based on:

- `wallet-service/src/main/java`
- `wallet-service/src/main/resources` except log config
- `grpc-proto/wallet-proto/src/main/proto`
- shared `common-util` classes that wallet-service directly depends on for idempotency, transaction rollback, Kafka retry, and outbox retry

It excludes test code, log config, backup property files, and generated proto Java code.

## Critical Logic Analysis

### 1. Transaction Flow

#### Shared pre-transaction gate

All three wallet transaction entry points are thin wrappers around the same pipeline:

- `AtomicTransactionProcessor.executeAtomic(...)`
- `ReserveTransactionProcessor.reserve(...)`
- `ApplyReservationTransactionProcessor.apply(...)`

Each one builds a `RequestInfo` and then calls:

1. `IdempPrecheckProcessor.idempAndValidatePrecheck(...)`
2. `BeforePostLedgerProcessor.beforePostingLedger(...)`
3. if ledger posting is required, later `AfterPostLedgerProcessor.afterPostLedger(...)`

`RequestInfo.getStableHash()` is the payload identity boundary. It hashes:

- `referenceId`
- `serviceIdPb`
- `idempotenceKey`
- `businessTypePb`
- `transactionType`
- `requestName`
- all `TransactionLinePb` values after sorting by `walletRef`, `assetCode`, `operationType`, `amount`, `reservationRef`

This means the same line set in a different order is treated as the same request, but the same idempotency key with different business content is rejected as a hash conflict.

`ValidateHelper.requestPrecheck(...)` is intentionally shallow. It validates:

- `serviceId`
- `businessType`
- `transactionType`
- `operationType != Unknown`
- `amount > 0`

It does not validate blank `reference_id`, `wallet_ref`, `asset_code`, or `reservation_ref`; the downstream processors treat those values as lookup keys.

#### Atomic transaction flow

`AtomicTransactionProcessor` always calls `beforePostingLedger(requestInfo, true)`.

Inside `BeforePostLedgerProcessor`, the flow is:

1. Create a `WalletTransaction` in `PENDING` state.
2. Load `BalanceSnapshot` rows by `(initiator, wallet_ref)` using `BalanceSnapshotRepository.selectByRefs(...)`.
3. Load existing `WalletReservation` rows for any non-empty `reservation_ref` values.
4. Convert each proto line into wallet-local reservations and actions through `ObjectBuilder`.

Per-line decomposition is concrete in `ObjectBuilder.createActionsFromLine(...)`:

- `OperationTypePb_Debit`
  - create a new reservation with:
    - `referenceId = request.referenceId + ":" + walletId`
    - `remaining = 0`
    - `pendingSettle = amount`
  - create actions:
    - `RESERVE`
    - `CONSUME`
    - `TRANSFER_OUT`

- `OperationTypePb_Credit`
  - create action:
    - `TRANSFER_IN`

So an atomic debit is modeled locally as "move available into reserved, mark the reservation as pending settlement, then wait for ledger confirmation before reducing reserved balance".

Validation then runs through `ValidateHelper.validateActionInfo(...)`.

What it actually enforces:

- `actions` must not be empty
- transfer legs must balance by `assetId`
- each action wallet/asset must match the loaded snapshot
- each reservation-backed action must match the loaded reservation wallet/asset

What it does not enforce:

- exact amount equality between an action and its reservation during validation
- any wallet ownership relation beyond `serviceId + walletReferenceId`

If validation passes, `createOutboxIfNeeded(...)` converts only `TRANSFER_OUT` and `TRANSFER_IN` actions into ledger entries. `RESERVE`, `CONSUME`, and `RELEASE` are wallet-local only and never leave the service.

The DB transaction in `updateDbToCreateTxn(...)` then:

1. updates existing reservations if needed
2. updates snapshots for `RESERVE` / `RELEASE`
3. inserts newly created reservations
4. inserts `wallet_transactions`
5. inserts `wallet_actions`
6. inserts a claimed common `outbox` row when ledger posting is needed

For atomic debit/credit, the pre-ledger local state after commit is:

- debit wallet snapshot: `available - amount`, `reserved + amount`
- debit reservation: already exists in `pendingSettle`
- credit wallet snapshot: unchanged until ledger reply
- wallet transaction: `PENDING`

After commit, wallet marks Redis idempotency as accepted and then tries immediate Kafka publish.

The async completion path is `AfterPostLedgerProcessor.afterPostLedger(txnId)`, which is supposed to:

- move reservation `pendingSettle -> consumed`
- reduce reserved balance for `TRANSFER_OUT`
- increase available balance for `TRANSFER_IN`
- move wallet transaction `PENDING -> COMPLETED`

Observed current behavior from code:

- `AfterPostLedgerProcessor.getTxnInfo(...)` loads `CONSUME`, `TRANSFER_OUT`, and `TRANSFER_IN` actions.
- But `TransactionInfo.create(...)` only puts `action.isTransfer()` actions into `walletIdToPostActions`.
- `updateReservationAfterPosting(...)` iterates `walletIdToPostActions` and only reacts to `ActionType.CONSUME`.
- Because `CONSUME` actions are not present in that map, reservation `pendingSettle -> consumed` does not happen in the current implementation.

So the current post-ledger finalization does update snapshots and transaction status, but it does not settle reservation amounts the way the method naming and comments imply.

#### Reserve transaction flow

`ReserveTransactionProcessor` is the only path with explicit operation-type validation before the common pipeline.

`validate(...)` enforces that every line is `OperationTypePb_Reserve`, then the processor calls `beforePostingLedger(requestInfo, false)`.

That flag changes the behavior materially:

- `WalletTransaction` is marked `COMPLETED` before DB write
- no outbox is created
- no ledger call is made
- no async reply is expected

Per-line behavior:

- create a `WalletReservation` with `remaining = amount`
- create a `RESERVE` action
- update snapshot `available - amount`, `reserved + amount`
- insert reservation + wallet transaction + wallet action in one DB transaction

This is a purely local hold flow. The reply is synchronous and includes generated reservation references.

#### Apply reservation transaction flow

`ApplyReservationTransactionProcessor` calls `beforePostingLedger(requestInfo, true)` directly. Unlike `ReserveTransactionProcessor`, it has no operation-type validator.

Per-line behavior in `ObjectBuilder.createActionsFromLine(...)`:

- `OperationTypePb_Earmark`
  - requires an existing reservation
  - creates:
    - `CONSUME`
    - `TRANSFER_OUT`

- `OperationTypePb_Release`
  - requires an existing reservation
  - creates:
    - `RELEASE`

- `OperationTypePb_Credit`
  - creates:
    - `TRANSFER_IN`

Pre-ledger local state changes are split across reservations and snapshots:

- `Earmark`
  - reservation: `remaining - amount`, `pendingSettle + amount`
  - snapshot: no immediate change

- `Release`
  - reservation: `remaining - amount`, `released + amount`
  - snapshot: `available + amount`, `reserved - amount`
  - if `total == consumed + released`, reservation becomes `FINISHED`

- `Credit`
  - no pre-ledger snapshot change
  - available balance increases only after async finalization

A non-obvious consequence of `ApplyReservationTransactionProcessor` always using `needPostLedger = true`:

- even a release-only apply request goes through outbox creation and Kafka publishing
- `ObjectBuilder.createOutbox(...)` will generate zero ledger entries when there are no `TRANSFER_OUT` / `TRANSFER_IN` actions
- the wallet side still persists the pre-ledger local release mutation before attempting to send that empty ledger post request

Assumption based on previously analyzed `ledger-service`:

- ledger async posting only emits a reply event for successful posts
- non-OK results are turned into exceptions and retried/DLQed instead of generating a reply event

If that assumption holds, a release-only apply request can leave wallet state mutated locally while the wallet transaction remains `PENDING`, because the wallet service has no local fast-path that bypasses ledger for release-only apply operations.

### 2. Concurrency / Consistency

#### Redis idempotency is the first write barrier

`IdempPrecheckProcessor` claims Redis before touching MySQL.

Key shape comes from `CommonIdempHelper.idempKey(...)`:

- `idemp_k:wallet:post_transaction:{idempotencyKey}`

Value shapes are:

- pending: `P:{hash}:{token}`
- accepted: `A:{hash}:{token}:{txnId}`

Behavior:

- if Redis claim succeeds, this request owns the idempotency slot
- if Redis already holds a matching accepted value, wallet returns the stored `txnId`
- if Redis holds a pending value, wallet returns `REQUEST_IN_PROCESSING`
- if Redis holds a different hash under the same idempotency key, wallet returns `REQUEST_HASH_CONFLICT`

This is stronger than simple duplicate suppression: the idempotency key is bound to a canonicalized payload hash.

#### MySQL is the idempotency backstop

After Redis claim, wallet still checks `wallet_transactions` by `(initiator, idempotencyKey)` through `WalletTransactionRepository.selectByIdempotencyKey(...)`.

This covers the crash window where:

- DB commit succeeded
- Redis accepted state was not written yet
- or the Redis key expired

When a row is found, wallet writes Redis accepted state from DB and returns the transaction id.

#### Failed pre-DB paths release the Redis claim conditionally

`BeforePostLedgerProcessor.releaseIdempBeforeReturnError(...)` rebuilds the exact pending value and calls `IdempRedisClient.releaseIdempIfOwned(...)`, which uses the Lua compare-and-delete script from `common-util`.

That matters because a failed request only deletes the key if it still owns the original pending token. A different request that reclaimed the key later is not deleted by mistake.

#### Snapshot consistency is enforced by SQL predicates, not in-memory math

`BalanceSnapshotRepository` applies balance mutations through conditional `UPDATE` statements:

- `reserveFromWalletId(...)`
  - requires `wallet_status = OPEN`
  - requires `available >= amount`

- `releaseFromWalletId(...)`
  - requires `wallet_status = OPEN`
  - requires `reserved >= amount`

- `transferOutFromWalletId(...)`
  - requires `wallet_status = OPEN`
  - requires `reserved >= amount`

- `transferInToWalletId(...)`
  - requires `wallet_status = OPEN`

So insufficient balance is not decided by the stale snapshot object alone; the database predicate is the final guard.

`BeforePostLedgerProcessor.updateSnapshotsBeforePosting(...)` and `AfterPostLedgerProcessor.updateSnapshotsAfterPosting(...)` both sort snapshots by primary key before issuing updates. The code comment explicitly says this is to update rows in id order, which is a deadlock-reduction pattern for multi-wallet transactions.

#### Reservation consistency uses row locks plus optimistic lock checks

`UpdateReservationProcessor.updateReservations(...)` does two things:

1. loads reservations with `selectInIdForUpdate(...)`
2. applies `updateWithOptimisticLock(...)` on the mutable amount/status fields

The optimistic-lock predicate includes:

- `reservationStatus`
- `remaining`
- `pendingSettle`
- `consumed`
- `released`

So even after taking row locks, the final `UPDATE` still checks that the numeric state is exactly what was read.

This is stricter than the snapshot updates, which rely only on the SQL balance predicates.

#### Finalization is idempotent at the wallet transaction level

`AfterPostLedgerProcessor.afterPostLedger(...)` first reloads the transaction and exits early when `walletTxn.hasFinalized()` is true.

That means duplicate ledger reply events do not keep reapplying snapshot mutations once the wallet transaction is already `COMPLETED` or `CLOSED`.

#### There is still a post-commit gap

The critical gap is between these two lines of behavior in `BeforePostLedgerProcessor`:

1. commit wallet DB state and insert outbox row
2. mark Redis idempotency done

If the process dies after DB commit but before `markIdempDone(...)`, Redis can still show `PENDING` until TTL expiry. The DB backstop resolves this eventually, but during the pending TTL window a duplicate caller can receive `REQUEST_IN_PROCESSING` even though the transaction row already exists.

### 3. Event Flow

#### Outbound wallet -> ledger command flow

Wallet posts to ledger through the shared outbox, not through the unused `PostServiceClient`.

`OutboxHelper.fromPostTransactionRequest(...)` builds the outbound record with:

- `eventType = LEDGER_POST`
- `eventId = IdGenerator.generateEventId(txnId)`
- `commandId = txnId`
- `destination = WalletTopic.POST_LEDGER` (`wallet.ledger.command.posting`)
- `partitionKey = txnId`
- payload = serialized `PostTransactionRequestPb`

`ObjectBuilder.createOutbox(...)` sets the ledger request `reference_id` to the wallet transaction id, so wallet correlates ledger replies to its own local transaction id rather than the original client reference.

#### Immediate publish plus durable retry

The send path is deliberately split:

1. `updateDbToCreateTxn(...)` inserts the outbox row in the same DB transaction as wallet state
2. `postLedger(...)` tries immediate Kafka publish after commit
3. on publish success, wallet finalizes the outbox row to `SENT`
4. on publish failure, wallet logs the error and returns success to the caller anyway
5. `OutboxDispatcher` later runs `OutboxRetryHandler.retry()` on cron `${app.outbox.interval}`

From `application.yml` and wallet constants:

- outbox cron interval: every 5 seconds
- retry page limit: `2`
- initial next-attempt offset after insert: `15s`

From shared `OutboxRetryHandler`:

- rows are claimed with `selectForClaimSkipLock(...)`
- claim increments `attempt_count` and advances `next_attempt_at`
- successful republish finalizes rows to `SENT`
- unsuccessful rows stay `PENDING`

So the business success boundary for wallet RPC is "local wallet state + outbox row committed", not "ledger command definitely delivered".

#### Inbound ledger -> wallet reply flow

`DefaultListener` consumes topic `ledger.wallet.reply.posting` using the shared `concurrentCommandKafkaListenerContainerFactory`.

Shared container behavior from `common-util`:

- concurrency = `3`
- manual ack
- common error handler attached

Wallet listener behavior:

1. parse `EventEnvelopePb`
2. require `eventType == "POST_LEDGER_REPLY"`
3. parse `PostTransactionReplyPb`
4. call `AfterPostLedgerProcessor.afterPostLedger(reply.getReferenceId())`
5. only after successful processing, acknowledge the Kafka record

This means the wallet reply consumer uses `reply.referenceId` as the transaction correlation key.

#### Wallet reply handling ignores ledger reply status

The most important event-flow detail is in `DefaultListener.handlePostLedgerReply(...)`:

- it logs `reply`
- it does not inspect `reply.getError().getCode()`
- it always calls `afterPostLedgerProcessor.afterPostLedger(reply.getReferenceId())`

So if a ledger reply event is ever produced with a non-OK `ErrorPb`, wallet still tries to finalize local state as if the post succeeded.

Cross-service dependency from the previously analyzed `ledger-service`:

- ledger's async consumer only creates a reply outbox for `ERROR_OK`
- non-OK post results are converted into exceptions and therefore retried or sent to DLQ instead of being published back to wallet

Taken together, the current end-to-end behavior is:

- success path: wallet gets a reply and finalizes locally
- ledger-side business failure path: wallet may never receive a reply event, so the local wallet transaction remains pending after pre-ledger mutations have already been committed
- hypothetical non-OK reply path: if a reply were published anyway, wallet would still finalize because it ignores `reply.error`

### 4. Cache Logic

#### Wallet-service has no business read cache

Although `WalletServer` imports `RedisCacheRegister`, the wallet code under `wallet-service/src/main/java` does not define any cache DAO/store analogous to ledger's `LedgerTxnCache` / `LedgerRefCache`.

Observed current behavior:

- `BalanceSnapshot`, `WalletReservation`, `WalletTransaction`, and `WalletAction` are always read from MySQL repositories
- Redis is used only for idempotency keys through `IdempRedisClient`

So the cache logic in wallet-service is effectively idempotency logic, not balance/query caching.

#### Idempotency TTL behavior

Wallet binds its Redis timing to `CustomCacheConfig.Idemp`:

- `pendingTtl`
- `doneTtl`
- `jitterMs`

From `application.yml`:

- pending TTL = `120s`
- done TTL = `24h`

Observed usage:

- `claimIdempCacheIfAbsent(...)` uses jittered pending TTL
- DB-backstop recovery path also uses jittered done TTL
- normal post-commit `markIdempDone(...)` uses the raw `doneTtl` without jitter

So done-state TTL jitter is inconsistent depending on whether the accepted state came from normal processing or DB recovery.

#### Malformed idempotency entries self-heal

If Redis returns a malformed idempotency value:

- `CommonIdempHelper.parseIdempValue(...)` fails
- wallet logs the parse failure
- wallet force-deletes the Redis key
- wallet falls back to the DB check

This is a concrete self-recovery branch, not just a log-and-fail path.

Assumption:

- `CustomCacheConfig.Idemp` requires `jitterMs`, but the analyzed `wallet-service/src/main/resources/application.yml` does not show that property. It is assumed to come from another profile or environment; otherwise the config would be incomplete at runtime.

### 5. Result and Error Handling

#### Processors return `Result<?>`; gRPC adapters flatten everything into `ErrorPb`

Most wallet business logic is expressed as `Result<T>`:

- `Results.success(...)`
- `Results.fail(...)`

`WalletServiceImpl` catches any thrown exception and always returns a protobuf reply with `ErrorPb` built from `ErrorCode.SERVER_ERROR`.

Implications:

- wallet gRPC methods do not surface transport-level gRPC status errors for business failures
- expected business failures and unexpected exceptions are both returned as normal protobuf replies, just with different `ErrorPb`

#### DB rollback is tied to failed `Result<?>`

`DbTransactionHelper.executeWithResult(...)` rolls back on either:

- thrown exception
- returned `Result.isFailed()`

Wallet relies on that pattern heavily in:

- `CreateWalletProcessor.enableWallet(...)`
- `BeforePostLedgerProcessor.updateDbToCreateTxn(...)`
- `AfterPostLedgerProcessor.afterPostLedger(...)`
- shared `OutboxRetryHandler.retry()` claim loop

So the service’s transactional correctness depends on returning `Results.fail(...)` instead of partially handling errors and continuing.

#### Kafka path uses exceptions, not `Result<?>`

The reply consumer is the opposite of the RPC layer:

- parse failures become `AbnormalProtoDataException`
- retryable processing failures become `RetriableException`
- the shared `DefaultErrorHandler` retries three times with 1-second delays, then DLQs
- `wallet-service` config marks `AbnormalProtoDataException` as non-retryable

This means wallet has two error semantics in the same service:

- RPC path: always return a protobuf reply
- Kafka path: throw exceptions to control retry/DLQ behavior

#### Publish failure is intentionally downgraded

`BeforePostLedgerProcessor.postLedger(...)` is explicit that publish failure should not block the main flow.

If immediate Kafka publish fails:

- the wallet transaction stays committed
- the outbox row stays pending
- the RPC caller still receives success or pending transaction information
- eventual delivery is delegated to the outbox scheduler

That is a deliberate reliability trade-off: caller latency and DB durability are favored over synchronous confirmation that ledger has accepted the command.

#### Current error boundary is asymmetric around ledger replies

The service is careful about pre-ledger failures:

- validation failures return wallet error codes
- pre-DB failures release the idempotency claim
- DB failures roll back local state

But the post-ledger boundary is much looser:

- wallet does not inspect ledger reply `ErrorPb`
- wallet finalization is driven by `reply.referenceId` alone
- wallet depends on ledger to publish only successful reply events

So the current design only behaves safely if that cross-service assumption remains true.

## Assumptions and boundaries

- The statement about reply events only being published on successful ledger posts is based on the previously analyzed `ledger-service` Kafka consumer, not on wallet-service alone.

- MySQL DDL is not in the analyzed scope, so uniqueness/index assumptions behind `insertIgnore(...)`, `selectByIdempotencyKey(...)`, and reservation reference lookups are inferred from code usage.

- The analysis focuses on active code paths. `PostServiceClient`, `WalletOutbox`, and `WalletOutboxMapper` exist but are not part of the active runtime behavior in the analyzed scope.
