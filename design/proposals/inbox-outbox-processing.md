# Proposal: Durable Inbox/Outbox Processing Refactor

> Status: rough alternative plan; not implemented or selected.
>
> Scope: replacing Kafka-driven business retries with durable local inbox processing for the Wallet-to-Ledger command and reply flow. This is an architectural refactor, not a small outbox optimization.

## Why consider it

The current listener performs business processing before acknowledging the Kafka record. Redelivery can therefore reconstruct the same Ledger reply, which makes reply identity and duplicate-outbox comparison part of recovery.

A durable inbox changes the ownership boundary. Kafka delivery is acknowledged after the service has stored the request locally; a database worker then owns execution and retry. Duplicate Kafka delivery is absorbed at inbox insertion rather than repeatedly invoking the business workflow.

## High-level flow

```text
Kafka record
    → insert or find inbox row
    → acknowledge Kafka record
    → inbox worker claims row
    → execute/recover business processing
    → create reply outbox and advance inbox state
    → outbox publisher sends reply
```

The acknowledgement after inbox insertion is safe only if a durable worker continuously claims and recovers unfinished inbox rows. A process crash after acknowledgement must leave enough state for another worker to resume the request.

## Two transaction-boundary variants

### Variant A — Atomic business, reply outbox, and inbox completion

In one database transaction, the worker:

1. applies Ledger business changes;
2. inserts the reply outbox row;
3. marks the inbox row completed.

This gives the simplest recovery rule: either all three changes commit or the inbox remains retryable. It also couples reply-outbox persistence to the business transaction, which differs from the current accepted Ledger boundary where reply failure does not roll back successful business work.

### Variant B — Durable business result, then reply materialization

To preserve the current decoupling intent:

1. commit the Ledger business result and advance the inbox to `BUSINESS_COMPLETED` in one transaction;
2. have a later retryable stage create the reply outbox and advance the inbox to `REPLY_CREATED`;
3. publish the durable outbox normally.

Conceptual states:

```text
RECEIVED → PROCESSING → BUSINESS_COMPLETED → REPLY_CREATED
                              ↘ terminal business outcome
```

This variant does not make reply persistence atomic with business changes, but it makes the accepted gap explicit, durable, queryable, and recoverable. It is therefore better aligned with the current Ledger design decision, at the cost of another state transition and worker responsibility.

Neither variant is selected yet.

## Reliability behavior

| Failure window | Expected recovery direction |
|---|---|
| DB failure before inbox insert commits | Do not acknowledge Kafka; broker delivery remains responsible for retry. |
| Crash after inbox commit but before Kafka acknowledgement | Kafka may redeliver; inbox uniqueness finds the existing row and prevents another logical intake. |
| Crash after Kafka acknowledgement but before business processing | The inbox row remains claimable by a worker. |
| Worker crashes during an uncommitted business transaction | Transaction rollback plus an expiring claim makes the inbox row retryable. |
| Variant A commits, then worker crashes | Completed inbox state and reply outbox are both durable. |
| Variant B commits business but not reply creation | `BUSINESS_COMPLETED` identifies the exact recovery backlog for reply materialization. |
| Broker accepts reply but outbox finalization fails | The reply may be published again; Wallet must still tolerate duplicate delivery. |

The architecture does not create end-to-end exactly-once messaging. It provides durable handoff and database-controlled processing retries while retaining duplicate-permitting outbox publishing.

## Identity and idempotency implications

- Inbox uniqueness should absorb redelivery of the same source event. The exact uniqueness scope—such as consumer identity plus source `event_id`—must be defined before implementation.
- Different source event IDs that represent the same business request still require Ledger's domain/database idempotency. An inbox is not a replacement for business idempotency.
- One successfully materialized reply outbox row keeps one event ID across all publishing retries.
- Wallet remains effect-idempotent because outbox delivery can still duplicate a successful reply.
- Malformed records that cannot provide a trustworthy inbox identity need a separate poison-message/DLT policy.

## Relationship to unique reply UUIDs

The smaller [unique reply-event proposal](ledger-reply-event-identity.md) allows repeated source processing to create multiple replies and moves logical convergence to Wallet. An inbox instead prevents the same source event from repeatedly entering the logical workflow.

| Option | Primary strategy | Main consequence |
|---|---|---|
| Unique UUID per persisted reply | Tolerate repeated reply creation | Smaller change; more duplicate rows/messages; Wallet owns convergence. |
| Durable inbox/outbox | Deduplicate intake and retry from DB state | Broader refactor; fewer reconstructed replies; adds durable worker/state lifecycle. |

If the inbox refactor is selected, generating a normal unique ID for its one materialized reply remains reasonable, but “one new reply for every source retry” is no longer the intended behavior. The inbox would make most of that UUID proposal's duplicate-tolerance path unnecessary.

## Major costs and open decisions

- Inbox schema, payload retention, uniqueness scope, and cleanup policy.
- Claim ownership, lease expiry, retry backoff, exhaustion, and terminal states.
- Ordering and concurrency for multiple inbox rows sharing one Wallet transaction.
- Whether Variant A's atomicity or Variant B's business/reply decoupling is preferred.
- Durable representation of the business result needed to reconstruct a reply in Variant B.
- Valid business-failure handling versus poison-message and infrastructure-failure DLT behavior.
- Metrics, alerts, re-drive tooling, and operator recovery for stuck rows.
- Latency and capacity impact of an additional durable queue and worker stage.

This alternative is more promising as a long-term reliability architecture because it addresses repeated processing at intake. It should be evaluated as a separate consistency refactor rather than silently folded into the current [Kafka/outbox reliability design](../infrastructure/kafka-outbox-reliability.md).
