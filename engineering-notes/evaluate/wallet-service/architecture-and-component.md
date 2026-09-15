# Wallet Service and Wallet Proto Architecture

## Scope

This summary is based on:

- `wallet-service/src/main/java`
- `wallet-service/src/main/resources` except log config
- `grpc-proto/wallet-proto/src/main/proto`

It excludes test code, log config, backup property files, and generated proto Java code.

## Architecture Summary

### Module layout

1. `grpc-proto/wallet-proto`
   - Defines the wallet-facing gRPC contract in `wallet_service.proto`:
     - `WalletService.createWallet`
     - `WalletService.atomicTransaction`
     - `WalletService.reserveTransaction`
     - `WalletService.applyReserveTransaction`
   - Defines request/response shapes in:
     - `create_wallet.proto`
     - `wallet_transaction.proto`
   - Defines wallet enums in `wallet_enum.proto`:
     - `OwnerTypePb`
     - `ServiceIdPb`
     - `TransactionStatusPb`
     - `BusinessTypePb`
     - `ActionTypePb`
     - `OperationTypePb`

2. `wallet-service`
   - Spring Boot + gRPC application bootstrapped by `WalletServer`.
   - Runtime areas:
     - `service`: gRPC adapter (`WalletServiceImpl`)
     - `processor`: wallet creation and transaction orchestration
     - `processor.transaction.step`: idempotency, pre-ledger mutation, post-ledger finalization
     - `dao.repository` / `dao.mapper`: MySQL persistence for wallet state
     - `client`: synchronous gRPC clients to ledger account/post services
     - `kafka.consumer` / `kafka.producer`: ledger command publishing and reply consumption
     - `cronjob.outbox`: scheduled retry of failed ledger-post events
     - `po` / `po.enums`: persisted domain entities and enums
     - `result`, `exception`, `utils`: error mapping, enum mapping, id generation, envelope/outbox helpers

### Runtime composition

`WalletServer` shows that the service is composed from both wallet-local code and shared infrastructure:

- scans `com.exchange.app.wallet`
- also scans shared packages:
  - `com.exchange.common.outbox`
  - `com.exchange.common.kafka`
- imports shared registration classes:
  - `CommonDbComponentRegister`
  - `RedisCacheRegister`
  - `RedisIdempRegister`
- registers wallet mappers plus common outbox mappers
- enables scheduling, which is used by `OutboxDispatcher`

So `wallet-service` is not a standalone balance calculator. It is a business service that persists wallet-side state locally and relies on `common-util` for transactions, Redis idempotency, Kafka listener wiring, and outbox retry.

### Responsibilities

The active code implements five concrete responsibilities.

#### 1. Wallet bootstrap and ledger-account provisioning

`CreateWalletProcessor` owns wallet creation.

It:

- validates `service_id` and `owner_type`
- looks up an existing wallet by `(service_id, reference_id)` through `WalletMapper.selectByReferenceId(...)`
- inserts a new `wallets` row in `INIT` state when missing
- synchronously calls ledger `AccountService.createAccount(...)`
- uses `walletId` as the ledger account `reference_id`
- creates a 1:1 `wallet_account_mappings` row where `accountRefId = walletId`
- creates an initial `balance_snapshots` row with `available = 0` and `reserved = 0`
- flips wallet status from `INIT` to `OPEN`

The ledger account category and normal side are hard-coded in `CreateWalletProcessor.createAccount(...)` as:

- `AccountCategory_Liability`
- `NormalSidePb_Credit`

That means wallet balances are modeled as wallet-local snapshots backed by ledger liability accounts.

#### 2. Wallet transaction orchestration

`WalletServiceImpl` is only an RPC adapter. All three mutating transaction APIs are routed into processors that create a `RequestInfo` and then share the same pipeline:

- `AtomicTransactionProcessor`
- `ReserveTransactionProcessor`
- `ApplyReservationTransactionProcessor`

Common pipeline:

1. build `RequestInfo`
2. compute stable request hash from sorted lines in `RequestInfo.getStableHash()`
3. run `IdempPrecheckProcessor.idempAndValidatePrecheck(...)`
4. call `BeforePostLedgerProcessor.beforePostingLedger(...)`
5. optionally wait for async ledger reply, then finalize through `AfterPostLedgerProcessor`

This is the real service architecture: RPC handlers are thin, while the transaction state machine is implemented in the step processors.

#### 3. Local reservation and balance state management

