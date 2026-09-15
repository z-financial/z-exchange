# GetBalanceSnapshotProcessor Review

## 1. Scope reviewed

- Branch: `feature/cache-strategy-for-hot-data`
- Target commits:
  - `a313cb7` `support hot snapshot cache strategy`
  - `a60ad6c` `implementing hot path cache logic (NOT DONE)`

## 2. Relevant files / modules inspected

- `wallet-service/src/main/java/com/exchange/app/wallet/processor/balance/GetBalanceSnapshotProcessor.java`
- `wallet-service/src/main/java/com/exchange/app/wallet/dao/store/BalanceSnapshotStore.java`
- `wallet-service/src/main/java/com/exchange/app/wallet/dao/cache/WalletCacheRegister.java`
- `wallet-service/src/main/java/com/exchange/app/wallet/register/PromotionClassifierRegister.java`
- `wallet-service/src/main/java/com/exchange/app/wallet/constant/cache/CacheTtlStrategies.java`
- `wallet-service/src/main/java/com/exchange/app/wallet/processor/CreateWalletProcessor.java`
- `wallet-service/src/main/java/com/exchange/app/wallet/processor/transaction/step/BeforePostLedgerProcessor.java`
- `wallet-service/src/main/java/com/exchange/app/wallet/processor/transaction/step/AfterPostLedgerProcessor.java`
- `wallet-service/src/main/java/com/exchange/app/wallet/dao/repository/BalanceSnapshotRepository.java`
- `wallet-service/src/main/java/com/exchange/app/wallet/po/wallet/BalanceSnapshot.java`
- `common-util/src/main/java/com/exchange/common/component/PromotionClassifier.java`
- `common-util/src/main/java/com/exchange/common/component/DefaultPromotionClassifier.java`
- `common-util/src/main/java/com/exchange/common/redis/cache/model/StrategyOption.java`
- `common-util/src/main/java/com/exchange/common/redis/cache/strategy/VersionCacheStrategy.java`
- `common-util/src/main/java/com/exchange/common/redis/cache/strategy/impl/DefaultVersionCacheAsideStrategy.java`
- `common-util/src/main/java/com/exchange/common/redis/cache/strategy/impl/DefaultVersionPostRefreshStrategy.java`
- `common-util/src/main/java/com/exchange/common/redis/cache/ops/impl/VersionNegativeCacheOpsImpl.java`
- `wallet-service/src/test/java/com/exchange/app/wallet/processor/balance/GetBalanceSnapshotProcessorUnitTest.java`
- `wallet-service/src/test/java/com/exchange/app/wallet/processor/QueryBalanceTest.java`
- `wallet-service/src/test/java/com/exchange/app/wallet/dao/store/BalanceSnapshotStoreUnitTest.java`

## 3. Current logic flow of `GetBalanceSnapshotProcessor`

1. `GetBalanceSnapshotProcessor.getBalanceSnapshot(...)` validates `lookupType` and `lookupValue`, then delegates to `BalanceSnapshotStore`.
2. `BalanceSnapshotStore.getByWalletId(...)`:
   - chooses cache strategy through `determineCacheStrategy(walletId)`;
   - reads versioned snapshot cache;
   - falls back to `BalanceSnapshotRepository.getByWalletId(...)`;
   - on DB hit calls `afterDbHit(...)`, on DB miss calls `afterDbMiss(...)`.
3. `BalanceSnapshotStore.getByRefId(...)`:
   - first checks stable ref cache `refId -> walletId` or negative;
   - if ref cache hits a wallet id, delegates to `getByWalletId(...)`;
   - otherwise loads snapshot by ref from DB, writes ref cache, then warms snapshot cache.
4. `CreateWalletProcessor` is the only reviewed flow that calls `BalanceSnapshotStore.postInsert(...)`.
5. Snapshot mutation flows in `BeforePostLedgerProcessor` and `AfterPostLedgerProcessor` update DB rows directly through `BalanceSnapshotRepository`, without any snapshot-cache callback afterward.

## 4. How hot snapshot cache strategy currently works

