# Ledger Service and Ledger Proto Architecture

## Scope

This summary is based on:

- `ledger-service/src/main/java`
- `ledger-service/src/main/resources` except log config
- `grpc-proto/ledger-proto/src/main/proto`

It excludes test code, log config, and generated proto Java code.

## Architecture Summary

### Module layout

1. `grpc-proto/ledger-proto`
   - Defines the ledger-facing gRPC contract:
     - `AccountService.createAccount`
     - `PostService.postTransaction`
     - `PostService.getTxnById`
     - `PostService.getTxnByRefId`
   - Defines shared ledger enums in `ledger_common.proto`:
     - `LedgerDirectionPb`
     - `AccountCategoryPb`
     - `NormalSidePb`
     - `OwnerTypePb`
     - `ServiceIdPb`

2. `ledger-service`
   - Spring Boot + gRPC application bootstrapped by `LedgerServer`.
   - Runtime areas:
     - `service`: gRPC adapters
     - `processor`: business orchestration
     - `dao.repository` and `dao.mapper`: MySQL persistence
     - `dao.cache` and `dao.store`: Redis-backed read path
     - `kafka.consumer` / `kafka.producer`: async command/reply integration
     - `cronjob.outbox`: scheduled retry of failed reply publication
     - `po` / `po.enums`: domain entities and persisted enums
     - `result`, `exception`, `utils`: error mapping, conversion, helper logic

### Runtime composition

`LedgerServer` shows how the service is assembled:

- scans `com.exchange.app.ledger`
- also scans shared packages:
  - `com.exchange.common.outbox`
  - `com.exchange.common.kafka`
  - `com.exchange.common.redis.cache.component`
  - `com.exchange.common.component`
- imports shared DB and Redis registration classes:
  - `CommonDbComponentRegister`
  - `RedisCacheRegister`
  - `RedisIdempRegister`
- enables scheduling for outbox retry
- registers both service mappers and common outbox mappers

This makes `ledger-service` a business module sitting on top of the `common-util` DB, Redis, Kafka, and outbox infrastructure.

### Responsibilities

The code implements five concrete responsibilities.

#### 1. Account creation

`CreateAccountProcessor` validates request enums, checks that the asset exists, generates an account id, and inserts into `accounts`.

Evidence:

- enum mapping via `EnumMappers`
- asset existence check via `AssetMapper`
- write via `AccountRepository.insertIgnore(...)`

#### 2. Ledger posting

`PostLedgerProcessor` is the core posting path. It:

- validates the request structure and debit/credit balance per asset
- enforces idempotency in Redis keyed by `referenceId`
- resolves external `accountRef` values to internal `accountId` values
- inserts one `ledger_transactions` row plus many `ledger_entries` rows in one DB transaction
- marks Redis idempotency state as accepted after commit
- clears negative `referenceId` cache after a successful insert

#### 3. Ledger lookup

`GetLedgerTxnProcessor` supports two lookup modes:

- by `ledgerTxnId`
- by `referenceId`

The actual read path goes through `LedgerTxnStore`, which mixes Redis and MySQL:

- txn-id lookup uses a versioned object cache
- reference-id lookup uses a stable string cache from `refId -> txnId`
- reference-id misses are negative-cached

#### 4. Kafka command consumption

`DefaultListener` consumes `wallet.ledger.command.posting`, parses `EventEnvelopePb`, and currently only supports event type `POST_LEDGER`.

It calls the same `PostLedgerProcessor` used by gRPC, so synchronous and async posting share identical business logic.

#### 5. Reply publication with outbox fallback

The service does not publish reply events directly and forget them. Instead:

- `DefaultListener` converts the posting result into an `Outbox` row
- inserts it with `PENDING` status
- acknowledges Kafka
- tries immediate publication to `ledger.wallet.reply.posting`
- leaves failed publishes in the outbox table
- `OutboxDispatcher` later invokes the shared `OutboxRetryHandler`

This is the service's reliability boundary for Kafka replies.

### Data flow

#### 1. gRPC account creation flow

1. `AccountServiceImpl.createAccount(...)` receives `CreateAccountRequestPb`.
2. `CreateAccountProcessor` maps proto enums into domain enums.
3. It validates the category/normal-side pair using `ValidateHelper`.
4. It confirms `asset_id` exists in `assets`.
5. It inserts an `accounts` row with generated `accountId`.
6. It returns `CreateAccountReplyPb` with common `ErrorPb`.

