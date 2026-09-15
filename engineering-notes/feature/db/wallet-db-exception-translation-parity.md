# Wallet DB Exception Translation Parity

## Scope

- Add `spring-boot-starter-aop` as a direct wallet dependency.
- Enable AspectJ auto-proxying from `WalletServer`.
- Reuse common repository and `DbTxnExecutor` exception translation unchanged.
- Retain the existing MyBatis statement and full-transaction metrics.

## Runtime Behavior

Calls through `DbBaseRepository` subclasses or `DbTxnExecutor` now translate recognized Spring,
JDBC, and transaction failures into `DbException`. Existing `DbException` instances pass through
unchanged, and unclassified exceptions remain unmodified. Repository translation inside a
transaction is not wrapped a second time at the transaction boundary.

No mapper, SQL, processor, transaction boundary, retry policy, or metric definition changed.

## Verification

The focused wallet configuration test verifies that repository and transaction beans are proxied,
connection failures map to `DB_UNAVAILABLE`, an existing `DbException` retains its identity, and
the transaction error timer still records the failed execution.