Wallet keeps its own operational state in MySQL before and after ledger posting.

The local model is split across:

- `wallet_transactions`: business transaction shell and status
- `wallet_actions`: normalized action legs such as `RESERVE`, `CONSUME`, `RELEASE`, `TRANSFER_OUT`, `TRANSFER_IN`
- `wallet_reservations`: reservation lifecycle amounts (`remaining`, `pendingSettle`, `consumed`, `released`)
- `balance_snapshots`: wallet-visible balances (`available`, `reserved`)

`ObjectBuilder.createActionsFromLine(...)` makes the local action model explicit:

- `Reserve` line -> `RESERVE`
- `Earmark` line -> `CONSUME` + `TRANSFER_OUT`
- `Release` line -> `RELEASE`
- `Debit` line -> `RESERVE` + `CONSUME` + `TRANSFER_OUT`
- `Credit` line -> `TRANSFER_IN`

So the service does not post raw client lines directly to ledger. It first decomposes them into wallet-local state transitions.

#### 4. Ledger handoff through common outbox + Kafka

The active posting path is asynchronous.

Evidence:

- `BeforePostLedgerProcessor` creates a common `Outbox` only when `needPostLedger = true`
- `ObjectBuilder.createOutbox(...)` converts only `TRANSFER_OUT` and `TRANSFER_IN` actions into `PostTransactionRequestPb`
- `OutboxHelper.fromPostTransactionRequest(...)` routes the event to topic `wallet.ledger.command.posting`
- `DefaultPublisher` wraps the payload in `EventEnvelopePb` with event type `POST_LEDGER`
- `DefaultListener` listens on `ledger.wallet.reply.posting`

A notable architectural detail is that `PostTransactionRequestPb.reference_id` is set to `walletTxn.getTxnId()`, not the original client `reference_id`. The async reply is therefore correlated back to the wallet transaction id.

Also, `PostServiceClient` exists, but there are no active call sites in the analyzed `wallet-service/src/main/java` code. The runtime path in use is Kafka/outbox, not direct gRPC posting to ledger.

#### 5. Asynchronous completion after ledger confirmation

`AfterPostLedgerProcessor` finalizes wallet state after the ledger reply arrives.

It:

- reloads the wallet transaction by `txnId`
- reloads post-ledger actions (`TRANSFER_IN`, `TRANSFER_OUT`, `CONSUME`)
- reloads snapshots and consumed reservations
- re-validates that actions match snapshot/reservation identity
- in one DB transaction:
  - moves reservation `pendingSettle -> consumed`
  - decreases snapshot `reserved` for `TRANSFER_OUT`
  - increases snapshot `available` for `TRANSFER_IN`
  - updates wallet transaction status from `PENDING -> COMPLETED`

This creates a two-stage wallet lifecycle:

- before ledger post: reserve local funds and persist pending state
- after ledger reply: settle the local pending state into final balances

### Data flow

#### 1. Create wallet flow

1. `WalletServiceImpl.createWallet(...)` receives `CreateWalletRequestPb`.
2. `CreateWalletProcessor` maps proto enums to domain enums.
3. It checks for an existing wallet by `(service_id, reference_id)`.
4. If missing, it inserts a `wallets` row with generated `walletId` and status `INIT`.
5. It calls ledger `AccountService.createAccount(...)` using:
   - `reference_id = walletId`
   - liability category
   - credit normal side
6. Inside one DB transaction, it:
   - updates wallet status `INIT -> OPEN`
   - inserts `wallet_account_mappings(walletId, walletId)`
   - inserts a zeroed `balance_snapshots` row
7. It returns `CreateWalletReplyPb`.

The local wallet is therefore considered usable only after both the ledger account exists and local snapshot/mapping rows are written.

#### 2. Atomic transaction flow

1. `atomicTransaction(...)` creates `RequestInfo` with transaction type `ATOMIC`.
2. `IdempPrecheckProcessor` validates request enums and amounts, claims Redis idempotency under scope `post_transaction`, and falls back to `wallet_transactions` lookup by `(initiator, idempotencyKey)` when needed.
3. `BeforePostLedgerProcessor` loads `balance_snapshots` by `(initiator, wallet_ref)`.
4. For each line, it builds wallet actions and, for `Debit`, a synthetic reservation whose `referenceId` is `request.referenceId + ":" + walletId`.
5. `ValidateHelper.validateActionInfo(...)` enforces:
   - transfer assets are balanced per asset code
   - action wallet/asset matches the loaded snapshot
   - reservation-backed actions match the reservation wallet/asset
