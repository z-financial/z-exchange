# Kafka Listener Refactor Change Scope

## 1. Change Scope

Target scope is intentionally narrow:

- `common-util`
  - add shared listener action model
  - add shared `DefaultListener` orchestration logic
  - add default message handler adapters
  - add listener retry/DLQ exceptions
  - add retry attempt resolver based on Kafka headers
  - add idempotent reply outbox insert/verification support
  - add ack-failure offset recovery marker and retryable listener error
  - optionally add listener-related metrics/logging hooks
- `ledger-service`
  - replace current ledger Kafka command listener with the common listener template
  - provide ledger-specific message handler
  - provide ledger-specific failure reply factory
  - provide ledger-specific immediate reply publisher
  - preserve current wallet-service contract and reply payload format

Out of scope for this phase:

- wallet-service listener refactor
- protobuf schema changes
- adding `attempt_count` to `EventEnvelopePb`
- changing wallet transaction state machine
- changing ledger business transaction logic
- changing outbox dispatcher publish retry behavior, except where needed for shared insert verification APIs

## 2. Design Philosophy

The listener should preserve one core invariant:

```text
ack source Kafka message only after the durable local outcome exists
```

For ledger command consumption, the durable local outcome is usually a reply outbox row. The reply row must exist before ack so downstream reply delivery can recover through the outbox retry worker even if immediate publish fails.

Ack failure is not a business failure. If ack fails after durable processing, the listener should mark the Kafka headers with `zx-ack-failed` and throw a retryable listener exception. On the retry record, the listener should acknowledge directly and skip business/outbox processing, because the previous attempt already made the local outcome durable.

The refactor separates responsibilities:

```text
DefaultListener
  -> owns generic listener lifecycle:
     parse envelope, call handler, persist action, ack, immediate publish, retry/DLQ routing,
     ack-failure offset recovery

MessageHandler
  -> owns service-specific success and failure action creation

FailureReplyFactory
  -> owns service-specific failed reply outbox creation

private DefaultListener outbox insert method
  -> owns reply outbox durable-before-ack persistence and idempotent duplicate handling
```

Retry and DLQ are not normal success actions. They are listener boundary outcomes represented by exceptions:

- `KafkaListenerRetriableException`
  - retry this source message
- `DlqException`
  - route this source message to DLT/DLQ

Normal handler actions stay small:

- `AckOnly`
- `OutboxReply`

`EventEnvelopePb` must remain immutable business/event data. Listener retry attempt count belongs to Kafka retry headers, not protobuf payload.

## 3. Core Model Pseudocode

### Listener Actions

```java
sealed interface ListenerAction permits AckOnly, OutboxReply {
    static AckOnly ack() { ... }
    static OutboxReply reply(Outbox outbox) { ... }
}

record AckOnly() implements ListenerAction {}

record OutboxReply(Outbox outbox) implements ListenerAction {}
```

### Listener Failure

```java
record ListenerFailure(
        ErrorCode errorCode,
        String detail,
        Throwable cause,
        ListenerFailureType type
) {}

enum ListenerFailureType {
    NON_RETRYABLE,
    UNEXPECTED,
    RETRY_EXHAUSTED
}
```

### Message Handler

```java
interface MessageHandler {
    ListenerAction handle(EventEnvelopePb envelope);

    ListenerAction handleFailure(EventEnvelopePb envelope, ListenerFailure failure);
}
```

### Default Handler Adapters

```java
class OutboxReplyDefaultMessageHandler implements MessageHandler {
    private final Function<EventEnvelopePb, Outbox> handler;
    private final FailureReplyFactory failureReplyFactory;

    ListenerAction handle(EventEnvelopePb envelope) {
        return ListenerAction.reply(handler.apply(envelope));
    }

    ListenerAction handleFailure(EventEnvelopePb envelope, ListenerFailure failure) {
        return ListenerAction.reply(failureReplyFactory.createFailureReply(envelope, failure));
    }
}
```