Non-obvious behavior:

- duplicate insert is treated as a successful idempotent outcome (`"account already exists"`), not as an error path.
- the exact uniqueness constraint is not visible in the analyzed scope; it is assumed to be enforced in MySQL because the code relies on `insertIgnore`.

#### 2. gRPC post transaction flow

1. `PostServiceImpl.postTransaction(...)` delegates to `PostLedgerProcessor`.
2. `PostLedgerProcessor.reqPrecheck(...)` validates:
   - each entry amount is non-zero
   - direction enum is valid
   - there is at least one debit and one credit
   - debit and credit totals balance per `assetId`
3. It computes a stable request hash from:
   - `referenceId`
   - `description`
   - sorted entries
4. It claims Redis idempotency under:
   - service: `ledger`
   - scope: `post_ledger`
   - id: `referenceId`
5. If Redis already has:
   - `PENDING`: returns `REQUEST_IN_PROCESSING`
   - `ACCEPTED`: returns the stored `txnId`
   - malformed value: deletes bad Redis data and falls back to DB check
6. It checks MySQL for an existing transaction by `referenceId`.
7. It resolves every request `accountRef` to an internal `accountId`.
8. In one DB transaction:
   - inserts `ledger_transactions`
   - batch-inserts `ledger_entries`
9. After commit:
   - removes any negative `referenceId` cache
   - writes idempotency state to accepted with the generated `txnId`
10. It returns `PostTransactionReplyPb`.

This path uses Redis as a fast concurrency/idempotency gate, but MySQL remains the final source of truth.

#### 3. Kafka posting flow

1. `DefaultListener.onMessage(...)` receives raw Kafka bytes.
2. It parses `EventEnvelopePb`.
3. `handleMessage(...)` dispatches on `event_type`.
4. `handlePostLedger(...)` parses `PostTransactionRequestPb` from envelope payload.
5. It reuses `PostLedgerProcessor.postTransaction(...)`.
6. It converts the reply into an outbox record via `OutboxHelper.fromPostTransactionReply(...)`.
7. It inserts the outbox row with:
   - destination `ledger.wallet.reply.posting`
   - partition key `commandId`
   - event id derived from `commandId`
8. It acknowledges the inbound Kafka message before reply publication.
9. It attempts immediate publish through `DefaultPublisher`.
10. If publish fails, the outbox row stays pending for scheduled retry.

Important nuance from code:

- the listener's error classification is explicitly unfinished. A TODO in `handlePostLedger(...)` says retriable-error design is not settled.
- today, any non-`ERROR_OK` reply is converted into either `RetriableException` or `AbnormalProtoDataException`, which means Kafka retry/DLQ behavior is partly policy code and partly still provisional.

#### 4. Ledger lookup flow

1. `PostServiceImpl.getTxnById(...)` or `getTxnByRefId(...)` calls `GetLedgerTxnProcessor`.
2. `GetLedgerTxnProcessor` chooses `LedgerTxnStore.getByTxnId(...)` or `getByRefId(...)`.
3. `LedgerTxnStore` checks Redis first:
   - `LedgerTxnCache` for `txnId -> LedgerTxn`
   - `LedgerRefCache` for `refId -> txnId`
4. On cache miss, it queries MySQL.
5. If the lookup was by `referenceId` and nothing exists, it writes a negative cache entry.
6. If `includeEntries=true`, `LedgerEntryRepository` paginates through `ledger_entries` using `id > lastId`.
7. `PbConverter` converts the domain objects back into protobuf reply objects.

Non-obvious behavior:

- `LedgerEntryPb.accountRef` is populated from `LedgerEntry.accountId`, not from the original external account reference.
- This is because `PostLedgerProcessor` persists only the resolved internal `accountId`, and `PbConverter` reads that field back into the proto field named `accountRef`.

### Cache architecture inside ledger-service

There are two distinct cache shapes, chosen intentionally:

1. `LedgerTxnCache`
   - extends `VersionCacheAsideAbstract<LedgerTxn>`
   - key format: `ledger:id:{txnId}`
   - stores the full `LedgerTxn`
   - uses version CAS and can write tombstones after metadata update
   - no negative cache for txn-id lookup because the code comments treat it as mostly internal and low-risk

