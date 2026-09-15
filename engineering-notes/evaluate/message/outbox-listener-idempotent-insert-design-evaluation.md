# Outbox Listener Idempotent Insert Design Evaluation

## Verdict

The design direction is sound: duplicate outbox insertion must not be treated as automatically retriable, and Kafka listener retry should be explicit rather than "retry everything except configured exclusions".

The design is not complete yet. The largest missing piece is deterministic outbox identity. Current outbox `event_id` generation includes a sequence suffix, and current SQL only makes `event_id` unique while `command_id` is only indexed. With that shape, a duplicate command can create a new outbox row instead of returning `inserted = 0`, so the proposed verification logic will not reliably trigger.

## Outbox Insert Logic

Your proposed decision table is correct:

| Case | Decision | Kafka ack |
| --- | --- | --- |
| `inserted = 1` | success, try immediate publish | ack |
| `inserted = 0`, existing row is same intent | idempotent success | ack |
| `inserted = 0`, existing row missing | ambiguous DB state, retriable | do not ack |
| `inserted = 0`, existing row conflicts | contract/data conflict, non-retriable | DLQ |

This is the right model for at-least-once Kafka delivery. A duplicate delivery should be idempotent success if the durable outbox intent already exists.

Required correction: outbox identity must be deterministic.

Current state:

- Ledger `IdGenerator.generateEventId(commandId)` returns `commandId + "-" + sequence`.
- Wallet has the same sequence-based event id pattern.
- SQL has `unique key(event_id)` but only `key(command_id)`.
- `OutboxRepository.selectByCommandId` uses `selectOne`, but the schema does not guarantee one row per `command_id`.

This means a duplicate command can produce a different `event_id`, so `insertIgnore` may insert another row instead of returning `0`.

Recommended fix before implementing this logic:

1. Define the outbox idempotency key.
   Prefer deterministic `event_id = hash/source + command_id + event_type`, or add a unique key such as `(event_type, command_id)`.
2. Add repository lookup by the same unique key used by the insert.
3. Do not rely on non-unique `command_id` with `selectOne`.
4. Add tests for duplicate command delivery.

## Existing Intent Verification

The "same intent" comparison should compare stable intent fields only:

- `event_type`
- `event_id` or the deterministic unique key
- `command_id`
- `destination`
- `partition_key`
- `payload` bytes, or a deterministic payload hash

Do not compare lifecycle fields:

- `outbox_status`
- `attempt_count`
- `last_attempt_at`
- `next_attempt_at`
- `last_error`
- `finalized_at`

Those lifecycle fields can legitimately differ after the first insert.

Recommended common-util API:

```java
OutboxInsertDecision insertWithClaimAndVerify(Outbox intended, LocalDateTime nextAttemptAt)
```

Possible decision values:

```java
INSERTED
EXISTING_SAME_INTENT
EXISTING_MISSING_AMBIGUOUS
EXISTING_CONFLICT
```

This keeps duplicate handling out of each service listener.

## Exception Model

The proposed exception behavior is directionally correct, but should be made more explicit.

Recommended common exceptions:

- `KafkaListenerRetriableException`
  - for listener-level retry
  - should preserve the original cause
  - can wrap a `CustomizedException` whose error code implements `RetryableErrorCode`
- `KafkaListenerNonRetriableException`
  - for DLQ
  - should preserve the original cause
- `DataConflictException`
  - reasonable for outbox same-key/different-intent conflict
  - should be non-retriable
- `ProtoParseException`
  - better name than wrapping `InvalidProtocolBufferException` directly
  - non-retriable, because malformed bytes are deterministic poison messages

The existing `RetriableException` is usable, but it currently lacks constructors with `cause`. If listener retry wraps lower-level exceptions, add cause-preserving constructors.

## DefaultErrorHandler Policy

Your proposed direction is correct: retry only explicitly retriable listener exceptions.

Current common-util behavior is the inverse:

```java
errorHandler.addNotRetryableExceptions(customKafkaConfig.getNotRetriableExceptions());
```

That means unknown exceptions are retried by default. This is risky because bad proto data, invalid enum values, contract violations, and data conflicts can waste retry capacity before going to DLQ.

Recommended policy:

- retry only `KafkaListenerRetriableException` / `RetriableException`
- send all other exceptions to DLQ immediately
- do not expose `notRetriableExceptions` as service config for core behavior
- make retry classification centralized in common-util

