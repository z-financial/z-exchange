# Common Util and Common Proto Architecture

## Scope

This summary is based on:

- `common-util/src/main/java`
- `common-util/src/main/resources/script/lua`
- `grpc-proto/common-proto/src/main/proto`

It excludes test code, log config, and generated proto Java code.

## Architecture Summary

### Module layout

1. `grpc-proto/common-proto`
   - Defines the cross-service wire contracts:
     - `common.ErrorPb` and `ErrorCodePb` in `error.proto`
     - `common.EventEnvelopePb` in `event_envelope.proto`
   - `ErrorPb` is the common error envelope with `code`, `message`, and `detail`.
   - `EventEnvelopePb` is the common Kafka envelope with `event_id`, `command_id`, `event_type`, raw `payload`, and `occurred_at`.

2. `common-util`
   - A shared infrastructure library, not an executable service.
   - The code groups into:
     - `component`: JSON wrapper
     - `db`: MyBatis-Plus base repository, timestamps, transaction helper
     - `redis.cache`: codecs, clients, cache strategies, Lua-backed version CAS
     - `redis.idemp`: Redis idempotency claim/complete/release logic
     - `kafka`: listener container wiring, retry/DLQ policy
     - `outbox`: outbox entity, repository, retry engine
     - `utils`: result wrapper, enum mapper, TTL/hash/time/token helpers

### Responsibilities

`common-util` is the shared "plumbing" layer used by business services:

- DB infrastructure
  - `DbBaseRepository` wraps MyBatis `BaseMapper` and converts duplicate-key writes into `0` with `insertIgnore`.
  - `CommonDbComponentRegister` enables MyBatis-Plus optimistic locking.
  - `AutofillMetaObjectHandler` auto-fills `createdAt` and `updatedAt`.
  - `DbTransactionHelper` rolls transactions back on both thrown exceptions and failed `Result<?>`.

- Cache infrastructure
  - `ValueCodec` encodes cache values as `marker:content`.
  - `VersionCodec` encodes versioned values as `version|marker:content`.
  - `CacheType` markers are explicit: `S`, `J`, `T`, `N`.
  - `SimpleRedisSupport` handles stable values and negative cache.
  - `VersionedRedisSupport` handles CAS-style versioned writes and tombstones.

- Redis idempotency
  - Keys are `idemp_k:{service}:{scope}:{idempId}` through `CommonIdempHelper.idempKey`.
  - Values are compact state strings such as `P:{hash}:{token}` or `A:{hash}:{token}:{content}`.
  - `IdempRedisClient` supports claim-if-absent, mark-done, read, and release-if-owned.

- Kafka infrastructure
  - `CommandContainerRegister` creates a manual-ack listener container with concurrency `3`.
  - `KafkaErrorHandlingRegister` retries failed records with `StairStepBackOff`, then routes them to `<topic>.dlq`.

- Outbox infrastructure
  - `OutboxRepository` manages outbox claiming/finalization in MySQL.
  - `OutboxRetryHandler` claims pending rows with `for update skip locked`, republishes them through `IPublisher`, and finalizes successful rows.

- Shared result/error model
  - `Result<T>` carries `success`, `value`, `errorCode`, and `errorDetail`.
  - `CommonErrorCode` maps library failures into common proto error codes, mostly `ERROR_INTERNAL`.

### Data flow

#### 1. Common event flow

`EventEnvelopePb` is the shared Kafka wrapper:

1. A service creates a business-specific proto payload.
2. The payload is wrapped in `EventEnvelopePb`.
3. `event_type` is kept as a string for loose coupling across services/languages.
4. Consumers parse the envelope first, then parse the raw `payload` according to `event_type`.

This is defined directly in `grpc-proto/common-proto/src/main/proto/com/exchange/proto/common/event_envelope.proto`.

#### 2. Cache read/write flow

The cache stack is layered:

1. Service-specific code calls a strategy abstraction such as `StableCacheAbstract` or `VersionCacheAsideAbstract`.
2. That abstraction delegates to `SimpleCacheClient` or `VersionCacheClient`.
3. The client delegates to `SimpleRedisSupport` or `VersionedRedisSupport`.
4. `ReadRedisSupport` loads raw Redis strings and decodes them through `ValueCodec` or `VersionCodec`.
5. Parse failures come back as `CommonErrorCode.PARSE_CACHE_ERROR`; the abstract strategies then try to self-heal by deleting the bad key.

