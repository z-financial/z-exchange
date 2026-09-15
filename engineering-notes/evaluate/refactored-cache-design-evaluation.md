# Cache Design Evaluation

## 1. Overall score
- Score: 6.5/10

## 2. Overall verdict
- not bad

## 3. Suggested engineer level
- mid

## 4. Scope reviewed
- Reviewed `common-util/src/main/java/com/exchange/common/redis`, excluding `common-util/src/main/java/com/exchange/common/redis/idemp`.
- Reviewed the main cache composition and usage in:
  - `wallet-service/src/main/java/com/exchange/app/wallet/dao/cache/BalanceSnapshotCacheRegister.java`
  - `wallet-service/src/main/java/com/exchange/app/wallet/dao/store/BalanceSnapshotStore.java`
  - `wallet-service/src/main/java/com/exchange/app/wallet/processor/balance/GetBalanceSnapshotProcessor.java`
  - `ledger-service/src/main/java/com/exchange/app/ledger/dao/cache/LedgerCacheRegister.java`
  - `ledger-service/src/main/java/com/exchange/app/ledger/dao/store/LedgerTxnStore.java`
  - `ledger-service/src/main/java/com/exchange/app/ledger/processor/post/GetLedgerTxnProcessor.java`
- Also checked supporting code where it affects cache design:
  - key builders, repository update methods, hot-key promotion, Lua script, and cache strategy guide notes in `CLAUDE.md`, `standard/cache-design.txt`, and `standard/next-step-design.txt`.
- Intentionally did not score unfinished snapshot update business flow.

## 5. Design summary
- The refactor has a clear layered shape:
  - codecs: `ValueCodec`, `VersionCodec`
  - read/write supports: `DefaultCacheReader`, `RawCacheWriter`, `VersionCacheWriter`, `RawCacheDeleter`
  - optional features: `NegativeCacheFeature`, `SelfRecoverFeature`
  - typed strategy layer: `DefaultRawCacheStrategy`, `DefaultVersionCacheStrategy`
  - service-side assembly: `LedgerCacheRegister`, `BalanceSnapshotCacheRegister`
- The most important invariant is the split between:
  - mutable entity cache -> versioned value with Lua CAS and tombstone support
  - stable reference cache -> raw value with optional negative cache
- The hot wallet snapshot path is implemented as a versioned strategy with:
  - client-side cache reader selection
  - negative cache enabled
  - `POST_REFRESH` write policy
  - local promotion via `PromotionClassifier`
- The design is real and coherent. It is not a toy wrapper over Redis anymore. The best part is the versioned entity cache path. The weaker part is the system-level runtime contract around failures and cross-key consistency.

## 6. What is working well
- The mutable-vs-stable split matches the guide well. `CLAUDE.md` says "Versioned cache for mutable data, stable cache for immutable mapping", and that is reflected directly in `DefaultVersionCacheStrategy` vs `DefaultRawCacheStrategy`.
- The versioned entity path has a solid core correctness model:
  - `VersionCacheWriter.setIfAbsentOrNewer(...)` and `setTombstone(...)`
  - `ScriptExecutor.setIfAbsentOrNewer(...)`
  - `set-if-absent-or-newer.lua`
  - `DefaultVersionCacheStrategy.afterDbHit(...)` and `afterUpdate(...)`
  Older readers cannot overwrite newer values or tombstones if the caller provides the correct DB version.
- Negative and tombstone semantics are separated correctly at the data model level:
  - `CacheValueInfo.ifCacheHit(...)` treats `NEGATIVE` as a terminal cache hit and `TOMBSTONE` as an invalidate-and-reload signal.
  - That is the right distinction for stable-miss protection vs mutable invalidation.
- Parse-error self-recovery is a good operational choice:
  - `DefaultCacheReader.get(...)` returns `PARSE_CACHE_ERROR`
  - both `DefaultRawCacheStrategy.get(...)` and `DefaultVersionCacheStrategy.get(...)` invoke `SelfRecoverFeature.recover(...)`
  - current recovery implementation deletes the malformed key via `RawDeleteSelfRecoverFeature`
  This is a mature boundary. Corrupt cache should not poison the path for long.
- The hot-path read customization is cleanly introduced by swapping the read support, not by forking business logic:
  - `BalanceSnapshotCacheRegister.hotSnapshotCache(...)` uses `clientSideVersionCacheReader`
  - normal path uses `defaultVersionCacheReader`
  That is a good abstraction seam.
- Service-side cache registration is explicit. It is easy to see what each business path is choosing:
  - ledger txn id -> versioned + tombstone
  - ledger ref id -> raw + negative
  - wallet normal snapshot -> versioned + tombstone
  - wallet hot snapshot -> versioned + negative + client-side read + post-refresh

