# Ledger Account Result Migration

## Scope

This change migrates the ledger account gRPC slice onto the refactored shared `Result` contract in [`Result.java`](D:/TestProgram/Java/JavaEE/z-exchange/common-util/src/main/java/com/exchange/common/result/Result.java).

Touched ledger code:

- [`AccountServiceImpl.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/service/AccountServiceImpl.java)
- [`CreateAccountProcessor.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/processor/account/CreateAccountProcessor.java)
- [`PbErrorBuilder.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/result/PbErrorBuilder.java)

Added boundary-side support:

- [`LedgerServiceErrorCode.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/result/LedgerServiceErrorCode.java)
- [`LedgerBoundaryErrorMapper.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/result/LedgerBoundaryErrorMapper.java)
- [`ProtoErrorMapper.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/result/ProtoErrorMapper.java)

It does not migrate the ledger post flow or delete the legacy ledger `Results` helper.

## What Changed

### Account path now uses shared `Result`

[`CreateAccountProcessor.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/processor/account/CreateAccountProcessor.java) now imports `com.exchange.common.result.Result` and uses:

- `Result.failure(...)` for validation failures
- `Result.success()` for the success path
- `isSuccess()` instead of the old record-style `success()`

This removes account-slice dependency on:

- `com.exchange.common.utils.result.Result`
- `ledger-service Results.fail(...)`
- `Results.getErrorCode(...)` for local reply building

### Ledger boundary mapping is split from the shared model

Added [`ProtoErrorMapper.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/result/ProtoErrorMapper.java) in `ledger-service`.

It maps shared `ErrorCategory` values to `ErrorCodePb` at the ledger boundary, instead of importing proto mapping from `common-util`.

### Boundary policy is now separate from protobuf assembly

Added [`LedgerBoundaryErrorMapper.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/result/LedgerBoundaryErrorMapper.java) for ledger-owned public error mapping.

Current rule:

- pass through `LedgerServiceErrorCode` unchanged
- map current common-util internal/shared errors explicitly:
- `IdempErrorCode.INVALID_IDEMP_KEY` -> `LedgerServiceErrorCode.INTERNAL_ERROR`
- `IdempErrorCode.INVALID_IDEMP_VALUE` -> `LedgerServiceErrorCode.INTERNAL_ERROR`
- `CacheErrorCode.PARSE_CACHE_ERROR` -> `LedgerServiceErrorCode.INTERNAL_ERROR`
- `OutboxErrorCode.UNEXPECTED_DB_ERROR` -> `LedgerServiceErrorCode.INTERNAL_ERROR`
- fallback every other foreign/common error to `LedgerServiceErrorCode.INTERNAL_ERROR`

Unmapped fallback is logged before returning the ledger-owned internal error.

This keeps foreign/common error enums out of the ledger gRPC surface while still allowing business code to return ledger-owned errors directly when the public meaning is already known.

[`PbErrorBuilder.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/result/PbErrorBuilder.java) is now back to a narrower role on the new path:

- build `ErrorPb` from `LedgerServiceErrorCode`
- keep legacy overloads only for the untouched post flow

That separation is intentional. Mapping foreign/common errors into ledger public errors is service policy, not protobuf assembly.

### Ledger shared-service errors are isolated from the legacy catch-all enum

Added [`LedgerServiceErrorCode.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/result/LedgerServiceErrorCode.java) as the new service-wide naming direction, even though this step only migrates the account flow.

- `INTERNAL_ERROR`
- `SERVER_ERROR`
- `INVALID_REQUEST_PARAMETER`
- `ASSET_NOT_FOUND`

The enum implements the shared `ErrorCode` contract, uses the broader `ledger` namespace, and carries category metadata for boundary mapping.

## Remaining Work

The ledger legacy path is still present in:

- [`ledger-service/src/main/java/com/exchange/app/ledger/result/ErrorCode.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/result/ErrorCode.java)
- [`ledger-service/src/main/java/com/exchange/app/ledger/result/Results.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/result/Results.java)
- post-flow callers such as [`PostServiceImpl.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/service/PostServiceImpl.java) and [`PostLedgerProcessor.java`](D:/TestProgram/Java/JavaEE/z-exchange/ledger-service/src/main/java/com/exchange/app/ledger/processor/post/PostLedgerProcessor.java)

So this step removes the cast-driven boundary handling only for the account slice, not for all of `ledger-service`.

The next meaningful step is to migrate the post flow onto the shared `Result` contract, route it through `LedgerBoundaryErrorMapper`, then delete the legacy ledger `Results` helper and the enum-held `protoCode` field.

## Verification

Targeted scan confirms the migrated account slice no longer imports the legacy shared result package or ledger `Results` helper.

I did not complete a Maven compile in this environment because earlier module compile attempts failed on local Java toolchain setup:

- `invalid target release: 21`
