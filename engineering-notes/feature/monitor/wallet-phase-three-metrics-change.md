# Wallet Phase 3 Metrics Change

## Scope

- Registered the common gRPC server interceptor globally for wallet.
- Added explicit wallet request and processing-duration metrics at gRPC and Kafka business boundaries.
- Decorated the four balance cache strategies with shared cache result/error metrics.
- Migrated wallet idempotency logic from `IdempRedisClient` to measured `IdempotencyClient`.
- Added wallet histogram/SLO configuration for gRPC, business, Redis, and DB timers.

## Behavior

- Metric tags are bounded; request, wallet, transaction, reservation, and idempotency IDs are excluded.
- Redis claim/read failures still fall back to DB idempotency.
- Hash conflicts map to `request_hash_conflict`; malformed values are deleted best effort before DB fallback.
- Post-commit `mark_done` and release failures are measured and logged without replacing the DB outcome.
- Cache bean names, hot/normal routing, TTLs, negative-cache behavior, and business flow are unchanged.

## Deferred

`zexchange.wallet.transactions.completed` remains deferred because wallet does not yet distinguish a
new completion from an idempotent replay.

## Verification

- Wallet and dependent modules compile.
- Focused API/business metric, cache registration, idempotency, Kafka handler, and configuration tests pass.