The handler can still log the root cause and error code for observability.

## DLQ And Reply Generation Policy

The refined DLQ policy is sound and better matches request/reply semantics:

> DLQ should contain only messages for which the listener cannot create a durable reply decision.

For commands that require a reply, the source message should be acknowledged only after one of these outcomes is true:

- a success reply outbox row exists
- a failure reply outbox row exists
- an existing same-intent reply outbox row is verified

This changes DLQ from a generic "business failed" bucket into a poison-message or reply-impossible bucket. That is the right direction because the upstream service should receive an explicit reply whenever the request is parseable and the system can make the reply durable.

Recommended listener policy:

| Case | Reply outbox | Kafka ack | DLQ |
| --- | --- | --- | --- |
| Envelope parse failure | impossible, no stable request id | no | yes |
| Envelope ok, payload parse failure | failure reply, e.g. `proto_parse_error` | yes after durable insert | only if failed reply cannot be inserted after retry limit |
| Business success | success reply | yes after durable insert | no |
| Business/non-retryable failure | failure reply with business error code | yes after durable insert | only if failed reply cannot be inserted after retry limit |
| Retryable failure before reply threshold | no reply yet | no | no |
| Retryable failure after reply threshold | failure reply, e.g. `kafka:retry_exhausted` | yes after durable insert | only if failed reply cannot be inserted before `@RetryableTopic.attempts` is exhausted |
| Reply outbox insert DB failure | retry source message | no | yes only after retry-topic exhaustion |
| Ack failure after durable reply exists | existing durable reply | no; retry offset recovery | only after retry/DLT exhaustion |
| Retry record has ack-failed marker | existing durable reply | ack directly without business/outbox replay | only after retry/DLT exhaustion |

Use one framework retry ceiling and one listener reply threshold:

- `reply_failure_after_attempts`, for example `10`
  - after this many source-processing attempts, a retryable failure is converted into a durable failure reply such as `reply:{request_event_id}:error:kafka:retry_exhausted`
- `@RetryableTopic.attempts`, for example `15`
  - the final retry/DLT ceiling owned by Spring Kafka; if failed-reply persistence keeps throwing retryable exceptions until this ceiling, the record is routed to DLT

These values serve different purposes. The reply threshold prevents an upstream request from waiting forever without a reply. The retry-topic attempts value prevents infinite retry and owns the final DLT routing. A separate listener-level DLT attempt threshold is not needed because it duplicates Spring's retry-topic ceiling and can drift out of sync.

Important implementation details:

- Listener retry count and outbox publish `attempt_count` are different concepts. `Outbox.attempt_count` tracks publishing an already-created outbox row; it should not be reused as the source-message processing attempt count.
- If the source processing attempt count must survive process restart or rebalance, do not rely only on in-memory blocking retry state. Use Kafka retry-topic headers as the preferred attempt source. If headers are not enough for the required operational guarantee, use a durable listener-attempt record keyed by consumer group and source event id.
- Failure reply event ids must be deterministic. Use stable codes such as `reply:{request_event_id}:error:kafka:retry_exhausted`; put volatile detail in the payload/log, not the event id.
- After the reply outbox is durable, ack the source message before immediate publish. If immediate publish fails, the outbox retry worker owns delivery recovery.
- If ack fails after the reply outbox is durable, treat it as Kafka offset recovery. Mark the retry record with `zx-ack-failed`, throw retryable `ACK_FAILED`, and on the retry path only acknowledge the record instead of re-running business/outbox logic.
- Wallet must treat failure replies as at-least-once signals, not unconditional final state transitions, unless wallet state guards make compensation and normal completion mutually exclusive.

This policy supports the product-level expectation that every valid request receives at least one reply unless Kafka/DB is unavailable for too long or the message is too corrupted to identify the request.

### Attempt Count Source

Do not add retry attempt count to `EventEnvelopePb`.

`EventEnvelopePb` should remain immutable business/event data. Listener retry count is consumer delivery metadata, not event payload. Putting `attempt_count` in the protobuf envelope would create avoidable coupling and correctness risks:

- attempt count is consumer-group specific, while the event payload may be consumed by multiple groups
- retry topics naturally carry retry metadata in Kafka headers, not by mutating protobuf bytes
- changing envelope bytes across retries can confuse same-intent comparison, payload hashing, idempotency checks, and logs
- business schema would become coupled to one listener retry implementation

Recommended approach:

```text
EventEnvelopePb
  -> immutable event/request identity and payload

Kafka retry headers
  -> retry attempt and retry-topic metadata

ListenerAttemptResolver
  -> common-util helper that reads Kafka headers and returns a normalized attempt count
```

Listener code should depend on the helper, not raw Spring Kafka header names:

```java
int attempt = listenerAttemptResolver.resolve(record.headers());

if (retryable && attempt < replyFailureAfterAttempts) {
    throw new KafkaListenerRetriableException(...);
}

if (retryable && attempt >= replyFailureAfterAttempts) {
    insertRetryExhaustedReplyOutbox(envelope);
    ack.acknowledge();
}
```

## RetryableTopic And Listener Exception Classification

`RetryableTopic` with a `DltHandler` is a better fit than the current blocking `DefaultErrorHandler` if the listener retry window grows beyond a few short attempts. It can keep the main consumer moving while retryable messages wait in delayed retry topics.

Recommended retry-topic policy:

```java
@RetryableTopic(
        attempts = "7",
        include = { KafkaListenerRetriableException.class },
        exclude = { DlqException.class },
        autoCreateTopics = "false"
)
```

The important rule is to retry only explicit retriable listener exceptions. Do not configure "retry every `Exception` except `DlqException`" unless the listener boundary catches and classifies every expected case first. Otherwise code bugs, invalid enums, data conflicts, and non-retryable DB errors can waste retry capacity.

Recommended exception classification:

```text
cannot identify request
  -> throw DlqException

CustomizedException
  -> classify by exception type / error code / retryable flag
  -> retryable: throw KafkaListenerRetriableException
  -> non-retryable and reply required: generate failed reply outbox, then ack
  -> non-retryable and no reply contract: DLQ or ack according to listener type

unexpected exception, not CustomizedException
  -> generate failed reply outbox with internal_error, then ack
```

This keeps listener behavior simple: parseable server bugs should produce an explicit `internal_error` reply instead of making the upstream wait forever. For example, a `NullPointerException` after the envelope is parsed should be converted into a failed reply outbox, not retried by default.

One invariant should be documented and tested:

> All retryable or ambiguous durable-state failures must be translated before reaching the listener boundary.

This means DB unavailable, DB timeout, lock conflict, and ambiguous transaction failures should be converted by the DB/infrastructure layer into `CustomizedException` / `DbException` with retryability metadata. If such an exception escapes as a plain `RuntimeException`, that is a bug in the lower-level exception translation. The listener should not contain complex "maybe ambiguous DB outcome" logic; that would duplicate the DB classifier and make listener behavior fragile.

For retry attempts, the listener should use the retry-topic attempt count:

```text
attempt < reply_failure_after_attempts
  -> retryable failure throws KafkaListenerRetriableException

attempt >= reply_failure_after_attempts
  -> try to insert deterministic retry_exhausted failure reply
  -> if insert succeeds, ack/return success
  -> if insert fails with retryable DB error, throw KafkaListenerRetriableException
  -> if insert fails with non-retryable DB error, throw DlqException

ack fails after any durable success/failure reply exists
  -> add zx-ack-failed header
  -> throw KafkaListenerRetriableException(ACK_FAILED)
  -> retry record skips business/outbox logic and only attempts ack

attempt >= RetryableTopic.attempts and still no durable reply
  -> DltHandler receives the record
```

The `DltHandler` should accept all dead messages for logging, metrics, alerting, and optional final idempotent failure-reply insertion. It should not be the normal path for business/non-retryable failures; those should create failure reply outboxes inside the listener before DLT.

Ordering caveat: retry topics are non-blocking, so a later record on the main topic may be processed before an earlier record finishes retrying. This is acceptable only when wallet/ledger idempotency and state guards tolerate that ordering. If strict per-transaction message order is required at the Kafka level, blocking retry or a per-transaction sequencing guard is still needed.

## Common DefaultListener Shape

The proposed common listener is useful, but `Function<EventEnvelopePb, Result<Outbox>>` is too narrow for all services.

Ledger command listener:

- consumes wallet command
- creates ledger records
- creates reply outbox
- acks only after reply outbox is durable