## 7. Main design concerns
- The biggest design weakness is that the `Result` API suggests "cache failure degrades safely", but the code only models parse errors that happen after Redis returns a value. Actual Redis operation failures can still throw and escape:
  - `BaseRedisSupport.get/set/delete(...)` directly call `RedisTemplate`
  - `DefaultCacheReader.get(...)` does not catch Redis runtime exceptions around `baseCacheReadSupport.get(key)`
  - `VersionCacheWriter.doSetIfAbsentOrNewer(...)` and `ScriptExecutor.setIfAbsentOrNewer(...)` do not catch Redisson/runtime exceptions
  This means the design does not fully satisfy the guide's intended failure model of "Redis failure -> degrade to DB".
- Raw ref-cache insert consistency is weaker than it should be. `DefaultRawCacheStrategy.afterInsert(...)` only clears negative cache and does not write the positive mapping. Both `LedgerTxnStore.postInsert(...)` and `BalanceSnapshotStore.postInsert(...)` rely on that behavior. Consequences:
  - the first read by ref after insert still falls through to DB
  - a stale miss path can write a new negative entry after the insert-side clear
  - ref cache and entity cache can temporarily disagree on existence
- The hot-path design only partially matches the hot-key guide in `standard/next-step-design.txt`:
  - it has write-through intent via `StrategyType.POST_REFRESH`
  - it has optional local/L1-like read acceleration via client-side cache
  - it does not have miss collapse, distributed lock, or any other DB protection for synchronized hot-key misses/expiry
  For a true extremely hot key path, that is a real missing piece.
- A lot of correctness still depends on caller discipline rather than being encoded in the abstraction:
  - `DefaultVersionCacheStrategy.afterUpdate(...)` changes behavior based on `StrategyType`
  - the interface assumes callers pass the committed, newest version
  - there is no type-level distinction between "cache key" and "business id"
  The raw `afterUpdate(...)` implementation bug in `DefaultRawCacheStrategy` is a good example: it double-builds the key by calling `afterDbHit(key, value)` after already building the key. I am not scoring that as a standalone bug; I am scoring it as evidence that the current API makes this kind of misuse easy.
- The hot-key promotion policy is intentionally simple, but also very weak for long-term operation:
  - `DefaultPromotionClassifier` is local-only, unbounded, and has no demotion
  - promotion is sticky for process lifetime
  This is acceptable for an early local heuristic, but not a durable hot-key control plane.

## 8. Strategy alignment review
- Normal mutable entity cache: ledger txn id and normal wallet snapshot
  - expected by guide: versioned cache for mutable data, cache-aside refill on read, tombstone after write/update
  - actual implementation: `LedgerCacheRegister.ledgerTxnCache(...)` and `BalanceSnapshotCacheRegister.normalSnapshotCache(...)` both use `DefaultVersionCacheStrategy` with `StrategyType.CACHE_ASIDE`; reads go through `afterDbHit(...)`, updates use tombstone in `afterUpdate(...)`
  - alignment: strong
  - mismatch or uncertainty: correctness depends on the caller using the post-DB version; wallet snapshot update flow is unfinished, so only the design contract can be judged here
- Stable reference cache: ref -> wallet id / txn id
  - expected by guide: raw stable cache with negative cache protection for externally reachable lookup keys
  - actual implementation: `LedgerCacheRegister.ledgerRefCache(...)` and `BalanceSnapshotCacheRegister.balanceSnapshotRefCache(...)` use `DefaultRawCacheStrategy` with `DefaultNegativeCacheFeature`
  - alignment: partial
  - mismatch or uncertainty: the core choice is right, but `afterInsert(...)` only clears negative cache instead of writing the stable positive mapping, so the existence boundary after commit is weaker than the strategy implies
- Hot system snapshot cache
  - expected by guide: versioned cache, write-through/post-refresh behavior, optional L1/client-side read layer, and explicit hot-key miss protection
  - actual implementation: `BalanceSnapshotCacheRegister.hotSnapshotCache(...)` uses `clientSideVersionCacheReader`, enables negative cache, and sets `StrategyType.POST_REFRESH`; `BalanceSnapshotStore.determineCacheStrategy(...)` routes promoted ids to this strategy
  - alignment: partial
  - mismatch or uncertainty: read-side composition is good, but there is no miss-collapsing/lock mechanism, and the write/update path is not complete enough to validate the full hot-path contract end to end

## 9. Runtime / system behavior review
- cache miss
  - Normal versioned entity path behaves correctly: miss or tombstone -> DB -> `afterDbHit(...)` -> Lua CAS write.
  - Raw ref path behaves functionally, but the insert path is still lazy. After a successful insert, ref lookup may still miss cache and go to DB until a later read fills the positive mapping.
  - Hot snapshot miss still fans out to DB under concurrency. Client-side cache helps repeated reads after warmup, but it does not solve cold-start or post-expiry herd behavior.
- stale data risk
  - The versioned mutable path has low stale-overwrite risk because `set-if-absent-or-newer.lua` blocks older versions from replacing newer tombstones or values.
  - The hot path should have a smaller stale window than tombstone-only invalidation once post-refresh updates are wired, but that is still an assumption at this stage.
  - The ref path has the highest stale-not-found risk because negative entries can be reintroduced after insert if a stale reader writes miss state late.
