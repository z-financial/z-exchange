# Cache Design Evaluation

## 1. Overall score
- Score: 7/10

## 2. Overall verdict
- decent (interview-ready)

## 3. Suggested engineer level
- strong mid

## 4. Scope reviewed

- Reviewed `common-util/src/main/java/com/exchange/common/redis`, excluding `common-util/src/main/java/com/exchange/common/redis/idemp`.
- Reviewed reference call sites:
  - `wallet-service/src/main/java/com/exchange/app/wallet/processor/balance/GetBalanceSnapshotProcessor.java`
  - `ledger-service/src/main/java/com/exchange/app/ledger/processor/post/GetLedgerTxnProcessor.java`
- Reviewed cache-binding stores and registration code:
  - `wallet-service/src/main/java/com/exchange/app/wallet/dao/store/BalanceSnapshotStore.java`
  - `wallet-service/src/main/java/com/exchange/app/wallet/dao/cache/BalanceSnapshotCacheRegister.java`
  - `ledger-service/src/main/java/com/exchange/app/ledger/dao/store/LedgerTxnStore.java`
  - `ledger-service/src/main/java/com/exchange/app/ledger/dao/cache/LedgerCacheRegister.java`
- Reviewed selected surrounding code needed for invariants and runtime behavior: wallet update paths, ledger insert/update paths, `PromotionClassifier`, `standard/cache-design.txt`, and `CLAUDE.md`.
- Did not read existing files under `./ai`.
- TTL durations were not scored in detail, per instruction.

## 5. Design summary

The refactor separates cache behavior into a component stack:

- `ValueCodec` encodes raw values as `{type}:{content}` and decodes value, tombstone, and negative-cache markers.
- `VersionCodec` wraps the value codec with `{version}|{encoded-value}`.
- `DefaultCacheReader` reads a string from either Redis or Redisson client-side caching and returns `CacheReadResult<T>`.
- `RawCacheWriter` writes raw stable values and negative values.
- `VersionCacheWriter` writes versioned values, tombstones, and negative values. Versioned value and tombstone writes go through `set-if-absent-or-newer.lua`.
- `RawCacheStrategy` and `VersionCacheStrategy` expose lifecycle operations: `get`, `afterDbHit`, `afterDbMiss`, `afterUpdate`, and `afterInsert`.
- `StrategyFactory` can wrap strategies to downgrade `CacheException` into `Result` failures so business flows fall back to DB.

Service code composes these primitives:

- `BalanceSnapshotCacheRegister` creates normal and hot balance caches. Normal balance cache is versioned cache-aside with tombstone. Hot balance cache is versioned post-refresh with negative cache and client-side reads. Ref caches are raw caches, with client-side reads for the hot ref path.
- `BalanceSnapshotStore` chooses hot vs normal strategies through in-memory `PromotionClassifier` instances. System-owner snapshots promote their wallet id and scoped ref id.
- `LedgerCacheRegister` creates a raw negative ref cache and a versioned cache-aside transaction cache.
- `LedgerTxnStore` composes `ref -> txn_id` and `txn_id -> txn` caches manually.

## 6. What is working well

- The mutable-entity cache uses a versioned payload and atomic version comparison. `DefaultVersionCacheStrategy.afterDbHit` calls `VersionCacheWriter.setIfAbsentOrNewer`, and `DefaultVersionCacheStrategy.afterUpdate` uses tombstones for `CACHE_ASIDE` strategies. This is the right shape for avoiding stale DB-read cache fill after a newer write.
- The Lua script gives the important invariant: absent keys are set, and existing values are replaced only when `newVersion >= oldVersion`. That protects entity caches from older values overwriting newer values.
- Tombstones are represented as first-class cache states through `CacheReadResult.TOMBSTONE_HIT`, not as ad hoc nulls.
- Cache failures are not business failures. The factory wrapper downgrades `CacheException`, and both `BalanceSnapshotStore` and `LedgerTxnStore` continue through DB reads on cache errors.
- The design distinguishes mutable entity caches from stable ref/index caches. That matches the project rule in `CLAUDE.md`: "Versioned cache for mutable data, stable cache for immutable mapping".
- The ref-cache read path handles a stale positive index defensively. In both `LedgerTxnStore.getByRefId` and `BalanceSnapshotStore.doGetByRefId`, a ref hit followed by a missing entity falls back to DB by ref instead of returning null immediately.
- Negative-cache hits do not renew their TTL in the store logic. That avoids accidentally making random misses sticky forever.
- Client-side caching is isolated behind `BaseCacheReadSupport`, so hot reads can switch from RedisTemplate reads to Redisson client-side reads without changing the store read flow.
- There is an explicit self-recovery hook for malformed cached values: `DefaultRawCacheStrategy.get` and `DefaultVersionCacheStrategy.get` delete malformed/serialization-failing values through `RawDeleteSelfRecoverFeature`.

