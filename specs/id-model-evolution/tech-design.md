# Technical Design — Internal ID and Typed Reference Evolution

Status: Proposed | Last updated: 2026-10-01

Related documents: [SPEC](./spec.md) | [Implementation tasks](./tasks.md) |
[Generator integration SPEC](../id-generator-integration/spec.md)

## 1. Purpose and boundaries

This design implements the identity model defined by the phase-two SPEC after
`z-id-generator` has been integrated into Wallet and Ledger.

Every covered domain-entity row has two identities:

- `internal_id`: generated numeric database identity owned by one table; and
- `*_ref`: immutable typed identity used outside that owner row.

Outbox rows use the same generated `internal_id` storage key, but `event_ref`
identifies the delivered message rather than the outbox row. This distinction
allows Ledger replies to derive a stable message identity from the request and
outcome without coupling it to a particular reply-row allocation.

The current deployment gives Wallet and Ledger separate generator domains.
`internal_id` is therefore domain-scoped, currently service-scoped, but code
and schemas must not make service scope a permanent contract. Typed references
are unique across all covered entity types in one deployment environment.

This phase is a clean cutover. There are no compatibility columns, dual reads,
dual writes, backfills, or legacy aliases.

The following remain outside this phase:

- BalanceSnapshot identity redesign or a `balance_ref`;
- reference-format version and generator-version fields;
- routing, sharding, buckets, and physical partition metadata; and
- generated numeric values above `Long.MAX_VALUE`.

## 2. Identity contracts

### 2.1 `internal_id`

Each covered owner table uses:

```sql
internal_id BIGINT UNSIGNED NOT NULL PRIMARY KEY
```

The owner service allocates the value from its managed generator before the
insert. MyBatis uses an assigned ID rather than `AUTO_INCREMENT`; Java uses
`Long` while values remain in `1..Long.MAX_VALUE`.

`internal_id` is valid for owner-table persistence and direct owner-table
lookup. It must not be exposed by APIs or messages or used as another owner's
persisted relationship. Business logic must not infer ordering, time, worker,
or sequence from it.

The database type intentionally leaves room for a future unsigned generator.
Using values above `Long.MAX_VALUE` requires a separate Java/JDBC mapping
change and is not a transparent generator replacement.

### 2.2 Typed entity references

Each covered domain entity stores one non-null, unique typed reference. The
conceptual format is:

```text
<TYPE>-<ENC_INTERNAL_ID>-<CHK>
```

The reference is generated locally and deterministically from the entity type
and `internal_id`. It contains no environment, generator version, service
domain, worker, bucket, partition, or storage location.

The type registry must distinguish every covered domain entity. Equal numeric
IDs from different generator domains therefore remain different references.

Before implementation, the project must fix the permanent type codes,
encoding alphabet, checksum algorithm, maximum length, and validation errors.
Once issued, a type code must never be reassigned to another entity meaning.

Typed entity references are canonical identities for:

- API and protobuf fields;
- Kafka envelopes, commands, replies, and partition keys;
- structured logs and cross-service correlation;
- cache keys and cached entity relationships;
- reconciliation and analytical exports; and
- persisted relationships to covered entities.

They are opaque to business code. Only the shared codec may parse them.

`event_ref` follows a separate message-identity contract. It is immutable,
opaque, and unique in an outbox, but it is not required to decode to that
outbox row's `internal_id`.

### 2.3 Caller-owned references and idempotency

Caller-provided business references and idempotency keys are not entity
identity and remain separate. A field currently named `reference_id` must be
classified before renaming:

- caller-provided references remain caller-owned request data; and
- fields that actually identify another covered entity become that entity's
  typed `*_ref`.

No caller reference may be silently reinterpreted as a generated typed
reference.

## 3. Shared identity infrastructure

`common-util` owns only the infrastructure contract:

```java
interface EntityRefCodec {
    String encode(EntityRefType type, long internalId);
    DecodedEntityRef decode(String value);
}

record DecodedEntityRef(EntityRefType type, long internalId) {}

interface EventRefFactory {
    String newRequestRef(long outboxInternalId);
    String replyRef(String requestEventRef, String outcome);
}
```

The codec shall:

- accept generated values only in `1..Long.MAX_VALUE`;
- emit one canonical uppercase representation;
- reject malformed, non-canonical, unknown-type, and checksum-invalid input;
- round-trip the entity type and `internal_id` without precision loss; and
- operate without database or network access.

The type registry is an identity protocol registry, not a place for Wallet or
Ledger business rules. The codec must not route requests, authorize access, or
branch on entity business behavior. `EventRefFactory` owns only canonical
message-reference construction; the caller supplies the already classified
reply outcome.

The existing managed `z-id-generator` bean remains the only source of new
`internal_id` values. Allocation and reference encoding happen before an
insert, and both values are committed in the same transaction as the entity
and any required outbox row.

## 4. Schema cutover

### 4.1 Covered owner tables

