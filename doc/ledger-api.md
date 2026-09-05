# Ledger API Boundaries

Ledger exposes its business operations through gRPC and consumes asynchronous posting commands through Kafka. It does not expose a business REST/HTTP API.

## Network boundaries

| Port/channel | Purpose |
| --- | --- |
| gRPC `9191` | Ledger account, posting, and transaction-query APIs |
| HTTP `8081` | Spring Boot Actuator only: health, metrics, and Prometheus scraping |
| Kafka | Wallet-to-Ledger posting commands and Ledger-to-Wallet replies |

Port `8081` must remain private to monitoring and operational clients. There are no `/api/...` Ledger routes.

## gRPC services

The contracts are defined under [`grpc-proto/ledger-proto/src/main/proto`](../grpc-proto/ledger-proto/src/main/proto/).

### `AccountService`

- `createAccount(CreateAccountRequestPb)` provisions an accounting account.
- Wallet uses this RPC while creating and opening a wallet.

### `PostService`

- `postTransaction(PostTransactionRequestPb)` validates and writes a balanced ledger transaction.
- `getTxnById(GetTxnByIdRequestPb)` queries by Ledger transaction ID.
- `getTxnByRefId(GetTxnByRefIdRequestPb)` queries by the caller's reference ID.
- Both query methods can optionally include ledger entries.

The query RPCs share `GetLedgerTxnProcessor` and the same business metric boundary. Errors are returned through the protobuf `ErrorPb` contract; unexpected internal details are not exposed to callers.

## Asynchronous posting

Wallet's active settlement path does not call `postTransaction` synchronously. It writes a transactional outbox event and publishes a command to Kafka. Ledger consumes that command through the same posting processor used by gRPC, persists an idempotent reply outbox, and acknowledges the source record only after a durable outcome exists.

This preserves one posting implementation while supporting two ingress paths:

- gRPC for direct trusted-service calls and tests;
- Kafka for Wallet's eventually consistent settlement workflow.

## Observability

Ledger records gRPC server and business-processing metrics separately. Business metrics include an `ingress` tag (`grpc` or `kafka`) so processor behavior can be compared without treating transport time as business time. Prometheus scrapes `/actuator/prometheus` on port `8081`.
