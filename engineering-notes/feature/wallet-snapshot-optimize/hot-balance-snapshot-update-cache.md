# Balance Snapshot Cache Coherency Evaluation

## Verdict

The design direction is correct: if `GetSnapshotByRefId` is expected to return current balances, the mutable `walletId -> BalanceSnapshot` entity cache must be made coherent before spending more effort on the hot `refId -> walletId` index cache.

The current hot ref cache only accelerates the index lookup. It does not make the returned balance fresh, because `BalanceSnapshotStore.getByRefId(...)` resolves the wallet id and then calls `getByWalletId(...)`. If the wallet-id entity cache is stale, both `GetSnapshotByWalletId` and `GetSnapshotByRefId` can return stale balances regardless of the ref cache optimization.

I would support the refactor in principle, but not exactly as written unless the implementation handles version increments, post-commit timing, action aggregation, lock ordering, and cache-refresh failure semantics explicitly.

## Current Project State

- `BeforePostLedgerProcessor.updateSnapshotsBeforePosting(...)` updates `balance_snapshots` directly through `BalanceSnapshotRepository.reserveFromWalletId(...)` and `releaseFromWalletId(...)`.
- `AfterPostLedgerProcessor.updateSnapshotsAfterPosting(...)` updates `balance_snapshots` directly through `BalanceSnapshotRepository.transferOutFromWalletId(...)` and `transferInToWalletId(...)`.
- Those repository methods use SQL arithmetic through `LambdaUpdateWrapper.setSql(...)`.
- `BalanceSnapshotStore` is used for reads and post-insert cache actions, but it is not involved in snapshot updates.
- `VersionCacheStrategy.afterUpdate(...)` already has the right high-level semantics for mutable entity cache updates.
- Normal `CACHE_ASIDE` snapshot cache writes a versioned tombstone.
- Hot `POST_REFRESH` snapshot cache writes the refreshed value.
- `BalanceSnapshotStore` currently does not expose an `afterDbUpdate(...)` method to call that strategy.
- `BalanceSnapshot.version` exists and is annotated with `@Version`, and MyBatis-Plus optimistic locking is registered globally.

The main correctness gap is not the missing ref cache. The gap is that snapshot rows are mutable, cached, and updated outside the cache-aware store path.

## What The Proposed Refactor Solves

- It gives the update path the committed `BalanceSnapshot` value needed for a correct cache action.
- It can make hot system-wallet reads much safer because `POST_REFRESH` can write the new snapshot after the DB update instead of leaving an old entity value in Redis/L1.
- It can make normal wallet reads safer because `CACHE_ASIDE.afterUpdate(...)` can write a versioned tombstone, preventing a stale read-through value from overwriting the invalidation.
- It aligns balance snapshots with the existing reservation update pattern: load current row, mutate in memory, then update with an old-state guard.
- It removes reliance on SQL arithmetic updates that currently do not produce an updated Java object for cache refresh.

This is a better foundation than trying to special-case `GetSnapshotByRefId`. The ref cache is an immutable index optimization; the wallet-id entity cache is where freshness is won or lost.

## Critical Implementation Requirements

### 1. The update must increment `BalanceSnapshot.version`

This is non-negotiable.

The versioned cache only works if every DB update produces a strictly newer snapshot version. Today the SQL arithmetic methods call `mapper.update(wrapper)` with no entity. That path is risky because it may not engage MyBatis-Plus `@Version` behavior or update the Java object with the new version.

If you refactor to `mapper.update(now, wrapper)`, verify with an integration test or logged SQL that:

- the generated SQL increments `version`
- the `WHERE` clause guards against the expected old version or old amount fields
- the `now` entity has the new version after the update
- `BalanceSnapshotStore.afterDbUpdate(...)` receives that new version

If this is not verified, `afterUpdate(walletId, snapshot, snapshot.getVersion())` can write a cache value with the old version, and newer cache/tombstone ordering guarantees become unreliable.

### 2. Revalidate balances after locking

The processors currently load snapshots before the DB transaction for request validation and action construction. That preloaded list is explicitly treated as memory data, not real-time data.

After the refactor, the locked rows must be the source of truth for monetary checks:

- `RESERVE` must check `available >= amount`.
- `RELEASE` must check `reserved >= amount`.
- `TRANSFER_OUT` must check `reserved >= amount`.
- `TRANSFER_IN` must check wallet status and asset match.
- All updates must preserve `WalletStatus.OPEN`.

Do not rely only on `ValidateHelper.validateActionInfo(...)`. That helper validates action/snapshot matching and asset balance, but the current DB wrappers also enforce amount constraints. The in-memory flow must preserve those DB-side guards.

### 3. Lock rows in deterministic PK order

The current processors sort snapshots by `id` before issuing per-row arithmetic updates. The proposed `select * where id in (...) for update` should preserve that discipline.

Do not just copy `WalletReservationRepository.selectInIdForUpdate(...)` blindly. It uses `in(...).last("for update")`, but it does not add `orderByAsc(id)`. For multi-wallet transfers, inconsistent lock order is a deadlock risk.

