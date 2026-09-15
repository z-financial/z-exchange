# Kafka Listener Refactor Test Cases

## Scope

These cases cover the behavior described in
`engineering-notes/feature/message/kafka-listener-refactor-change-scope.md`,
especially section `6. Scenario Behavior`, plus additional cases found in the
current `common-util` and `ledger-service` implementation.

This document is the test design blueprint for the unit tests implemented in
this phase. Spring Kafka broker/retry-topic integration tests remain optional
and are intentionally skipped for now.

## Test Layers

- `common-util` unit tests:
  - `DefaultListener`
  - `KafkaHeaderListenerAttemptResolver`
  - `OutboxRepository.insertWithClaimAndVerify`
  - default `MessageHandler` adapters
- `ledger-service` unit tests:
  - `LedgerCommandMessageHandler`
  - `LedgerFailureReplyFactory`
  - `LedgerListenerRetryProperties`
  - ledger common listener config wiring by reflection
- Optional Spring Kafka integration tests:
  - retry-topic routing
  - DLT handler invocation

## Shared Test Fixtures

Use stable fixture names so all tests assert the same business contract:

| Fixture | Value |
| --- | --- |
| `requestEventId` | `wallet-event-123` |
| `commandId` | `wallet-command-123` |
| `successReplyEventId` | `reply:wallet-event-123:success` |
| `serializeErrorReplyEventId` | `reply:wallet-event-123:error:ledger:serialize_error` |
| `internalErrorReplyEventId` | `reply:wallet-event-123:error:ledger:internal_error` |
| `ackFailedHeader` | `zx-ack-failed` |
| `replyFailureAfterAttempts` | `5` |
| `retryTopicAttempts` | `7` |

Mock dependencies for `DefaultListener`:

- `MessageHandler`
- `OutboxRepository`
- `IPublisher`
- `ListenerAttemptResolver`
- `Acknowledgment`
- `Headers`

## Common DefaultListener Tests

### KL-DL-001: Cannot Parse EventEnvelopePb Goes To DLQ

Given invalid envelope bytes.

Expect:

- throws `DlqException`
- error code is `KafkaListenerErrorCode.ENVELOPE_PARSE_ERROR`
- `messageHandler.handle` is not called
- `messageHandler.handleFailure` is not called
- `outboxRepository.insertWithClaimAndVerify` is not called
- `ack.acknowledge` is not called
- `replyPublisher.publish` is not called

### KL-DL-002: Business Success Inserts Reply Outbox Before Ack

Given a parseable envelope and `messageHandler.handle` returns
`ListenerAction.reply(successOutbox)`.

And `insertWithClaimAndVerify` returns `INSERTED`.

Expect:

- `outboxRepository.insertWithClaimAndVerify` is called before `ack.acknowledge`
- outbox `nextAttemptAt` is based on `baseAttemptIntervalMs`
- `ack.acknowledge` is called once
- `replyPublisher.publish(successOutbox)` is called after ack
- no exception is thrown

### KL-DL-003: AckOnly Success Acks Without Outbox Or Publish

Given a parseable envelope and `messageHandler.handle` returns
`ListenerAction.ack()`.

Expect:

- `outboxRepository.insertWithClaimAndVerify` is not called
- `ack.acknowledge` is called once
- `replyPublisher.publish` is not called
- no exception is thrown

### KL-DL-004: Payload Parse Failure Creates Failed Reply

Given the envelope parses, but `messageHandler.handle` throws a
`CustomizedException` representing payload parse failure.

And `messageHandler.handleFailure` returns a failed reply outbox.

Expect:

- `messageHandler.handleFailure` receives `ListenerFailureType.NON_RETRYABLE`
- failure error code is the original customized error code
- failed reply outbox is inserted before ack
- `ack.acknowledge` is called after insert
- immediate publish is attempted after ack
- no source-message retry occurs unless outbox persistence fails retryably

### KL-DL-005: Business Non-Retryable Failure Creates Failed Reply

Given `messageHandler.handle` throws a non-retryable `CustomizedException`.

Expect:

- `messageHandler.handleFailure` receives `ListenerFailureType.NON_RETRYABLE`
- failed reply outbox is inserted
- source message is acked after durable insert
- immediate publish is attempted
- no `KafkaListenerRetriableException` is thrown

### KL-DL-006: Unexpected Exception Creates Internal Failed Reply

Given `messageHandler.handle` throws an unexpected `RuntimeException`.

Expect:

- `messageHandler.handleFailure` receives `ListenerFailureType.UNEXPECTED`
- failure error code is the listener `unexpectedErrorCode`
- failed reply outbox is inserted
- source message is acked after durable insert
- immediate publish is attempted

### KL-DL-007: Retryable Failure Before Reply Threshold Retries Without Reply

Given `messageHandler.handle` throws `KafkaListenerRetriableException`.

And `attemptResolver.resolve(headers)` returns `4` when
`replyFailureAfterAttempts = 5`.

Expect:

- the same retryable exception is thrown
- `messageHandler.handleFailure` is not called
- outbox insert is not called
- ack is not called
- immediate publish is not called

### KL-DL-008: Retryable Failure At Reply Threshold Creates Retry-Exhausted Reply

Given `messageHandler.handle` throws `KafkaListenerRetriableException`.

And `attemptResolver.resolve(headers)` returns `5`.

Expect:

- `messageHandler.handleFailure` receives `ListenerFailureType.RETRY_EXHAUSTED`
- failure error code is `KafkaListenerErrorCode.RETRY_EXHAUSTED`
- failed reply outbox is inserted before ack
- source message is acked
- immediate publish is attempted
- no exception is thrown if outbox insert succeeds

### KL-DL-009: Retryable Failure Above Reply Threshold Also Creates Failed Reply

Given the same setup as `KL-DL-008`, but attempt is `6` or higher.

Expect:

- same behavior as at threshold
- no listener-level `dlqAfterAttempts` check exists
- Spring `@RetryableTopic(attempts)` owns final DLT routing if failed reply
  persistence keeps failing

### KL-DL-010: Retry-Exhausted Reply Persistence Retryable Failure Keeps Retrying

Given a retryable business failure at or above reply threshold.

And `insertWithClaimAndVerify` throws retryable `DbException`.

Expect:

- throws `KafkaListenerRetriableException`
- error code is the DB retryable error code
- ack is not called
- immediate publish is not called
- the message remains eligible for Spring retry-topic retry

### KL-DL-011: Duplicate Reply Outbox With Same Intent Is Idempotent Success

Given `insertWithClaimAndVerify` returns `EXISTING_SAME_INTENT` with an existing
outbox row.

Expect:

- no exception is thrown
- source message is acked
- returned existing outbox is used for immediate publish decision
- if existing outbox status is `PENDING`, immediate publish is attempted
- if existing outbox status is not `PENDING`, immediate publish is skipped

### KL-DL-012: Duplicate Reply Outbox With Conflicting Intent Goes To DLQ

Given `insertWithClaimAndVerify` returns `EXISTING_CONFLICT`.

Expect:

- throws `DlqException`
- error code is `KafkaListenerErrorCode.REPLY_OUTBOX_CONFLICT`
- ack is not called
- immediate publish is not called

### KL-DL-013: Ambiguous Reply Outbox Insert Retries

Given `insertWithClaimAndVerify` returns `EXISTING_MISSING_AMBIGUOUS`.

Expect:

- throws `KafkaListenerRetriableException`
- error code is `KafkaListenerErrorCode.REPLY_OUTBOX_AMBIGUOUS`
- ack is not called
- immediate publish is not called

### KL-DL-014: Reply Outbox Retryable DbException Retries

Given `insertWithClaimAndVerify` throws `DbException` with `isRetryable() = true`.

Expect:

- throws `KafkaListenerRetriableException`
- ack is not called
- immediate publish is not called

### KL-DL-015: Reply Outbox Non-Retryable DbException Goes To DLQ

Given `insertWithClaimAndVerify` throws `DbException` with `isRetryable() = false`.

Expect:

- throws `DlqException`
- ack is not called
- immediate publish is not called

### KL-DL-016: Ack Failure After Durable Outcome Marks Header And Retries

Given reply outbox insert succeeds.

And `ack.acknowledge` throws.

Expect:

- headers contain `zx-ack-failed=true`
- throws `KafkaListenerRetriableException`
- error code is `KafkaListenerErrorCode.ACK_FAILED`
- immediate publish is not attempted in this failed attempt
- durable outbox row remains the recovery path

### KL-DL-017: Retry Record With Ack-Failed Header Skips Business And Outbox

Given headers contain `zx-ack-failed`.

Expect:

- envelope bytes are not parsed
- `messageHandler.handle` is not called
- `messageHandler.handleFailure` is not called
- `outboxRepository.insertWithClaimAndVerify` is not called
- `replyPublisher.publish` is not called
- `ack.acknowledge` is called once

