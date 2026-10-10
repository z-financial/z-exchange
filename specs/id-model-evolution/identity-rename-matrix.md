# Identity Schema and Protobuf Rename Matrix

Status: Fixed for clean cutover | Last updated: 2026-10-10

This matrix is normative for the phase-two identity cutover. Existing protobuf
field numbers are retained when a field is renamed. Caller-owned business
references, idempotency keys, asset identifiers, and owner identifiers do not
become generated entity references.

## 1. Owner tables

| Table | Current storage key | Target storage key | Current generated identity | Target typed identity |
| --- | --- | --- | --- | --- |
| `wallets` | `id` | `internal_id` | `wallet_id` | `wallet_ref` |
| `wallet_transactions` | `id` | `internal_id` | `txn_id` | `wallet_txn_ref` |
| `wallet_reservations` | `id` | `internal_id` | `reservation_id` | `wallet_reservation_ref` |
| `wallet_actions` | `id` | `internal_id` | `action_id` | `wallet_action_ref` |
| `accounts` | `id` | `internal_id` | `account_id` | `ledger_account_ref` |
| `ledger_transactions` | `id` | `internal_id` | `txn_id` | `ledger_txn_ref` |
| `ledger_entries` | `id` | `internal_id` | `entry_id` | `ledger_entry_ref` |
| Wallet `outbox` | `id` | `internal_id` | `event_id` | `event_ref` |
| Ledger `outbox` | `id` | `internal_id` | `event_id` | `event_ref` |

Every target `internal_id` is `BIGINT UNSIGNED NOT NULL PRIMARY KEY`, assigned
by the application and constrained to `1..Long.MAX_VALUE`. Every entity typed
reference is `CHAR(22) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE`.
The binary collation preserves the case-sensitive Base62 contract. Event-reference
length belongs to the separate event contract and is not assumed to be 22.

Covered Java persistence models use `Long internalId` annotated with
`@TableId(value = "internal_id", type = IdType.INPUT)`. Entity construction
receives one `GeneratedEntityIdentity` from the shared factory; MyBatis does
not generate or reinterpret the value.

## 2. Persisted relationships and caller references

| Current column | Target column | Classification |
| --- | --- | --- |
| `wallet_actions.txn_id` | `wallet_txn_ref` | generated Wallet transaction reference |
| `wallet_actions.wallet_id` | `wallet_ref` | generated Wallet reference |
| `wallet_actions.reservation_id` | `wallet_reservation_ref` | optional generated reservation reference |
| `wallet_reservations.wallet_id` | `wallet_ref` | generated Wallet reference |
| `wallet_reservations.wallet_reference_id` | `wallet_business_ref` | caller-owned Wallet business reference |
| `wallet_reservations.reserve_txn_id` | `reserve_wallet_txn_ref` | generated Wallet transaction reference |
| `balance_snapshots.wallet_id` | `wallet_ref` | generated Wallet reference; snapshot PK remains `id` |
| `balance_snapshots.wallet_reference_id` | `wallet_business_ref` | caller-owned Wallet business reference |
| `balance_snapshots.last_txn_id` | `last_wallet_txn_ref` | generated Wallet transaction reference |
| `wallet_account_mappings.wallet_id` | `wallet_ref` | generated Wallet reference |
| `wallet_account_mappings.account_ref_id` | `ledger_account_ref` | generated Ledger account reference |
| `ledger_entries.txn_id` | `ledger_txn_ref` | generated Ledger transaction reference |
| `ledger_entries.account_id` | `ledger_account_ref` | generated Ledger account reference |
| `ledger_transactions.reference_id` | `wallet_txn_ref` | source Wallet command identity |
| `accounts.reference_id` | `wallet_ref` | source Wallet identity |
| `outbox.command_id` | `command_ref` | typed command/correlation reference |

The following fields remain caller data with unchanged meaning:

- `wallets.reference_id`;
- `wallet_transactions.reference_id`;
- `wallet_reservations.reference_id`;
- all `idempotency_key`, `owner_id`, and `asset_id` fields.

## 3. Common event protobuf

| Message | Field number | Current field | Target field |
| --- | ---: | --- | --- |
| `EventEnvelopePb` | 1 | `event_id` | `event_ref` |
| `EventEnvelopePb` | 2 | `command_id` | `command_ref` |

No protobuf field carries an outbox `internal_id`.

## 4. Wallet protobuf