2. `LedgerRefCache`
   - extends `StableCacheAbstract<String>`
   - key format: `ledger:ref:{refId}`
   - stores only `txnId`
   - uses negative cache for not-found responses because ref-id lookup may be externally reachable

This split is one of the clearest architectural decisions in the module: object cache for internal immutable-ish transaction reads, lightweight index cache for external ref-id lookup.

## Key Components

### Core services and orchestrators

- `LedgerServer`
  - application bootstrap and shared infrastructure registration

- `PostServiceImpl`
  - gRPC adapter for posting and querying transactions

- `AccountServiceImpl`
  - gRPC adapter for account creation

- `PostLedgerProcessor`
  - central posting workflow
  - owns request validation, Redis idempotency, account resolution, DB insert transaction, and post-commit idempotency completion

- `GetLedgerTxnProcessor`
  - central query workflow
  - owns lookup-mode routing and optional ledger-entry loading

- `CreateAccountProcessor`
  - central account-creation workflow

- `LedgerTxnStore`
  - cache-aware read model for transaction lookup

- `DefaultListener`
  - Kafka command ingress adapter

- `DefaultPublisher`
  - Kafka reply publisher

- `OutboxDispatcher`
  - scheduled retry trigger for pending outbox rows

### Data models

- `LedgerTxn`
  - table: `ledger_transactions`
  - fields: `txnId`, `referenceId`, `metadata`, `version`, inherited timestamps
  - uses MyBatis `@Version` for optimistic locking

- `LedgerEntry`
  - table: `ledger_entries`
  - fields: `entryId`, `txnId`, `assetId`, `accountId`, `amount`, `direction`

- `Account`
  - table: `accounts`
  - fields: `accountId`, `serviceId`, `referenceId`, `category`, `normalSide`, `ownerId`, `ownerType`, `assetId`, `accountStatus`

- `Asset`
  - table: `assets`
  - fields: `assetId`, `assetType`, `symbol`, `decimals`, `extraData`

- shared `Outbox`
  - reused from `common-util` for async reply persistence and retry

- proto request/response models
  - `CreateAccountRequestPb`, `CreateAccountReplyPb`
  - `PostTransactionRequestPb`, `PostTransactionReplyPb`
  - `GetTxnByIdRequestPb`, `GetTxnByRefIdRequestPb`, `GetTxnReplyPb`
  - `LedgerTransactionPb`, `LedgerEntryPb`
  - common `ErrorPb` and `EventEnvelopePb`

### External dependencies

- MySQL
  - primary source of truth for accounts, assets, ledger transactions, ledger entries, and outbox rows

- Redis
  - idempotency state
  - ledger read caches
  - negative cache for `referenceId` misses

- Redisson
  - Lua-backed CAS operations for versioned cache and idempotency release
  - configured as single-server Redis in `redisson.yml`

- Kafka
  - inbound topic: `wallet.ledger.command.posting`
  - outbound topic: `ledger.wallet.reply.posting`
  - DLQ naming inherited from `common-util`: `<topic>.dlq`

- gRPC / protobuf
  - synchronous API surface for account creation and ledger posting/query

- Spring Boot
  - application runtime, configuration binding, validation, scheduling

- MyBatis-Plus
  - ORM mapper layer, optimistic locking, enum persistence

## Assumptions and boundaries

- MySQL DDL is not in the analyzed scope, so unique-key assumptions are inferred from `insertIgnore(...)` usage:
  - `accounts` is expected to reject duplicate business identity
  - `ledger_transactions` is expected to reject duplicate ledger identity, likely around `referenceId` and/or `txnId`

- Column naming is inferred from entity fields, `@TableName`, and proto comments. No mapper XML or schema files were analyzed.

- `LedgerTxnStore.updateMetadataByPk(...)` is a real write path with cache tombstoning, but no analyzed service entry point invokes it. It should be treated as an available extension point, not an active user-facing flow.

- The wallet-side producer/consumer is not in scope. Its existence is inferred from topic names, `commandId` usage, and `ServiceId.WALLET`.

- `post_service.proto` comments still refer to `extra_data`, while the Java domain model uses `metadata`. The service currently maps `LedgerTxn.metadata` into `LedgerTransactionPb.extraData`.