```java
class AckOnlyDefaultMessageHandler implements MessageHandler {
    private final Consumer<EventEnvelopePb> consumer;
    private final BiFunction<EventEnvelopePb, ListenerFailure, ListenerAction> failurePolicy;

    ListenerAction handle(EventEnvelopePb envelope) {
        consumer.accept(envelope);
        return ListenerAction.ack();
    }

    ListenerAction handleFailure(EventEnvelopePb envelope, ListenerFailure failure) {
        return failurePolicy.apply(envelope, failure);
    }
}
```

The ack-only adapter exists as common infrastructure, but wallet-service should not be migrated in this phase.

### Failure Reply Factory

```java
interface FailureReplyFactory {
    Outbox createFailureReply(EventEnvelopePb envelope, ListenerFailure failure);
}
```

Ledger implementation:

```text
ListenerFailure
  -> LedgerServiceErrorCode
  -> PostTransactionResult.failure(...)
  -> PostTransactionResultConverter.toProto(...)
  -> OutboxHelper.fromPostTransactionResult(...)
```

### Retry Attempt Resolver

```java
interface ListenerAttemptResolver {
    int resolve(Headers headers);
}
```

Implementation should read Kafka retry-topic headers and normalize them into a 1-based source-processing attempt count. Listener code should not depend on raw Spring Kafka header names.

## 4. DefaultListener Core Logic

Recommended shape:

```java
void onMessage(byte[] envelopeBytes, Acknowledgment ack, Headers headers) {
    EventEnvelopePb envelope = null;
    Outbox outboxToPublish = null;

    if (hasAckFailedHeader(headers)) {
        acknowledgeOrThrowRetriable(ack, headers);
        return;
    }

    try {
        envelope = parseEnvelopeOrThrowDlq(envelopeBytes);

        ListenerAction action = messageHandler.handle(envelope);
        outboxToPublish = persistActionOrThrow(action);
    } catch (DlqException e) {
        throw e;
    } catch (KafkaListenerRetriableException e) {
        outboxToPublish = handleRetriableFailureOrThrow(envelope, e, headers);
    } catch (CustomizedException e) {
        ListenerFailure failure = ListenerFailure.nonRetryable(e.getErrorCode(), e.getMessage(), e);
        ListenerAction action = messageHandler.handleFailure(requireEnvelope(envelope), failure);
        outboxToPublish = persistActionOrThrow(action);
    } catch (Exception e) {
        ListenerFailure failure = ListenerFailure.unexpected(internalErrorCode, "unexpected listener failure", e);
        ListenerAction action = messageHandler.handleFailure(requireEnvelope(envelope), failure);
        outboxToPublish = persistActionOrThrow(action);
    }

    acknowledgeOrThrowRetriable(ack, headers);

    if (outboxToPublish != null) {
        tryImmediatePublishSuppressingErrors(outboxToPublish);
    }
}
```

Ack failure handling:

```java
void acknowledgeOrThrowRetriable(Acknowledgment ack, Headers headers) {
    try {
        ack.acknowledge();
    } catch (Exception e) {
        headers.add("zx-ack-failed", "true".getBytes(UTF_8));
        throw new KafkaListenerRetriableException(ackFailedError, "ack failed after durable processing", e);
    }
}
```

This helper is called only after durable listener processing. Therefore, `ACK_FAILED` means "recover Kafka offset completion", not "retry business logic". The retry header lets the next retry record skip handler/outbox execution and only acknowledge the message.

Envelope parse failure is special:

```java
EventEnvelopePb parseEnvelopeOrThrowDlq(byte[] envelopeBytes) {
    try {
        return EventEnvelopePb.parseFrom(envelopeBytes);
    } catch (InvalidProtocolBufferException e) {
        throw new DlqException(kafkaEnvelopeParseError, "cannot parse event envelope", e);
    }
}
```

Retry handling:

