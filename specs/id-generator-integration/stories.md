# Agile Stories — Phase-One ID Generator Integration

Source: [spec.md](./spec.md)

## Story 1 — Provide the managed generator runtime

As a service developer, I want `z-id-generator` managed by Spring so that every process has one safe, shared ID
source without owning infrastructure manually.

Acceptance criteria:

- Wallet and Ledger use separate configured domains and one generator instance per process.
- Worker acquisition, renewal, ownership loss, and shutdown follow the generator lifecycle.
- Metadata and leases use a dedicated bounded Hikari pool that Spring closes.
- Generation after initialization performs no database call per ID.
- The generator artifact and compatible Java 21 dependency versions are fixed.

## Story 2 — Migrate Wallet ID generation

As the Wallet service, I want all scoped Wallet IDs allocated by the managed generator so that they remain unique
across threads, instances, entity types, and safe restarts.

Acceptance criteria:

- Wallet, transaction, reservation, and action creation use the injected Wallet generator.
- Existing schemas, field types, protobufs, caches, and API shapes remain unchanged.
- String fields store the canonical positive decimal form of the generated `long`.
- Accepted idempotent replay returns persisted IDs without allocating replacements.
- Existing balance guards, reservation invariants, and transaction boundaries remain intact.

## Story 3 — Migrate Ledger ID generation

As the Ledger service, I want account, transaction, and entry IDs allocated by its managed generator so that
concurrent processing cannot produce duplicate accounting identities.

Acceptance criteria:

- Account, transaction, and entry creation use the injected Ledger generator.
- All IDs are within `1..Long.MAX_VALUE`; consumers neither decode nor order by their internals.
- Accepted replay creates no replacement transaction or entry IDs.
- Allocation or duplicate failure rolls back the complete double-entry transaction.
- Ledger contains no process-local wall-clock or `AtomicLong` generator path.

## Story 4 — Stabilize request and reply event identity

As the messaging layer, I want request IDs to survive every delivery attempt and reply IDs to remain deterministic
so that at-least-once delivery does not create duplicate effects.

Acceptance criteria:

- A Wallet request event receives one generated `event_id` in the Wallet business transaction.
- Immediate publication, outbox retry, redelivery, and correlation reuse the stored `event_id`.
- The Wallet transaction ID remains `command_id`, request reference, and Kafka partition key.
- Ledger reply IDs consume no generated value and are deterministic from request ID and outcome.
- Publishers never replace a persisted event ID.

## Integration Task — Verify fail-closed cutover

Verify that the completed generator integration rejects unsafe generation, exposes actionable failures, and
contains no legacy or fallback generator path.

Acceptance criteria:

- Invalid configuration, database failure, worker exhaustion, acquisition failure, or unsafe ownership blocks ID writes.
- No wall-clock, `AtomicLong`, selectable fallback, or regeneration-after-partial-write path remains.
- Failure signals distinguish acquisition, renewal, ownership, clock rollback, sequence exhaustion, and allocation.
- Concurrency tests cover multiple threads, at least two JVMs in one domain, and safe restart without duplicates.
- Allocation and forced-duplicate tests prove full rollback with no completed idempotency result or orphaned outbox.
