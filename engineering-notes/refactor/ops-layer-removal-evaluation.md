# Ops Layer Removal Evaluation

## 1. Scope reviewed
- Read first:
  - `engineering-notes/refactor/cache-component-refactor-evaluation.md`
- Reviewed Ops interfaces:
  - `common-util/src/main/java/com/exchange/common/redis/cache/ops/ReadOps.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/ops/RawWriteOps.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/ops/NegativeCacheOps.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/ops/VersionWriteOps.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/ops/VersionNegativeCacheOps.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/ops/VersionTombstoneOps.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/ops/SelfRecoverOps.java`
- Reviewed Ops implementations:
  - `common-util/src/main/java/com/exchange/common/redis/cache/ops/impl/ReadOpsImpl.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/ops/impl/RawWriteOpsImpl.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/ops/impl/NegativeCacheOpsImpl.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/ops/impl/VersionWriteOpsImpl.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/ops/impl/VersionNegativeCacheOpsImpl.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/ops/impl/VersionTombstoneOpsImpl.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/ops/impl/RawDeleteRecoverOpsImpl.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/ops/impl/L1L2ReadOpsImpl.java`
- Reviewed strategy consumers:
  - `common-util/src/main/java/com/exchange/common/redis/cache/strategy/StableCacheStrategy.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/strategy/VersionCacheStrategy.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/strategy/impl/DefaultStableCacheStrategy.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/strategy/impl/DefaultVersionCacheAsideStrategy.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/strategy/impl/DefaultVersionPostRefreshStrategy.java`
  - `common-util/src/main/java/com/exchange/common/redis/cache/strategy/impl/StrategyFactory.java`
- Reviewed assembly usage:
  - `wallet-service/src/main/java/com/exchange/app/wallet/dao/cache/WalletCacheRegister.java`
  - `ledger-service/src/main/java/com/exchange/app/ledger/dao/cache/LedgerRegister.java`
- Important uncertainty:
  - The repository still shows the pre-split `SimpleCache` / `VersionCache` support design. The “after refactor” state discussed in the prior evaluation is not fully present in source. So this evaluation is based on the current code plus the refactor direction already documented.

## 2. Executive summary
- The current Ops layer is mostly too thin.
- Most concrete `*OpsImpl` classes are pass-through adapters that do three things:
  - build a cache key with `CacheDescriptor`
  - delegate once to `SimpleCache` or `VersionCache`
  - add a small amount of logging
- That means the current Ops implementations do not provide enough standalone architectural value to justify their number and assembly overhead.
- But removing the Ops idea entirely is not the best move.
- The useful part of Ops is the capability seam between strategy logic and lower-level cache components:
  - read
  - raw write
  - versioned write
  - tombstone write
  - negative-cache handling
  - self-recovery
- Recommendation:
  - remove or collapse the trivial Ops implementation layer
  - keep a capability-oriented seam somewhere
  - if the support refactor is completed with narrow reader/writer/deleter interfaces, let strategies depend on those directly and retire the Ops package
  - do not make strategies depend directly on broad bucket classes like `SimpleCache` or `VersionCache`

## 3. Does Ops still provide real value?
- Yes, conceptually.
- No, mostly not in its current concrete form.

- Real value that still exists:
  - Strategy-facing capability separation.
    - `DefaultStableCacheStrategy` depends on `ReadOps`, `RawWriteOps`, `NegativeCacheOps`, and `SelfRecoverOps`.
    - `DefaultVersionCacheAsideStrategy` depends on `ReadOps`, `VersionWriteOps`, `VersionTombstoneOps`, `VersionNegativeCacheOps`, and `SelfRecoverOps`.
    - That is a cleaner strategy boundary than depending directly on one large cache component.
  - Strategy isolation.
    - The strategy layer talks in terms of behavior, not transport classes.
    - This is good architecture in principle.
  - Future extensibility seam.
    - `L1L2ReadOpsImpl.java` is currently commented out, but it shows the intended use: swapping different read behavior without changing strategy logic.
    - The same seam could support metrics, retries, singleflight, guarded delete, or alternate negative-cache handling.
  - Key translation boundary.
    - `ReadOpsImpl`, `RawWriteOpsImpl`, `VersionWriteOpsImpl`, etc. keep `CacheDescriptor` and cache-key construction out of strategy code.

- Value that is mostly gone in the current implementation:
  - `ReadOpsImpl.get(...)` is key build + one delegate + one log.
  - `RawWriteOpsImpl.set(...)` is key build + one delegate + one log.
  - `VersionWriteOpsImpl.set(...)` is key build + one delegate + one log.
  - `VersionTombstoneOpsImpl.setTombstone(...)` is key build + one delegate + one log.
  - `NegativeCacheOpsImpl` and `VersionNegativeCacheOpsImpl` are the same pattern.
  - `RawDeleteRecoverOpsImpl.recover(...)` is also a very small adapter.
