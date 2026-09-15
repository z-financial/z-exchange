# Result and Error-Handling Implementation Checklist

## Purpose

This checklist is for turning the proposed `Result` / `ErrorCode` refactor into an enforceable design instead of a convention-based one.

The specific problems to eliminate are:

- proto mapping drift
- duplicate or inconsistent error-code definitions
- service-local `Results` behavior drift
- lossy remote error wrapping
- cross-module `Result` composition failures

## Current Problem Points In This Repo

These are the concrete places the current design breaks or is fragile:

- transport coupling in [`PbMappableErrorCode.java`](D:/TestProgram/Java/JavaEE/z-exchange/common-util/src/main/java/com/exchange/common/utils/result/PbMappableErrorCode.java)
- mixed shared error catalog in [`CommonErrorCode.java`](D:/TestProgram/Java/JavaEE/z-exchange/common-util/src/main/java/com/exchange/common/utils/result/CommonErrorCode.java)
- service-local downcast logic in [`ledger-service Results.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/result/Results.java) and [`wallet-service Results.java`](D:/TestProgram/Java/JavaEE/z-exchange/wallet-service/src/main/java/com/exchange/app/wallet/result/Results.java)
- duplicated boundary builders in [`ledger PbErrorBuilder.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/result/PbErrorBuilder.java) and [`wallet PbErrorBuilder.java`](D:/TestProgram/Java/JavaEE/z-exchange/wallet-service/src/main/java/com/exchange/app/wallet/result/PbErrorBuilder.java)
- lossy remote error wrapping in [`PostServiceClient.java`](D:/TestProgram/Java/JavaEE/z-exchange/wallet-service/src/main/java/com/exchange/app/wallet/client/PostServiceClient.java) and [`CreateWalletProcessor.java`](D:/TestProgram/Java/JavaEE/z-exchange/wallet-service/src/main/java/com/exchange/app/wallet/processor/CreateWalletProcessor.java)
- duplicate numeric code risk already visible in [`wallet ErrorCode.java`](D:/TestProgram/Java/JavaEE/z-exchange/wallet-service/src/main/java/com/exchange/app/wallet/result/ErrorCode.java)

## Target End State

The refactor should end in this shape:

- `common-util` defines one transport-neutral `ErrorCode` contract
- `common-util` defines one shared `Result<T>` contract and one default implementation
- `common-util` defines one shared `Results` utility
- `common-util` does not import `ErrorCodePb`
- services own proto conversion only at service boundaries
- common-util submodules use separate subsystem enums where that reflects real ownership
- proto mapping is category-based, not large hand-maintained per-code switch logic
- remote error wrapping is done through one helper only
- error-code uniqueness and contract validity are checked automatically in tests

## Phase 1: Core Contracts In `common-util`

### Add

- `common-util/.../result/ErrorCategory.java`
- `common-util/.../result/ErrorCode.java`
- `common-util/.../result/IResult.java` or `ResultView.java` if you want to avoid class/interface naming collision
- `common-util/.../result/DefaultResult.java`
- `common-util/.../result/Results.java`

### Required contract

`ErrorCode` should contain:

- `getNamespace()`
- `getCode()`
- `getMessage()`
- `getCategory()`

`Result<T>` should contain:

- `isSuccess()`
- `getValue()`
- `getErrorCode()`
- `getDetail()`
- `getScope()`
- default `isFailed()`

### Rules

- `Result<T>` must keep `getValue()`
- rename current `errorDetail` semantics to `detail`
- `scope` is diagnostic metadata only
- do not let `ErrorCode` know about proto

### Acceptance criteria

- no new type in `common-util/.../result` imports `com.exchange.proto.common.error.ErrorCodePb`
- shared `Results` utility does not inspect concrete enum class

## Phase 2: Remove Transport Coupling

### Delete or deprecate

- [`PbMappableErrorCode.java`](D:/TestProgram/Java/JavaEE/z-exchange/common-util/src/main/java/com/exchange/common/utils/result/PbMappableErrorCode.java)

### Refactor

- stop storing `ErrorCodePb` inside service enums
- stop storing `ErrorCodePb` inside any shared enum

### Acceptance criteria

- service `ErrorCode` enums have no `protoCode` field
- `common-util` has no compile-time dependency on proto error types

## Phase 3: Split `common-util` Error Catalog By Subsystem

### Replace `CommonErrorCode`

Do not keep one growing catch-all enum. Split by meaningful subsystem contract:

- `CacheErrorCode`
- `IdempErrorCode`
- `MessagingErrorCode` or `OutboxErrorCode`

Optional:

- `DbErrorCode`, only if callers truly branch on DB-specific failures

### Rules

- one enum per meaningful shared subsystem
- not one enum per class
- no catch-all `CommonErrorCode` for unrelated concerns

### Acceptance criteria

- each common-util error enum has a clear module owner
- unrelated concerns are no longer mixed in one shared enum

## Phase 4: Centralize Proto Mapping

### Add

- `common-util/.../result/ProtoErrorMapper.java`

or, if you want service-local wrappers:

- `wallet-service/.../result/WalletProtoErrorMapper.java`
- `ledger-service/.../result/LedgerProtoErrorMapper.java`

### Recommended rule

Map `ErrorCategory -> ErrorCodePb`, not `specific code -> ErrorCodePb`.

This should be mostly mechanical:

- `INTERNAL -> ERROR_INTERNAL`
- `UNAVAILABLE -> ERROR_UNAVAILABLE`
- `INVALID_ARGUMENT -> ERROR_INVALID_ARGUMENT`
- `NOT_FOUND -> ERROR_NOT_FOUND`
- `ALREADY_EXISTS -> ERROR_ALREADY_EXISTS`
- `FAILED_PRECONDITION -> ERROR_FAILED_PRECONDITION`
- `CONFLICT -> ERROR_CONFLICT`
- `PROCESSING -> ERROR_PROCESSING`

