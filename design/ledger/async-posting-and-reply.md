# Ledger Asynchronous Posting and Durable Replies

**Design:** D-009
**Status:** Verified against the current implementation on 2026-09-09 (time-boxed pass)

**Module context:** [Ledger Service Overview](ledger-service-overview.md)

## Design in one minute

![Ledger asynchronous command and reply lifecycle](../assets/ledger-async-posting-reply-flow.svg)

Wallet sends posting commands through Kafka. Ledger parses the common event envelope, invokes the same `PostLedgerProcessor` used by its retained test-only gRPC endpoint, and turns the result into a durable reply outbox row. The source message is acknowledged only after the reply row is inserted or an equivalent existing row is verified. Reply publication is then attempted immediately, with eligible pending rows handled by the scheduled outbox dispatcher.

This separation keeps the Ledger journal transaction focused on the accounting write and makes the reply independently retryable. The shared transport machinery is described in [Kafka and Outbox Reliability](../infrastructure/kafka-outbox-reliability.md); this document owns Ledger's command policy, reply construction, identities, and Wallet-facing recovery assumptions.

## Responsibility boundary

Wallet owns the business transaction and constructs the accounting command. Ledger owns journal validation and persistence, classifies its posting result for Kafka handling, and materializes a Wallet reply. Kafka and the outbox permit repeated delivery while retries remain eligible, so both the Ledger posting effect and Wallet reply application must be idempotent.

The active Wallet-to-Ledger write integration is Kafka. `PostServiceImpl.postTransaction` remains useful for tests and direct processor exercise, but direct gRPC posting is an expired integration API and is not the supported cross-service production path.

## Command and identity model

The command arrives as `EventEnvelopePb` with an embedded `PostTransactionRequestPb` payload.

| Identity | Current source and role |
|---|---|
| Request `eventId` | Created by Wallet's command outbox; identifies one transport event and seeds the deterministic reply ID. |
| `commandId` | Wallet transaction ID; correlates command and reply and is the Kafka partition key in both directions. |
| Posting `referenceId` | Also the Wallet transaction ID; it is Ledger's durable business/idempotency identity. |
| Reply `eventId` | Success: `reply:{requestEventId}:success`; failure: `reply:{requestEventId}:error:{namespace}:{errorIdentity}`. |
| Ledger `txnId` | Created or replayed by Ledger and carried in a successful reply. |

Using `commandId` as the partition key keeps records for the same Wallet transaction on the same partition of each topic. It does not establish global ordering or ordering across the command and reply topics.

## Ledger command handling

`DefaultListener.onMessage` delegates the record bytes, headers, timestamp, and manual acknowledgment to the shared listener. The Ledger-specific `LedgerCommandMessageHandler` then:

1. accepts only `POST_LEDGER` envelopes;
2. parses the inner posting protobuf;
3. records the Kafka-ingress posting metric;
4. calls `PostLedgerProcessor.postTransaction`;
5. converts a successful result into a reply outbox, or classifies the failure for retry/reply handling.

Malformed outer envelopes cannot supply a trustworthy command identity and go to the DLT path. An identifiable envelope with an invalid event type or inner payload can be converted into a durable Ledger failure reply.

`REQUEST_IN_PROCESSING` is the only Ledger business result explicitly classified as retryable. Other returned posting failures become non-retryable service exceptions, allowing the common listener to construct and persist a failure reply. Unexpected exceptions are normalized to the configured Ledger server error at that boundary.

## Durable reply boundary

For a normal success or a materialized failure, `OutboxHelper` creates a `PENDING` row addressed to the Wallet reply topic. The row stores the reply event identity, command correlation, partition key, serialized `PostTransactionReplyPb`, and delivery state.

The common listener calls `OutboxRepository.insertWithClaimAndVerify` before acknowledging the source record:

- a new reply row is inserted;
- an existing row with the same intent is reused;
- a missing row after a zero-row insert is treated as retryable ambiguity;
- reuse of the same event ID with different intent is treated as a contract conflict and routed to DLT.

Only after a durable reply action exists does the listener request source acknowledgment. An acknowledgment failure adds a marker to the current Kafka headers. If Spring propagates that marker to the retry record, the later attempt acknowledges without repeating business work. Business idempotency and outbox verification remain the fallback because propagation of the mutated header is not established by an integration test.

After acknowledgment, the listener starts an asynchronous immediate publish. Producer or finalization failure is suppressed because the reply row is already durable. The scheduled `OutboxDispatcher` later claims eligible pending rows, republishes them, and conditionally marks broker-acknowledged rows `SENT`. Delivery may repeat, so Wallet must tolerate duplicate replies; no automatic re-drive exists after the row exhausts its retry budget.

## Transaction boundaries

Ledger deliberately uses two separate durability phases:

1. `PostLedgerProcessor` commits the `ledger_transactions` header and its complete `ledger_entries` batch.
2. After the processor returns, the listener inserts the reply outbox, or after a duplicate insert reads the existing row and verifies its intent, outside the journal transaction. There is no explicit transaction wrapping that multi-statement insert-or-verify sequence; a new-row insert is itself the durable statement boundary.

No database lock is held while publishing to Kafka, and a broker acknowledgment is not part of either durability phase. This avoids a distributed database/broker transaction and keeps journal locks short, at the cost of a window in which the accounting effect exists before its reply is durable.

The journal transaction and posting correctness are owned by [Posting and Accounting Correctness](posting-and-accounting-correctness.md). Request replay is owned by [Posting Idempotency and Replay](posting-idempotency-and-replay.md).

## Failure, retry, and recovery behavior

| Condition | Current behavior |
|---|---|
| `REQUEST_IN_PROCESSING` before the configured reply threshold | Rethrow for retry-topic delivery; do not acknowledge or persist a reply. |
| Retryable result at or above the reply threshold | Materialize a retry-exhausted Ledger failure reply, then acknowledge after it is durable. |
| Non-retryable posting or parseable command failure | Materialize an outcome-specific failure reply. |
| Outer-envelope parse failure | No reply identity is available; route toward DLT. |
| Reply insert is ambiguous or its database operation is retryable | Retry without acknowledging. |
| Reply event ID conflicts with different persisted intent | Route toward DLT. |
| Immediate reply publish or finalization fails | Leave the durable row eligible for scheduled outbox recovery. |
| Source acknowledgment fails after durable work | Retry acknowledgment; the marker path skips repeated business work when preserved. |
| DLT receives a record | Emit a DLT metric and structured log; no business re-drive occurs. |

The current retry counts, delays, dispatcher schedule, page size, and TTLs are pre-launch configuration snapshots rather than verified timing guarantees.

## Post-commit convergence gap

Ledger can commit its journal and then fail before a usable success reply is persisted or delivered. A surviving Redis `PENDING` value can also make immediate transport retries return `REQUEST_IN_PROCESSING` until the claim expires. Retry exhaustion or DLT observation does not repair the Wallet transaction.

The accepted recovery direction is Wallet-owned: a scheduled job will detect stuck Wallet transactions and repost the same logical Ledger request with the same business identity and payload. Ledger's reference uniqueness makes the accounting effect safe to retry. DLT and retry-exhaustion signals are operational alerts and metrics only, not the business-correctness mechanism. The Wallet recovery job is not implemented yet.

Reliable convergence also requires resolving the current reply-intent mismatch. Creation and idempotent replay use the same deterministic success event ID but can serialize different detail text. If a reply already exists, whole-payload equality can therefore classify a reconstructed logical success as conflicting intent. Alternative future directions are described in [Unique Ledger Reply Events and Wallet Outcome Convergence](../proposals/ledger-reply-event-identity.md) and [Durable Inbox/Outbox Processing](../proposals/inbox-outbox-processing.md).

## Observability

The listener records processing delay, duration, and bounded outcomes such as success, failure-replied, retryable, acknowledgment failure, and non-retryable. The DLT handler records only confirmed DLT delivery. Publisher and outbox metrics cover send results and delivery delay. Ledger business metrics distinguish Kafka ingress and posting outcome.

These signals diagnose transport and backlog behavior; they do not substitute for a Wallet query that identifies business transactions still awaiting Ledger convergence.

## Trade-offs

Separating the journal and reply transactions avoids holding the journal transaction open across reply construction or broker I/O and allows reply delivery to recover independently. It also means local atomicity alone cannot guarantee that every committed journal effect has a usable Wallet reply.

Deterministic reply IDs make redelivery cheap when reconstructed intent is byte-for-byte stable. Whole-payload comparison is stricter than the logical reply contract, so diagnostic payload differences can prevent otherwise valid convergence.

## Evidence and verification gaps

Focused Ledger tests cover success/failure reply construction, deterministic event identity, invalid inner payload/type, `REQUEST_IN_PROCESSING`, public error mapping, and retry-threshold validation. The shared listener suite covers durable-action-before-ack ordering, reply-insert decisions, retry thresholds, and acknowledgment recovery.

No real Kafka/MySQL test currently covers crash-after-journal-commit, reply-insert ambiguity/conflict, retry-to-failure-reply, retry-header propagation after acknowledgment failure, or end-to-end Wallet convergence. The time-boxed verification reran the hermetic handler/config tests but did not use a live broker or database.

## Required future work

1. Implement Wallet's scheduled stuck-transaction re-drive with unchanged Ledger business identity and payload.
2. Replace the current deterministic-ID/whole-payload conflict with a reply identity and Wallet convergence contract that survives successful Ledger replay.
3. Add real broker/database fault-injection tests for the split transaction and acknowledgment boundaries.
4. Define operational alerts for DLT delivery, exhausted pending outbox rows, reply age, and stuck Wallet transactions without making those signals the correctness dependency.
5. Validate required envelope identifiers before using them to create durable reply identities.