### KL-DL-018: Ack-Failed Retry Still Retries If Ack Fails Again

Given headers contain `zx-ack-failed`.

And `ack.acknowledge` throws again.

Expect:

- throws `KafkaListenerRetriableException`
- error code is `KafkaListenerErrorCode.ACK_FAILED`
- no duplicate ack-failed header is required
- business and outbox logic are still skipped

### KL-DL-019: Immediate Publish Result Failure Is Suppressed

Given durable outbox insert succeeds and ack succeeds.

And `replyPublisher.publish` returns `Result.failure`.

Expect:

- no exception is thrown
- source message has already been acked
- `updateStatusToFinalize` is not called
- outbox retry worker remains responsible for delivery

### KL-DL-020: Immediate Publish Exception Is Suppressed

Given durable outbox insert succeeds and ack succeeds.

And `replyPublisher.publish` throws.

Expect:

- no exception is thrown
- source message has already been acked
- `updateStatusToFinalize` is not called
- outbox retry worker remains responsible for delivery

### KL-DL-021: Immediate Publish Success Finalizes Outbox

Given durable outbox insert succeeds and ack succeeds.

And `replyPublisher.publish` returns `Result.success`.

Expect:

- `outboxRepository.updateStatusToFinalize(outbox, SENT, finalizedAt)` is called
- no exception is thrown

### KL-DL-022: Finalize Sent Failure Is Suppressed

Given immediate publish succeeds.

And `updateStatusToFinalize` returns `0` or throws.

Expect:

- no exception is thrown
- source message remains acked
- duplicate reply publish risk is accepted and should be handled by upstream
  idempotency

## Message Handler Adapter Tests

### KL-MH-001: OutboxReplyDefaultMessageHandler Wraps Success Outbox

Given the delegate function returns an outbox.

Expect:

- `handle(envelope)` returns `ListenerAction.OutboxReply`
- outbox is the exact delegate result

### KL-MH-002: OutboxReplyDefaultMessageHandler Uses FailureReplyFactory

Given a `ListenerFailure`.

Expect:

- `handleFailure(envelope, failure)` calls `FailureReplyFactory.createFailureReply`
- returns `ListenerAction.OutboxReply`

### KL-MH-003: AckOnlyDefaultMessageHandler Success Returns AckOnly

Given the consumer completes successfully.

Expect:

- `handle(envelope)` returns `ListenerAction.AckOnly`
- no reply outbox is created

### KL-MH-004: AckOnlyDefaultMessageHandler Failure Policy Is Explicit

Given a custom failure policy.

Expect:

- `handleFailure(envelope, failure)` returns the action produced by the policy
- null action is rejected

## Retry Attempt Resolver Tests

### KL-AR-001: Missing Headers Resolve To First Attempt

Given headers are null or do not contain retry attempt headers.

Expect:

- resolved attempt is `1`

### KL-AR-002: RetryTopic Attempts Header Has Priority

Given both Spring retry-topic attempt header and Kafka delivery attempt header are
present.

Expect:

- the retry-topic attempt header is used

### KL-AR-003: Binary Integer Attempt Header Is Supported

Given retry attempt header value is a 4-byte integer.

Expect:

- resolved attempt equals that integer when positive

### KL-AR-004: Binary Long Attempt Header Is Supported

Given retry attempt header value is an 8-byte long.

Expect:

- resolved attempt equals that value when within integer range
- values larger than `Integer.MAX_VALUE` resolve to `Integer.MAX_VALUE`

### KL-AR-005: Text Attempt Header Is Supported

Given retry attempt header value is UTF-8 text such as `5`.

Expect:

- resolved attempt is `5`

### KL-AR-006: Invalid Or Non-Positive Attempt Falls Back

Given retry attempt header is invalid text, zero, or negative.

Expect:

- resolver falls back to delivery attempt if valid
- otherwise returns `1`

## Outbox Repository Insert Verification Tests

### KL-OR-001: Inserted Row Returns INSERTED

Given `insertIgnore` returns `1`.

Expect:

- decision type is `INSERTED`
- existing row lookup is not required

### KL-OR-002: Insert Zero And Existing Missing Returns Ambiguous

Given `insertIgnore` returns `0`.

And `selectByEventId` returns null.

Expect:

- decision type is `EXISTING_MISSING_AMBIGUOUS`

### KL-OR-003: Insert Zero And Same Stable Intent Returns Same Intent

Given `insertIgnore` returns `0`.

And existing row has same:

- `eventType`
- `eventId`
- `commandId`
- `destination`
- `partitionKey`
- `payload`

