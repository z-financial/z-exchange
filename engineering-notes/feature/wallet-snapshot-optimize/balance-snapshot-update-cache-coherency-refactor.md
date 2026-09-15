# Balance Snapshot Update Cache Coherency Refactor

## Summary

This change refactors the wallet balance snapshot update path so mutable snapshot rows are locked, updated in memory, written once per changed snapshot, and then passed to the entity cache strategy after the DB transaction succeeds.

The goal matches `hot-balance-snapshot.md`: the hot `refId -> walletId` cache is only an index optimization. If snapshot freshness matters, the mutable `walletId -> BalanceSnapshot` cache must be invalidated or refreshed after snapshot DB updates.

## Implemented Flow

- `BeforePostLedgerProcessor.updateSnapshotsBeforePosting(...)` no longer calls the SQL-arithmetic repository methods directly.
- `AfterPostLedgerProcessor.updateSnapshotsAfterPosting(...)` no longer calls the SQL-arithmetic repository methods directly.
- `UpdateBalanceSnapshotProcessor` now locks target snapshots by PK via `BalanceSnapshotRepository.selectInIdForUpdate(...)`.
- Snapshot ids are sorted before locking, and the SQL query also orders by PK before `FOR UPDATE`.
- All actions for a wallet are applied to the locked `BalanceSnapshot` in memory.
- Each changed snapshot is updated once through `BalanceSnapshotRepository.updateBalanceWithInOptimisticLock(...)`.
- Only changed balance fields are set on the `toUpdate` entity; `updatedAt` is left to the MyBatis-Plus fill interceptor.
- `toUpdate.version` is set to the old version so the MyBatis-Plus optimistic locker can produce the versioned update.
- After a successful DB transaction, processors call `BalanceSnapshotStore.postDbUpdate(...)` for each changed snapshot.
- `postDbUpdate(...)` only touches the `walletId -> BalanceSnapshot` entity cache via `VersionCacheStrategy.afterUpdate(...)`; it does not update the ref-index cache.

## Validation Behavior

The refactor validates locked DB state before mutating it:

- `RESERVE` checks `available >= amount`.
- `RELEASE` checks `reserved >= amount`.
- `TRANSFER_OUT` checks `reserved >= amount`.
- `TRANSFER_IN` checks wallet status, asset, wallet id, and positive amount.
- All mutating actions check `WalletStatus.OPEN`.
- All mutating actions check wallet id and asset id against the locked snapshot.

A validation failure returns a failed `Result` inside the transaction callback, so `DbTxnExecutor` marks the transaction rollback-only.

## Fit Against The Referenced Design

This implementation follows the referenced design in the important areas:

- It fixes the entity-cache coherency path before relying on the hot ref cache.
- It keeps cache refresh outside the DB transaction.
- It treats cache freshness as best effort, not a strict correctness guarantee.
- It preserves the existing `PromotionClassifier` design.
- It keeps the ref-index cache separate from balance mutation cache handling.
- It avoids unrelated cache strategy/interface refactors.

The main deliberate constraint is that the old SQL-arithmetic repository methods are left in place but no longer used by the posting processors. Removing them is cleanup, not required for this refactor.

## Remaining Risks

- The post-commit cache step is still best effort. If the process crashes after DB commit and before `postDbUpdate(...)`, the old cached snapshot can remain until TTL or a later update.
- Correct version propagation depends on MyBatis-Plus optimistic-lock behavior for `mapper.update(toUpdate, wrapper)` with `toUpdate.version` set to the old version. This should be covered by an integration test before treating cache version ordering as proven.
- The implementation updates only changed balance fields, but it still relies on wrapper guards for old `available`, old `reserved`, `walletId`, `assetId`, and `walletStatus`. If future mutable fields are added to snapshots, this method should not be reused blindly.
- Existing finalized transaction retry behavior still does not repair a missed post-commit cache refresh. That is acceptable only under the stated best-effort cache contract.

## Verification

- Static call-site check confirms posting processors no longer call `reserveFromWalletId(...)`, `releaseFromWalletId(...)`, `transferOutFromWalletId(...)`, or `transferInToWalletId(...)`.
- `git diff --check` passed for the touched Java files.
- Maven compile was attempted with `mvn -pl wallet-service -am -DskipTests compile`, but it failed before reaching wallet-service because the local JDK cannot compile target release 21: `invalid target release: 21`.

## Recommendation

This refactor is aligned with the design direction and is a better foundation for hot balance snapshot reads than optimizing the ref cache alone.

Before relying on this for freshness-sensitive production reads, add integration coverage for:

- version increment and propagated new version after `mapper.update(toUpdate, wrapper)`
- rollback behavior on balance validation failure
- post-commit cache action not running on transaction failure
- normal cache tombstone behavior and hot cache refresh behavior