- Hot routing is driven by `PromotionClassifier`.
- A wallet is promoted only when `BalanceSnapshotStore.postInsert(..., ownerType)` runs and `ownerType == OwnerType.SYSTEM`.
- Hot route:
  - bean: `hotSnapshotCache`
  - read path: `clientSideReadableCache`
  - write policy: `DefaultVersionPostRefreshStrategy`
- Normal route:
  - bean: `normalSnapshotCache`
  - read path: `defaultReadableCache`
  - write policy: `DefaultVersionCacheAsideStrategy`
- Both strategies use the same Redis snapshot key space; the difference is read source and write/update policy.
- The classifier is JVM-local only (`ConcurrentHashMap.newKeySet()`), with no rebuild from DB/Redis and no cross-instance sharing.

## 5. Bugs found

### Bug 1: snapshot cache is never refreshed or invalidated after balance mutations

Evidence:

- `BalanceSnapshotStore` only has `postInsert(...)`; there is no post-update method.
- `BeforePostLedgerProcessor.updateSnapshotsBeforePosting(...)` updates snapshots through:
  - `reserveFromWalletId(...)`
  - `releaseFromWalletId(...)`
- `AfterPostLedgerProcessor.updateSnapshotsAfterPosting(...)` updates snapshots through:
  - `transferOutFromWalletId(...)`
  - `transferInToWalletId(...)`
- None of those flows call back into `BalanceSnapshotStore` after the DB write succeeds.

Impact:

- Once a snapshot has been cached by `getByWalletId(...)` or `getByRefId(...)`, later reserve/release/transfer operations can leave Redis serving stale `available` / `reserved` values until TTL expiry.
- The hot strategy is especially inconsistent: `DefaultVersionPostRefreshStrategy.afterUpdate(...)` exists specifically to refresh hot data, but it is never invoked.
- This is a direct balance-query correctness bug, not just a cache-hit-rate problem.

Concrete fix:

- Add a `postUpdate(...)` path in `BalanceSnapshotStore`, and call it after the DB transaction commits for every affected snapshot in both `BeforePostLedgerProcessor` and `AfterPostLedgerProcessor`.
- For hot snapshots, pass refreshed value + new version.
- For normal snapshots, at minimum publish tombstone/new-version invalidation.
- Safest implementation: reload the committed snapshot rows after commit, then update cache from the committed state instead of trying to reuse stale in-memory objects.

### Bug 2: hot routing does not self-correct on read, so existing system wallets are misrouted

Evidence:

- `BalanceSnapshotStore.promoteHotSnapshot(...)` is only called from `postInsert(...)`.
- `getByWalletId(...)` decides `snapshotCache` before reading DB, and never re-promotes after learning `ownerType`.
- `doGetByRefId(...)` has `snapshot.getOwnerType()` on DB hit, but does not call `promoteHotSnapshot(...)` before warming cache.
- `DefaultPromotionClassifier` keeps promotions only in local memory.

Impact:

- A system wallet that already exists before this JVM starts will usually stay on `normalSnapshotCache`, not `hotSnapshotCache`.
- A first query by `walletId` cannot route itself to hot even after DB reveals it is a system wallet.
- Different application instances can disagree about whether the same wallet is hot, because the classifier is local-only.
- In practice, the hot route is only reliably activated for wallets that passed through the insert path on the current JVM.

Concrete fix:

- Promote on read once DB reveals `OwnerType.SYSTEM`.
- In `getByWalletId(...)`, recompute the strategy after DB hit before writing cache.
- In `doGetByRefId(...)`, promote before `afterDbHit(...)`.
- Prefer a durable or deterministic router instead of an instance-local promoted-key set. If hotness is really `OwnerType.SYSTEM`, route from durable business data rather than local memory.

### Bug 3: `requireNegativeCache` logic is inverted in the generic versioned strategies

Evidence:

- `DefaultVersionCacheAsideStrategy.afterDbMiss(...)` returns early when `option.requireNegativeCache()` is `true`.
- `DefaultVersionPostRefreshStrategy.afterDbMiss(...)` does the same.
- `DefaultVersionCacheAsideStrategy.afterInsert(...)` also skips negative cleanup when `option.requireNegativeCache()` is `true`.

Impact:

- `BalanceSnapshotStore.getByWalletId(...)` will not negative-cache wallet-id misses even when the strategy is configured with `requireNegativeCache = true`.
- The same bug also flips behavior for other users of the generic strategy, including ledger cache wiring.
- This is a code-level correctness bug in the abstraction, not just a wallet-specific configuration issue.

Concrete fix:

- Invert the conditionals:
  - if negative cache is disabled, return success without writing;
  - if negative cache is enabled, actually set/clean it.
- Add focused unit tests for both boolean branches in both strategy implementations.

## 6. Potential risks / edge cases

- `BalanceSnapshotStore.getByRefId(...)` always calls `setNegative(...)` after a null result, even when the null came from an existing negative-cache hit. The code already has a TODO here. Under repeated miss traffic, this can renew negative TTL indefinitely.
- `VersionNegativeCacheOpsImpl.cleanNegative(...)` uses raw delete with no CAS. If write-side cache integration is completed later, this can delete a newer cache entry created by another concurrent path.
- `WalletCacheRegister.normalSnapshotCache(...)` uses `cacheTtlStrategies.getHotLedgerTxnOption()` instead of a normal-snapshot-specific option. Today both options are identical, so this is not a live correctness bug, but it is an obvious copy/paste trap.
- `CacheTtlStrategies` in wallet still uses ledger-oriented names (`hotLedgerTxnOption`, `normalLedgerTxnOption`) for snapshot strategy options. That makes future tuning error-prone.

## 7. Business-flow risks

- Current read/write behavior does not match the intended “hot snapshot” design. The read path caches snapshot data, but the balance mutation path does not publish any corresponding refresh/invalidation, so queries can observe pre-transaction balances.
- On multi-instance deployment, the same system wallet can use different routes on different nodes because the promotion set is local memory only. Today this mostly reduces hot-path coverage; once write-side strategy callbacks are added, it can also change invalidation behavior per node.
- If the intent is “system wallets are hot”, the current implementation does not cover existing system wallets after restart. That means the feature misses the real hot set until each wallet is re-promoted somehow.

## 8. Fix suggestions

- Add `BalanceSnapshotStore.postUpdate(...)` and invoke it after successful snapshot DB mutations in both posting stages.
- Use committed DB state for post-update cache actions. For hot data, refresh Redis with the committed snapshot; for normal data, publish tombstone/new-version invalidation.
- Promote hot wallets on read when DB reveals `OwnerType.SYSTEM`.
- Replace or augment `PromotionClassifier` with deterministic durable routing.
- Fix the inverted `requireNegativeCache` branches in generic cache strategies.
- Restore store-level tests and add end-to-end query-freshness tests around reserve/release/transfer flows.

## 9. Optimization suggestions

- Rename wallet cache options from ledger-specific names to snapshot-specific names to reduce copy/paste mistakes.
- Extract the hot/normal selection into a dedicated router or policy class so `BalanceSnapshotStore` does not need to own both business classification and cache mechanics.
- Add explicit tests for:
  - system-wallet promotion after restart / first read;
  - stale-cache prevention after reserve/release/transfer;
  - negative-cache flag semantics;
  - repeated ref miss behavior.

## 10. Overall assessment

- `GetBalanceSnapshotProcessor` itself is simple and fine; the main issues are below it in store/strategy/write-path coordination.
- Bean names and injection wiring for `hotSnapshotCache`, `normalSnapshotCache`, and `hotBalanceSnapshotClassifier` are internally consistent in the reviewed code.
- The feature is not complete enough for production balance queries yet. The branch introduces the read-side cache plumbing, but the write-side cache behavior and durable hot routing are still incomplete, and current code can return stale balances.

## 11. Open questions / incomplete parts

- Is “hot snapshot” exactly equivalent to `OwnerType.SYSTEM`, or is there another durable business classifier?
- Should wallet-id lookups intentionally use negative caching? If not, rename the option so the abstraction is not misleading.
- When you wire post-update cache actions, do you want strict post-commit refresh for hot data, or tombstone-only invalidation plus lazy refill? The current hot strategy class assumes the former, but the processors do not yet provide the committed value/version needed for it.