- Strictly speaking, these are not rich architectural components. They are adapter glue.

## 4. Benefits of removing Ops
- Fewer indirection layers.
  - Registers like `WalletCacheRegister` and `LedgerRegister` would stop manually allocating multiple one-line adapters per strategy.
- Less duplication.
  - If the support layer is split into narrow capabilities such as reader/writer/deleter, the Ops interfaces will duplicate the same capability taxonomy.
- Simpler assembly.
  - Today, one strategy assembly call often requires creating 4-5 tiny objects whose only real job is wrapping a cache component plus `CacheDescriptor`.
- Lower class count without losing meaning.
  - Removing trivial `*OpsImpl` classes would reduce noise more than it would remove substance.
- Better code locality.
  - If these adapters stay necessary, they can often live as private assembler helpers or lambdas rather than top-level classes.

## 5. Risks of removing Ops
- Strategy code may become coupled to the wrong level.
  - If Ops is removed and strategies start depending directly on `SimpleCache` or `VersionCache`, the design gets worse, not better.
  - Those support classes are broader than the strategy layer should know about.
- Key-building logic may leak upward.
  - Without some adapter boundary, `CacheDescriptor.buildCacheKey(...)` may migrate into strategies or domain registers in an inconsistent way.
- Capability vocabulary may be lost.
  - `ReadOps`, `VersionWriteOps`, `VersionTombstoneOps`, and `SelfRecoverOps` are still meaningful behavioral categories.
  - If they disappear without replacement, strategy behavior becomes less explicit.
- Future substitutions become harder.
  - A real `L1/L2` read implementation, guarded negative cleanup, metrics wrappers, or retry wrappers fit naturally at the Ops seam.
  - Direct strategy-to-component coupling would make those substitutions more invasive.
- Testability may get worse if removal is done naively.
  - Strategies are easier to unit-test against narrow behaviors than against richer concrete components.

## 6. Impact on future evolution
- If the support-layer refactor is not done:
  - Removing Ops now would hurt architecture.
  - Strategies would likely end up depending on `ReadableCache`, `SimpleCache`, `VersionCache`, or even lower-level Redis supports directly.
  - That would weaken abstraction clarity and make future strategy expansion harder.

- If the support-layer refactor is done well:
  - Removing the current Ops package is reasonable.
  - But only because the new support layer would already provide narrow, capability-oriented interfaces such as:
    - raw reader
    - versioned reader
    - raw writer
    - versioned CAS writer
    - raw deleter
    - guarded delete
  - In that design, Ops no longer adds a new abstraction. It only renames the same abstraction one more time.

- For future strategy expansion:
  - Keeping a behavior seam still matters.
  - Keeping the current thin `*OpsImpl` classes does not matter much.
  - This is the key distinction.

- Practical future trade-off:
  - Remove the current top-level Ops implementation layer if the support layer becomes narrow and typed.
  - Preserve the behavior-oriented dependency shape at the strategy boundary.

## 7. Recommended direction
- Recommended answer: do not remove the behavioral seam, but do remove redundant Ops plumbing.

- Best path:
  - 1. Complete the support-layer split described in `cache-component-refactor-evaluation.md`
  - 2. Make strategies depend directly on narrow capability interfaces from that refactored support layer
  - 3. Retire most or all of `cache/ops/impl`
  - 4. Keep `CacheDescriptor` application in assembler/builders, not in strategies
  - 5. Keep named adapter classes only where there is real behavior

- In other words:
  - good to remove:
    - most current `*OpsImpl` classes as top-level architectural components
  - not good to remove:
    - the concept of narrow behavior-oriented dependencies at the strategy boundary

- If you want a minimal intermediate step:
  - keep the `ops` interfaces for now
  - move trivial adapter creation into factory/assembler code
  - delete standalone `*OpsImpl` classes only when there is no real logic inside them

- If you want the cleaner end state:
  - remove the `ops` package entirely
  - but only after the support layer itself exposes the narrow capability interfaces that strategies can safely consume

## 8. Final judgment
- The current Ops layer is mostly pass-through indirection.
- As a set of concrete classes, it does not provide enough architectural value to fully justify itself.
- As a behavioral boundary between strategy logic and lower-level cache mechanics, the same idea is still useful.
- So the strict answer is:
  - removing the current Ops implementation layer is a good idea
  - removing the capability seam altogether is not a good idea
- Final recommendation:
  - do not preserve the current Ops package out of habit
  - do preserve the narrow, strategy-facing capability boundaries
  - once the support layer is properly split and typed, retire Ops rather than keeping it as duplicate architecture
