# Implementation Tasks — Phase-One ID Generator Integration

Source: [SPEC](./spec.md) | Jira epic: [ZEX-1](https://szy2425973547.atlassian.net/browse/ZEX-1)

## Task 1 — Provide the managed generator runtime

Jira story: [ZEX-8](https://szy2425973547.atlassian.net/browse/ZEX-8)

Integrate `z-id-generator` as a Spring-managed, process-wide runtime shared by each service's threads and entity
types.

Done when:

- Wallet and Ledger use separate configured domains and one generator instance per process.
- Worker acquisition, renewal, ownership loss, and shutdown follow the generator lifecycle.
- Metadata and leases use a dedicated bounded Hikari pool that Spring closes.
- Generation after initialization performs no database call per ID.
- The generator artifact and compatible Java 21 dependency versions are fixed.

## Task 2 — Migrate Wallet ID generation

Jira story: [ZEX-9](https://szy2425973547.atlassian.net/browse/ZEX-9)

Replace every scoped Wallet ID allocation path with the injected Wallet generator.

Done when:

- Wallet, transaction, reservation, and action creation use the injected Wallet generator.
- Existing schemas, field types, protobufs, caches, and API shapes remain unchanged.
- String fields store the canonical positive decimal form of the generated `long`.
- Accepted idempotent replay returns persisted IDs without allocating replacements.
- Existing balance guards, reservation invariants, and transaction boundaries remain intact.

## Task 3 — Migrate Ledger ID generation

Jira story: [ZEX-10](https://szy2425973547.atlassian.net/browse/ZEX-10)

Replace every scoped Ledger ID allocation path with the injected Ledger generator.

Done when:

- Account, transaction, and entry creation use the injected Ledger generator.
- All IDs are within `1..Long.MAX_VALUE`; consumers neither decode nor order by their internals.
- Accepted replay creates no replacement transaction or entry IDs.
- Allocation or duplicate failure rolls back the complete double-entry transaction.
- Ledger contains no process-local wall-clock or `AtomicLong` generator path.

## Task 4 — Stabilize request and reply event identity

Jira story: [ZEX-11](https://szy2425973547.atlassian.net/browse/ZEX-11)

Migrate request-event allocation while preserving stable publication, retry, and reply identities.

Done when:

- A Wallet request event receives one generated `event_id` in the Wallet business transaction.
- Immediate publication, outbox retry, redelivery, and correlation reuse the stored `event_id`.
- The Wallet transaction ID remains `command_id`, request reference, and Kafka partition key.
- Ledger reply IDs consume no generated value and are deterministic from request ID and outcome.
- Publishers never replace a persisted event ID.

## Integration Task — Verify fail-closed cutover

Jira task: [ZEX-12](https://szy2425973547.atlassian.net/browse/ZEX-12)

Verify that the completed generator integration rejects unsafe generation, exposes actionable failures, and
contains no legacy or fallback generator path.

Done when:

- Invalid configuration, database failure, worker exhaustion, acquisition failure, or unsafe ownership blocks ID writes.
- No wall-clock, `AtomicLong`, selectable fallback, or regeneration-after-partial-write path remains.
- Failure signals distinguish acquisition, renewal, ownership, clock rollback, sequence exhaustion, and allocation.
- Concurrency tests cover multiple threads, at least two JVMs in one domain, and safe restart without duplicates.
- Allocation and forced-duplicate tests prove full rollback with no completed idempotency result or orphaned outbox.
