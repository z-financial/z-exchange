# Phase-One Service-Scoped ID Generator Integration

Status: Proposed | Last updated: 2026-10-01

## 1. Context

Wallet and Ledger currently generate IDs with wall-clock and process-local `AtomicLong` helpers. They do not
coordinate between JVMs and reset after restart, allowing concurrent instances or overlapping restarts to collide.

`z-id-generator` generates 64-bit IDs in process and coordinates workers through MySQL leases. A process renews
its lease in the background and must stop when ownership cannot be proven. Because the system is not in
production, phase one is a clean cutover without legacy-data migration or algorithm fallback.

## 2. Decision and Scope

Wallet and Ledger shall replace their local generators with one managed `z-id-generator` instance per process,
shared by all entity types and threads. Each service has an independent domain; values are unique within the
owner service across types, instances, and restarts, but the same numeric value may exist in both services.

Wallet-generated IDs cover wallets, transactions, reservations, actions, and Wallet-to-Ledger request events.
Ledger-generated IDs cover accounts, transactions, and entries. Existing Java, database, protobuf, cache, and
Kafka field types remain unchanged; string fields use `Long.toString(generatedId)`.

Phase one does not change caller-provided references or idempotency keys, owner/service/asset identifiers,
symbols, version columns, auto-increment storage or polling IDs, or deterministic Ledger reply IDs. It does not
introduce `internal_id`/typed `*_ref`, redesign schemas or transport envelopes, provide a remote generator, or
promise cross-service uniqueness, gaplessness, global ordering, or decodable timestamp/worker/sequence fields.
It does not redesign durable inbox processing or reply-outcome convergence, or replace IDs during replay.

## 3. Design

### 3.1 Ownership and worker leasing

```text
Wallet domain: all Wallet entity IDs and Wallet request-event IDs
Ledger domain: all Ledger entity IDs
```

Within a domain, values must not collide even across entity types. Cross-service use retains source and field
context, and consumers treat IDs as opaque strings. Entity types do not receive separate registrations or workers.

Instances lease workers dynamically. A worker ID is stable only for the active lease and may change after
restart; application state and routing must not depend on it. Capacity covers maximum concurrent instances plus
rolling-deployment overlap, and configured cooling and ownership rules control worker reuse.

### 3.2 Registration and layout

```text
Wallet: domain-name=z-exchange-wallet, generator-name=internal-id
Ledger: domain-name=z-exchange-ledger, generator-name=internal-id
```

The services may share the generator database but not a domain. `domain_id` and
`generator_id` are metadata, not encoded ID components. The initial layout is:

```text
1 reserved sign bit | 41 time bits | 10 worker bits | 12 sequence bits
```

This supports 1,024 workers per domain and 4,096 IDs per worker per millisecond.
The epoch is fixed in seed configuration and immutable after IDs are issued.

### 3.3 Event identity

A Wallet-to-Ledger outbox row receives one Wallet-generated ID while its durable record is built. The row and
Wallet changes commit in the same transaction. Its decimal `event_id` is reused for immediate publication, every
outbox retry, Kafka redelivery, and reply correlation; publishers never replace it. The Wallet transaction ID
remains the request `command_id`, request reference, and Kafka partition key.

Wallet-service uniqueness is sufficient while Wallet is the only producer in the Ledger command namespace.
Adding another producer first requires source namespacing or a global-reference contract.

Ledger reply IDs consume no generated value and remain deterministic so
redelivery of the same request and outcome reconstructs the same outbox key:

```text
reply:<request-event-id>:success
reply:<request-event-id>:error:<namespace>:<error-code>
```

### 3.4 Runtime resources

Each service uses a dedicated, bounded, Spring-managed Hikari pool for generator metadata and leases, isolated
from its business-data pool. Spring owns and closes the runtime, renewal work, and pool. MySQL supports
registration and leasing, never one call per generated ID.

## 4. Required Behavior

### REQ-1 — Complete migration and representation

Every in-scope identity shall come from the owner service's single injected generator and be in
`1..Long.MAX_VALUE`. Existing string fields store `Long.toString(value)`; field types and external shapes remain
unchanged, and consumers shall not decode or order by ID internals. Static builders become injected components or
receive an allocated ID. `common-util` may supply infrastructure but no Wallet or Ledger domain policy.

### REQ-2 — Allocation, transactions, and idempotency