6. It creates an outbox payload containing only transfer legs mapped to ledger account refs through `wallet_account_mappings`.
7. In one DB transaction it:
   - reserves balances in `balance_snapshots`
   - inserts created reservations
   - inserts `wallet_transactions`
   - inserts `wallet_actions`
   - inserts claimed common outbox row
8. It marks Redis idempotency as done with the wallet transaction id.
9. It tries immediate Kafka publish; failed publish does not roll back the wallet transaction because retry is delegated to the outbox scheduler.
10. When `ledger.wallet.reply.posting` arrives, `AfterPostLedgerProcessor` finalizes local balances and marks the wallet transaction `COMPLETED`.

#### 3. Two-step reserve/apply flow

`reserveTransaction(...)` and `applyReserveTransaction(...)` deliberately split the lifecycle.

Reserve phase:

- `ReserveTransactionProcessor.validate(...)` allows only `OperationTypePb_Reserve`
- it calls `beforePostingLedger(..., false)`
- `BeforePostLedgerProcessor` marks the wallet transaction `COMPLETED` immediately because no ledger posting is needed
- snapshot update is local only: `available - amount`, `reserved + amount`
- created reservations start in `ACTIVE`

Apply phase:

- `ApplyReservationTransactionProcessor` calls `beforePostingLedger(..., true)` with transaction type `TWO_STEP`
- `Earmark` consumes from an existing reservation and produces `TRANSFER_OUT`
- `Release` returns reserved balance to available locally and may finish the reservation without any ledger leg
- `Credit` creates `TRANSFER_IN`
- only transfer legs go to ledger through the outbox

The design separates local hold management from ledger settlement. Reservation creation is synchronous and local; settlement is asynchronous and ledger-backed.

#### 4. Event and retry flow

1. Wallet writes a common outbox row with event type `LEDGER_POST` and destination `wallet.ledger.command.posting`.
2. `DefaultPublisher` converts the outbox row into `EventEnvelopePb` and sends to Kafka.
3. If immediate send fails, the outbox row remains pending.
4. `OutboxDispatcher` runs on cron `${app.outbox.interval}` and calls shared `OutboxRetryHandler.retry()`.
5. `DefaultListener` consumes `ledger.wallet.reply.posting` with manual ack.
6. It only accepts envelope event type `POST_LEDGER_REPLY`.
7. It parses `PostTransactionReplyPb` and finalizes the local wallet transaction through `AfterPostLedgerProcessor`.
8. On processing failure it throws `RetriableException`, so retry/DLQ behavior is delegated to the common Kafka error handler.

This makes wallet-service an event-driven coordinator around ledger settlement rather than a purely synchronous RPC service.

## Key Components

### Core services and orchestrators

- `WalletServer`
  - application bootstrap and shared DB/Redis/Kafka/outbox registration

- `WalletServiceImpl`
  - gRPC adapter for wallet creation and transaction APIs

- `CreateWalletProcessor`
  - orchestrates wallet bootstrap, ledger account creation, status transition, mapping creation, and initial snapshot creation

- `AtomicTransactionProcessor`
  - entry point for one-shot debit/credit transfers

- `ReserveTransactionProcessor`
  - entry point for local reserve-only operations

- `ApplyReservationTransactionProcessor`
  - entry point for settlement/release operations against prior reservations

- `IdempPrecheckProcessor`
  - owns request precheck, stable-hash based idempotency claim, Redis fallback recovery, and DB backstop lookup

- `BeforePostLedgerProcessor`
  - central pre-ledger orchestrator
  - builds actions/reservations, validates them, mutates snapshots/reservations, persists transaction state, creates outbox, and triggers initial publish

- `AfterPostLedgerProcessor`
  - central post-ledger orchestrator
  - settles pending reservation/snapshot state after async ledger confirmation

- `UpdateReservationProcessor`
  - row-locks reservations with `for update` and applies optimistic-lock updates to amount fields and status

- `AccountServiceClient`
  - synchronous gRPC dependency used during wallet creation

- `DefaultPublisher`
  - Kafka publisher for wallet outbox rows

- `DefaultListener`
  - Kafka consumer for ledger reply events

- `OutboxDispatcher`
  - scheduled trigger for shared outbox retry

- `PostServiceClient`
  - direct gRPC posting client exists, but there are no active callers in the analyzed scope

