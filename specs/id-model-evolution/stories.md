# Agile Stories — Internal ID and Typed Reference Evolution

Source: [spec.md](./spec.md)

## Story 1 — Define the identity foundation

As a service developer, I want a shared typed-reference codec and generated internal-ID model so that Wallet and
Ledger use one unambiguous identity contract.

Acceptance criteria:

- Stable type codes, encoding, checksum, maximum length, and validation errors are defined.
- The codec creates and parses `<TYPE>-<ENC_INTERNAL_ID>-<CHK>` references locally and deterministically.
- Covered persistence models support generated `internal_id BIGINT UNSIGNED` values in `1..Long.MAX_VALUE`.
- MyBatis round-trips `1`, representative values, and `Long.MAX_VALUE` without sign or precision changes.
- The diagnostic generator decoder is available, but production business logic does not depend on decoded fields.

## Story 2 — Migrate Wallet identities

As the Wallet service, I want covered Wallet entities to use internal IDs and typed references so that external and
cross-service flows no longer depend on storage identity.

Acceptance criteria:

- Wallet, transaction, reservation, and action rows use generated `internal_id` primary keys and unique typed refs.
- Legacy owner `id` and generated business-ID columns are removed.
- APIs, protobufs, caches, logs, and relationships use the appropriate Wallet reference.
- BalanceSnapshot keeps its current identity, while references to migrated Wallet entities use typed refs.
- Creation and accepted idempotent replay preserve the original references atomically.

## Story 3 — Migrate Ledger identities

As the Ledger service, I want covered Ledger entities to use internal IDs and typed references so that accounting
records have stable identities without exposing database keys.

Acceptance criteria:

- Account, transaction, and entry rows use generated `internal_id` primary keys and unique typed refs.
- Legacy owner `id` and generated business-ID columns are removed.
- Relationships, APIs, protobufs, caches, logs, and reconciliation output use Ledger or Wallet typed refs.
- `wallet_txn_ref` is the Ledger command identity; successful replies expose `ledger_txn_ref`.
- Reprocessing the same command creates no duplicate accounting effect and returns stored references.

## Story 4 — Migrate event and outbox identity

As the messaging layer, I want durable event references separated from outbox row IDs so that retries and
deduplication retain one stable message identity.

Acceptance criteria:

- Wallet and Ledger outbox rows use generated `internal_id` primary keys and unique `event_ref` values.
- A Wallet request `event_ref` is committed with business state and reused for immediate publication and retries.
- Ledger reply references are deterministic for the same request and outcome.
- Kafka partitioning uses `wallet_txn_ref`; cross-service messages expose no internal IDs.
- Polling by `(next_attempt_at, internal_id)` eventually selects concurrently inserted eligible rows without starvation.

## Integration Task — Complete the safe cutover

Verify that the completed identity migration remains fail-closed and observable and contains no legacy or
compatibility path.

Acceptance criteria:

- Allocation, encoding, duplicate, and ownership failures roll back the complete transaction with no fallback ID.
- Duplicate internal IDs or references produce a high-severity operational signal.
- Malformed, unknown-type, and checksum-invalid references return controlled errors.
- Existing lease, restart, multi-instance, clock-skew, resource-isolation, and shutdown tests continue to pass.
- Builds and tests pass with no legacy identity path, dual write, backfill, or ambiguous cross-service ID remaining.