Wallet reply listener:

- consumes ledger reply
- updates wallet state
- no reply outbox is needed

So common-util should probably provide either:

1. A specialized `OutboxReplyCommandListener` for handlers that must create a reply outbox.
2. A more general listener template returning a richer action:

```java
sealed interface ListenerAction {
    AckOnly ackOnly();
    OutboxReply outboxReply(Outbox outbox);
    Retry retry(ErrorCode errorCode, String detail);
    Dlq dlq(ErrorCode errorCode, String detail);
}
```

For the current phase, option 1 is simpler and safer if the target is ledger's command listener only.

Also, `replyPublisher` should not be `Consumer<Outbox>`. Use `IPublisher` or `Function<Outbox, Result<Void>>` so publish failures can be logged/metriced consistently. Since publish happens after ack, a publish failure must not trigger Kafka listener retry; the outbox retry worker owns recovery after this point.

## Refactored Ledger Listener

The proposed ledger-service cleanup is good:

- parse `EventEnvelopePb`
- route by `event_type`
- parse concrete payload
- call business handler
- create failure reply outbox for business/non-retryable result
- return retry result or throw retriable listener exception for explicit retryable infra failure

Recommended corrections:

- Default event-type branch should throw `InvalidEnumException`, not `AbnormalProtoDataException`.
- Payload parse failure should be wrapped in `ProtoParseException`, not rethrown as `InvalidProtocolBufferException`.
- Do not use protobuf error fields for listener control flow after payload parsing.
- Do not publish `internal` reply outbox for transient infra failure unless the product contract explicitly treats it as a terminal result.

## Business Retry Question

For retryable business-flow errors such as DB unavailable, use bounded Kafka listener retry if no durable local outcome or reply outbox has been created yet.

Recommended ownership:

1. Component-level retry:
   - very short retry around low-level transient operations
   - useful for deadlock, lock wait timeout, brief pool hiccup
2. Kafka listener retry:
   - bounded retry for the whole message
   - only for explicit retriable failures before ack
   - do not ack while no durable outcome exists
3. Upstream stuck-transaction recovery:
   - long-horizon recovery
   - retries or reconciles transactions that did not receive a reply

These layers are not redundant if each layer has a different time horizon. The risky design is unlimited or broad listener retry.

Do not convert DB unavailable into a normal outbox reply with `internal` by default. That can make upstream treat a transient infrastructure failure as a terminal business result. Prefer:

- short component retry
- bounded listener retry before any durable reply exists
- create a deterministic retry-exhausted failure reply after the reply threshold if the message is parseable and a reply is required
- DLQ and alert only when the failure reply still cannot be made durable before `@RetryableTopic.attempts` is exhausted
- upstream recovery can re-drive the command using the same idempotency key/command id

## Missing Metrics And Observability

When implementing this, add metrics for the new decision points:

- outbox insert decision: inserted, existing_same_intent, missing_ambiguous, conflict
- listener outcome: success_reply, failure_reply, retry, dlq, ack_failed, ack_failed_retry
- listener error type: proto_parse, invalid_enum, outbox_ambiguous, outbox_conflict, db_unavailable, db_timeout
- listener retry stage: before_reply_threshold, after_reply_threshold, retry_topic_exhausted
- ack recovery marker count: marked, retried
- immediate publish result after ack: success, failure
- DLQ publish count

These metrics will be useful when validating whether duplicate delivery is idempotent or producing conflicts.

## Final Recommendation

Implement in this order:

1. Make outbox identity deterministic or add a unique key matching the intended idempotency key.
2. Add `OutboxRepository.insertWithClaimAndVerify`.
3. Add explicit listener retry/DLQ exception classes or update `RetriableException` with cause support.
4. Decide whether to replace blocking `DefaultErrorHandler` with `RetryableTopic` for command listeners that need non-blocking retry.
5. Configure listener retry to include only explicit retriable listener exceptions.
6. Refactor ledger listener to use the common outbox-reply listener template.
7. Add ack-failure offset recovery: retryable `ACK_FAILED`, marker header, and ack-only retry path.
8. Refactor wallet listener later with a separate ack-only template if needed.

The concept is solid, but deterministic outbox identity is a prerequisite. Without that, `inserted = 0` will not reliably represent duplicate delivery.
