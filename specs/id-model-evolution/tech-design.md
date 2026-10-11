# Technical Design — Internal ID and Typed Reference Evolution

Status: Proposed | Last updated: 2026-10-10

Related documents: [SPEC](./spec.md) | [Implementation tasks](./tasks.md) |
[Identity rename matrix](./identity-rename-matrix.md) |
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
- diagnostic decoding of generator time, worker, or sequence fields;
- reference-format version and generator-version fields;
- physical shard topology, logical-bucket migration mechanics, and distributed transaction design; and
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
lookup after the correct partition has already been selected. It must not be
exposed by APIs or messages or used as another owner's persisted relationship.
Business logic must not infer ordering, time, worker, sequence, or storage
location from it.

The database type intentionally leaves room for a future unsigned generator.
Using values above `Long.MAX_VALUE` requires a separate Java/JDBC mapping
change and is not a transparent generator replacement.

### 2.2 Typed entity references

Each covered domain entity stores one non-null, unique typed reference. The
conceptual format is:

```text
<TYPE>-<BUCKET>-<ENC_INTERNAL_ID>-<CHK>
```

The reference is generated locally and deterministically from the entity type
and `internal_id` after its logical bucket has been selected. `BUCKET` is an
immutable virtual routing bucket, not a physical database or table number. A
routing map resolves the bucket to its current physical partition; resharding
moves the bucket mapping without rewriting issued references. The reference
contains no environment, generator version, service domain, worker, or
physical storage location.

The type registry must distinguish every covered domain entity. Equal numeric
IDs from different generator domains therefore remain different references.

The format is fixed as follows, subject to the remaining logical-bucket width
decision:

- type codes are the three-character values in the registry below;
- `BUCKET` is the canonical, fixed-width Base62 encoding of the entity's
  logical bucket;
- `ENC_INTERNAL_ID` is the canonical case-sensitive Base62 representation,
  using `0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz` and
  left-padded with `0` to exactly 11 characters;
- `CHK` is the six-character, zero-padded Base62 encoding of the
  CRC32C of the ASCII payload `<TYPE>-<BUCKET>-<ENC_INTERNAL_ID>`;
- every entity reference is exactly `23 + BUCKET_WIDTH` characters;
- reference columns use `utf8mb4` with `utf8mb4_bin` collation so Base62
  letter case remains significant without restricting the table to ASCII; and
- only numeric values in `1..Long.MAX_VALUE` are valid.

| Entity | Type code | Allocator domain |
| --- | --- | --- |
| Wallet | `WAL` | `z-exchange-wallet` |
| Wallet transaction | `WTX` | `z-exchange-wallet` |
| Wallet reservation | `WRS` | `z-exchange-wallet` |
| Wallet action | `WAC` | `z-exchange-wallet` |
| Ledger account | `LAC` | `z-exchange-ledger` |
| Ledger transaction | `LTX` | `z-exchange-ledger` |
| Ledger entry | `LEN` | `z-exchange-ledger` |

Once issued, a type code is never changed or reassigned. Within one
environment, exactly one allocator domain may issue a given type. This rule is
what makes equal numeric IDs from the Wallet and Ledger domains globally
distinct; the checksum is error detection and does not provide uniqueness.

Typed entity references are canonical identities for:

- API and protobuf fields;
- Kafka envelopes, commands, replies, and partition keys;
- structured logs and cross-service correlation;
- cache keys and cached entity relationships;
- reconciliation and analytical exports; and
- persisted relationships to covered entities.

They are opaque to business code. Only shared identity and routing
infrastructure may parse them.

`event_ref` follows a separate message-identity contract. It is immutable,
opaque, and unique in an outbox, but it is not required to decode to that
outbox row's `internal_id`.

### 2.3 Why persisted relationships use references

Persisted relationships use the target entity's typed reference even when the
source and target currently belong to the same service. The alternative,
storing another owner's `internal_id`, creates several long-term problems:

- an internal ID identifies a row only after the owning partition is known;
- generator-domain uniqueness is not a permanent storage-routing contract;
- a numeric value alone does not prove the expected target entity type;
- related tables may later use different partitioning or placement rules; and
- internal-ID relationships create a second identity form that can leak into
  caches, messages, logs, and service boundaries.

A typed reference carries the target type and immutable logical bucket. The
router can therefore select the current physical partition before performing
the owner-table lookup, and the relationship survives movement of that bucket
without rewriting referencing rows. Using the same relationship form in
persistence and at boundaries also removes conversions and ambiguity during
reconciliation and migration. The resulting wider relationship columns and
indexes are an accepted tradeoff.

For example, `wallet_actions.wallet_txn_ref` can route to the owning Wallet
transaction and `wallet_actions.wallet_ref` can independently route to the
affected Wallet. The bucket in `wallet_action_ref` identifies the action row's
own placement. These values make routing deterministic, but they do not make a
multi-wallet transaction atomic across physical shards; transaction
coordination remains a separate design concern.

### 2.4 Logical-bucket rules

Each entity receives one logical bucket at creation according to its owner's
placement rule. The bucket never changes. Physical databases and tables may be
added, removed, or rebalanced by changing the routing map from logical buckets
to physical partitions. Business code neither calculates physical locations
from the bucket nor parses references; it supplies the complete reference to
shared routing infrastructure.