```java
Outbox handleRetriableFailureOrThrow(
        EventEnvelopePb envelope,
        KafkaListenerRetriableException e,
        Headers headers
) {
    int attempt = listenerAttemptResolver.resolve(headers);

    if (attempt < replyFailureAfterAttempts) {
        throw e;
    }

    ListenerFailure failure = ListenerFailure.retryExhausted(
            kafkaRetryExhausted,
            e.getMessage(),
            e
    );
    ListenerAction action = messageHandler.handleFailure(requireEnvelope(envelope), failure);
    // If this persistence attempt still fails with a retryable listener exception,
    // rethrow it and let @RetryableTopic(attempts) own final DLT routing.
    return persistActionOrThrow(action);
}
```

`persistActionOrThrow` stays inside `DefaultListener` as a private method for phase one:

```java
Outbox persistActionOrThrow(ListenerAction action) {
    return switch (action) {
        case AckOnly ignored -> null;
        case OutboxReply reply -> insertReplyOutboxOrThrowListenerException(reply.outbox());
    };
}
```

The private insert method converts DB/outbox problems into listener-level exceptions:

```java
Outbox insertReplyOutboxOrThrowListenerException(Outbox outbox) {
    try {
        OutboxInsertDecision decision = outboxRepository.insertWithClaimAndVerify(outbox, nextAttemptAt());
        return switch (decision.type()) {
            case INSERTED, EXISTING_SAME_INTENT -> outbox;
            case EXISTING_MISSING_AMBIGUOUS -> throw new KafkaListenerRetriableException(...);
            case EXISTING_CONFLICT -> throw new DlqException(...);
        };
    } catch (DbException e) {
        if (isRetryable(e)) {
            throw new KafkaListenerRetriableException(..., e);
        }
        throw new DlqException(..., e);
    }
}
```

`tryImmediatePublishSuppressingErrors` must not rethrow:

```java
void tryImmediatePublishSuppressingErrors(Outbox outbox) {
    try {
        Result<Void> result = publisher.publish(outbox);
        if (result.isSuccess()) {
            finalizeOutboxAsSent(outbox);
        } else {
            log error and let outbox retry worker recover
        }
    } catch (Exception e) {
        log error and let outbox retry worker recover
    }
}
```

## 5. Ledger-Service Flow

Ledger should provide an `OutboxReplyDefaultMessageHandler`.

Normal handler:

```java
Outbox handleLedgerCommand(EventEnvelopePb envelope) {
    switch (envelope.getEventType()) {
        case POST_LEDGER:
            PostTransactionRequestPb req = parsePayloadOrThrowCustomizedException(envelope.getPayload());
            PostTransactionResult result = ledgerBusinessMetrics.recordPostTransaction(
                    KAFKA,
                    () -> postLedgerProcessor.postTransaction(req)
            );

            if (result.isSuccess()) {
                return OutboxHelper.fromPostTransactionResult(
                        result,
                        envelope.getEventId(),
                        envelope.getCommandId()
                );
            }

            handlePostTransactionFailure(result);
        default:
            throw invalid enum customized exception;
    }
}
```

Failure handling for `PostTransactionResult.Kind.ERROR`:

```java
void handlePostTransactionFailure(PostTransactionResult result) {
    if (isExplicitRetryable(result)) {
        throw new KafkaListenerRetriableException(...);
    }
    throw new CustomizedException(result.errorCode(), result.detail());
}
```

Current limitation:

- `PostTransactionResult` currently stores `LedgerServiceErrorCode`, not retryability metadata.
- `LedgerBoundaryErrorMapper` maps common DB/Redis/cache errors to ledger `INTERNAL_ERROR`, losing the original retryability signal.
- Therefore, for this phase, retry only explicitly recognized retryable failures. Treat most `PostTransactionResult.Kind.ERROR` cases as non-retryable and generate a failed reply outbox.

Payload parse errors:

```text
EventEnvelopePb parse failure
  -> DlqException, no reply possible

EventEnvelopePb parsed but payload parse failure
  -> CustomizedException / parse failure
  -> failed reply outbox
```

Ledger failure reply factory:

```java
Outbox createFailureReply(EventEnvelopePb envelope, ListenerFailure failure) {
    LedgerServiceErrorCode ledgerError = mapToLedgerError(failure.errorCode());
    PostTransactionResult result = PostTransactionResult.failure(ledgerError, failure.detail());
    return OutboxHelper.fromPostTransactionResult(result, envelope.getEventId(), envelope.getCommandId());
}
```

## 6. Scenario Behavior

| Scenario | Behavior | Ack | Retry | DLQ | Reply |
| --- | --- | --- | --- | --- | --- |
| Cannot parse `EventEnvelopePb` | Throw `DlqException` | no | no | yes | no |
| Envelope parsed, payload parse fails | `handleFailure` creates failed reply | yes after outbox insert | only if outbox insert retryable failure | only after `@RetryableTopic.attempts` is exhausted | failed reply |
| Business success | Create success reply outbox | yes after outbox insert | no | no | success reply |
| Business non-retryable failure | `handleFailure` creates failed reply | yes after outbox insert | only if outbox insert retryable failure | only after `@RetryableTopic.attempts` is exhausted | failed reply |
| Business retryable failure before reply threshold | Throw `KafkaListenerRetriableException` | no | yes | no | no |
| Business retryable failure at/after reply threshold | Create retry-exhausted failed reply | yes after outbox insert | only if outbox insert retryable failure | only after `@RetryableTopic.attempts` is exhausted | failed reply |
| Retry topic attempts exhausted and no durable reply exists | Spring routes to DLT | no | no | yes | no |
| Duplicate reply outbox, same intent | Treat as idempotent success | yes | no | no | existing reply |
| Duplicate reply outbox, conflicting intent | Throw `DlqException` | no | no | yes | no |
| Reply outbox insert DB unavailable/timeout/transient | Convert to `KafkaListenerRetriableException` | no | yes | no until retry-topic exhaustion | no |
| Reply outbox insert non-retryable DB error | Convert to `DlqException` | no | no | yes | no |
| Ack fails after durable success/failure outcome | Mark `zx-ack-failed`, throw `ACK_FAILED` retryable error | no | yes, offset recovery only | only if ack cannot recover by retry/DLT policy | existing durable outcome |
| Retry record has ack-failed marker | Skip business/outbox logic and acknowledge directly | yes if ack succeeds | only if ack fails again | only after retry/DLT exhaustion | existing durable outcome |
| Immediate publish fails after ack | Suppress/log; outbox retry recovers | already acked | no source retry | no | durable pending reply |
| Finalize sent status fails after publish | Suppress/log; outbox retry may republish | already acked | no source retry | no | possible duplicate reply, handled by upstream idempotency |
| Unexpected non-custom exception after envelope parsed | `handleFailure` creates `internal_error` reply | yes after outbox insert | only if outbox insert retryable failure | only after `@RetryableTopic.attempts` is exhausted | failed reply |

## 7. Outbox Insert Verification

`OutboxRepository.insertWithClaim(...)` is not enough because it only returns insert count.

Add a method equivalent to:

```java
OutboxInsertDecision insertWithClaimAndVerify(Outbox intended, LocalDateTime nextAttemptAt);
```

Decision types:

```java
enum OutboxInsertDecisionType {
    INSERTED,
    EXISTING_SAME_INTENT,
    EXISTING_MISSING_AMBIGUOUS,
    EXISTING_CONFLICT
}
```

Compare stable intent fields:

- `event_type`
- `event_id`
- `command_id`
- `destination`
- `partition_key`
- `payload` bytes or deterministic payload hash

Do not compare lifecycle fields:

- `outbox_status`
- `attempt_count`
- `last_attempt_at`
- `next_attempt_at`
- `last_error`
- `finalized_at`

Duplicate same intent is success. Duplicate different intent is a contract/data conflict and should go to DLQ.

## 8. RetryTopic And Attempt Count

Use `@RetryableTopic` for non-blocking retry when listener retry window is longer than a few short attempts.

Recommended direction:

```java
@RetryableTopic(
        attempts = "${app.kafka.listener.retry.attempts}",
        include = { KafkaListenerRetriableException.class },
        exclude = { DlqException.class },
        autoCreateTopics = "false"
)
```

Configuration must satisfy:

```text
retryTopicAttempts >= replyFailureAfterAttempts
```

Example:

```text
replyFailureAfterAttempts = 5
retryTopicAttempts = 7
```

`retryTopicAttempts` is the only final retry ceiling. `replyFailureAfterAttempts`
is the business/listener threshold for creating a durable failed reply. Keeping a
separate listener-level DLT attempt threshold would duplicate Spring's retry-topic
ceiling and can drift out of sync.

Do not put retry count in `EventEnvelopePb`.

Reason:

- retry attempt is consumer delivery metadata
- attempt count is consumer-group specific
- retry topics already carry retry metadata in Kafka headers
- mutating protobuf bytes across retries can break same-intent comparison and confuse logs/idempotency

Use `ListenerAttemptResolver` to read Kafka headers and hide Spring Kafka details.

## 9. Metrics And Observability

Useful metrics for this refactor:

- listener action outcome
  - `ack_only`
  - `outbox_reply`
  - `retry`
  - `dlq`
- failure type
  - `non_retryable`
  - `unexpected`
  - `retry_exhausted`
- outbox insert decision
  - `inserted`
  - `existing_same_intent`
  - `existing_missing_ambiguous`
  - `existing_conflict`
- retry stage
  - `before_reply_threshold`
  - `after_reply_threshold`
  - `retry_topic_exhausted`
- ack recovery
  - `ack_failed`
  - `ack_failed_retry`
- immediate publish result
  - `success`
  - `failure`
- DLT/DLQ received count

Logs should include:

- source topic/partition/offset
- event id
- command id
- event type
- attempt count
- listener outcome
- error namespace/code/category/message

Avoid logging full payload bytes in normal logs.

## 10. Important Constraints

- Do not move ledger-specific reply generation into common-util.
- Do not let `DefaultListener` understand ledger protobuf payloads.
- Do not ack before durable reply outbox insert for reply-required commands.
- Do not convert ack failure into a business failure reply or immediate DLQ. It is Kafka offset recovery after durable processing.
- Do not re-run business/outbox logic on an `ack_failed` retry record.
- Do not retry arbitrary `Exception`; retry only explicit `KafkaListenerRetriableException`.
- Do not use `Outbox.attempt_count` as source-message retry attempt count.
- Do not add retry metadata to `EventEnvelopePb`.
- Keep immediate publish best-effort after ack; outbox retry owns recovery.
- Keep wallet-service unchanged in this phase.

## 11. Implementation Order

1. Move evaluation docs into `ai/codex/evaluate/kafka-listener-refactor`.
2. Add common listener action model.
3. Add `ListenerFailure`.
4. Add `MessageHandler`.
5. Add default handler adapters.
6. Add `FailureReplyFactory`.
7. Add `ListenerAttemptResolver`.
8. Add listener exceptions.
9. Add `OutboxInsertDecision` and idempotent insert verification.
10. Add common `DefaultListener` orchestration.
11. Refactor ledger listener to delegate to the common listener.
12. Add focused tests for:
    - envelope parse -> DLQ
    - payload parse -> failed reply
    - success -> reply outbox then ack
    - non-retryable failure -> failed reply then ack
    - retryable before threshold -> retry
    - retryable after threshold -> retry-exhausted reply
    - DB unavailable during reply insert -> retry
    - duplicate same-intent outbox -> ack/idempotent success
    - duplicate conflicting outbox -> DLQ
    - ack failure after durable outbox -> retryable `ACK_FAILED` with ack-failed header
    - retry with ack-failed header -> ack only, no business/outbox call

## Final Decision

Implement one general common listener, but keep service-specific success/failure conversion behind `MessageHandler`.

For phase one, only ledger-service should use the outbox-reply adapter. Wallet-service should remain untouched until ack-only failure behavior is explicitly designed and tested.
