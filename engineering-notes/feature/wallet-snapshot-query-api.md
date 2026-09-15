# Wallet Snapshot Query API

## Summary

This change implements the two wallet snapshot gRPC query APIs already declared in `grpc-proto/wallet-proto/src/main/proto/wallet/wallet_service.proto`:

- `getSnapshotByWalletId`
- `getSnapshotByRefId`

The implementation follows the existing ledger query layering:

- gRPC boundary in `WalletServiceImpl`
- query processor in `processor/balance`
- store abstraction in `dao/store`
- repository reads in `dao/repository`

The ref-id path adds a stable cache similar in role to `LedgerTxnStore.getByRefId(...)`.
The wallet-id path intentionally remains DB-only with no added cache logic.

## Design

### Boundary

`WalletServiceImpl` now wires:

- `getSnapshotByWalletId(GetSnapshotByIdRequestPb)`
- `getSnapshotByRefId(GetSnapshotByRefIdRequestPb)`

The boundary mirrors the ledger query style:

1. delegate to a processor with a lookup type
2. convert a successful PO result to proto
3. map business failure to `ErrorPb`
4. catch uncaught exceptions and return `SERVER_ERROR`

### Processor

`GetBalanceSnapshotProcessor` is the query orchestrator.

Responsibilities:

- validate lookup value and lookup type
- route lookup to the store
- translate `null` result into `BALANCE_SNAPSHOT_NOT_FOUND`
- keep the boundary thin

This is intentionally symmetric with `GetLedgerTxnProcessor`, but simpler because snapshot query has no child-row loading step.

### Store

`BalanceSnapshotStore` is the query data-access boundary.

Responsibilities:

- `getByWalletId(walletId)`
  - DB read only
  - no cache added
- `getByRefId(referenceId)`
  - stable cache for `referenceId -> walletId`
  - negative cache on miss
  - cached positive ref lookup still reloads the snapshot from DB by wallet id
- `cleanNegativeCacheAfterInsert(referenceId)`
  - used after wallet creation succeeds so an older negative ref cache does not mask a newly created snapshot

### Cache registration

The wallet service had no snapshot query cache wiring before this change.
This change adds:

- wallet cache key scope for snapshot ref lookup
- TTL strategy holder for snapshot ref index cache + negative cache
- cache bean registration for `balanceSnapshotRefCache`

The implementation reuses the shared simple-cache abstractions already used elsewhere in the repo.

### Proto conversion

`PbConverter.convertToBalanceSnapshotPb(...)` converts `BalanceSnapshot` to `BalanceSnapshotPb`.
`EnumPbMappers` now also includes wallet status reverse mapping for query responses.

## API Flow

### `getSnapshotByWalletId`

1. gRPC request arrives with `walletId`
2. `WalletServiceImpl` calls `GetBalanceSnapshotProcessor` with `WALLET_ID`
3. processor validates input
4. store queries `BalanceSnapshotRepository.getByWalletId(...)`
5. processor returns:
   - snapshot on success
   - `BALANCE_SNAPSHOT_NOT_FOUND` if absent
   - `INVALID_REQUEST_PARAMETER` if lookup value is empty
6. boundary converts the snapshot to `BalanceSnapshotPb`

### `getSnapshotByRefId`

1. gRPC request arrives with `referenceId`
2. `WalletServiceImpl` calls `GetBalanceSnapshotProcessor` with `REF_ID`
3. processor validates input
4. store checks stable cache `referenceId -> walletId`
5. on cache hit:
   - positive hit: DB load by wallet id
   - negative hit: fast not-found
6. on cache miss:
   - DB load by wallet reference id
   - positive result caches `referenceId -> walletId`
   - null result writes negative cache
7. processor maps null to `BALANCE_SNAPSHOT_NOT_FOUND`
8. boundary converts the snapshot to `BalanceSnapshotPb`

## Cache Strategy

### `getSnapshotByWalletId`

- No new cache logic
- Direct DB lookup only

### `getSnapshotByRefId`

- Stable cache key stores `referenceId -> walletId`
- Negative cache is enabled for miss protection
- Positive ref cache does not cache the snapshot object itself
- The final snapshot read still goes to DB by wallet id

This keeps the ref-id query fast and stable without introducing snapshot-object cache invalidation logic on the wallet-id path.

## Files Changed

- `wallet-service/src/main/java/com/exchange/app/wallet/service/WalletServiceImpl.java`
- `wallet-service/src/main/java/com/exchange/app/wallet/processor/balance/GetBalanceSnapshotProcessor.java`
- `wallet-service/src/main/java/com/exchange/app/wallet/dao/store/BalanceSnapshotStore.java`
- `wallet-service/src/main/java/com/exchange/app/wallet/dao/repository/BalanceSnapshotRepository.java`
- `wallet-service/src/main/java/com/exchange/app/wallet/dao/cache/WalletCacheRegister.java`
- `wallet-service/src/main/java/com/exchange/app/wallet/constant/cache/CacheScope.java`
- `wallet-service/src/main/java/com/exchange/app/wallet/constant/cache/CacheTtlStrategies.java`
- `wallet-service/src/main/java/com/exchange/app/wallet/utils/PbConverter.java`
- `wallet-service/src/main/java/com/exchange/app/wallet/utils/EnumPbMappers.java`
- `wallet-service/src/main/java/com/exchange/app/wallet/config/CustomCacheProperties.java`
- `wallet-service/src/main/java/com/exchange/app/wallet/processor/CreateWalletProcessor.java`
- `wallet-service/src/main/resources/application.yml`
- `wallet-service/src/test/java/com/exchange/app/wallet/dao/store/BalanceSnapshotStoreUnitTest.java`
- `wallet-service/src/test/java/com/exchange/app/wallet/processor/balance/GetBalanceSnapshotProcessorUnitTest.java`

## High-Value Optimization Ideas

- Add a versioned snapshot-object cache only if query pressure proves high and the write-side invalidation story is strong enough. Right now the user requirement explicitly avoids that on wallet-id query.
- Consider a unique constraint or a proven invariant for wallet reference uniqueness. The current API accepts only `referenceId`, but the schema models wallet identity with service/ref semantics and does not enforce uniqueness on ref alone.
- If snapshot-by-ref becomes a hot public path, consider exposing and documenting the exact uniqueness contract for `referenceId` so cache correctness is not relying on convention.
- The ledger query path and this snapshot query path now share the same broad pattern. A later cleanup could extract a small common query-boundary helper, but that is intentionally not done here to keep the change minimal.