| Table | Primary-key change | Canonical reference |
| --- | --- | --- |
| `wallets` | `id` → generated `internal_id` | `wallet_id` → `wallet_ref` |
| `wallet_transactions` | `id` → generated `internal_id` | `txn_id` → `wallet_txn_ref` |
| `wallet_reservations` | `id` → generated `internal_id` | `reservation_id` → `wallet_reservation_ref` |
| `wallet_actions` | `id` → generated `internal_id` | `action_id` → `wallet_action_ref` |
| `accounts` | `id` → generated `internal_id` | `account_id` → `ledger_account_ref` |
| `ledger_transactions` | `id` → generated `internal_id` | `txn_id` → `ledger_txn_ref` |
| `ledger_entries` | `id` → generated `internal_id` | `entry_id` → `ledger_entry_ref` |
| Wallet `outbox` | `id` → generated `internal_id` | `event_id` → message `event_ref` |
| Ledger `outbox` | `id` → generated `internal_id` | `event_id` → message `event_ref` |

Every canonical reference has a unique constraint. Existing generated string
identity columns are renamed rather than retained beside the new names.

`assets` and other unlisted owner tables keep their current identity model.

### 4.2 Wallet relationships

Wallet schema and model relationships change as follows:

| Current field | Target field | Meaning |
| --- | --- | --- |
| `wallet_actions.txn_id` | `wallet_txn_ref` | owning Wallet transaction |
| `wallet_actions.wallet_id` | `wallet_ref` | affected Wallet |
| `wallet_actions.reservation_id` | `wallet_reservation_ref` | optional reservation |
| `wallet_reservations.wallet_id` | `wallet_ref` | owning Wallet |
| `wallet_reservations.reserve_txn_id` | `reserve_wallet_txn_ref` | creating transaction |
| `balance_snapshots.wallet_id` | `wallet_ref` | Wallet identity |
| `balance_snapshots.last_txn_id` | `last_wallet_txn_ref` | last applied transaction |
| `wallet_account_mappings.wallet_id` | `wallet_ref` | Wallet side of mapping |
| `wallet_account_mappings.account_ref_id` | `ledger_account_ref` | Ledger side of mapping |

`balance_snapshots.id` remains its existing auto-increment primary key. The
table receives no independent typed reference. If `wallet_reference_id` is
only a duplicate lookup for the current Wallet entity, it is removed in favor
of `wallet_ref`; if a verified caller-owned use remains, it must be renamed to
an explicit business-reference name before implementation.

The same classification applies to
`wallet_reservations.wallet_reference_id`: it must not survive as an ambiguous
second Wallet identity.

### 4.3 Ledger relationships

Ledger schema and model relationships change as follows:

| Current field | Target field | Meaning |
| --- | --- | --- |
| `ledger_entries.txn_id` | `ledger_txn_ref` | owning Ledger transaction |
| `ledger_entries.account_id` | `ledger_account_ref` | affected Ledger account |
| `ledger_transactions.reference_id` | `wallet_txn_ref` | source Wallet transaction |
| `accounts.reference_id` | `wallet_ref` | source Wallet for Wallet-owned accounts |

The last mapping reflects the current Wallet-to-Ledger account creation flow.
If accounts later support multiple owner services, the contract needs an
explicit typed source reference rather than a generic `reference_id`.

There are no foreign keys across service databases. Full typed references are
the authoritative cross-service relationship.

### 4.4 Indexes and assigned IDs

All covered MyBatis models replace `@TableId(type = IdType.AUTO)` with assigned
ID behavior and rename `id` accessors to `internalId`. Insert builders must
require both identity fields instead of supplying `0L` for an auto-increment
key.

Indexes that currently target generated string IDs move to the corresponding
typed reference. Compound business/idempotency constraints remain unchanged
unless a renamed column participates in them.

SQL and mapper pagination that currently orders by an owner table's `id` uses
`internal_id`. Numeric order remains a stable tie-breaker, not a creation-order
contract.

## 5. API, protobuf, cache, and log cutover

The protobuf cutover is coordinated across Wallet, Ledger, and common event
contracts in one change because no compatibility period is required.

Required boundary renames include:

- entity identity fields such as `transaction_id` become the applicable
  `wallet_txn_ref` or `ledger_txn_ref`;
- `event_id` becomes `event_ref`;
- `command_id` becomes `command_ref`;
- Wallet-to-Ledger command identity and Kafka partition key use
  `wallet_txn_ref`; and
- successful Ledger replies expose `ledger_txn_ref` while retaining the source
  `wallet_txn_ref` for correlation.

Field numbers in existing protobuf messages should be retained when a field's
semantics are a direct rename. Generated Java call sites, tests, and stored
outbox payload fixtures change in the same cutover.

Cache scopes and keys migrate from generated legacy IDs to typed references.
This does not change existing stable/versioned cache behavior or CAS rules.
Structured logs use entity-specific `*_ref` keys. An owner service may log
`internal_id` as additional diagnostic data, but correlation uses typed refs.

## 6. Event and outbox flow

An outbox row has:

```text
internal_id   generated row primary key
event_ref     unique message identity
command_ref   typed business/correlation identity
```