For versioned writes, `VersionedRedisSupport` runs `set-if-absent-or-newer.lua`, so an older DB snapshot cannot overwrite a newer cache value or tombstone.

#### 3. Idempotency flow

`IdempRedisClient` implements a strict pending/done protocol:

1. `claimIdempIfAbsent` writes a pending Redis value with TTL.
2. Business code runs while holding the token.
3. `markIdempDone` overwrites the key with accepted state plus returned content.
4. `releaseIdempIfOwned` runs `release-idemp-if-owned.lua`, which deletes the key only when the stored value exactly matches the caller's pending token.

This avoids a late failure deleting another request's lock.

#### 4. Outbox retry flow

`OutboxRetryHandler.retry()` is the shared retry engine:

1. In a DB transaction, it selects eligible `PENDING` rows with `selectForClaimSkipLock(...)`.
2. It updates `next_attempt_at` and increments `attempt_count` to claim them.
3. Outside the claim transaction, it calls the service-provided `IPublisher.publish`.
4. Successful rows are batch-finalized to `SENT`.
5. Failed rows remain `PENDING` for later retry.

Important nuance from code:

- `OutboxStatus.DEAD` exists, but the analyzed retry code never transitions rows into `DEAD`.
- Rows whose `attempt_count` reaches `maxRetries` simply stop matching `selectForClaimSkipLock(...)`.

## Key Components

### Core infrastructure components

- `JsonParser`
  - thin wrapper around Spring's `ObjectMapper`
  - used by cache codecs to serialize/deserialize JSON payloads

- `DbBaseRepository<T, M>`
  - base repository used by service repositories
  - normalizes `insertIgnore` duplicate handling

- `AutofillMetaObjectHandler`
  - central timestamp policy for `BaseEntity`

- `SimpleRedisSupport` / `VersionedRedisSupport`
  - Redis IO layer behind the higher-level cache strategies
  - `VersionedRedisSupport` is the critical piece for version-CAS writes and tombstones

- `IdempRedisClient`
  - shared Redis idempotency client backed by Redisson Lua execution

- `KafkaErrorHandlingRegister`
  - shared Kafka retry and DLQ policy

- `OutboxRepository`
  - shared MySQL repository for the `outbox` table

- `OutboxRetryHandler`
  - shared scheduled retry implementation used by services that publish from the outbox table

### Data models

- `ErrorPb` / `ErrorCodePb`
  - common error contract across RPC and event workflows

- `EventEnvelopePb`
  - common Kafka message envelope

- `Result<T>`
  - internal library result wrapper

- `Outbox`
  - persistent event record with:
    - routing fields: `destination`, `partitionKey`
    - business identity: `eventId`, `commandId`
    - operational state: `outboxStatus`, `attemptCount`, `lastAttemptAt`, `nextAttemptAt`, `finalizedAt`
    - payload storage: `payload`, optional `metadata`

- `BaseEntity`
  - shared persistence base with `createdAt` and `updatedAt`

- `CacheValueInfo<T>`
  - decoded cache payload plus `cacheType` and optional `version`

- `IdempKey` / `IdempValue`
  - parsed Redis idempotency key/value structures

### External dependencies

- Spring Boot
  - bean registration, configuration properties, validation, Redis and Kafka integration

- MyBatis-Plus
  - base mapper model, optimistic locking, enum persistence, auto-fill hooks

- MySQL driver
  - runtime backing store for service repositories and outbox rows

- Redis
  - cache storage and idempotency state storage

- Redisson
  - Lua script execution for atomic CAS/release behavior

- Spring Kafka
  - listener container, retry handler, DLQ recoverer, producer integration

- Jackson
  - JSON cache serialization through `JsonParser`

- Guava
  - currently used for `EnumMapper` internals and `StableHashHelper`

- Caffeine
  - declared in `common-util/pom.xml`, but there is no active usage in the analyzed `src/main/java` code

## Assumptions and boundaries

- `common-util` is a library module. It has no `main` class or standalone runtime entry point in the analyzed scope.
- Service adoption is explicit: a service must import the module and scan/register its configuration classes or packages. `ledger-service` does this in `LedgerServer`.
- The inactive files `JsonVersionedCacheRedisClient.java` and `BaseCacheAbstract.java` are fully commented out, so they are not part of the active architecture.
- DDL is not in scope, so outbox indexes/constraints are inferred from repository behavior rather than confirmed from schema files.
