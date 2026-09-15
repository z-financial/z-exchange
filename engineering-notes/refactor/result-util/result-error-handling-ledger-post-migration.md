# Ledger Post Result Migration

## Scope

This change extends the earlier ledger account migration and moves the active ledger post flow onto the refactored shared [`Result.java`](D:/TestProgram/Java/JavaEE/z-exchange/common-util/src/main/java/com/exchange/common/result/Result.java) model.

This step supersedes the old "post flow still untouched" part of [`result-error-handling-ledger-account-migration.md`](D:/TestProgram/Java/JavaEE/z-exchange/engineering-notes/refactor/result-util/result-error-handling-ledger-account-migration.md).

This follow-up also retires the old ledger-local result helpers:

- removed [`ErrorCode.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/result/ErrorCode.java)
- removed [`Results.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/result/Results.java)

Touched ledger code:

- [`PostServiceImpl.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/service/PostServiceImpl.java)
- [`PostLedgerProcessor.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/processor/post/PostLedgerProcessor.java)
- [`GetLedgerTxnProcessor.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/processor/post/GetLedgerTxnProcessor.java)
- [`LedgerTxnStore.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/dao/store/LedgerTxnStore.java)
- [`DefaultPublisher.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/kafka/producer/DefaultPublisher.java)
- [`OutboxHelper.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/utils/OutboxHelper.java)
- [`DefaultListener.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/kafka/consumer/DefaultListener.java)
- [`PbErrorBuilder.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/result/PbErrorBuilder.java)
- [`LedgerServiceErrorCode.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/result/LedgerServiceErrorCode.java)

## What Changed

### Active post flow now uses shared `Result`

The post service slice no longer imports `com.exchange.common.utils.result.Result`.

Migrated callers now use:

- `Result.success(...)`
- `Result.failure(...)`
- `Result.failure(foreignResult)` for failure propagation
- `isSuccess()` / `isFailed()`
- `getValue()` / `getDetail()`

This removed active ledger post-flow usage of:

- legacy shared `Result`
- ledger `Results.fail(...)`
- `Results.getErrorCode(...)` downcast propagation

### Boundary replies now use ledger-owned errors only

[`PostServiceImpl.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/service/PostServiceImpl.java) and [`PostLedgerProcessor.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/processor/post/PostLedgerProcessor.java) now follow the same boundary pattern as the account slice:

- direct business failures return `LedgerServiceErrorCode` where the public ledger meaning is known
- foreign/common failures reaching a boundary go through [`LedgerBoundaryErrorMapper.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/result/LedgerBoundaryErrorMapper.java)
- protobuf assembly stays in [`PbErrorBuilder.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/result/PbErrorBuilder.java)

This removes the active cast-driven boundary path from both post RPC handlers.

### `PbErrorBuilder` is now narrowed to the new path

[`PbErrorBuilder.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/result/PbErrorBuilder.java) no longer accepts the legacy shared result type or legacy ledger error enum.

It now builds protobuf errors only from:

- `LedgerServiceErrorCode`
- explicit success helpers

That is the intended boundary shape after the account and post migrations.

### Ledger-owned error catalog was expanded for the post flow

Added the currently needed post-flow values to [`LedgerServiceErrorCode.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/result/LedgerServiceErrorCode.java):

- `PUBLISH_KAFKA_ERROR`
- `REQUEST_HASH_CONFLICT`
- `REQUEST_IN_PROCESSING`
- `LEDGER_DUPLICATED`
- `LEDGER_NOT_FOUND`
- `ACCOUNT_NOT_FOUND`

The post flow now returns these ledger-owned codes directly instead of borrowing the legacy ledger enum.

### Supporting helpers were aligned to the shared contract

[`LedgerTxnStore.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/dao/store/LedgerTxnStore.java), [`DefaultPublisher.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/kafka/producer/DefaultPublisher.java), [`OutboxHelper.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/utils/OutboxHelper.java), and [`DefaultListener.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/kafka/consumer/DefaultListener.java) were updated to the new shared result accessors and factories.

Behavioral intent stayed the same:

- cache failures in `LedgerTxnStore` still log and do not block main lookup flow
- malformed internal outbox event mapping still becomes an internal ledger failure
- publisher failures still propagate as failure results and are logged by the caller

### Exception path no longer depends on the legacy ledger enum

[`DefaultListener.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/kafka/consumer/DefaultListener.java) now throws ledger exceptions with `LedgerServiceErrorCode`.

[`CustomThrowable.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/exception/CustomThrowable.java) and its subclasses now depend on the shared `com.exchange.common.result.error.ErrorCode` contract instead of the deleted ledger-local enum.

## Remaining Work

`ledger-service` no longer contains the old ledger-local `ErrorCode` / `Results` result path.

The remaining legacy result code is now repo-wide, not ledger-local:

- [`common-util/src/main/java/com/exchange/common/utils/result`](D:/TestProgram/Java/JavaEE/z-exchange/common-util/src/main/java/com/exchange/common/utils/result)

So the next meaningful cleanup step is outside `ledger-service`:

1. verify `wallet-service` migration away from the legacy shared package
2. remove the old `common-util` legacy result package when no callers remain

## Verification

Targeted repository scans after the change show:

- no active ledger caller outside `ledger-service/result/*` still imports `com.exchange.common.utils.result`
- no active boundary caller still uses `Results.getErrorCode(...)`
- active `PbErrorBuilder` call sites now use only `LedgerServiceErrorCode`
- no `ledger-service` source file still references the deleted ledger-local `ErrorCode` or `Results` classes

That means legacy result code is fully removed from `ledger-service`.

It is not fully removed from the whole repository yet, because the old shared package still exists in `common-util`.

I did not complete a Maven compile in this environment because the local toolchain still fails on Java setup:

- `invalid target release: 21`