For Wallet requests, `command_ref` and the Kafka partition key are the
`wallet_txn_ref`. Wallet creates `event_ref` once and commits it atomically
with Wallet state and the outbox row. Immediate publication and every retry
reuse the stored value.

Ledger uses the request's `wallet_txn_ref` for command correlation. A
successful result may additionally carry the created `ledger_txn_ref`.
Ledger reply `event_ref` remains deterministic from the request `event_ref`
and reply outcome, so redelivery reconstructs the same identity. It does not
need to encode the reply outbox row's `internal_id`.

Wallet and Ledger event references use distinct canonical namespaces so a
request and reply cannot collide. The exact namespace and deterministic reply
encoding are finalized with the entity-reference format decisions. Event
references are validated as message references and are excluded from the
entity codec's decode-to-`internal_id` invariant.

Outbox same-intent comparison, lookups, status changes, publisher metrics, and
logs migrate from `event_id`/`command_id` to `event_ref`/`command_ref` without
changing their idempotency semantics.

Retry claiming uses:

```text
(outbox_status, next_attempt_at, internal_id)
```

and an exclusive `(next_attempt_at, internal_id)` cursor. A row concurrently
inserted behind the current cursor may wait one additional retry cycle. This is
an accepted latency effect because repeated cycles eventually select every
eligible row; persistent starvation is a defect.

## 7. Transaction and idempotency behavior

For a new entity, the owner service performs this sequence inside the existing
business transaction boundary:

1. obtain a generated `internal_id`;
2. encode the entity's typed reference;
3. construct the row with assigned identity;
4. persist the row and required relationships;
5. persist required outbox data; and
6. complete the idempotency result with the stored typed references.

An accepted replay reads and returns the already persisted references. It does
not allocate replacements.

Generated-ID gaps are acceptable when a transaction rolls back. The database
unique constraints remain the final duplicate guard. A duplicate internal ID
or typed reference rolls back the enclosing transaction and emits a
high-severity operational signal; code must not regenerate after a partial
write.

The existing rules remain unchanged: Wallet owns business decisions and
balance invariants, Ledger remains a passive double-entry journal, outbox rows
commit with business state, and Redis/DB idempotency behavior is preserved.

## 8. Diagnostic decoding

`z-id-generator` may expose a diagnostic decoder that accepts the configured
epoch and layout and reports approximate time, worker, and sequence fields.

The decoder is for tests, logs, and operational tools only. Business modules
must not use decoded values for routing, ordering, validation, authorization,
uniqueness, or any other correctness decision. The reference codec is the only
supported parser for `*_ref` values.

No generator-version bits are introduced. The configured generator layout and
epoch remain immutable for issued IDs, and the dependency version remains
pinned as required by the generator integration SPEC.

## 9. Failure and operational behavior

- Generator startup, lease, or ownership failure prevents identity-creating
  writes. No fallback generator is permitted.
- Reference encoding or validation failure aborts the write before any partial
  mutation or completed idempotency result is committed.
- Malformed, unknown-type, and checksum-invalid references produce controlled
  validation or not-found results; services do not guess an entity type.
- Duplicate internal IDs or references roll back the transaction and trigger a
  high-severity signal.
- Clock rollback, sequence exhaustion, dedicated-pool lifecycle, metrics, and
  shutdown behavior remain governed by the integration SPEC.
- MySQL remains authoritative for the stored unsigned value. Java/MyBatis must
  round-trip `1..Long.MAX_VALUE` without sign or precision changes.

## 10. Implementation sequence

1. Finalize the reference registry, encoding, checksum, length, and controlled
   error contract.
2. Add the generic codec and diagnostic decoder tests.
3. Change standard Wallet and Ledger schemas to assigned `internal_id` keys,
   typed references, renamed relationships, and updated indexes.
4. Migrate Wallet persistence, transaction builders, caches, APIs, and tests.
5. Migrate Ledger persistence, posting flow, caches, reconciliation, APIs, and
   tests.
6. Migrate common event protobufs, outbox models/repositories, publishers,
   listeners, retry cursors, and fixtures.
7. Remove remaining legacy generated-ID paths and run the integration SPEC's
   lease, restart, multi-instance, clock-skew, and shutdown verification.

The schema and code change together; this sequence is for implementation
organization, not for mixed-version deployment.

## 11. Decisions required before implementation

1. Permanent entity type codes plus distinct Wallet-event and Ledger-event
   namespaces.
2. Reference encoding alphabet, checksum algorithm, and maximum length.
3. Controlled error codes for malformed, unknown-type, checksum-invalid, and
   unresolved references.
4. Final treatment and names of each current caller-owned `reference_id` and
   redundant `wallet_reference_id` field.
5. Exact MyBatis assigned-ID annotation and the range guard used before
   persistence.
6. Final outbox DDL and cursor query using `internal_id`.
7. The complete protobuf field rename matrix while preserving applicable field
   numbers.
8. The deterministic Ledger reply-reference encoding and canonical outcome
   vocabulary.