The final bucket count and encoding width must provide sufficient virtual
buckets for future rebalancing and must not be derived from the current number
of physical shards. Checksum validation covers the bucket so a mistyped bucket
cannot silently route an otherwise valid identifier to the wrong partition.

### 2.5 Caller-owned references and idempotency

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
    String encode(EntityRefType type, int logicalBucket, long internalId);
    DecodedEntityRef decode(String value);
}

record DecodedEntityRef(EntityRefType type, int logicalBucket, long internalId) {}

record GeneratedEntityIdentity(long internalId, String reference) {}

interface EntityIdentityFactory {
    GeneratedEntityIdentity next(EntityRefType type, int logicalBucket);
}

interface EventRefFactory {
    String newRequestRef(long outboxInternalId);
    String replyRef(String requestEventRef, String outcome);
}
```

The codec shall:

- accept generated values only in `1..Long.MAX_VALUE`;
- emit one canonical, case-sensitive Base62 representation;
- reject malformed, non-canonical, unknown-type, out-of-range-bucket, and
  checksum-invalid input;
- round-trip the entity type, logical bucket, and `internal_id` without
  precision loss; and
- operate without database or network access.

Validation uses the controlled `entity_reference` error namespace with the
codes `MALFORMED`, `UNKNOWN_TYPE`, `BUCKET_OUT_OF_RANGE`, `CHECKSUM_INVALID`,
`OVER_LENGTH`, `OUT_OF_RANGE`, and `UNEXPECTED_TYPE`. Callers that expect a
specific entity must use expected-type decoding rather than decode and route
on their own.

The type registry is an identity protocol registry, not a place for Wallet or
Ledger business rules. The codec must not route requests, authorize access, or
branch on entity business behavior. `EventRefFactory` owns only canonical
message-reference construction; the caller supplies the already classified
reply outcome.

The existing managed `z-id-generator` bean remains the only source of new
`internal_id` values. `EntityIdentityFactory` allocates and encodes one
immutable pair for a bucket selected by routing infrastructure so the row key
and reference cannot diverge. The caller invokes the factory before its
database transaction; both values are then committed in the same transaction
as the entity and any required outbox row.

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

`wallet_reservations.wallet_reference_id` is removed because `wallet_ref`
already identifies the reservation's Wallet; the reservation does not require
a denormalized copy of the Wallet's caller-owned business reference.

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
the authoritative relationship both within a service and across services.

### 4.4 Indexes and assigned IDs

All covered MyBatis models replace `@TableId(type = IdType.AUTO)` with
`@TableId(value = "internal_id", type = IdType.INPUT)` and rename `id`
accessors to `internalId`. Insert builders must require both identity fields
instead of supplying `0L` for an auto-increment key.

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
Structured production logs use entity-specific `*_ref` keys and do not expose
`internal_id`. Restricted database and operational tooling may inspect the
stored key, but application correlation always uses typed references.

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
entity codec's decode-to-`internal_id` invariant. Their deterministic request
and reply construction belongs to the event/outbox task, not the entity codec.

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

## 8. ID opacity

Diagnostic decoding of generator time, worker, and sequence fields is deferred.
Business modules must not decode generated IDs or references or use their
values for routing, ordering, validation, authorization, uniqueness, or
another correctness decision. Shared identity and routing infrastructure is
the only supported parser for `*_ref` values and may use the logical bucket
solely to select the current physical partition.

No generator-version bits are introduced. The configured generator layout and
epoch remain immutable for issued IDs, and the dependency version remains
pinned as required by the generator integration SPEC.

## 9. Failure and operational behavior

- Generator startup, lease, or ownership failure prevents identity-creating
  writes. No fallback generator is permitted.
- Reference encoding or validation failure aborts the write before any partial
  mutation or completed idempotency result is committed.
- Malformed, unknown-type, out-of-range-bucket, and checksum-invalid references
  produce controlled validation or not-found results; services do not guess an
  entity type or physical partition.
- Duplicate internal IDs or references roll back the transaction and trigger a
  high-severity signal.
- Clock rollback, sequence exhaustion, dedicated-pool lifecycle, metrics, and
  shutdown behavior remain governed by the integration SPEC.
- MySQL remains authoritative for the stored unsigned value. Java/MyBatis must
  round-trip `1..Long.MAX_VALUE` without sign or precision changes.

## 10. Implementation sequence

1. Finalize logical-bucket cardinality and assignment rules, then finalize the
   reference registry, encoding width, checksum, length, and controlled error
   contract.
2. Add the generic codec and MySQL/MyBatis numeric-contract tests.
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

## 11. Remaining event-task decisions

The entity-reference registry and schema/protobuf rename matrix are fixed by
this design and its linked matrix. Logical-bucket cardinality, assignment,
encoding width, resulting reference length, and routing-map ownership remain
entity-reference decisions. The event/outbox task still owns:

1. distinct Wallet-request and Ledger-reply event namespaces;
2. deterministic Ledger reply-reference encoding and canonical outcomes; and
3. final outbox DDL and retry cursor SQL using `internal_id`.

