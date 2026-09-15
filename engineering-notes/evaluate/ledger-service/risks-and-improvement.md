# Ledger Service Risks and Improvements

## Scope

This note is based on active code in:

- `ledger-service/src/main/java`
- `ledger-service/src/main/resources/application.yml`
- `grpc-proto/ledger-proto/src/main/proto`
- the shared code paths from `common-util` that ledger-service directly depends on

It excludes tests, log config, and generated proto Java code.

## Risks Table

| ID | Risk | Evidence in code | Impact | Level | Suggested improvement |
|---|---|---|---|---|---|
| LS-1 | IDs are generated locally with only time offset plus process-local sequence, so multi-instance deployments can collide. | `IdGenerator.generateLedgerTxnId()`, `generateLedgerEntryId()`, `generateAccountId()`, and `generateEventId()` use `System.currentTimeMillis() - START_TIME` plus a static `AtomicLong seq`. There is no node id, shard id, or DB sequence. | Two service instances can generate the same ids, causing duplicate-key failures or cross-entity confusion under horizontal scale. | High | Replace `IdGenerator` with a distributed-safe scheme such as DB sequences, UUIDv7, or Snowflake-style ids with worker bits. |
| LS-2 | Kafka error classification is likely inverted for business vs internal failures. | In `DefaultListener.handlePostLedger(...)`, any non-`ERROR_OK` reply enters exception logic. If the code is not `ERROR_INTERNAL`, it throws `RetriableException`; if it is `ERROR_INTERNAL`, it throws `AbnormalProtoDataException`, which is configured as non-retriable in `application.yml`. | Business errors like `ACCOUNT_NOT_FOUND` or `REQUEST_HASH_CONFLICT` can retry and poison the topic, while internal failures can go directly to DLQ without retry. | High | Separate business-failure replies from transport failures. Publish reply events for business failures and reserve retries for infra/transient exceptions only. |
| LS-3 | Posting request validation is incomplete for identifiers. | `PostLedgerProcessor.reqPrecheck(...)` validates amount and balance, but does not validate `referenceId`, `accountRef`, or `assetId` as non-empty. `CreateAccountProcessor.validateReq(...)` validates enums, but not `referenceId`, `assetId`, or `ownerId` emptiness. | Empty or malformed identifiers can enter idempotency keys, MySQL rows, and Kafka payloads if the DB schema does not reject them. | High | Add explicit required-field validation for all business identifiers before any Redis or DB interaction. |
| LS-4 | Query responses return internal `accountId` in a field named `accountRef`. | `PostLedgerProcessor` persists resolved internal `accountId` in `LedgerEntry`. `PbConverter.convertToLedgerEntryPbs(...)` then sets `LedgerEntryPb.accountRef = entry.getAccountId()`. | Consumers can misinterpret the field and feed an internal account id back into APIs that expect an external account reference. This is a contract inconsistency, not just a naming issue. | High | Either persist the original external account reference alongside `accountId`, or rename the protobuf field to match the actual payload semantics. |
| LS-5 | Posting resolves accounts by `referenceId` only and does not verify that the account matches the entry asset or other dimensions. | `AccountRepository.getAccountIdInRef(...)` selects only `accountId` and `referenceId`. `PostLedgerProcessor` then creates each `LedgerEntry` with `entryPb.getAssetId()` and the resolved `accountId`, with no cross-check against the account's `assetId`, `serviceId`, `ownerType`, or `ownerId`. | A caller can potentially pair a valid account reference with the wrong asset or wrong account attributes, creating semantically invalid journal entries. | High if `referenceId` is not globally unique; Medium otherwise | Resolve full account records for posting and validate that each entry's asset and business dimensions match the target account before insert. |
| LS-6 | Immediate Kafka publishing blocks listener and retry threads. | `DefaultPublisher.publish(...)` does `kafkaTemplate.send(...).get(3, TimeUnit.SECONDS)`. `DefaultListener` uses it on the Kafka consumer path, and `OutboxRetryHandler` uses the same publisher on retry. Listener concurrency is only `3`, and outbox page limit is `2`. | Under broker latency, throughput collapses because consumer and retry workers block synchronously on each send. | Medium-High | Switch to async send with callbacks/futures, and decouple reply publication from the inbound consumer thread. Keep the outbox as the durability boundary. |
| LS-7 | Freshly committed transactions can remain temporarily invisible by `referenceId` when negative-cache cleanup fails. | After a successful post, `PostLedgerProcessor` calls `LedgerTxnStore.cleanNegativeCacheAfterInsert(...)` outside the DB transaction. The method logs failure but does not retry or write-through a positive ref cache entry. Negative ref TTL is `30s` in `application.yml`. | A committed transaction can still return not found on `getTxnByRefId(...)` until the negative cache expires. | Medium | After commit, write the positive `refId -> txnId` cache entry directly, and treat negative-cache delete failure as a retryable cache-maintenance task instead of a log-only event. |
| LS-8 | Read amplification for entry loading is configured very aggressively low. | `LedgerEntryRepository.getByTransactionId(...)` pages by `id > lastId` with `defaultBatchSize`, and `application.yml` sets `app.db.query.default-batch-size: 3`. | Transactions with many entries cause many small DB round trips, which is especially expensive for historical query paths. | Medium | Raise the batch size materially, or make it endpoint-specific. If typical txn sizes are bounded, a single ordered select by `txn_id` may be simpler and faster. |
| LS-9 | The stable request hash is only a 64-bit Murmur-derived value, which is weak for a financial idempotency discriminator. | `PostLedgerProcessor.getReqStableHash(...)` builds a canonical string and passes it to `StableHashHelper.stableHash(...)`, which returns `Hashing.murmur3_128().hashString(...).asLong()`. | A collision is unlikely but not impossible. In a fintech ledger flow, a false hash match could incorrectly treat two different payloads as the same request. | Medium | Store a stronger digest, such as full 128-bit output or SHA-256 over a canonical serialization. |
| LS-10 | Duplicate account creation returns success without checking semantic equality of the existing row. | `CreateAccountProcessor.createAccount(...)` returns `replySuccess("account already exists")` whenever `insertIgnore(...) == 0`, with no read-back comparison of the stored row against the requested attributes. | If the duplicate key is triggered by the same business key but different attributes, the API can report success for a mismatched account definition. | Medium | On duplicate insert, load the existing account and compare the defining fields before returning idempotent success. |

