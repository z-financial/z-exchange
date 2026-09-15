# Wallet Kafka Listener Template Change

## Scope

- Replaced wallet's blocking `DefaultErrorHandler` listener path with the common listener template.
- Added `@RetryableTopic`, DLT handling, Kafka-header attempt resolution, and ack-failure recovery.
- Extracted ledger-reply parsing and completion into `WalletLedgerReplyMessageHandler`.
- Registered the handler through `AckOnlyDefaultMessageHandler`; wallet emits no reply outbox.

## Behavior

- Only a valid `ERROR_OK` ledger reply can finalize a wallet transaction.
- Retryable processing failures use non-blocking retry topics until the configured final attempt.
- Malformed messages, non-retryable failures, unexpected failures, and exhausted retries route to DLT.
- A retry caused only by acknowledgment failure skips already-durable wallet processing.
- Failed ledger replies leave the wallet transaction pending and are retained in DLT for reconciliation.

## Configuration

`app.kafka.listener.retry` controls attempts, backoff, and topic auto-creation. Production retry and
DLT topics remain infrastructure-managed.

## Verification

Unit tests cover successful completion, failed/malformed replies, error classification, explicit
AckOnly failure routing, and retry-policy alignment.