## 7. Main design concerns

1. Raw ref negative cache can overwrite a newly inserted positive mapping.

`RawCacheWriter.setNegative` uses an unconditional Redis `set`, and the code comment explicitly says "conflict risk without version check". That risk is real for both `ledgerRefCache` and `balanceRefCache`.

Example race:

- Request A calls `getByRefId(ref)`, cache misses, DB returns null.
- Request B inserts the wallet or ledger txn and runs `afterInsert`.
- Request A then runs `afterDbMiss` and writes `N:` over the ref key.
- Later external callers hit the negative cache and get "not found" until negative TTL expires.

For normal raw caches, `DefaultRawCacheStrategy.afterInsert` under `CACHE_ASIDE` only clears negative cache. It does not make a positive write win against a delayed negative write. For hot raw caches, `POST_REFRESH` writes the positive value, but a delayed `setNegative` can still overwrite it because raw negative writes are unconditional.

This is the most important correctness flaw in the design because the prompt explicitly says ref lookups may be external and need negative-cache protection.

2. Hot-cache promotion is local and best-effort, not a system invariant.

`DefaultPromotionClassifier` is an in-memory concurrent set. It is not distributed, not persisted, has no eviction, and is lost on restart. `BalanceSnapshotStore.determineBalanceStrategy` and `determineRefStrategy` therefore select hot behavior only on instances that have already observed and promoted the id.

This matters for writes. `BalanceSnapshotStore.postDbUpdate` chooses the update strategy from the local classifier. If a system wallet update runs on an instance that has not promoted the wallet id, the update uses `normalBalanceCache` and writes a tombstone instead of post-refreshing the hot value. The design still remains correct enough under DB fallback, but it does not reliably implement "system balance snapshot is hot path for both update and read".

3. Initial system-wallet reads are not fully hot on the first access.

`BalanceSnapshotStore.getByWalletId` calls `doGetByWalletId` before promotion. If the wallet id is not already in the local classifier, the first read uses `normalBalanceCache`. Only after the DB/cache value is returned does `getByWalletId` promote the id. Because normal and hot caches share the same Redis key namespace, the next read can benefit, but the first read does not get the hot strategy's negative-cache or client-side behavior.

This may be an acceptable "lazy local promotion" trade-off, but it should be documented as a warm-up behavior, not described as always hot.

4. Cache update is best effort, so stale value risk is bounded mostly by TTL, not by the design invariant.

The version CAS prevents older cache writes from replacing newer cache writes. It does not protect against missed cache writes. If `postDbUpdate` is not called, fails, or runs on the wrong strategy, an old value can remain until TTL expires. That is normal for cache-aside, but it is an important boundary for balance snapshots because reads may return stale balances.

The DB update paths are still the source of truth and use DB locks/optimistic versioning. The concern is read freshness, not balance mutation correctness.

5. The strategy abstraction does not own the read-through invariant.

`RawCacheStrategy` and `VersionCacheStrategy` expose lifecycle hooks, but the service stores manually implement:

- how to interpret `VALUE_HIT`, `NEGATIVE_HIT`, and `TOMBSTONE_HIT`
- when to query DB
- when to write negative cache
- how to repair stale ref -> entity links
- how to combine ref caches and entity caches

