# Result / Error-Handling Implementation Evaluation

## Verdict

The direction is correct, but the current branch is still scaffolding, not a usable phase 1/2/3 completion.

- Phase 1: partially done
- Phase 2: only started conceptually
- Phase 3: not materially started in runtime code

The strongest improvement is that the new `common.result` package is transport-neutral. The biggest problem is that the implementation currently does not change any real error-propagation behavior in the repo, because all active callers still use `com.exchange.common.utils.result`.

## What Is Good

- `common-util/src/main/java/com/exchange/common/result/error/ErrorCode.java` adds the right missing metadata: `namespace`, `code`, `message`, `category`.
- `common-util/src/main/java/com/exchange/common/result/error/ErrorCategory.java` is the right direction for boundary mapping. This is much better than keeping `ErrorCodePb` inside shared enums.
- `common-util/src/main/java/com/exchange/common/result/error/IdempErrorCode.java` is directionally the right split. `CommonErrorCode` should not keep owning idempotency, cache, and outbox errors together.

## Main Gaps

### 1. `Results` is still the critical missing piece

`common-util/src/main/java/com/exchange/common/result/Results.java` is empty.

That is not a minor TODO in this repo. The core failure mode you were trying to remove is unsafe propagation logic:

- service-local `Results.fail(Result<?>)`
- service-local `Results.getErrorCode(Result<?>)`
- foreign-result downcasts

Until the shared `Results` helper is implemented, phase 1 has not solved the most important repo-specific problem.

### 2. The new `Result` shape is migration-risky as a public record

`common-util/src/main/java/com/exchange/common/result/Result.java` is a public record with constructor order:

- `success`
- `errorCode`
- `value`
- `detail`
- `scope`

The old active record in `common.utils.result.Result` uses:

- `success`
- `value`
- `errorCode`
- `errorDetail`

That difference is easy to misuse during migration because this repo already constructs `Result` directly in helpers. A public record constructor gives you no real control over that.

Better alternative:

- follow the checklist more literally: keep `IResult` or `ResultView`, and use a concrete `DefaultResult` class with factories
- or, if you keep the record, preserve old field order to reduce migration mistakes

Right now you get the downside of both approaches: new constructor shape plus no factory protection.

### 3. The contract naming is internally inconsistent

Your docs/checklist target `isSuccess()`, `getValue()`, `getErrorCode()`, `getDetail()`, `getScope()`.

The actual implementation uses:

- `success()`
- `value()`
- `errorCode()`
- `detail()`
- `scope()`

Trade-off:

- this record-style API reduces migration churn because existing call sites already use `result.success()` and `result.value()`
- but it diverges from the contract you documented, so the design is already forked before rollout starts

This is not fatal, but you should decide once and lock it. In this repo, I would favor the record-style accessor names for lower migration cost, but then the checklist and future helpers should be updated to match.

### 4. `ErrorCategory.SUCCESS` weakens the model

`common-util/src/main/java/com/exchange/common/result/error/ErrorCategory.java` includes `SUCCESS`.

That is a smell in this design. `Result.success()` already models success. Category should classify failures, especially if you want category-based proto mapping later.

If `SUCCESS` stays:

- every mapper must special-case a non-error category
- validator and coverage tests become less clear
- the boundary between `Result` state and `ErrorCode` metadata stays muddy

Better alternative:

- remove `SUCCESS` from `ErrorCategory`
- keep success handling in `Results.success(...)` and boundary builders

## Phase 2 Status

Phase 2 is not done yet in any practical sense.

It is true that the new package does not import proto types. That part is good.

But the active code path is still fully proto-coupled:

- `common-util/src/main/java/com/exchange/common/utils/result/PbMappableErrorCode.java`
- `wallet-service/src/main/java/com/exchange/app/wallet/result/ErrorCode.java`
- `ledger-service/src/main/java/com/exchange/app/ledger/result/ErrorCode.java`
- `wallet-service/src/main/java/com/exchange/app/wallet/result/PbErrorBuilder.java`
- `ledger-service/src/main/java/com/exchange/app/ledger/result/PbErrorBuilder.java`

So phase 2 currently exists only as a parallel type system, not as a repo behavior change.

## Phase 3 Status

Phase 3 is also not materially started in runtime code.

You added `IdempErrorCode`, but it is still empty, and the actual idempotency code still returns `CommonErrorCode`:

- `common-util/src/main/java/com/exchange/common/redis/idemp/utils/CommonIdempHelper.java`

The same is true for other shared concerns:

- cache still uses `CommonErrorCode.PARSE_CACHE_ERROR`
- outbox retry still uses `CommonErrorCode.UNEXPECTED_DB_ERROR`

So the current repo still has one real shared error catalog, and one new placeholder subsystem enum.

## Additional Risks

### 1. `ErrorNamespace` may become the next catch-all

`common-util/src/main/java/com/exchange/common/result/error/ErrorNamespace.java` is fine as a temporary constants holder, but `MESSAGE` is too vague and likely to become another dumping ground like `CommonErrorCode`.

Prefer namespace names that match actual ownership, such as:

- `idemp`
- `cache`
- `outbox`

If a namespace is too broad, the split has not really happened.

### 2. Two parallel `Result` packages will create import mistakes

You now have both:

- `com.exchange.common.utils.result.Result`
- `com.exchange.common.result.Result`

That is a concrete migration hazard in this repo, especially with IDE auto-import and many existing `Result` call sites.

If rollout will be gradual, a less collision-prone naming scheme is safer:

- `ResultView`
- `DefaultResult`

That was the safer part of the checklist, and the current implementation gives up that advantage.

## Recommended Next Moves

1. Finish the shared `Results` helper before adding more enums. That is the piece that actually removes service-local downcast behavior.
2. Decide the contract shape now:
   either keep record-style accessors consistently everywhere, or switch back to the checklist naming before more code depends on it.
3. Do one complete subsystem migration, not just a placeholder enum:
   move idempotency callers off `CommonErrorCode` and prove the split with real usages.
4. Do not claim phase 2 complete until old proto-coupled types are at least deprecated and blocked from new use.
5. If you want constructor discipline, replace the public record with a concrete default implementation rather than relying on TODO factory methods.

## Bottom Line

This is a good start, but it is still infrastructure scaffolding.

The design intent is better than the old model, especially around transport decoupling and subsystem ownership. The implementation is not yet strong where this repo actually hurts today: shared propagation helpers, migration safety, and replacing the active proto-coupled path.