### Refactor

- update [`ledger PbErrorBuilder.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/result/PbErrorBuilder.java)
- update [`wallet PbErrorBuilder.java`](D:/TestProgram/Java/JavaEE/z-exchange/wallet-service/src/main/java/com/exchange/app/wallet/result/PbErrorBuilder.java)

### Rules

- fallback to `ERROR_INTERNAL` only for impossible or unmapped states
- fallback must log and emit a metric

### Acceptance criteria

- no boundary builder reads `error.protoCode`
- proto mapping logic exists in exactly one place per service or one shared place

## Phase 5: Remove Service-Local `Results`

### Delete or deprecate

- [`ledger-service/.../result/Results.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/result/Results.java)
- [`wallet-service/.../result/Results.java`](D:/TestProgram/Java/JavaEE/z-exchange/wallet-service/src/main/java/com/exchange/app/wallet/result/Results.java)

### Replace with

- one shared `common-util` `Results`

### Rules

- no service helper may downcast `Result.errorCode()` to its own enum class
- `Results.fail(foreignResult)` must preserve metadata and never throw
- `Results.result(newValue, result)` must preserve status/code/detail/scope

### Acceptance criteria

- no `ClassCastException` path remains in error propagation helpers
- service code no longer needs `getErrorCode(result)` just to propagate a failure

## Phase 6: Standardize Remote Error Wrapping

### Add

- `common-util/.../result/RemoteErrorWrapper.java`

or service-local wrappers if you want boundary ownership to stay in each service:

- `wallet-service/.../result/WalletRemoteErrorWrapper.java`
- `ledger-service/.../result/LedgerRemoteErrorWrapper.java`

### Required wrapper behavior

When one service wraps another service's failure, preserve:

- upstream service name
- upstream proto code
- upstream message
- upstream detail

### Refactor these callers first

- [`PostServiceClient.java`](D:/TestProgram/Java/JavaEE/z-exchange/wallet-service/src/main/java/com/exchange/app/wallet/client/PostServiceClient.java)
- [`CreateWalletProcessor.java`](D:/TestProgram/Java/JavaEE/z-exchange/wallet-service/src/main/java/com/exchange/app/wallet/processor/CreateWalletProcessor.java)

### Rules

- do not wrap remote failures by passing only `reply.getError().getMessage()`
- all remote wrapping must go through one helper

### Acceptance criteria

- local wrapped result detail still contains upstream code/message/detail
- remote-failure wrapping policy is consistent across wallet and ledger clients

## Phase 7: Add Validators

### Add

- `common-util/.../result/ErrorCodeValidator.java`

### Validator must fail on

- duplicate `(namespace, code)`
- blank namespace
- blank message
- null category

### Usage

- unit tests for each service enum
- optional startup validation in dev/test profiles

### Acceptance criteria

- duplicate code definitions fail fast
- invalid error-code metadata fails fast

## Phase 8: Add Contract Tests

### Tests to add

#### `ErrorCodeUniquenessTest`

Verify:

- no duplicate `(namespace, code)` within wallet
- no duplicate `(namespace, code)` within ledger
- no duplicate `(namespace, code)` across common-util shared enums if they share namespace rules

#### `ErrorCodeContractTest`

Verify for every enum constant:

- namespace non-blank
- message non-blank
- category non-null

#### `ProtoMappingTest`

Verify:

- every `ErrorCategory` maps to exactly one `ErrorCodePb`
- fallback behavior logs and returns `ERROR_INTERNAL`

#### `RemoteErrorWrappingTest`

Verify:

- wrapped remote failures preserve upstream proto code
- wrapped remote failures preserve upstream message
- wrapped remote failures preserve upstream detail
- local wrapper code/category stays correct

#### `ResultPropagationTest`

Verify:

- `Results.fail(result)` preserves original code/detail/scope
- `Results.result(newValue, result)` preserves original failure metadata
- foreign `Result` propagation does not throw

### Acceptance criteria

- these tests run in CI
- merge is blocked on failure

## Phase 9: Rollout Order

Use this order to reduce migration risk:

1. add new shared contracts in `common-util`
2. add `ErrorCategory`
3. add proto mapper
4. update `PbErrorBuilder` in wallet and ledger
5. migrate service enums to new `ErrorCode`
6. split `CommonErrorCode` into subsystem enums
7. add remote wrapper helper and refactor wallet clients
8. remove service-local `Results`
9. delete `PbMappableErrorCode`

## Phase 10: CI / Governance Gates

Add the following as mandatory checks:

- compile check for all modules
- result/error contract tests
- grep-style guard preventing new uses of:
  - `protoCode` field on enums
  - `PbMappableErrorCode`
  - service-local `Results.getErrorCode(...)` downcast patterns
  - direct remote wrapping via `reply.getError().getMessage()` only

## Definition Of Done

The refactor is done only when all of the following are true:

- `common-util` is transport-neutral
- proto mapping is category-based and centralized
- service-local `Results` helpers are gone
- remote wrapping preserves upstream context
- duplicate error codes fail automatically in tests
- `Result` propagation is enum-agnostic and does not throw on foreign results
- CI prevents regression into the old patterns

## Expected Score Impact

If this checklist is completed well, the design should improve because the architecture no longer depends mainly on team discipline.

The specific gains are:

- stronger correctness boundary between domain/infrastructure and transport
- safer cross-module error propagation
- lower risk of mapper drift
- lower risk of duplicate code drift
- more consistent cross-service behavior under failure

That is what moves the design from "better abstraction" to "enforced system contract".