`CacheReadResult.shouldQueryDb()` exists but is not used by either store. The important behavior is duplicated across `BalanceSnapshotStore` and `LedgerTxnStore`. This keeps the common layer flexible, but it leaves correctness conventions outside the abstraction.

6. Raw cache strategy is a footgun for mutable data.

`DefaultRawCacheStrategy.afterUpdate` delegates to `afterDbHit`, which is an unconditional raw overwrite. That is fine only if the data is genuinely stable or if stale overwrite is acceptable. The type name `RawCacheStrategy` does not prevent use on mutable data, and the strategy does not encode "positive must beat negative" semantics.

7. Client-side cache runtime behavior is not proven in this code.

`RedissonRegister` creates a separate RESP3 Redisson client for client-side caching and `CacheSupportRegister` wires client-side readers for hot paths. That is architecturally reasonable. However, I did not find an integration test proving that writes through RedisTemplate/default Redisson invalidate or bypass stale client-side cached values as expected. I also did not see tests around client-side cache behavior under tombstone/post-refresh updates.

8. Lua malformed-version handling is incomplete.

The script handles missing `|` by overwriting defensively, but it does not handle a non-numeric version prefix. `tonumber(...)` can return nil, and comparing `newVersion >= oldVersion` can fail. Read self-recovery handles malformed cached values, but write-side self-recovery is weaker.

## 8. Strategy alignment review

### Versioned cache for mutable entity data

- expected by guide: Mutable data should use versioned cache so stale fills cannot overwrite newer values.
- actual implementation: `DefaultVersionCacheStrategy.afterDbHit` uses `VersionCacheWriter.setIfAbsentOrNewer`; `VersionCacheWriter` delegates to `set-if-absent-or-newer.lua`.
- alignment: strong
- mismatch or uncertainty: The invariant only holds when cache writes happen. Failed or missing post-DB cache updates still leave stale values until TTL.

### Cache-aside with tombstone for normal mutable data

- expected by guide: Normal mutable entity cache should set a tombstone after DB writes, then DB-read refill should only write if its version is current.
- actual implementation: `normalBalanceCache` and `ledgerTxnCache` are configured as `StrategyType.CACHE_ASIDE`; `DefaultVersionCacheStrategy.afterUpdate` writes a tombstone for cache-aside.
- alignment: strong
- mismatch or uncertainty: The store must remember to call `postDbUpdate` after DB commit. There is no common transaction hook that enforces this.

### Hot system balance snapshot path

- expected by guide: `wallet_id -> system balance snapshot` should use versioned cache, negative cache, lazy local promotion, client-side cache, and a specific Redisson client.
- actual implementation: `hotBalanceCache` is versioned `POST_REFRESH`, has negative cache enabled, and uses `clientSideVersionCacheReader`. `RedissonRegister` defines a separate RESP3 client-side cache client. Promotion is via local `PromotionClassifier`.
- alignment: partial
- mismatch or uncertainty: Promotion is local and best-effort. First access may use normal strategy. Writes on an unpromoted instance may tombstone instead of post-refresh. Negative cache only applies after local promotion selects `hotBalanceCache`.

### Normal balance snapshot and ledger transaction entity paths

- expected by guide: `wallet_id -> user balance snapshot` and `txn_id -> ledger_txn` are normal paths using versioned cache and tombstone.
- actual implementation: `normalBalanceCache` and `ledgerTxnCache` are versioned `CACHE_ASIDE` caches with tombstone TTL.
- alignment: strong
- mismatch or uncertainty: `LedgerTxnStore.getByTxnId` checks `isNegativeHit()` even though `ledgerTxnCache` disables negative cache. This is harmless, but it shows the store layer is not tightly aligned with per-strategy capabilities.

### External ref lookup protection

- expected by guide: `ref -> wallet_id/txn_id` is stable and externally callable, so it should use raw cache plus negative cache.
- actual implementation: `balanceRefCache` and `ledgerRefCache` are raw cache-aside strategies with `DefaultNegativeCacheFeature`.
- alignment: partial
- mismatch or uncertainty: The raw negative write is not safe against concurrent insert. Positive ref mappings do not reliably win over delayed negative writes.