The snapshot lock method should:

- sort ids before querying, or query with `orderByAsc(BalanceSnapshot::getId)`
- use `FOR UPDATE` only inside the posting DB transaction
- return locked rows in the same deterministic order used for mutation

This matters more for snapshots than reservations because transfer transactions naturally touch multiple wallets.

### 4. Prefer one DB update per snapshot, not one update per action

The current code loops actions per snapshot and updates once per action, with a comment saying there is usually only one relevant action today. That is a weak invariant.

The refactor should aggregate all actions for a wallet and apply them to the locked snapshot in memory, then perform one DB update for the final state. This has several benefits:

- one version increment per snapshot per transaction
- one cache action per snapshot per transaction
- less row-lock churn
- cleaner failure logging because the final old/new snapshot pair is visible

If you keep one update per action, cache refresh must happen only for the final snapshot state after commit, not after each in-transaction action.

### 5. Do cache actions only after DB commit

The proposed `after db commit -> BalanceSnapshotStore.afterDbUpdate(...)` boundary is correct.

Calling cache refresh inside the DB transaction would be wrong because a later rollback could expose an uncommitted balance in Redis or client-side cache.

The current `DbTxnExecutor.executeWithDefault(...)` uses `TransactionTemplate` and returns after the transaction callback completes. There is no built-in `afterCommit` hook in this helper. A minimal implementation can collect updated snapshots inside the callback and call store methods only if `executeWithDefault(...)` returns success.

Do not call `afterDbUpdate(...)` from inside `updateSnapshotsBeforePosting(...)` or `updateSnapshotsAfterPosting(...)` while those methods still execute inside the transaction.

## Hidden Risks

### Post-commit cache refresh is still best effort

Even with the proposed refactor, there is a crash window:

- DB commit succeeds.
- Process crashes before `BalanceSnapshotStore.afterDbUpdate(...)`.
- Old cached snapshot remains until TTL or a later successful update.

This is especially visible in `AfterPostLedgerProcessor`: if the transaction status is already updated to `COMPLETED`, a retry may return early from `txnInfo.walletTxn.hasFinalized()` and never repair the cache.

If current balance is a strict API contract, best-effort post-commit refresh is not enough. You need either:

- a reliable cache-invalidation outbox/retry path
- a read path that bypasses the entity cache for freshness-critical APIs
- a reconciliation/repair mechanism that can tombstone or refresh stale snapshot keys

If the contract is eventual consistency, document the staleness bound. With the current config, entity cache TTL is `120s`; that is too large to pretend the endpoint is current if invalidation can be missed.

### Full-entity `mapper.update(now, wrapper)` can widen the write surface

`BalanceSnapshot` contains identity and metadata fields:

- `walletId`
- `serviceId`
- `walletReferenceId`
- `assetId`
- `walletStatus`
- `ownerType`
- `ownerId`
- `available`
- `reserved`
- `version`

Updating the whole loaded entity is convenient, but it makes accidental mutation of identity fields persistable. `WalletReservationRepository.updateWithInOptimisticLock(...)` does this for reservations, but balance snapshots are more central and are cached by identity.

Safer options:

- update only `available`, `reserved`, `version`, and `updatedAt`
- keep immutable fields in the `WHERE` clause as guards
- add old amount/version checks to the `WHERE` clause
- use a small repository method dedicated to balance-state mutation, not a generic full-entity update

If you follow the reservation pattern exactly, add tests proving identity fields are not mutated by the update function.

### Cache strategy choice depends on promotion state

`BalanceSnapshotStore.determineBalanceStrategy(walletId)` chooses hot vs normal cache based on `hotBalanceClassifier`.

That means `afterDbUpdate(...)` must be clear about promotion behavior:

- If the wallet is already promoted, `POST_REFRESH` should write the new snapshot value.
- If the wallet is not promoted, `CACHE_ASIDE` should write a tombstone.
- If `OwnerType.SYSTEM` should always be hot, then update flow may need to promote before choosing the strategy.

Be careful here. Promoting on every update can grow the permanent in-memory classifier. Not promoting means an updated system snapshot may get tombstoned instead of refreshed until it is read and promoted. Both are defensible, but the policy must be explicit.

### Ref index cache does not need update on balance mutations

The `refId -> walletId` mapping is an index. Balance mutations do not change it.

`afterDbUpdate(...)` should refresh/invalidate only the wallet-id entity cache. It should not rewrite the ref index unless future code allows `serviceId`, `walletReferenceId`, or `walletId` to change.

This separation is important for layering: ref-cache correctness is about identity; entity-cache correctness is about mutable balances.

### Cross-service consistency is still bounded by wallet-service DB state

The wallet service updates snapshots before and after ledger posting:

- before posting: reserve/release changes are committed with wallet transaction/action/outbox creation
- after posting: transfer-in/transfer-out/consume effects are committed when ledger posting completes

Cache refresh should reflect the wallet-service DB state after each commit. It cannot prove ledger-service state by itself.

