# Ledger Service Critical Logic Analysis

## Scope

This note focuses on critical runtime logic in:

- `ledger-service/src/main/java`
- `ledger-service/src/main/resources/application.yml`
- `grpc-proto/ledger-proto/src/main/proto`
- the shared runtime pieces that the ledger flow directly depends on in `common-util/src/main/java`

It excludes tests, log config, and generated proto Java code.

## 1. Transaction Flow

### 1.1 Post transaction flow is centralized in one processor

Both entrypoints reuse the same posting logic:

- gRPC path: `PostServiceImpl.postTransaction(...) -> PostLedgerProcessor.postTransaction(...)`
- Kafka path: `DefaultListener.handlePostLedger(...) -> PostLedgerProcessor.postTransaction(...)`

That means request validation, idempotency, account resolution, and DB writes are not duplicated across sync and async ingress.

### 1.2 The posting sequence is explicit and ordered

`PostLedgerProcessor.postTransaction(...)` executes in this order:

1. Generate an ownership token with `TokenHelper.generateToken(GlobalServiceId.LEDGER.name())`.
2. Run `idempClaimAndReqValidate(...)`.
3. If the request is already accepted, return the existing `txnId` without touching MySQL.
4. Generate a new ledger transaction id and ledger entry ids with `IdGenerator`.
5. Resolve every incoming `LedgerEntryPb.accountRef` to an internal `Account.accountId` through `AccountRepository.getAccountIdInRef(...)`.
6. Persist the transaction and entries in one DB transaction via `DbTransactionHelper.executeWithResult(...)` with `PROPAGATION_REQUIRED`.
7. After DB commit succeeds:
   - clean negative `refId` cache through `LedgerTxnStore.cleanNegativeCacheAfterInsert(...)`
   - mark Redis idempotency state as done via `IdempRedisClient.markIdempDone(...)`
8. On failure, call `releaseIdempIfOwned(...)` and return an error reply.

### 1.3 Posting validation is business-specific, not transport-level

`reqPrecheck(...)` in `PostLedgerProcessor` does more than null/enum checks:

- rejects zero-amount entries
- maps protobuf direction into domain `Direction`
- requires at least one debit and one credit
- aggregates debit and credit totals by `assetId`
- rejects any request where debit total and credit total differ for any asset

This makes the posting unit effectively a balanced multi-entry journal transaction, not an arbitrary list of entries.

### 1.4 The DB write is atomic for the journal itself

Inside `DbTransactionHelper.executeWithResult(...)`, the processor does:

- `ledgerTxnRepository.insertIgnore(ledgerTxn)`
- `ledgerEntryMapper.batchInsert(entries)`

If either step returns a failed `Result` or throws, `DbTransactionHelper` marks the Spring transaction rollback-only.

So the code intends the journal header (`ledger_transactions`) and journal lines (`ledger_entries`) to commit or roll back together.

### 1.5 Account creation is intentionally simpler than posting

`CreateAccountProcessor.createAccount(...)`:

- validates enums and the category/normal-side pair
- checks asset existence in `assets`
- inserts into `accounts` with `insertIgnore(...)`

There is no explicit Spring transaction around account creation because the flow is a single write after a single lookup.

## 2. Concurrency and Consistency

### 2.1 Request-level concurrency is controlled in Redis first

`PostLedgerProcessor` uses Redis idempotency before touching MySQL:

- key: `idemp_k:ledger:post_ledger:{referenceId}`
- pending value format: `P:{hash}:{token}`
- accepted value format: `A:{hash}:{token}:{txnId}`

This logic comes from `CommonIdempHelper` and `IdempRedisClient`.

Practical effect:

- one in-flight owner for a given `referenceId`
- same request body can observe existing accepted state and return the prior `txnId`
- different request bodies on the same `referenceId` are rejected by hash comparison as `REQUEST_HASH_CONFLICT`

### 2.2 Redis is the fast gate, but MySQL is the backstop

The processor does not trust Redis as the only source of truth:

- if Redis contains malformed idempotency data, it force-deletes the bad value and falls back to MySQL lookup
- if Redis is absent but `ledger_transactions` already contains the `referenceId`, it marks Redis done and returns the existing `txnId`

This is the main consistency safety net for cases like Redis expiry, Redis parse failure, or process crash after DB commit.

### 2.3 There is a real post-commit gap, and the code compensates for it

The DB transaction finishes before these two follow-up steps:

- negative cache cleanup
- `markIdempDone(...)`

So there is a window where:

- MySQL already contains the transaction
- Redis may still show `PENDING`, may be empty, or may still hold stale negative cache

The design compensates with the DB backstop described above. Repeated requests can still find the committed transaction by `referenceId`.

### 2.4 Release of idempotency ownership is compare-and-delete, not blind delete

`releaseIdempIfOwned(...)` uses `release-idemp-if-owned.lua` through Redisson.

