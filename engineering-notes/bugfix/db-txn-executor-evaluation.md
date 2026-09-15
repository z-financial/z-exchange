# DbTxnExecutor Evaluation

## Scope

- Evaluated branch: `dev-ai`
- Evaluated fix commit: `404436e` (`fix db transaction executor bug`)
- Optimization branch: `ai/codex/bugfix/optimize-db-txn-executor`

## Evaluation of the `dev-ai` fix

The `dev-ai` change fixes the immediate CU-1 race in the current code paths.

Why:

- The old bug came from mutating a shared `TransactionTemplate` via `setPropagationBehavior(...)` before `execute(...)`.
- The `dev-ai` change removed that mutation entirely.
- Current callers only use the new `executeWithDefault(...)` path, and there are no remaining code references to alternate propagation modes in the service modules.

## Gap in the `dev-ai` fix

The implementation still kept a shared `TransactionTemplate` bean inside `DbTxnExecutor`.

That is safer than the old version because it is no longer mutated, but it is still weaker than the recommended design:

- it relies on the shared template never being mutated elsewhere
- it does not enforce per-call template isolation
- if propagation-specific execution is added later, it creates pressure to reintroduce mutable shared state

So the `dev-ai` fix was directionally correct, but not fully hardened.

## Minimal optimization applied

Changed only the shared executor wiring:

- `DbTxnExecutor` now depends on `PlatformTransactionManager`
- `DbTxnExecutor.executeWithDefault(...)` creates a fresh `TransactionTemplate` per invocation
- `CommonDbComponentRegister` now wires `PlatformTransactionManager` into `DbTxnExecutor`

Files changed:

- `common-util/src/main/java/com/exchange/common/db/utils/DbTxnExecutor.java`
- `common-util/src/main/java/com/exchange/common/db/register/CommonDbComponentRegister.java`

## Why the optimized fix is correct

- Each execution gets its own `TransactionTemplate`, so there is no shared per-call transaction configuration to race across threads.
- The actual transactional resource is still the shared `PlatformTransactionManager`, which is the correct Spring abstraction to share.
- Behavior for current flows is preserved: `new TransactionTemplate(transactionManager)` uses Spring defaults, which match the current call sites that were previously using default transaction behavior.
- Rollback semantics are unchanged:
  - failed `Result<?>` still marks rollback-only
  - thrown exceptions still mark rollback-only and are rethrown
- No service flow was changed, and no caller contract changed.

## Verification

Ran:

```powershell
mvn -pl common-util,ledger-service,wallet-service -am -DskipTests compile
```

Result: `BUILD SUCCESS`

## Follow-up note

If a future caller needs non-default propagation, add an explicit executor method that creates a fresh `TransactionTemplate` and sets propagation on that fresh instance only. Do not reintroduce mutation on a shared template bean.
