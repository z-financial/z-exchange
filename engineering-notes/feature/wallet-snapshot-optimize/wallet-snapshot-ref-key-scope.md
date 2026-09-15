# Wallet Snapshot Ref Key Scoping

## Summary

This change is the right fix to land before relying on the hot `ref_id -> wallet_id` optimization.

The original design bug was not only in Redis key shape. It was also in the read contract:

- gRPC `getSnapshotByRefId` only accepted `referenceId`
- `BalanceSnapshotStore` cached by bare `refId`
- `BalanceSnapshotRepository.getByWalletReferenceId(...)` queried by bare `walletReferenceId`

That was inconsistent with wallet creation and other repo paths that already treat wallet identity as `(serviceId, referenceId)`.

The updated path now scopes the ref lookup by service end to end:

- proto request carries `serviceId`
- processor validates `serviceId` for `REF_ID` lookup
- repository queries by `(serviceId, walletReferenceId)`
- store builds scoped ref cache id as `<serviceId>:<refId>`
- final Redis key becomes `balance_snapshot:ref:<serviceId>:<refId>`

Example:

- `balance_snapshot:ref:SYSTEM:reserve-wallet`

## What This Fix Solves

- It removes cross-service key collision for the ref index cache. `USER:abc` and `SYSTEM:abc` no longer share the same Redis entry.
- It removes cross-service hot-path misrouting. The hot ref classifier now promotes the scoped ref identity, not the bare `refId`.
- It aligns the cache path with the DB lookup path. Fixing only Redis key format would have been incomplete because the repository query was also ambiguous.
- It makes the API contract explicit. A ref lookup now requires the same service dimension that wallet creation already depends on.

## Why The Implementation Is Reasonable

- The change is minimal and local. It reuses the existing service -> processor -> store -> repository layering.
- It does not refactor the cache abstractions just to support this one fix.
- It scopes only the ref index cache. That is correct because `walletId` is already the globally unique identity used by the snapshot cache.
- It updates the wallet-creation `postInsert(...)` path as well, so negative-cache cleanup and hot promotion use the same scoped identity as query reads.

## Trade-Offs And Remaining Risks

- This is a runtime-breaking API change for old callers. In proto3, missing `serviceId` becomes `ServiceIdPb_Unknown`, and the processor now rejects that with `INVALID_REQUEST_PARAMETER`. That is correct behavior, but rollout has to be coordinated with clients.
- Legacy Redis keys in the old shape `balance_snapshot:ref:<ref_id>` will remain until TTL expiry. New code will not read them, so correctness is fine, but temporary memory duplication is expected.
- The scoped-key fix does not address the existing hot-classifier lifetime problem. Promoted ref ids and wallet ids are still process-lifetime and unbounded.
- The scoped-key fix does not address the negative-cache-renewal TODO in `BalanceSnapshotStore.getByRefId(...)`. Repeated misses can still keep extending negative TTL.
- The scoped id currently uses `serviceId.name()`. That is acceptable for this repo today, but it makes cache keys depend on enum naming stability.

## Project-Specific Evaluation

- This change should be approved. Without it, the hot ref cache would make the existing ambiguity worse by serving the wrong wallet id faster.
- The repository change is essential. If you had changed only the cache key and left `getByWalletReferenceId(...)` unscoped, the service would still be logically incorrect on cache miss.
- Updating `CreateWalletProcessor` is also essential. Otherwise the create-wallet recovery path would keep writing and promoting the old unscoped ref identity.
- Keeping the composite ref id construction inside `BalanceSnapshotStore` is acceptable for a minimal fix, but it is a hidden convention. If more scoped cache keys are added later, move the composition into `CacheScope` or another shared helper so the format is not duplicated ad hoc.

## Suggested Follow-Ups

- Coordinate client rollout for the new `serviceId` requirement before depending on this API in mixed-version environments.
- If Redis memory pressure matters, consider a one-time cleanup for legacy `balance_snapshot:ref:<ref_id>` keys. If not, letting TTL expire them is enough.
- If this repo adds more service-scoped cache indexes, centralize scoped key composition instead of repeating `serviceId + ":" + refId` in stores.
- Keep the next two cache improvements separate from this change:
  - bound or expire hot-classifier promotion state
  - fix negative-cache TTL renewal on repeated miss