## Suggested Improvements

### Architecture

- Split async command handling into business failure vs transport failure.
  - Evidence: `DefaultListener.handlePostLedger(...)` currently turns every non-success reply into an exception path.
  - Change: always create/publish a reply event for business outcomes, and only throw for parsing, broker, or storage failures.

- Replace local time-plus-sequence id generation.
  - Evidence: all ids come from `IdGenerator` with one process-local `AtomicLong`.
  - Change: use a deployment-safe id source that remains unique across nodes and restarts.

- Tighten account identity on posting.
  - Evidence: posting currently resolves accounts by reference only.
  - Change: resolve full account rows and validate `assetId`, owner dimensions, and service scope before building `LedgerEntry` rows.

- Fix the query contract mismatch around `accountRef`.
  - Evidence: replies currently expose internal `accountId` under an external-reference field name.
  - Change: either store both fields or change the protobuf contract to match what the service can actually return.

### Performance

- Remove synchronous `.get(3s)` publishing from listener and retry threads.
  - Evidence: `DefaultPublisher.publish(...)` blocks, and it is used from both `DefaultListener` and `OutboxRetryHandler`.
  - Change: use async send completion and finalize the outbox row from the callback or from a dedicated publisher worker.

- Increase `LedgerEntryRepository` batch size.
  - Evidence: current batch size is `3`, which is too low for ledger-style multi-entry transactions.
  - Change: set a materially larger default or branch between small and large transaction query paths.

- Pre-populate the positive ref cache after successful posting.
  - Evidence: the service currently only deletes negative ref cache.
  - Change: write `refId -> txnId` immediately after commit to reduce read-after-write misses.

### Reliability

- Add strict non-empty validation for all identifiers.
  - Evidence: posting and account creation both validate enums but not all string business keys.
  - Change: reject empty `referenceId`, `assetId`, `accountRef`, and `ownerId` before any side effects.

- On duplicate account insert, verify the existing row before returning success.
  - Evidence: `CreateAccountProcessor` currently assumes any duplicate means safe idempotency.
  - Change: compare the persistent row with the request and return a conflict when the payload differs.

- Strengthen idempotency hashing.
  - Evidence: current hash stores only a 64-bit Murmur-derived long string.
  - Change: use a stronger digest so request equality is not relying on a short non-cryptographic collision space.

- Add metrics around cache-maintenance fallbacks.
  - Evidence: `LedgerTxnStore.cleanNegativeCacheAfterInsert(...)` failure is log-only.
  - Change: count failed cache cleanup separately from DB success so read-after-write visibility issues can be detected quickly.

## Assumptions

- The exact MySQL unique constraints are not in scope, so risks around duplicate insert semantics are inferred from repository usage, not proven from DDL.
- The account-resolution risk in `PostLedgerProcessor` is highest if `accounts.reference_id` is not globally unique across assets or account dimensions. The code does not prove that uniqueness either way.
- The service is assumed to run in more than one instance for the ID-collision risk to matter. In single-instance development it may never surface.
