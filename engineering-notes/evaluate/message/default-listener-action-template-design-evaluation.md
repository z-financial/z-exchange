# Default Listener Action Template Design Evaluation

## Verdict

The design direction is reasonable and sound: keep normal handler output limited to `AckOnly` and `OutboxReply`, and move retry/DLQ decisions into exception handling at the listener boundary.

The design is not complete as written because common-util cannot generate a domain-specific failed reply outbox from a generic exception by itself. To keep common-util infra-only, the common listener needs a service-provided failure reply factory for outbox-reply listeners.

The improved shape is one general `DefaultListener` plus default `MessageHandler` adapters. For the current phase, apply the outbox-reply adapter only to ledger-service. Wallet-service can stay on the old path until the ack-only policy is intentionally configured.

## What Is Sound

Reducing `ListenerAction` to two normal actions is cleaner:

- `AckOnly`
  - handler completed durable local work and no reply is required
- `OutboxReply`
  - handler produced a reply outbox that must be inserted before ack

`Retry` and `Dlq` are better modeled as exceptions, not normal handler return values. They are control-flow outcomes at the listener boundary, not successful handler actions.

The high-level flow is also correct:

```text
parse envelope
  -> run message handler
  -> if OutboxReply, insert outbox with idempotent verification
  -> ack
  -> if ack fails, mark ack-failed header and throw retryable listener error
  -> if retry has ack-failed header, skip business/outbox logic and ack only
  -> after ack, try immediate publish
  -> suppress/log immediate publish failure because outbox retry owns recovery
```

This preserves the key invariant: source Kafka message is acknowledged only after the durable local outcome or reply outbox exists.

Ack failure should be treated as Kafka offset recovery, not as a domain failure. By the time ack is attempted, the durable local outcome already exists. A retryable `ACK_FAILED` signal plus a compact marker header prevents the retry path from re-running business logic just to recover the offset.

## General Listener With Default Handler Adapters

One general `DefaultListener` is a good target if the handler owns service-specific failure conversion.

Recommended handler contract:

```java
interface MessageHandler {
    ListenerAction handle(EventEnvelopePb envelope);

    ListenerAction handleFailure(EventEnvelopePb envelope, ListenerFailure failure);
}
```

`DefaultListener` owns the common mechanics:

- parse `EventEnvelopePb`
- call `messageHandler.handle(...)`
- insert outbox for `OutboxReply`
- ack after durable success
- convert ack exceptions into retryable offset-recovery errors
- skip handler/outbox execution on ack-failure retry records
- try immediate publish after ack
- catch listener exceptions and classify retry/DLQ behavior
- call `messageHandler.handleFailure(...)` when a parseable failure should become a normal failed reply or ack-only completion

Business code should normally create one of two default adapters.

Outbox-reply adapter:

```java
class OutboxReplyDefaultMessageHandler implements MessageHandler {
    private final Function<EventEnvelopePb, Outbox> handler;
    private final FailureReplyFactory failureReplyFactory;

    @Override
    public ListenerAction handle(EventEnvelopePb envelope) {
        return ListenerAction.reply(handler.apply(envelope));
    }

    @Override
    public ListenerAction handleFailure(EventEnvelopePb envelope, ListenerFailure failure) {
        return ListenerAction.reply(failureReplyFactory.createFailureReply(envelope, failure));
    }
}
```

Ack-only adapter:

```java
class AckOnlyDefaultMessageHandler implements MessageHandler {
    private final Consumer<EventEnvelopePb> consumer;
    private final BiFunction<EventEnvelopePb, ListenerFailure, ListenerAction> failurePolicy;

    @Override
    public ListenerAction handle(EventEnvelopePb envelope) {
        consumer.accept(envelope);
        return ListenerAction.ack();
    }

    @Override
    public ListenerAction handleFailure(EventEnvelopePb envelope, ListenerFailure failure) {
        return failurePolicy.apply(envelope, failure);
    }
}
```

The ack-only adapter should not hard-code "ack every failure" in common-util. That can hide bugs. Instead, the service should pass the policy explicitly. For phase one, wallet-service is not changing, so this remains a future option.

This design keeps common-util generic while avoiding repeated listener skeleton code in each service.

## Failure Reply Factory Is Required

The common listener should not know how to turn an arbitrary error into a ledger reply payload. That belongs to ledger-service.

Add a service-provided factory for outbox-reply listeners:

```java
interface FailureReplyFactory {
    Outbox createFailureReply(EventEnvelopePb envelope, ListenerFailure failure);
}
```

For ledger, the implementation can build:

```text
PostTransactionResult.failure(...)
  -> PostTransactionResultConverter.toProto(...)
  -> OutboxHelper.fromPostTransactionResult(...)
```

Without this factory, common-util would either need ledger-specific protobuf knowledge, which violates the service boundary, or it would be unable to insert failed reply outboxes in catch blocks.

## AckOnly Listener Caveat

The proposed catch blocks say `CustomizedException` and unexpected `Exception` should insert failed outbox records. That is only valid for an outbox-reply listener.

For `AckOnlyMessageHandler`, there may be no reply contract and no domain-specific failed reply payload. Its exception policy must be different:

- retryable customized exception: retry
- non-retryable customized exception: DLQ or ack, depending on listener contract
- unexpected exception: DLQ unless the listener has its own durable failure state

With the revised design, the distinction belongs in `MessageHandler.handleFailure(...)`:

- `OutboxReplyDefaultMessageHandler` uses `FailureReplyFactory` and returns `OutboxReply`
- `AckOnlyDefaultMessageHandler` uses a service-provided failure policy and may return `AckOnly` or throw `DlqException`

This keeps one listener template while preventing common-util from assuming that every listener has a reply contract.

## Exception Classification

Recommended listener boundary:

```text
cannot parse EventEnvelopePb
  -> throw DlqException

KafkaListenerRetriableException
  -> use retry-topic attempt count
  -> if below reply threshold, rethrow
  -> if at/above reply threshold, insert retry_exhausted failure reply

CustomizedException
  -> non-retryable by default unless explicitly converted earlier
  -> call messageHandler.handleFailure(...) with original error code

unexpected Exception
  -> call messageHandler.handleFailure(...) with internal_error
```

This matches the latest design discussion: listener logic should not perform complex DB ambiguity analysis. DB/infrastructure layers must translate retryable or ambiguous durable-state failures into `DbException` or another `CustomizedException` with retryability before the listener boundary.

Important invariant:

```text
All failures that require listener retry must be converted to KafkaListenerRetriableException before or at the listener boundary.
All unexpected non-CustomizedException failures are treated as server bugs and converted to an internal_error reply when request identity is known.
```

## Retry Attempt Handling

Use Kafka retry-topic headers, not `EventEnvelopePb`, to determine attempt count.

The listener should depend on a common helper:

```java
int attempt = listenerAttemptResolver.resolve(record.headers());
```

Then apply:

```text
attempt < reply_failure_after_attempts
  -> rethrow KafkaListenerRetriableException

attempt >= reply_failure_after_attempts
  -> insert retry_exhausted failure reply
  -> ack if insert succeeds
  -> retry again if insert fails with retryable DB error
  -> throw DlqException if insert fails with non-retryable failure

retry-topic attempts exhausted and failure reply still cannot be inserted
  -> let @RetryableTopic route the message to DLT
```

This keeps retry metadata out of `EventEnvelopePb`, which should remain immutable business event data.

This threshold check is useful after server restart because retry-topic attempt metadata is carried in Kafka record headers. Do not rely on in-memory listener counters. Configure `@RetryableTopic.attempts` to be at least `reply_failure_after_attempts`; otherwise Spring may route to DLT before the listener gets a chance to create the retry-exhausted failed reply.

## Outbox Insert Requirement

`insertOutboxWithIdempotentVerification` is a prerequisite.

Current `OutboxRepository.insertWithClaim(...)` returns only the `insertIgnore` count. That is not enough for safe duplicate handling. The common listener needs a method that can distinguish:

- inserted
- existing same intent
- existing missing / ambiguous
- existing conflict

Suggested behavior:

```text
inserted
  -> success

existing same intent
  -> idempotent success

existing missing / ambiguous
  -> KafkaListenerRetriableException

existing conflict
  -> DlqException
```

This is especially important after deterministic reply event ids. A duplicate message should not be treated as a retryable failure if the intended reply outbox already exists.

`insertOutboxWithIdempotentVerification` should convert DB exceptions into listener-level exceptions internally because it may be called from the normal path and from catch/failure paths:

```text
retryable DbException
  -> KafkaListenerRetriableException

non-retryable DbException
  -> DlqException
```

Do not let raw `DbException` leak from this method. A clearer method name would be `insertReplyOutboxOrThrowListenerException(...)`.

## Control-Flow Concern In The Pseudocode

The pseudocode has a subtle issue:

```java
ListenerAction action;
try {
    ...
} catch (...) {
    ...
}
switch (action) {
    ...
}
```

`action` may be unassigned or may not represent a failure reply created inside a catch block. A safer implementation should track the reply outbox to publish directly:

```java
Outbox outboxToPublish = null;

if (hasAckFailedHeader(headers)) {
    acknowledgeOrThrowRetriable(ack, headers);
    return;
}

try {
    ...
    if (action instanceof OutboxReply reply) {
        insertOutboxWithIdempotentVerification(reply.outbox());
        outboxToPublish = reply.outbox();
    }
} catch (CustomizedException e) {
    ListenerAction failureAction = messageHandler.handleFailure(envelope, ListenerFailure.from(e));
    if (failureAction instanceof OutboxReply reply) {
        insertReplyOutboxOrThrowListenerException(reply.outbox());
        outboxToPublish = reply.outbox();
    }
}

acknowledgeOrThrowRetriable(ack, headers);

if (outboxToPublish != null) {
    tryImmediatePublish(outboxToPublish);
}
```

This avoids publishing from stale or missing `action` state and keeps ack exceptions out of business failure conversion.

Ack helper:

```java
void acknowledgeOrThrowRetriable(Acknowledgment ack, Headers headers) {
    try {
        ack.acknowledge();
    } catch (Exception e) {
        markAckFailed(headers);
        throw new KafkaListenerRetriableException(ACK_FAILED, "ack failed after durable processing", e);
    }
}
```

The marker header is valid only after durable processing succeeds. It should never be added before reply outbox persistence or ack-only durable work is complete.

## Ledger Handler Evaluation

The proposed ledger handler policy is acceptable for this phase:

- Parse `envelope.payload` into `PostTransactionRequestPb`.
- Convert payload parse failure to a non-retryable customized exception so the common listener can create a failed reply.
- Convert retryable `DbException` into `KafkaListenerRetriableException`.
- Treat most `PostTransactionResult.Kind.ERROR` results as non-retryable because retryability is currently hidden after boundary mapping.
- Keep wallet-service unchanged for now.

The current code confirms this limitation:

- `PostTransactionResult` stores `LedgerServiceErrorCode`, not retryability metadata.
- `LedgerBoundaryErrorMapper` maps DB/Redis/cache/common errors to `LedgerServiceErrorCode.INTERNAL_ERROR`, which loses the original retryability signal.
- `DbErrorCode` does carry retryability, but that signal is lost once it is downgraded into ledger boundary result codes.

So the conservative rule is reasonable for now:

```text
only convert explicitly known retryable failures to KafkaListenerRetriableException;
otherwise create failed reply outbox.
```

Future improvement:

- add retryability metadata to `Result` / `PostTransactionResult`, or
- preserve original common error code alongside public ledger error code, or
- define a ledger-level retryability mapper for listener use only.

## Naming Recommendation

Avoid naming a parseable non-retryable business failure as `KafkaListenerNonRetriableException` if it should create a failed reply, not DLQ.

Clearer names:

- `DlqException`
  - cannot identify request or cannot create durable reply
- `KafkaListenerRetriableException`
  - retry source message
- normal `CustomizedException`
  - parseable failure that can be converted into failed reply

This keeps "non-retryable" from being confused with "dead-letter immediately."

## Recommended Implementation Scope

For ledger-service first:

1. Add common listener action model with only `AckOnly` and `OutboxReply`.
2. Add one general `DefaultListener` that delegates normal and failure actions to `MessageHandler`.
3. Add `OutboxReplyDefaultMessageHandler` and `AckOnlyDefaultMessageHandler`.
4. Add `FailureReplyFactory`.
5. Add `ListenerAttemptResolver` based on Kafka headers.
6. Add listener exceptions: `KafkaListenerRetriableException` and `DlqException`.
7. Add retryable `ACK_FAILED` plus an ack-failed marker header for offset recovery after durable processing.
8. Add `insertReplyOutboxOrThrowListenerException`.
9. Configure `@RetryableTopic.attempts` as the final retry/DLT ceiling; do not keep a second listener-level DLQ attempt threshold.
10. Refactor ledger listener to provide:
   - ledger message handler
   - ledger failure reply factory
   - ledger immediate publisher

Do not refactor wallet-service in the same step. Ack-only semantics deserve a separate pass.

## Final Assessment

The design is solid if `DefaultListener` stays generic and failure behavior is delegated to `MessageHandler.handleFailure(...)`. The main missing pieces are the failure reply factory, safe control-flow state after catch blocks, retry-topic DLT routing, idempotent outbox insert verification, and explicit ack-failure offset recovery.

I would implement the general listener interface now, but only wire the outbox-reply adapter into ledger-service in this phase. Keep wallet-service on its current path until the ack-only failure policy is explicitly designed and configured.
