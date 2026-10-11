# Implementation Tasks — Internal ID and Typed Reference Evolution

Source: [SPEC](./spec.md) | Jira epic: [ZEX-2](https://szy2425973547.atlassian.net/browse/ZEX-2)

## Task 1 — Define the identity foundation

Jira story: [ZEX-3](https://szy2425973547.atlassian.net/browse/ZEX-3)

Implement the shared typed-reference codec and generated internal-ID persistence contract.

Done when:

- Stable type codes, logical-bucket rules, encoding, checksum, maximum length, and validation errors are defined.
- The codec creates and parses `<TYPE>-<BUCKET>-<ENC_INTERNAL_ID>-<CHK>` references locally and deterministically.
- Covered persistence models support generated `internal_id BIGINT UNSIGNED` values in `1..Long.MAX_VALUE`.
- MyBatis round-trips `1`, representative values, and `Long.MAX_VALUE` without sign or precision changes.
- Production business logic does not decode generated IDs or references; shared routing infrastructure alone reads
  the logical bucket and maps it to a physical partition.

## Task 2 — Migrate Wallet identities

Jira story: [ZEX-4](https://szy2425973547.atlassian.net/browse/ZEX-4)

Migrate the covered Wallet owners and boundaries to generated internal IDs and typed references.

Done when:

- Wallet, transaction, reservation, and action rows use generated `internal_id` primary keys and unique typed refs.
- Legacy owner `id` and generated business-ID columns are removed.
- APIs, protobufs, caches, logs, and relationships—including relationships within Wallet—use the appropriate typed
  reference rather than another owner's `internal_id`.
- BalanceSnapshot keeps its current identity, while references to migrated Wallet entities use typed refs.
- Creation and accepted idempotent replay preserve the original references atomically.

## Task 3 — Migrate Ledger identities

Jira story: [ZEX-5](https://szy2425973547.atlassian.net/browse/ZEX-5)

Migrate the covered Ledger owners and boundaries to generated internal IDs and typed references.

Done when:

- Account, transaction, and entry rows use generated `internal_id` primary keys and unique typed refs.
- Legacy owner `id` and generated business-ID columns are removed.
- Relationships, APIs, protobufs, caches, logs, and reconciliation output use Ledger or Wallet typed refs.
- Ledger stores the Wallet `wallet_txn_ref` as `source_txn_ref`; successful replies expose `ledger_txn_ref`.
- Reprocessing the same command creates no duplicate accounting effect and returns stored references.

## Task 4 — Migrate event and outbox identity

Jira story: [ZEX-6](https://szy2425973547.atlassian.net/browse/ZEX-6)

Separate durable event references from outbox-row internal IDs and migrate delivery boundaries.

Done when:

- Wallet and Ledger outbox rows use generated `internal_id` primary keys and unique `event_ref` values.
- A Wallet request `event_ref` is committed with business state and reused for immediate publication and retries.
- Ledger reply references are deterministic for the same request and outcome.
- Kafka partitioning uses `wallet_txn_ref`; cross-service messages expose no internal IDs.
- Polling by `(next_attempt_at, internal_id)` eventually selects concurrently inserted eligible rows without starvation.

## Integration Task — Complete the safe cutover

Jira task: [ZEX-7](https://szy2425973547.atlassian.net/browse/ZEX-7)

Verify that the completed identity migration remains fail-closed and observable and contains no legacy or
compatibility path.

Done when:

- Allocation, encoding, duplicate, and ownership failures roll back the complete transaction with no fallback ID.
- Duplicate internal IDs or references produce a high-severity operational signal.
- Malformed, unknown-type, out-of-range-bucket, and checksum-invalid references return controlled errors.
- Existing lease, restart, multi-instance, clock-skew, resource-isolation, and shutdown tests continue to pass.
- Builds and tests pass with no legacy identity path, dual write, backfill, or ambiguous cross-service ID remaining.