But different lifecycle fields such as status, attempt count, retry timestamps,
or finalized time.

Expect:

- decision type is `EXISTING_SAME_INTENT`
- lifecycle differences do not cause conflict

### KL-OR-004: Insert Zero And Different Payload Returns Conflict

Given existing row has same event id but different payload.

Expect:

- decision type is `EXISTING_CONFLICT`

### KL-OR-005: Insert Zero And Different Routing Field Returns Conflict

Given existing row has same event id but different destination, partition key,
event type, or command id.

Expect:

- decision type is `EXISTING_CONFLICT`

### KL-OR-006: Insert With Claim Initializes Attempt Fields

Given `insertWithClaim` is called.

Expect:

- outbox attempt count is set to `1`
- outbox next attempt time is set to the provided `nextAttemptAt`

## Ledger Command Handler Tests

### KL-LH-001: POST_LEDGER Success Returns Success Reply Outbox

Given event type is `POST_LEDGER`.

And payload is a valid `PostTransactionRequestPb`.

And `PostLedgerProcessor` returns `PostTransactionResult.created`.

Expect:

- handler returns an outbox
- outbox event id is `reply:{requestEventId}:success`
- outbox command id is the source command id
- outbox destination is `LedgerTopic.REPLY_WALLET`
- outbox partition key is the command id
- outbox payload parses as `PostTransactionReplyPb`
- `LedgerBusinessMetrics.recordPostTransaction(KAFKA, ...)` wraps processor call

### KL-LH-002: POST_LEDGER Idempotent Replay Uses Same Success Reply Event Id

Given processor returns `PostTransactionResult.idempotentReplay`.

Expect:

- returned outbox event id is still `reply:{requestEventId}:success`
- no ledger transaction id is included in the reply event id

### KL-LH-003: Invalid Payload Becomes Non-Retryable Failed Reply

Given envelope is parseable but payload is not a valid `PostTransactionRequestPb`.

Expect:

- `LedgerCommandMessageHandler` throws `AbnormalProtoDataException`
- error code is `LedgerServiceErrorCode.SERIALIZE_ERROR`
- when run through `DefaultListener`, a failed reply outbox is created and acked
- reply event id is `reply:{requestEventId}:error:ledger:serialize_error`

### KL-LH-004: Invalid Event Type Becomes Non-Retryable Failed Reply

Given envelope event type is unsupported.

Expect:

- `LedgerCommandMessageHandler` throws `AbnormalProtoDataException`
- error code is `LedgerServiceErrorCode.INVALID_ENUM_ERROR`
- when run through `DefaultListener`, a failed reply outbox is created and acked

### KL-LH-005: Non-Retryable Post Result Becomes CustomizedException

Given processor returns `PostTransactionResult.failure` with a non-retryable
ledger error such as `ACCOUNT_NOT_FOUND`.

Expect:

- handler throws `CustomizedException`
- when run through `DefaultListener`, failed reply outbox is created
- source message is acked after outbox insert

### KL-LH-006: REQUEST_IN_PROCESSING Is Explicitly Retryable

Given processor returns `PostTransactionResult.failure(REQUEST_IN_PROCESSING, ...)`.

Expect:

- handler throws `KafkaListenerRetriableException`
- before reply threshold, `DefaultListener` retries without reply
- at or after reply threshold, `DefaultListener` creates retry-exhausted failed
  reply

### KL-LH-007: PUBLISH_KAFKA_ERROR Is Explicitly Retryable

Given processor returns `PostTransactionResult.failure(PUBLISH_KAFKA_ERROR, ...)`.

Expect:

- same behavior as `KL-LH-006`

### KL-LH-008: Retry-Exhausted Failure Reply Maps Kafka Error To Ledger Internal

Given `LedgerFailureReplyFactory` receives
`ListenerFailure.retryExhausted(KafkaListenerErrorCode.RETRY_EXHAUSTED, ...)`.

Expect:

- factory maps the common Kafka error to `LedgerServiceErrorCode.INTERNAL_ERROR`
- reply event id is `reply:{requestEventId}:error:ledger:internal_error`
- common Kafka namespace/code is not leaked in ledger reply payload

### KL-LH-009: Unexpected Listener Failure Reply Maps To Configured Ledger Error

Given `DefaultListener` is configured with
`LedgerServiceErrorCode.SERVER_ERROR` as `unexpectedErrorCode`.

And `messageHandler.handle` throws an unexpected exception.

Expect:

- failure reply payload uses ledger server/internal error surface according to
  `LedgerBoundaryErrorMapper`