### Data models

- `Wallet`
  - table: `wallets`
  - fields: `walletId`, `serviceId`, `referenceId`, `assetId`, `walletStatus`, `ownerType`, `ownerId`
  - business role: wallet identity and lifecycle gate (`INIT`, `OPEN`, etc.)

- `BalanceSnapshot`
  - table: `balance_snapshots`
  - fields: `walletId`, `serviceId`, `walletReferenceId`, `assetId`, `walletStatus`, `ownerType`, `ownerId`, `available`, `reserved`
  - business role: wallet-visible balance buckets used for reservation and transfer bookkeeping

- `WalletAccountMapping`
  - table: `wallet_account_mappings`
  - fields: `walletId`, `accountRefId`
  - business role: maps wallet identity into the account reference used when building ledger entries

- `WalletTransaction`
  - table: `wallet_transactions`
  - fields: `txnId`, `referenceId`, `initiator`, `idempotencyKey`, `txnStatus`, `txnType`, `businessType`, inherited timestamps
  - business role: transaction envelope and idempotency backstop

- `WalletAction`
  - table: `wallet_actions`
  - fields: `actionId`, `txnId`, `walletId`, `assetId`, `bucket`, `actionType`, `amount`, `reservationId`
  - business role: normalized state-transition legs used by both validation and settlement

- `WalletReservation`
  - table: `wallet_reservations`
  - fields: `reservationId`, `initiator`, `referenceId`, `walletId`, `walletReferenceId`, `assetId`, `total`, `remaining`, `consumed`, `pendingSettle`, `released`, `reservationStatus`, `reservationOutcome`, `reserveTxnId`
  - business role: tracks held funds across reserve, consume, and release stages

- shared `Outbox`
  - reused from `common-util`; active wallet code does not use the commented-out `WalletOutbox` model

- proto contracts
  - `CreateWalletRequestPb` / `CreateWalletReplyPb`
  - `AtomicTransactionRequestPb` / `AtomicTransactionReplyPb`
  - `ReserveTransactionRequestPb` / `ReserveTransactionReplyPb`
  - `ApplyReservationTransactionRequestPb` / `ApplyReservationTransactionReplyPb`
  - `TransactionLinePb`, `ReservationInfoPb`
  - common `ErrorPb` and `EventEnvelopePb`

### External dependencies

- MySQL
  - primary source of truth for wallets, snapshots, reservations, actions, transactions, and common outbox rows
  - configured in `application.yml` against schema `trade_walletservice`

- Redis
  - used for idempotency state through `IdempRedisClient`
  - configured in `application.yml` at `192.168.52.100:6379`

- Kafka
  - outbound topic: `wallet.ledger.command.posting`
  - inbound topic: `ledger.wallet.reply.posting`
  - retry/DLQ behavior is inherited from shared common Kafka configuration

- gRPC / protobuf
  - inbound wallet API surface via `WalletService`
  - outbound synchronous dependency on ledger `AccountService`
  - ledger client endpoints configured as `static://127.0.0.1:9091`

- `common-util`
  - supplies `DbTransactionHelper`, `IdempRedisClient`, outbox repository/retry, common result helpers, and shared Kafka listener configuration

- `ledger-proto`
  - supplies `CreateAccountRequestPb`, `PostTransactionRequestPb`, ledger enums, and reply messages used by wallet-to-ledger integration

- Spring Boot + Spring Kafka + grpc-spring-boot-starter + MyBatis-Plus
  - runtime container, scheduling, listener wiring, RPC server/client plumbing, and persistence layer

## Assumptions and boundaries

- MySQL DDL is not in the analyzed scope, so uniqueness assumptions are inferred from `insertIgnore(...)`, direct lookups, and reference-based query usage.

- `wallet_reservations.referenceId` is treated like a business key because reservations are loaded by reference in apply flows; the actual unique index is assumed, not verified from schema files.

- `PostServiceClient` appears to be an unused synchronous path. The active architecture in the analyzed code is outbox + Kafka for ledger posting.

- `WalletOutbox` and `WalletOutboxMapper` are fully commented out, so the active outbox implementation is the shared common outbox table/repository.

- `CustomCacheConfig.Idemp` requires `pendingTtl`, `doneTtl`, and `jitterMs`, but the analyzed `application.yml` only shows the TTL values. It is assumed that `jitterMs` is supplied by another profile or environment; otherwise this configuration would be incomplete at runtime.
