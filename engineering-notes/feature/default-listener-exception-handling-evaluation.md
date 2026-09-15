# DefaultListener Exception Handling Evaluation

## Purpose

This document evaluates the current exception-handling design in `ledger-service` `DefaultListener` before implementing Kafka listener metrics.

The current listener behavior should be corrected first. Otherwise listener metrics would accurately report flawed retry, DLQ, ack, and outbox behavior.

## Current Flow

Current high-level flow:

```text
Kafka record
  -> parse EventEnvelope
  -> handle message
  -> post ledger transaction
  -> convert result to reply proto
  -> insert reply outbox
  -> ack Kafka record
  -> try immediate publish
  -> finalize outbox if publish succeeds
```

This shape is reasonable, but several exception and retry decisions are currently incorrect.

## Confirmed Issues

### 1. Duplicate Outbox Insert Should Not Be Retriable

Current behavior:

```text
insertWithClaim(replyOutbox) != 1
  -> throw RetriableException
```

This is not sound.

`insertWithClaim` uses insert-ignore semantics. A duplicate outbox row usually means the listener previously created the reply outbox, then crashed or failed before the Kafka input record was acknowledged. Retrying the input record will likely keep producing the same duplicate outbox insert result.

Expected behavior:

```text
insert affected_rows = 0
  -> query existing outbox by stable unique key
  -> verify existing row matches the intended reply outbox
  -> if verified, treat as idempotent success
  -> ack input record
  -> optionally try immediate publish if existing outbox is still pending
```

Reason:

- duplicate outbox means the durable reply intent already exists;
- retrying the input record does not fix anything;
- the outbox retry worker can recover pending reply delivery.

The listener should not blindly treat every `affected_rows=0` as success. It should verify the existing row first.

Recommended verification fields:

```text
event_id
command_id
event_type
destination
partition_key
payload bytes
```

Fields that should normally not participate in intent verification:

```text
id
outbox_status
attempt_count
last_attempt_at
next_attempt_at
last_error
finalized_at
created_at
updated_at
```

Recommended decision table:

```text
inserted = 1
  -> success
  -> ack
  -> try immediate publish

inserted = 0 and existing row is same intent
  -> verified idempotent success
  -> ack
  -> optionally publish existing pending outbox

inserted = 0 and existing row is missing
  -> ambiguous DB state
  -> retriable
  -> do not ack

inserted = 0 and existing row conflicts with intended reply
  -> contract/data conflict
  -> non-retriable
  -> DLQ or alert
```

### 2. TODO Error Logic Is Inverted

Current code retries non-internal protobuf errors and DLQs internal errors.

That is backwards.

More importantly, retryability should not be derived from protobuf reply codes after conversion. It should be derived from the processor result/error category before building the reply payload.

Expected principle:

```text
transient/internal infrastructure exception
  -> retriable, do not ack

business failure result
  -> create reply outbox, ack
```

Examples of business failures that should normally produce a reply instead of Kafka retry:

- invalid request;
- account not found;
- request hash conflict;
- request already processing, depending on wallet contract.

### 3. Business Failure Should Usually Publish A Reply

The listener currently throws for non-OK ledger replies.

That is risky in async wallet-ledger choreography. Wallet needs a terminal reply for many ledger-side business failures. Sending those messages to DLQ means wallet may never learn the ledger result.

Expected behavior:

```text
postLedgerProcessor returns PostTransactionResult.failure(...)
  -> convert failure to reply proto
  -> insert reply outbox
  -> ack input
```

Only exceptions that prevent safe processing or durable reply creation should escape to Kafka error handling.

### 4. Customized Exceptions Are Not Locally Classified

The listener only catches `InvalidProtocolBufferException`.

Other custom exceptions are delegated to Spring Kafka `DefaultErrorHandler`. Currently only `AbnormalProtoDataException` is configured as non-retriable.

This works mechanically, but it is not enough for the planned metrics:

```text
zexchange.kafka.listener.duration{listener_name,outcome}
zexchange.kafka.listener.records{listener_name,outcome,error_type,retriable}
```

The listener or listener wrapper needs a consistent classifier:

```text
deserialize_error, retriable=false
processing_error, retriable=true|false
```

Without that classifier, listener metrics will be inconsistent or forced to infer retryability indirectly.

### 5. DB And Kafka Failures Are Not Separately Tracked

Current behavior mostly logs these paths:

- outbox insert failure;
- outbox finalize failure;
- Kafka publish failure.

The code does not yet distinguish them structurally for metrics.

For the planned metrics and outbox diagnosis, these should be classified separately:

```text
outbox insert DB failure
outbox duplicate
Kafka publish failure
outbox finalize DB failure
```

This distinction matters because the recovery action differs:

- insert DB failure: retry input record;
- duplicate: ack input record;
- publish failure: ack input record, retry outbox later;
- finalize failure: ack input record, retry outbox later.

### 6. Exceptions After Ack Should Not Escape

The listener acknowledges the input record before immediate reply publish:

```text
ack
try publish reply
```