That script deletes the Redis key only when the current stored value exactly matches the caller's pending token. This prevents one failed request from deleting another concurrent owner's key.

### 2.5 Cache consistency differs by cache type

`LedgerTxnStore` uses two cache strategies because the consistency needs are different:

- `LedgerTxnCache`
  - versioned object cache
  - full `LedgerTxn` payload
  - CAS write through `set-if-absent-or-newer.lua`
  - supports tombstones after metadata updates

- `LedgerRefCache`
  - stable string cache
  - maps `referenceId -> txnId`
  - supports negative caching
  - no CAS because the mapping is assumed stable once created

This is a deliberate split between mutable-ish object state and stable index state.

### 2.6 Optimistic locking exists, but only on the metadata update path

`LedgerTxn.version` is annotated with `@Version`, and `LedgerTxnRepository.updateMetadataByPk(...)` updates by primary key through MyBatis-Plus optimistic locking.

After a successful metadata update, `LedgerTxnStore.updateMetadataByPk(...)` writes a tombstone into the txn cache using the new version.

Important boundary:

- this is a real consistency mechanism in the codebase
- but no analyzed gRPC or Kafka entrypoint currently calls this method

### 2.7 Outbox retry concurrency is handled in SQL, not in memory

`OutboxRetryHandler.retry()` claims rows with:

- `select ... for update skip locked`
- `attempt_count < maxRetries`
- `next_attempt_at <= now`
- ordered scan by `id`

It then updates `next_attempt_at` and increments `attempt_count` inside the same DB transaction.

This lets multiple dispatcher instances avoid double-processing the same outbox rows, assuming the database supports `skip locked` semantics as used by the query.

### 2.8 Assumption on DB uniqueness

The code depends heavily on `insertIgnore(...)` semantics for:

- `ledger_transactions`
- `accounts`
- `outbox`

The exact unique indexes are not in the analyzed scope, so uniqueness guarantees are inferred, not proven from DDL.

## 3. Event Flow

### 3.1 Kafka ingress is envelope-based

`DefaultListener.onMessage(...)` receives raw bytes and parses `EventEnvelopePb` first.

Current dispatch behavior is narrow by design:

- supported event type: `POST_LEDGER`
- unsupported event type: throws `AbnormalProtoDataException`

The actual business payload for `POST_LEDGER` is `PostTransactionRequestPb`.

### 3.2 Async posting does not emit a reply event for every error

This is one of the most important logic differences between gRPC and Kafka entrypoints.

`PostLedgerProcessor.postTransaction(...)` always returns a `PostTransactionReplyPb`, even on business failure.

But `DefaultListener.handlePostLedger(...)` does not always publish that reply:

- if `reply.error.code == ERROR_OK`, it creates an outbox reply event
- otherwise it throws an exception instead of creating/publishing a reply event

Current result:

- gRPC callers receive immediate business errors in-band
- Kafka callers can cause retry or DLQ instead of getting a reply event carrying the business error

This is not an inference; it is the direct behavior of `handlePostLedger(...)`.

### 3.3 Reply publication uses an outbox-first reliability boundary

When async posting succeeds:

1. `OutboxHelper.fromPostTransactionReply(...)` converts the reply into an `Outbox` row.
2. The row is inserted with status `PENDING`.
3. The inbound Kafka record is acknowledged.
4. The service tries immediate publication through `DefaultPublisher`.
5. If immediate publish fails, the row stays in MySQL for scheduled retry.

This ordering matters:

- ack happens after durable outbox insert
- publish can fail without losing the ability to retry

### 3.4 Reply events are correlation-friendly

`OutboxHelper.fromPostTransactionReply(...)` sets:

- `eventId = generateEventId(commandId)`
- `commandId = envelope.commandId`
- `partitionKey = commandId`
- destination topic = `ledger.wallet.reply.posting`

`OutboxHelper.toEventEnvelope(...)` later wraps that into `EventEnvelopePb` and sets `occurred_at` using `LongTimeHelper.now()`.

So `commandId` is reused as both business correlation id and Kafka partition key for the reply stream.

### 3.5 Kafka retry and DLQ policy is split by exception class

`CommandContainerRegister` configures manual ack with concurrency `3`.

`KafkaErrorHandlingRegister` then applies:

- backoff: `1s, 1s, 1s`
- dead-letter topic: `<original-topic>.dlq`
- non-retriable exception list from `app.kafka.not-retriable-exceptions`

In `ledger-service`, `application.yml` sets `AbnormalProtoDataException` as non-retriable.

So the effective policy is:

- `AbnormalProtoDataException`: go directly to DLQ
- `RetriableException`: retry first, then DLQ after retries are exhausted

### 3.6 Outbox retry is intentionally decoupled from inbound Kafka retries

Inbound Kafka retry handles failures before the outbox row exists or before the listener can safely ack.

Outbox retry handles failures after the outbox row exists and the inbound message has already been acknowledged.

Those are two separate reliability stages, not one combined mechanism.