That is acceptable if the snapshot is defined as wallet-service state. It is not enough if the API is expected to prove ledger-service settlement state. In that case, the query contract needs to say which state it reports.

## Recommended Design

### Add a write-side snapshot update processor

Create a small component analogous to `UpdateReservationProcessor`, but stricter:

- input: candidate snapshots or snapshot ids plus a mutation function
- inside transaction: select locked snapshots by PK in deterministic order
- mutate locked rows in memory
- validate amount/status invariants while mutating
- update each changed row with old-state/version guards
- return the final updated snapshots to the caller

This keeps the posting processors focused on transaction orchestration and keeps row-lock/update rules in one place.

### Add repository methods with clear contracts

Recommended methods:

- `selectInIdForUpdate(List<Long> ids)` using `LambdaQueryWrapper`, `orderByAsc(id)`, and `last("for update")`
- `updateWithOptimisticLock(BalanceSnapshot now, BalanceSnapshot old)` or a narrower `updateBalancesWithOptimisticLock(...)`

The narrower method is better. It should guard:

- `id`
- old `version`
- `walletId`
- `assetId`
- `walletStatus`
- old `available`
- old `reserved`

Then it should set:

- new `available`
- new `reserved`
- new `version`
- `updatedAt`

Do not rely on a broad full-entity update unless tests prove the generated SQL and mutation boundaries are safe.

### Add `BalanceSnapshotStore.afterDbUpdate(...)`

The store method should be small and symmetric with `postInsert(...)`:

```java
public void afterDbUpdate(BalanceSnapshot snapshot) {
    Result<Boolean> result = determineBalanceStrategy(snapshot.getWalletId())
        .afterUpdate(snapshot.getWalletId(), snapshot, snapshot.getVersion());
    if (!result.isSuccess()) {
        log.error("after_update, cache strategy failed: {}, snapshot: {}", result, snapshot);
    }
}
```

Whether it promotes `OwnerType.SYSTEM` before strategy selection should be a conscious policy decision, not an accident.

### Wire cache actions after transaction success

For `BeforePostLedgerProcessor`:

- collect final updated snapshots from the transactional update
- commit wallet transaction/actions/reservations/outbox
- after successful commit, call `balanceSnapshotStore.afterDbUpdate(...)` for each final snapshot
- then continue idempotency done marking and outbox publishing

For `AfterPostLedgerProcessor`:

- collect final updated snapshots from the transactional update
- commit reservation/snapshot/transaction-status changes
- after successful commit, call `balanceSnapshotStore.afterDbUpdate(...)` for each final snapshot
- then reload and return the transaction

If cache refresh fails, do not roll back the already committed DB transaction. But log with enough identifiers to repair: `walletId`, `serviceId`, `walletReferenceId`, old version, new version, transaction id.

## Better Alternatives If Freshness Is Strict

If `GetSnapshotByRefId` must be strongly current, the better design may be:

- keep the hot `refId -> walletId` cache
- bypass the wallet-id entity cache for this endpoint
- fetch the current snapshot from DB by wallet id after resolving the ref

This preserves the useful index optimization while avoiding stale mutable cache reads. The trade-off is one DB read per query, but that is the honest price of strict freshness without a reliable invalidation pipeline.

Another option is to make entity invalidation reliable:

- emit cache-invalidation work into an outbox in the same DB transaction
- process it after commit with retry
- write versioned tombstones or refreshed values using the existing `VersionCacheStrategy`

That is more complex, but it closes the crash window that best-effort post-commit refresh leaves open.

## Tests I Would Require

- `reserve` updates `available`, `reserved`, and increments `version`.
- `release` updates `available`, `reserved`, and increments `version`.
- `transferOut` and `transferIn` update the expected fields and increment `version`.
- Multiple actions on the same wallet produce one final snapshot update and one cache action.
- Cache update is called only after a successful DB transaction.
- Cache update is not called when the DB transaction rolls back.
- Normal snapshot update writes a versioned tombstone through `CACHE_ASIDE`.
- Hot snapshot update writes the refreshed value through `POST_REFRESH`.
- A stale cached snapshot with an older version cannot overwrite a newer tombstone/value.
- Two-wallet transfers lock snapshot rows in deterministic id order.
- A retry of finalized `AfterPostLedgerProcessor` does not leave stale cache unrepairable, or the residual stale-window is explicitly accepted.

## Final Recommendation

Proceed with the coherency refactor before relying on the hot ref cache.

But keep the scope tight:

- do not redesign the whole cache strategy interface for this fix
- do not put cache writes inside DB transactions
- do not refresh the ref index on balance-only mutations
- do not rely on SQL arithmetic updates if you need a committed value/version for cache coherence

The best project-specific version is:

- lock snapshots by PK in stable order
- mutate locked snapshots in memory with balance/status checks
- update only the mutable balance fields with version/old-state guards
- collect final updated snapshots
- after commit, call a small `BalanceSnapshotStore.afterDbUpdate(...)`
- accept that this is still best-effort unless you add a reliable invalidation retry path