- failed reply is durable before ack

## Ledger Listener Configuration Tests

### KL-CFG-001: Retry Properties Reject Invalid Threshold

Given `attempts < replyFailureAfterAttempts`.

Expect:

- `LedgerListenerRetryProperties.validate()` throws `IllegalArgumentException`

### KL-CFG-002: Retry Properties Allow Equal Threshold

Given `attempts == replyFailureAfterAttempts`.

Expect:

- validation succeeds

### KL-CFG-003: Ledger Common Listener Uses Reply Failure Threshold

Given `LedgerListenerConfig.ledgerCommonListener(...)` is created.

Expect:

- `ListenerRetryPolicy.replyFailureAfterAttempts` equals configured
  `replyFailureAfterAttempts`
- no separate `dlqAfterAttempts` is required

### KL-CFG-006: Retry Topic Container Factory Has No Legacy Error Handler

Given `CommandContainerRegister.concurrentRetryTopicCommandKafkaListenerContainerFactory`.

Expect:

- factory uses manual ack mode
- delivery attempt header is enabled

## Optional Spring Kafka Integration Tests

### KL-IT-001: DlqException Routes Directly To DLT

Given the listener throws `DlqException`.

Expect:

- message is not retried through retry topics
- original record is routed to `{originalTopic}.dlq`
- DLT handler receives the DLT record
- DLT record includes exception metadata headers

### KL-IT-002: KafkaListenerRetriableException Uses Retry Topics

Given the listener throws `KafkaListenerRetriableException`.

Expect:

- message is published to retry topic
- retry attempt headers increment across retry topics
- listener sees attempt values through `ListenerAttemptResolver`
- when attempts are exhausted and no durable reply exists, Spring routes to DLT

### KL-IT-003: Failed Reply Threshold Is Independent From Final DLT Ceiling

Given `replyFailureAfterAttempts = 5` and `@RetryableTopic.attempts = 7`.

And business keeps throwing retryable failure.

Expect:

- attempts 1 to 4 retry without failed reply
- attempt 5 tries to persist failed reply
- if failed reply insert succeeds, source message is acked and no DLT occurs
- if failed reply insert keeps failing retryably, retries continue until Spring
  retry-topic attempts are exhausted and then DLT occurs

## Scenario Coverage Map

| Scenario from section 6 | Covered by |
| --- | --- |
| Cannot parse `EventEnvelopePb` | `KL-DL-001`, `KL-IT-001` |
| Envelope parsed, payload parse fails | `KL-DL-004`, `KL-LH-003` |
| Business success | `KL-DL-002`, `KL-LH-001`, `KL-LH-002` |
| Business non-retryable failure | `KL-DL-005`, `KL-LH-005` |
| Business retryable failure before reply threshold | `KL-DL-007`, `KL-LH-006`, `KL-LH-007` |
| Business retryable failure at/after reply threshold | `KL-DL-008`, `KL-DL-009`, `KL-IT-003` |
| Retry topic attempts exhausted and no durable reply exists | `KL-IT-002`, `KL-IT-003` |
| Duplicate reply outbox, same intent | `KL-DL-011`, `KL-OR-003` |
| Duplicate reply outbox, conflicting intent | `KL-DL-012`, `KL-OR-004`, `KL-OR-005` |
| Reply outbox insert DB unavailable/timeout/transient | `KL-DL-014`, `KL-DL-010` |
| Reply outbox insert non-retryable DB error | `KL-DL-015` |
| Ack fails after durable success/failure outcome | `KL-DL-016` |
| Retry record has ack-failed marker | `KL-DL-017`, `KL-DL-018` |
| Immediate publish fails after ack | `KL-DL-019`, `KL-DL-020` |
| Finalize sent status fails after publish | `KL-DL-022` |
| Unexpected non-custom exception after envelope parsed | `KL-DL-006`, `KL-LH-009` |

## Recommended First Implementation Batch

Start with unit tests that do not require Kafka broker infrastructure:

1. `DefaultListener` orchestration tests `KL-DL-001` to `KL-DL-022`.
2. `KafkaHeaderListenerAttemptResolver` tests `KL-AR-001` to `KL-AR-006`.
3. Ledger handler/factory tests `KL-LH-001` to `KL-LH-009`.
4. Config/reflection tests `KL-CFG-001` to `KL-CFG-003` and `KL-CFG-006`.

Add embedded Kafka integration tests later only if the team wants executable
proof of Spring retry-topic and DLT routing behavior.