## 4. Cache Logic

### 4.1 The service uses four TTL classes from config

From `application.yml` and `CacheTtlStrategies`:

- txn data cache TTL: `120s`
- ref index cache TTL: `1h`
- tombstone TTL: `120s`
- negative cache TTL: `30s`
- jitter on data caches: `100ms`

Idempotency TTLs are separate:

- pending TTL: `120s`
- done TTL: `24h`
- idempotency jitter: `100ms`

### 4.2 Read path by transaction id

`LedgerTxnStore.getByTxnId(...)`:

1. check `LedgerTxnCache`
2. if cache hit, return immediately
3. if cache miss, query `LedgerTxnRepository.getByTxnId(...)`
4. if DB finds a row, write it back through versioned cache-aside

Design note from code comments:

- txn-id lookup intentionally does not use negative cache because it is considered mostly internal and low-risk

### 4.3 Read path by reference id

`LedgerTxnStore.getByRefId(...)` is more defensive:

1. check `LedgerRefCache` for `refId -> txnId`
2. if cache hit and value is blank/negative, return not found fast
3. if cache hit with txn id, load the real transaction through `getByTxnId(...)`
4. on miss, query `LedgerTxnRepository.getByRefId(...)`
5. if found, populate both caches
6. if not found, write negative cache

The negative cache exists specifically because the code comments treat `referenceId` lookup as potentially external and susceptible to random miss traffic.

### 4.4 Cache parse failures are treated as recoverable infra faults

Both `StableCacheAbstract.get(...)` and `VersionCacheAsideAbstract.get(...)` do the same thing on parse failure:

- log the error
- if the failure is `PARSE_CACHE_ERROR`, delete the bad Redis key
- do not block the main business flow

So cache corruption is handled as a recoverable performance problem, not as a correctness stop condition.

### 4.5 Negative cache cleanup is one-way after successful insert

After a successful posting commit, `PostLedgerProcessor` calls `LedgerTxnStore.cleanNegativeCacheAfterInsert(referenceId)`.

That only cleans the negative `referenceId` cache. The positive caches are not proactively filled here; they are filled lazily on later reads.

### 4.6 Query responses expose internal account ids, not original account refs

This comes from the write/read combination:

- write side stores `accountId` in `LedgerEntry`
- read side maps `LedgerEntry.accountId` into `LedgerEntryPb.accountRef`

So the query API is not reconstructing the original request's external account reference.

## 5. Result and Error Handling

### 5.1 The service has a hard boundary between infra results and service results

The runtime result container is `com.exchange.common.utils.result.Result<T>`, but ledger-service wraps it with its own helper class `com.exchange.app.ledger.result.Results` and its own `ErrorCode` enum.

This is important because `ledger-service.Results.getErrorCode(...)` throws if the embedded `errorCode` is not actually a ledger `ErrorCode`.

So service-layer code only safely propagates results that it has normalized into ledger error space.

### 5.2 Service methods usually degrade infra failures instead of surfacing them directly

Examples:

- cache read/write failures are logged and the code falls back to DB or continues
- invalid Redis idempotency data is deleted and the flow falls back to DB
- immediate Kafka publish failure leaves an outbox row for retry instead of failing the already-acked inbound command

The dominant pattern is: protect correctness first, preserve availability second, treat cache/messaging as recoverable when possible.

### 5.3 gRPC replies always use `ErrorPb`, including success

`PbErrorBuilder.build(...)` is used on both success and failure.

So a successful reply is not "absence of error"; it is an explicit `ErrorPb` carrying `ERROR_OK` plus optional detail text such as `success` or `already exists`.

### 5.4 Async error semantics are exception-driven, not reply-driven

For Kafka ingress, `DefaultListener` converts post results into exceptions to drive Spring Kafka retry/DLQ behavior.

That means the async path's error semantics are governed by:

- `ErrorCodePb` value in the generated reply
- listener-side branch logic
- exception class chosen (`RetriableException` vs `AbnormalProtoDataException`)
- Spring Kafka's retry configuration

### 5.5 Current listener error classification is probably provisional

`handlePostLedger(...)` contains a TODO that explicitly says retriable-error design needs redesign.

Current code path:

- any non-`ERROR_OK` reply enters exception logic
- non-`ERROR_INTERNAL` currently becomes `RetriableException`
- `ERROR_INTERNAL` currently becomes `AbnormalProtoDataException`

So the present retry classification should be read as current implementation behavior, not necessarily finalized business policy.

## Assumptions

- MySQL schema and indexes are not in scope, so duplicate-protection claims are inferred from `insertIgnore(...)` usage.
- The database is assumed to support the `for update skip locked` pattern used by `OutboxRepository.selectForClaimSkipLock(...)`.
- No caller for `LedgerTxnStore.updateMetadataByPk(...)` appears in the analyzed entrypoints, so the version/tombstone logic is analyzed as an available consistency mechanism, not as a currently exercised user flow.