### Hot system ref lookup

- expected by guide: `ref -> wallet_id` for system wallet is stable and hot.
- actual implementation: `hotBalanceRefCache` uses raw `POST_REFRESH` and client-side reads after local promotion.
- alignment: partial
- mismatch or uncertainty: Same local-promotion concern as hot entity cache. Also, raw negative overwrite risk still applies.

## 9. Runtime / system behavior review

### cache miss

On a Redis miss, `DefaultCacheReader.get` returns `CacheReadResult.miss()`. The stores then query DB and call `afterDbHit` or `afterDbMiss`.

This behavior is reasonable. The downside is that it is implemented by each store rather than as a common read-through helper, so future stores may handle miss/tombstone/negative states differently.

### stale data risk

Versioning prevents stale cache fill from winning after a newer version is already present. It does not guarantee fresh reads after DB update. Freshness depends on:

- `postDbUpdate` or `afterUpdate` being called after DB commit
- the cache write succeeding
- the local strategy selection choosing the intended hot/normal behavior
- TTL expiration when cache refresh fails
- client-side cache invalidation or short client-side TTL

This is acceptable for non-authoritative reads, but for balance snapshot reads it should be stated explicitly: the cache is not a linearizable read model.

### concurrent reads/writes

Entity cache concurrency is mostly well designed:

- concurrent DB-read fills cannot overwrite a newer tombstone/value if their version is older
- tombstones prevent stale values from surviving normal cache-aside updates when the tombstone write succeeds
- hot post-refresh updates can write the new value directly, guarded by version comparison

Ref cache concurrency is weaker:

- raw positive writes and raw negative writes do not have a "positive wins" rule
- delayed negative writes can hide recently inserted stable refs
- `afterInsert` on cache-aside ref only deletes; delete is not enough to close the race

### future client-side cache / hot-key expansion

The reader abstraction makes hot-key expansion easy: a cache can switch from `defaultVersionCacheReader` to `clientSideVersionCacheReader` at registration time. That is a good extension point.

The limiting factor is classification. `DefaultPromotionClassifier` is unbounded and process-local. It works as a simple lazy promotion mechanism, but not as a robust hot-key control plane. A production hot-key design usually needs one or more of:

- deterministic classification from domain data
- preloading known hot keys
- distributed/shared hot-key state
- bounded local cache with eviction
- metrics-driven promotion/demotion
- tests proving client-side invalidation semantics

### ref cache and entity cache consistency

The design composes ref and entity caches manually:

- ref hit returns an entity id
- entity cache is queried
- if the entity is missing, the store falls back to DB by ref

That fallback is good. But the ref and entity caches are not updated atomically, and ref negative cache can conflict with inserts. The design assumes ref mappings are stable after creation. That assumption is reasonable, but "stable after creation" does not eliminate the absence-to-present race during creation.

## 10. Abstraction and extensibility review

The design is a meaningful improvement over hand-coded Redis calls. `CacheDescriptor`, `RawStrategyConfig`, `VersionStrategyConfig`, feature objects, codec objects, and the strategy factory provide useful modularity.

The main abstraction weakness is that the strategy layer is callback-oriented rather than behavior-oriented. It gives callers pieces of the protocol but does not enforce the whole protocol. For example, a caller can:

- forget to query DB on tombstone
- treat negative hit incorrectly
- call raw strategy on mutable data
- use cache-aside ref strategy where positive-after-insert should be preferred
- forget to run post-commit cache update

The current design is extensible for adding more cache registrations. It is less strong at preventing incorrect registrations.

A better abstraction boundary would provide domain-safe operations such as:

- `getOrLoad(id, loader)`
- `getByRefOrLoad(ref, entityLoader, entityCache)`
- `afterCommittedInsert(id, value, version)`
- `afterCommittedUpdate(id, value, version)`
- `setNegativeIfAbsent`
- `setPositiveOverNegative`

Those operations would let the common layer own the important cache-state transitions.

## 11. Strongest design decisions