Allocation occurs only after determining that a new entity is required. An
accepted idempotent replay returns persisted results without allocating or
persisting replacement IDs. Allocation failure must precede commit and leave no
completed idempotency result, partial business mutation, or orphaned outbox row.
Existing Wallet balance guards, reservation invariants, Ledger double-entry
validation, and transactional-outbox boundaries remain unchanged.

### REQ-3 — Event identity

A Wallet request event receives exactly one generated ID, persisted atomically
with Wallet changes and reused for all publication attempts. Ledger reply IDs
remain deterministic and do not consume generated IDs. All relationships in
Section 3.3 are preserved.

### REQ-4 — Fail-closed safety

Generation stops whenever safe worker ownership cannot be established; no fallback generator is permitted.
Existing unique constraints on entity IDs and outbox `event_id` remain the final duplicate guard; a duplicate
rolls back the enclosing transaction without regeneration after a partial write.

### REQ-5 — Runtime operation and reproducibility

The runtime follows Sections 3.1 and 3.4 and may batch allocations only when all
propagation rules remain intact. Operators can distinguish configuration,
database/registration, worker exhaustion/acquisition, renewal/ownership, clock
rollback, sequence exhaustion, and application allocation failures. Signals
identify service, domain, generator, and instance without exposing credentials.

Production code contains no wall-clock/`AtomicLong` generator or switch to it.
Deployments require the managed generator; an infrastructure `enabled` property
is not a rollout or fallback switch. Tests may inject a controlled generator
without MySQL leasing. Wallet and Ledger consume a fixed artifact version or
immutable revision, with one Java 21-compatible version of shared Jackson,
MyBatis/MyBatis-Plus, MySQL JDBC, and logging APIs.

## 5. Failure Behavior

- Invalid configuration or layout, unavailable generator database, missing
  registration, worker exhaustion, or acquisition failure makes startup or
  readiness fail visibly and prevents ID-creating writes.
- Lease loss or sustained renewal failure stops allocation before ownership is
  unsafe. Allocation resumes only after the managed runtime safely re-establishes
  generation. Safe reads and non-ID-creating operations may remain available;
  rejected writes return a controlled, observable error.
- Allocation failure after an idempotency claim does not complete that claim. If
  no transaction committed, a retry may allocate new IDs. Allocated but
  uncommitted IDs are permanent gaps and are never reclaimed or treated as
  missing records.
- A detected duplicate rolls back the transaction and emits a high-severity
  signal. Clock skew remains an accepted, non-blocking risk; time
  synchronization, configured bounds, worker cooling, fail-closed behavior, and
  database uniqueness remain required mitigations. See
  `design/issues/z-id-generator-clock-skew-risk.md`.

## 6. Acceptance Criteria

### AC-1 — Complete migration and representation

All scoped creation paths use their service's one injected generator and retain
existing schemas and transports. String IDs are canonical positive decimals;
the process-local implementation and selectable fallback are absent.

### AC-2 — Uniqueness, concurrency, and restart

Tests mixing all ID purposes produce no duplicates across concurrent threads,
at least two JVMs in one domain, and safe restarts, while confirming exclusive
worker ownership. Equal values across the separate Wallet and Ledger domains
are permitted.

### AC-3 — Transaction and idempotency safety

Accepted replay creates no new transaction, child entity, or outbox row.
Allocation and forced-duplicate failures roll back affected business, balance,
reservation, Ledger-entry, and outbox changes, do not complete idempotency, and
are rejected by existing unique constraints.

### AC-4 — Event identity stability

A committed Wallet request uses one `event_id` for immediate publish and every
retry. Reprocessing the same request and Ledger outcome reconstructs the same
reply ID and resolves to the existing same-intent reply.

### AC-5 — Failure and operational behavior

Startup/acquisition, renewal, ownership, clock-rollback, and sequence-exhaustion failures are distinguishable.
Unsafe generation blocks ID-creating writes without fallback, and retries follow Section 5.

### AC-6 — Resource lifecycle and throughput path

Metadata and leases use only the dedicated bounded pool; shutdown closes the
runtime and pool. Generation after initialization makes no database call per ID.

### AC-7 — Build compatibility

Wallet, Ledger, and `common-util` compile and pass unit/integration tests on the
resolved Java 21/Spring Boot 2.7 graph with the fixed generator artifact and
compatible shared dependencies.

## 7. Required Configuration

Before completion, deployments record the immutable epoch, capacity for normal and overlapping deployments,
lease/cooling settings, allocation-failure service error, readiness behavior after lease loss, and immutable
`z-id-generator` artifact version.