| Message | Field number | Current field | Target field | Meaning |
| --- | ---: | --- | --- | --- |
| `CreateWalletRequestPb` | 2 | `reference_id` | unchanged | caller business reference |
| `CreateWalletReplyPb` | 2 (new) | — | `wallet_ref` | created or replayed Wallet reference |
| `AtomicTransactionReplyPb` | 2 | `transaction_id` | `wallet_txn_ref` | Wallet transaction reference |
| `ReserveTransactionReplyPb` | 2 | `transaction_id` | `wallet_txn_ref` | Wallet transaction reference |
| `TwoStepTransactionReplyPb` | 2 | `transaction_id` | `wallet_txn_ref` | Wallet transaction reference |
| `ApplyReservationTransactionReplyPb` | 2 | `transaction_id` | `wallet_txn_ref` | Wallet transaction reference |
| transaction request messages | 1 | `reference_id` | unchanged | caller business reference |
| transaction request messages | 3 | `idempotency_key` | unchanged | caller idempotency key |
| `TransactionLinePb` | 1 | `wallet_ref` | unchanged name, typed semantics | Wallet reference |
| `TransactionLinePb` | 5 | `reservation_ref` | unchanged name, typed semantics | reservation reference |
| `ReservationInfoPb` | 1 | `reservation_ref` | unchanged name, typed semantics | reservation reference |
| `ReservationInfoPb` | 2 | `wallet_ref` | unchanged name, typed semantics | Wallet reference |
| `GetSnapshotByIdRequestPb` | 1 | `walletId` | `wallet_ref` | Wallet reference |
| `GetSnapshotByRefIdRequestPb` | 1 | `referenceId` | `wallet_business_ref` | caller business reference |
| `BalanceSnapshotPb` | 1 | `walletId` | `wallet_ref` | Wallet reference |
| `BalanceSnapshotPb` | 3 | `walletReferenceId` | `wallet_business_ref` | caller business reference |

RPC and message names migrate with their identity semantics:

- `GetSnapshotByIdRequestPb` → `GetSnapshotByWalletRefRequestPb`;
- `GetSnapshotByRefIdRequestPb` → `GetSnapshotByBusinessRefRequestPb`;
- `getSnapshotByWalletId` → `getSnapshotByWalletRef`; and
- `getSnapshotByRefId` → `getSnapshotByBusinessRef`.

## 5. Ledger protobuf

| Message | Field number | Current field | Target field | Meaning |
| --- | ---: | --- | --- | --- |
| `CreateAccountRequestPb` | 1 | `reference_id` | `wallet_ref` | source Wallet reference |
| `CreateAccountReplyPb` | 2 (new) | — | `ledger_account_ref` | created or replayed Ledger account reference |
| `PostTransactionRequestPb` | 1 | `referenceId` | `wallet_txn_ref` | source Wallet transaction reference |
| `PostTransactionReplyPb` | 1 | `referenceId` | `wallet_txn_ref` | source Wallet transaction reference |
| `PostTransactionReplyPb` | 2 | `ledgerTxnId` | `ledger_txn_ref` | Ledger transaction reference |
| `GetTxnByIdRequestPb` | 1 | `ledgerTxnId` | `ledger_txn_ref` | Ledger transaction reference |
| `GetTxnByRefIdRequestPb` | 1 | `referenceId` | `wallet_txn_ref` | source Wallet transaction reference |
| `LedgerTransactionPb` | 1 | `ledgerTxnId` | `ledger_txn_ref` | Ledger transaction reference |
| `LedgerTransactionPb` | 2 | `referenceId` | `wallet_txn_ref` | source Wallet transaction reference |
| `LedgerEntryPb` | 1 | `ledgerEntryId` | `ledger_entry_ref` | Ledger entry reference |
| `LedgerEntryPb` | 2 | `accountRef` | `ledger_account_ref` | Ledger account reference |

RPC and message names migrate with their identity semantics:

- `GetTxnByIdRequestPb` → `GetTxnByLedgerTxnRefRequestPb`;
- `GetTxnByRefIdRequestPb` → `GetTxnByWalletTxnRefRequestPb`;
- `getTxnById` → `getTxnByLedgerTxnRef`; and
- `getTxnByRefId` → `getTxnByWalletTxnRef`.

## 6. Boundary enforcement

Cache keys, Kafka keys, structured log fields, reconciliation output, and
idempotency result payloads use the target typed-reference names above.
Production boundaries never serialize or log `internal_id`. An inbound typed
reference is decoded by the shared codec with its expected `EntityRefType`;
business code does not parse it or infer routing from its contents.
