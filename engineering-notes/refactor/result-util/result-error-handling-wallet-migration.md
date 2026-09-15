# Wallet Result Migration

## Scope

This change migrates active `wallet-service` result/error handling onto the shared [`Result.java`](D:/TestProgram/Java/JavaEE/z-exchange/common-util/src/main/java/com/exchange/common/result/Result.java) model.

It follows the ledger migration pattern and does not change `ledger-service`.

Retired wallet-local legacy result files:

- removed [`ErrorCode.java`](D:/TestProgram/Java/JavaEE/z-exchange/wallet-service/src/main/java/com/exchange/app/wallet/result/ErrorCode.java)
- removed [`Results.java`](D:/TestProgram/Java/JavaEE/z-exchange/wallet-service/src/main/java/com/exchange/app/wallet/result/Results.java)

## What Changed

### Wallet now has a service-owned public error catalog

Added [`WalletServiceErrorCode.java`](D:/TestProgram/Java/JavaEE/z-exchange/wallet-service/src/main/java/com/exchange/app/wallet/result/WalletServiceErrorCode.java).

The enum implements the shared `com.exchange.common.result.error.ErrorCode` contract and no longer stores proto codes directly.

The old duplicate numeric code in the wallet enum was corrected while moving to the new enum:

- `WALLET_ACTION_MISMATCH` keeps `1403`
- `WALLET_ACTION_UPDATE_FAILED` now uses `1404`

### Boundary policy is separated from protobuf assembly

Added [`WalletBoundaryErrorMapper.java`](D:/TestProgram/Java/JavaEE/z-exchange/wallet-service/src/main/java/com/exchange/app/wallet/result/WalletBoundaryErrorMapper.java).

Current rule:

- pass through `WalletServiceErrorCode` unchanged
- map current `common-util` internal/shared errors to `WalletServiceErrorCode.INTERNAL_ERROR`
- log and fallback unmapped foreign/common errors to `WalletServiceErrorCode.INTERNAL_ERROR`

Updated [`PbErrorBuilder.java`](D:/TestProgram/Java/JavaEE/z-exchange/wallet-service/src/main/java/com/exchange/app/wallet/result/PbErrorBuilder.java) so the new path only builds from `WalletServiceErrorCode` or explicit success helpers.

Added [`ProtoErrorMapper.java`](D:/TestProgram/Java/JavaEE/z-exchange/wallet-service/src/main/java/com/exchange/app/wallet/result/ProtoErrorMapper.java) to map `ErrorCategory -> ErrorCodePb` at the wallet boundary.

### Active wallet code now uses shared `Result`

Migrated active wallet callers from `com.exchange.common.utils.result.Result` to `com.exchange.common.result.Result`.

The migration replaced:

- `Results.success(...)` -> `Result.success(...)`
- `Results.fail(...)` -> `Result.failure(...)`
- `Results.fail(result)` -> `Result.failure(result)`
- `result.success()` -> `result.isSuccess()`
- `result.value()` -> `result.getValue()`
- `result.errorDetail()` -> `result.getDetail()`
- `Results.getErrorCode(result)` -> `WalletBoundaryErrorMapper.toWalletErrorCode(result)`

Main migrated areas:

- gRPC boundary in [`WalletServiceImpl.java`](D:/TestProgram/Java/JavaEE/z-exchange/wallet-service/src/main/java/com/exchange/app/wallet/service/WalletServiceImpl.java)
- create-wallet flow in [`CreateWalletProcessor.java`](D:/TestProgram/Java/JavaEE/z-exchange/wallet-service/src/main/java/com/exchange/app/wallet/processor/CreateWalletProcessor.java)
- balance query flow in [`GetBalanceSnapshotProcessor.java`](D:/TestProgram/Java/JavaEE/z-exchange/wallet-service/src/main/java/com/exchange/app/wallet/processor/balance/GetBalanceSnapshotProcessor.java)
- transaction processors and step helpers
- cache store, publisher, listener, and outbox helper
- wallet exception base classes

### Remote ledger failures are no longer wrapped lossily

Added [`WalletRemoteErrorWrapper.java`](D:/TestProgram/Java/JavaEE/z-exchange/wallet-service/src/main/java/com/exchange/app/wallet/result/WalletRemoteErrorWrapper.java).

Updated [`PostServiceClient.java`](D:/TestProgram/Java/JavaEE/z-exchange/wallet-service/src/main/java/com/exchange/app/wallet/client/PostServiceClient.java) and [`AccountServiceClient.java`](D:/TestProgram/Java/JavaEE/z-exchange/wallet-service/src/main/java/com/exchange/app/wallet/client/AccountServiceClient.java) so remote ledger failures preserve:

- upstream service name
- upstream proto code
- upstream message
- upstream detail

The public wallet code remains wallet-owned:

- `POST_TRANSACTION_FAILED`
- `CREATE_ACCOUNT_FAILED`

## Remaining Work

The old shared legacy package still exists in `common-util`:

- [`common-util/src/main/java/com/exchange/common/utils/result`](D:/TestProgram/Java/JavaEE/z-exchange/common-util/src/main/java/com/exchange/common/utils/result)

After this wallet migration, the remaining cleanup is repo-wide:

1. scan all modules for remaining legacy shared result imports
2. remove the old `common-util` legacy result package when no callers remain
3. add the checklist contract tests/guards for duplicate codes, valid metadata, proto mapping, and remote wrapping

## Verification

Compile succeeded with Java 21:

- `JAVA_HOME=D:\Jdks\21\jdk-21.0.9`
- `mvn -pl wallet-service -am -DskipTests compile`

Targeted scans show no active wallet source/test references to:

- `com.exchange.common.utils.result`
- `com.exchange.app.wallet.result.ErrorCode`
- `com.exchange.app.wallet.result.Results`
- `Results.getErrorCode`
- `PbMappableErrorCode`
- `protoCode`
- direct lossy wrapping via `reply.getError().getMessage()`