- concurrent reads/writes
  - Versioned read/write concurrency is the strongest part of the design. The Lua CAS is the right primitive here.
  - Raw cache concurrency is acceptable only because the mapped data is intended to be stable and insert-only. It is not robust against late negative writes around creation.
  - Client-side cache introduces a deliberate trade-off: lower latency and lower Redis read load in exchange for short local staleness/invalidation lag. That is a reasonable trade for the hot snapshot path if update-side refresh is made reliable.
- future client-side cache / hot-key expansion
  - Good: the read source is a pluggable support, so moving one strategy from normal Redis reads to client-side reads did not require rewriting the strategy itself.
  - Weak: there is no built-in path yet for promotion thresholds, demotion, metrics-driven hot classification, or miss collapse. The current design supports a local experiment, not a mature fleet-wide hot-key policy.
- ref cache and entity cache consistency
  - On ref DB hit, both stores write ref cache and entity cache in the same read path. That is good.
  - On insert, the entity path is stronger than the ref path:
    - `LedgerTxnStore.postInsert(...)` and `BalanceSnapshotStore.postInsert(...)` call entity `afterInsert(...)`
    - but ref `afterInsert(...)` only clears negative cache
  - That asymmetry means the design is not fully consistent at the cross-key boundary exactly when an entity first appears.

## 10. Abstraction and extensibility review
- The separation between codec, read support, write support, and strategy is mostly healthy. The abstractions are not fake.
- `CacheDescriptor<T>` is a good minimal boundary. It keeps type and key-building together, which is enough for current usage.
- The current strategy interfaces are usable, but they are still a little too lifecycle-driven and caller-driven:
  - `afterDbHit`
  - `afterDbMiss`
  - `afterUpdate`
  - `afterInsert`
  These names expose internal cache choreography to the caller. They work, but they do not strongly encode when a call must happen relative to DB commit or what consistency guarantee it is meant to achieve.
- `StrategyType` is only meaningful for the versioned strategy. `RawStrategyConfig.strategyType` is currently unused, which is a sign the abstraction has been generalized one step beyond its real use.
- `StrategyFactory` is functional but thin. It does not add meaningful policy, validation, or safety checks. Right now it is mainly constructor indirection.
- Service-side registration is explicit but repetitive. `LedgerCacheRegister` and `BalanceSnapshotCacheRegister` manually compose the same building blocks with only small differences. A small preset/builder layer could reduce miswiring risk without adding much complexity.

## 11. Strongest design decisions
- Using DB version as the cache CAS version and enforcing ordering in Lua.
- Separating `NEGATIVE` from `TOMBSTONE` in both encoding and runtime semantics.
- Treating malformed cache as recoverable garbage, not as a hard business failure.
- Making client-side cache a read-support choice instead of a forked strategy family.
- Keeping raw stable mapping and versioned mutable entity caches as distinct abstractions.

## 12. Weakest design decisions
- Modeling cache operations as `Result`-based safe operations while still letting real Redis call failures escape unchecked.
- Making raw `afterInsert(...)` clear-only instead of writing the stable positive mapping.
- Leaving hot-key miss protection out of the hot strategy even though the guide explicitly calls it out.
- Depending on caller discipline for post-commit freshness and version correctness, especially on the `POST_REFRESH` path.

## 13. Signs of mature engineering judgment
- The versioned path uses the right primitive for mutable cache correctness: CAS plus tombstone, not plain overwrite.
- The design recognizes that user-facing ref lookups need negative cache while internal id lookups may not.
- The refactor introduces hot-path specialization incrementally instead of building a separate caching subsystem too early.
- The code keeps business logic out of `common-util`; it remains infra-oriented.

## 14. Signs of over-design or unnecessary complexity (if have)
- Mild over-design exists, but it is not the main problem.
- `StrategyFactory` is mostly constructor wrapping.
- `RawStrategyConfig.strategyType` is unused.
- `VersionNegativeCacheFeature` exists only as commented residue.
- There is some ceremony in feature/config/factory assembly without enough guardrails yet to fully justify it.

## 15. The 3 highest-leverage design improvements
- Make the operational failure boundary honest.
  - Catch Redis/Redisson runtime failures inside `DefaultCacheReader`, `RawCacheWriter`, `VersionCacheWriter`, and delete paths.
  - Return failed `Result`s consistently so the "cache failure -> degrade" contract is actually true.
- Strengthen ref-cache insert semantics.
  - For stable ref mappings, write the positive mapping on insert/commit instead of only clearing negative cache.
  - Keep negative write guarded so a stale miss cannot overwrite a committed positive mapping.
- Introduce explicit hot-key miss protection.
  - Add singleflight or distributed lock around DB refill for promoted hot keys.
  - Keep the current client-side cache and local promotion, but pair them with controlled refill behavior.

## 16. Final judgment
- The refactor is meaningful and directionally correct. The versioned entity-cache design is the strongest part and is clearly above average.
- The overall design is not senior-grade yet because three important boundaries are still weak:
  - real cache operation failure handling
  - ref-cache consistency after insert
  - hot-key miss protection under load
- My read is: solid mid-level work with one strong core idea, but not yet a strong end-to-end cache architecture.