That is acceptable only if everything after ack is best-effort and recoverable through outbox retry.

Therefore, after ack:

- Kafka publish failure should be logged/metriced, not thrown;
- outbox finalize failure should be logged/metriced, not thrown;
- unexpected exceptions in immediate publish/finalize should be caught and swallowed after recording.

If an exception escapes after ack, the Kafka error handler may treat the already-acked record as failed, which makes retry/DLQ semantics confusing.

### 7. Finalized Timestamp Looks Wrong

Current finalize call passes `replyOutbox.getLastAttemptAt()` as `finalizedAt`.

That appears incorrect. Finalization time should normally be current time when the outbox row is marked `SENT`.

Expected:

```text
finalizedAt = now
```

### 8. Hot-Path Info Logging Can Distort Performance Tests

The listener currently logs on every message and logs full envelope/request content at info level.

For performance testing, this can materially distort:

- TPS;
- p95/p99 latency;
- CPU usage;
- allocation pressure;
- log IO.

Recommendation:

- reduce per-record logs to debug level;
- keep warning/error logs for abnormal paths;
- avoid logging full payloads on hot paths.

### 9. Reply Outbox Is Not In The Same Transaction As Ledger Commit

Current flow commits ledger state in the processor, then creates the reply outbox afterwards in the listener.

This means there is a crash window:

```text
ledger transaction committed
process crashes before reply outbox insert
```

Recovery depends on Kafka redelivery and idempotent ledger processing.

This can be acceptable if duplicate outbox handling and replay logic are correct. However, it is weaker than the usual outbox invariant where state change and outbox insert happen in the same DB transaction.

Recommended phase-one stance:

- do not redesign this immediately unless required;
- make duplicate outbox handling correct;
- make listener retry behavior correct;
- document this recovery assumption clearly.

## Recommended Target Semantics

Recommended listener outcome model:

```text
deserialize/envelope invalid
  -> non-retriable
  -> DLQ
  -> record listener error: deserialize_error, retriable=false

processor throws transient DB/internal exception
  -> retriable
  -> no ack
  -> record listener error: processing_error, retriable=true

processor returns business failure result
  -> create reply outbox
  -> ack
  -> record listener success from Kafka-processing perspective

reply outbox insert duplicate with verified same intent
  -> idempotent success
  -> ack
  -> optionally try existing pending outbox publish

reply outbox insert duplicate with missing/conflicting existing row
  -> retriable if missing/ambiguous
  -> non-retriable if conflicting

reply outbox insert DB exception
  -> retriable
  -> no ack
  -> record listener error: processing_error, retriable=true

after reply outbox inserted and input acked
  -> immediate publish/finalize are best-effort
  -> log and metric failures
  -> do not throw
```

## Retryability Classification Recommendation

Use explicit exception classes or a classifier instead of ad hoc protobuf-code checks.

Suggested categories:

```text
Non-retriable:
  - invalid envelope protobuf
  - invalid event type
  - malformed event payload
  - unsupported command/event contract

Retriable:
  - database connection/timeout/deadlock/transient DB error
  - unexpected internal exception before durable reply outbox exists
  - reply outbox insert DB failure

Idempotent success:
  - verified duplicate reply outbox insert

Best-effort after ack:
  - Kafka reply publish failure
  - outbox finalize failure
```

## Metric Implications

Kafka listener metrics should be added after the above semantics are fixed.

Recommended listener metrics remain:

```text
zexchange.kafka.listener.active{listener_name}
zexchange.kafka.listener.duration{listener_name,outcome}
zexchange.kafka.listener.records{listener_name,outcome,error_type,retriable}
```

Suggested mapping:

```text
successful processing, including business failure reply creation:
  outcome=success
  error_type=none
  retriable=none

invalid protobuf/envelope:
  outcome=error
  error_type=deserialize_error
  retriable=false

processor or DB exception before durable reply outbox:
  outcome=error
  error_type=processing_error
  retriable=true

non-retriable processing contract error:
  outcome=error
  error_type=processing_error
  retriable=false
```

Outbox and publisher metrics should separately capture:

```text
outbox.lifecycle{event_type,source,lifecycle}
outbox.failures{event_type,source,stage,error_type}
kafka.publish.duration{topic,source,outcome}
```

## Implementation Order

Recommended order before Kafka listener metric implementation:

1. Fix reply/business failure handling so business failures create reply outbox instead of throwing.
2. Treat duplicate reply outbox insert as idempotent success only after querying and verifying the existing row.
3. Add explicit exception classification for retriable vs non-retriable listener failures.
4. Ensure no exception escapes after input ack.
5. Correct finalize timestamp.
6. Reduce hot-path info logs.
7. Add Kafka listener metrics using the corrected outcome model.

## Conclusion

The current `DefaultListener` exception design is not sound enough for reliable retry behavior or meaningful listener metrics.

The biggest issue is that business result handling, retryability, outbox duplication, and post-ack failures are mixed together. These concerns should be separated first, then metrics can be added with clean and stable meanings.