- Versioned entity cache with Lua CAS is the strongest part of the design.
- Tombstone support for cache-aside mutable data is a good fit for concurrent stale-read protection.
- Cache failures degrade to DB instead of failing the business operation.
- The design separates raw stable mapping caches from mutable entity caches.
- Negative-cache hits are not renewed on every negative hit.
- Client-side caching is introduced behind the read-support abstraction rather than scattered into store code.
- Ref-cache stale-positive fallback to DB by ref is a practical resilience choice.
- Cache content uses explicit markers instead of ambiguous null values.

## 12. Weakest design decisions

- Raw negative cache writes are unconditional and can overwrite positive mappings after insert.
- Hot classification is in-memory only and therefore cannot guarantee hot behavior on all instances or after restart.
- The strategy interface leaks too much protocol responsibility to service stores.
- Ref cache and entity cache updates are non-atomic and rely on manual ordering.
- `DefaultRawCacheStrategy.afterUpdate` is an unconditional overwrite and could be misused for mutable data.
- There is no evident integration coverage for the common cache package, Lua version edge cases, or client-side cache invalidation.
- The cache registration layer relies heavily on qualifiers and has multiple readers of the same generic type; future unqualified injection will be easy to get wrong.

## 13. Signs of mature engineering judgment

- The design correctly recognizes that mutable data and stable mappings need different cache strategies.
- It keeps DB as the source of truth and treats Redis failure as a performance degradation, not a correctness failure.
- Cache refresh after wallet DB updates is intentionally performed after the DB transaction commits.
- The versioned cache design accounts for stale DB readers racing with newer writes.
- There is explicit handling for tombstone, negative, value hit, and miss states.
- There is an attempt at self-healing malformed cache values.
- The ledger ref lookup has a defensive fallback when a positive ref cache points to a missing entity.
- The code avoids renewing negative-cache TTL on every negative hit.

## 14. The 3 highest-leverage design improvements

1. Make raw ref negative caching race-safe.

Positive mappings must always win over negative mappings for stable refs. At minimum:

- change negative writes to "set negative only if absent"
- change positive insert/write to overwrite negative and absent values
- do this atomically in Lua, not with separate get/set/delete commands
- prefer writing the positive ref mapping after insert for externally callable refs, not only clearing negative cache

This directly fixes the biggest correctness gap.

2. Make hot classification explicit enough for write paths.

If system wallets are truly hot for both reads and updates, the write path should not depend on whether the current JVM has lazily promoted the wallet id. Options:

- pass known `OwnerType` or hotness into `postDbUpdate`
- pre-promote system wallet ids from DB or config on startup
- persist/distribute hot-key classification
- use a bounded local hot-key cache fed by metrics or known system-wallet metadata
- consider using post-refresh for all balance updates if the operational cost is acceptable

The key requirement is that a system-wallet write should reliably use the intended strategy.

3. Move read-through and ref/entity composition into reusable common abstractions, then test them.

The common layer should own the protocol for:

- miss -> DB -> cache fill or negative
- tombstone -> DB -> versioned fill
- negative hit -> return null without renewal
- ref hit -> entity lookup -> fallback by ref if entity missing
- malformed value -> delete and DB fallback

Add focused tests for the common cache transitions, especially:

- stale DB fill loses to newer tombstone/value
- negative before insert is cleared or overwritten by positive insert
- delayed negative cannot overwrite positive ref mapping
- client-side reader observes update/tombstone behavior
- malformed version prefix does not break write recovery

## 15. Final judgment

This is a good refactor with a real cache architecture behind it. The versioned entity cache, tombstone model, explicit cache states, soft-fail behavior, and client-side-cache extension point are above average and show solid engineering judgment.

It is not yet a strong production cache design for the stated workload because two correctness boundaries are not closed: raw ref negative cache can hide newly created refs, and hot system-wallet behavior is local best-effort rather than a reliable invariant. The design is good enough to discuss in an interview as a serious implementation, but the expected next step is to harden the raw negative-cache transition and make hot-path strategy selection deterministic enough for writes.
