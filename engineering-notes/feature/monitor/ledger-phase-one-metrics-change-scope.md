# Ledger Phase-One Metrics Change Scope

## 1. Purpose

This document defines the agreed phase-one metrics for `ledger-service` after the follow-up review in `ai/monitor/evaluation/ledger-metrics-design-followup.md`.

The scope is application-side Micrometer instrumentation only. External dependencies such as MySQL exporter, Redis exporter, Kafka exporter, Kafka JMX exporter, host exporter, and dashboard provisioning are not included in this phase.

## 2. Implementation Scope

### 2.1 Configuration

Change points:

- define `spring.application.name=ledger-service`;
- keep Prometheus endpoint exposed;
- add histogram/SLO configuration only for selected latency metrics;
- keep common metric tags stable and low-cardinality.

Metrics affected:

- all application metrics through common `application=ledger-service` tag;
- timer histograms for API, ledger processing, Kafka listener, Kafka publish, outbox dispatch, Redis operation, and DB operation latency.

### 2.2 gRPC API Metrics

Change points:

- add a global gRPC server interceptor;
- record active calls when a request enters and decrement exactly once on close/cancel/error;
- record gRPC duration once per completed, cancelled, or failed call;
- use timer `_count` for request rate instead of a separate request counter.

Metrics:

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `zexchange.grpc.server.active` | Gauge | `service`, `method` | Current in-flight gRPC calls. Saturation signal when it rises while TPS stops scaling or latency rises. |
| `zexchange.grpc.server.duration` | Timer | `service`, `method`, `grpc_status` | Server-side gRPC call duration. Its `_count` is request count/TPS; buckets provide p50/p95/p99. |

Notes:

- `grpc_status` belongs on duration, not active calls.
- Protobuf business errors may still have `grpc_status=OK`; business outcome is measured separately.

### 2.3 Ledger Business Metrics

Change points:

- instrument business entry points for gRPC and Kafka ingress;
- normalize operation names;
- separate business outcome from transport outcome;
- record internal processing duration for both gRPC and Kafka paths;
- record new transaction creation only after a successful commit;
- record idempotent replay separately from new creation;
- record entries per transaction as transaction complexity, not as a separate total entry counter.

Metrics:

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `zexchange.ledger.requests` | Counter | `operation`, `ingress`, `outcome`, `error_code` | Business request result count. Used for business TPS and business error rate. |
| `zexchange.ledger.processing.duration` | Timer | `operation`, `ingress`, `outcome` | Time spent in ledger business processing, excluding pure transport framing. Useful for comparing gRPC and Kafka paths. |
| `zexchange.ledger.transactions.created` | Counter | none | Count of newly committed ledger transactions. Does not increment for idempotent replays. |
| `zexchange.ledger.transactions.idempotent` | Counter | `ingress`, `source` | Count of successful idempotent replays. `source=redis|db`. |
| `zexchange.ledger.entries.per_transaction` | Distribution summary | none | Number of entries in each newly committed ledger transaction. Measures transaction complexity. |

Allowed values:

```text
operation = post_transaction | get_ledger_txn_by_id | get_ledger_txn_by_ref
ingress = grpc | kafka
outcome = success | error
error_code = OK | ledger_not_found | account_not_found | invalid_request_parameter | ...
source = redis | db
```

Notes:

- Do not add `zexchange.ledger.entries.created` in phase one.
- Entry write pressure is covered by `entries.per_transaction` plus DB insert latency.

### 2.4 Idempotency Metrics

Change points:

- keep idempotency metrics lean;
- do not emit a detailed metric for every idempotency outcome;
- record idempotency errors that may not be visible clearly from endpoint-level business metrics;
- keep idempotent replay count in business metrics.

Metrics:

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `zexchange.idempotency.errors` | Counter | `service`, `scope`, `error_type` | Idempotency-specific failures or abnormal states. |

Allowed values:

```text
service = ledger-service
scope = post_ledger
error_type = hash_conflict | parse_error | redis_error | timeout | access | script_error | configuration_error | contract_violation | unknown
```

Notes:

- `scope` may be derived from the bounded `scope` argument passed to `IdempRedisClient`; it must be a code-defined constant, not request input.
- `service` may be emitted explicitly by the wrapper or supplied as a global Micrometer common tag.
- `hash_conflict` maps from `IdempErrorCode.HASH_CONFLICT`.
- `parse_error` maps from `RedisErrorCode.MALFORMED_KEY` or `RedisErrorCode.MALFORMED_VALUE`, including malformed idempotency key/value data.
- Redis/script/release exceptions should use the normalized error type converted from the underlying `RedisErrorCode`; do not introduce a separate release-specific idempotency error.
- `contract_violation` maps from `CacheErrorCode.CONTRACT_VIOLATION`.
- Do not tag by idempotency key, token, reference ID, request hash, or raw Redis value.
- Successful replay is tracked by `zexchange.ledger.transactions.idempotent`.

### 2.5 Cache Metrics

Change points:

- record cache read result using one low-cardinality metric;
- use explicit result names so value hit, tombstone hit, and negative hit are not confused;
- add a separate cache error metric for diagnosis;
- do not add a separate DB fallback metric in phase one.

Metrics:

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `zexchange.cache.ops` | Counter | `cache_type`, `result` | Cache read result count. Miss, tombstone, and error imply DB fallback in this service. |
| `zexchange.cache.errors` | Counter | `cache_type`, `error_type` | Cache-specific error count for Redis/decode/timeout failures. |

Allowed values:

```text
cache_type = ledger_ref | ledger_txn
result = value_hit | tombstone_hit | negative_hit | miss | error
error_type = redis_error | decode_error | timeout | unknown
```

Notes:

- `tombstone_hit` and `miss` should be interpreted as DB fallback pressure.
- `negative_hit` is a fast negative result and normally avoids DB access.

### 2.6 Redis Client-Side Metrics

Change points:

- record Redis latency at logical application operation level;
- do not emit one metric per raw Redis command;
- include cache/idempotency operation names, but keep them bounded.

Metrics:

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `zexchange.redis.operation.duration` | Timer | `operation`, `cache_type`, `outcome` | Client-observed Redis operation latency, including network/client-side wait and Lua execution from application perspective. |

Allowed values:

```text
operation = cache_get | cache_write | cache_delete | idemp_claim | idemp_get | idemp_done | idemp_release
cache_type = ledger_ref | ledger_txn | idempotency
outcome = success | error | timeout
```

Notes:

- This metric complements, but does not replace, future Redis exporter metrics.
- Avoid Redis key names as labels.

### 2.7 Kafka Listener Metrics

Change points:

- instrument the wallet-post listener;
- record active listener processing for saturation;
- record listener duration with low-cardinality outcome tags only;
- record listener record count with diagnostic error type and retryability tags;
- separate deserialize failures from processing failures;
- keep retryability as a dimension because processing errors may be retriable or non-retriable.

Metrics:

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `zexchange.kafka.listener.active` | Gauge | `listener_name` | Current records being processed by the listener. Saturation signal. |
| `zexchange.kafka.listener.duration` | Timer | `listener_name`, `outcome` | Listener processing duration with low-cardinality tags. Used for latency percentiles and processing-time health. |
| `zexchange.kafka.listener.records` | Counter | `listener_name`, `outcome`, `error_type`, `retriable` | Listener consumed-record count by result and normalized failure details. Used for listener TPS, error rate, and retriable/non-retriable breakdown. |

Allowed values:

```text
listener_name = wallet_post
outcome = success | error
error_type = none | processing_error | deserialize_error
retriable = true | false | none
```

Notes:

- Keep `duration` free of `error_type` and `retriable` so timer histogram cardinality stays low.
- Use `records` for listener processed-rate, success/error rate, and failure breakdown.
- Deserialize failures may be counter-only if the listener method is never invoked.
- Do not tag by Kafka key, command ID, transaction ID, or exception class name unless normalized.

### 2.8 Kafka Publisher Metrics

Change points:

- instrument ledger reply publishing;
- record client-side Kafka publish latency;
- tag by bounded topic and publish source;
- distinguish successful publish from failure/timeout.

Metrics:

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `zexchange.kafka.publish.duration` | Timer | `topic`, `source`, `outcome` | Application-observed Kafka publish latency. In current code this includes `KafkaTemplate.send(...).get(...)` wait time. |

Allowed values:

```text
topic = wallet_post_reply
source = immediate | retry
outcome = success | failure | timeout | invalid_event
```

Notes:

- `pending_retry` is not a Kafka publish outcome; it is an outbox state.
- Kafka publish failure should also be reflected in outbox failure metrics.

### 2.9 Outbox Lifecycle Metrics

Change points:

- keep normal success lifecycle simple;
- add compact failure-stage visibility so dashboards can distinguish DB and Kafka failure points;
- keep logs as supporting detail, not the only failure signal.

Metrics:

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `zexchange.outbox.lifecycle` | Counter | `event_type`, `source`, `lifecycle` | Successful lifecycle transition count for high-level outbox state. |
| `zexchange.outbox.failures` | Counter | `event_type`, `source`, `stage`, `error_type` | Failure count by outbox stage. Used to identify whether DB or Kafka is failing. |

Allowed values:

```text
event_type = ledger_post_reply
source = immediate | retry
lifecycle = created | sent | dead
stage = create | claim | publish | finalize | mark_dead
error_type = db_error | kafka_error | timeout | duplicate | invalid_event | unknown
```

Stage meaning:

- `create`: insert a new outbox row.
- `claim`: retry worker reserves pending rows for a retry attempt.
- `publish`: send outbox payload to Kafka.
- `finalize`: mark successfully published row as `SENT`.
- `mark_dead`: mark unrecoverable or retry-exhausted row as `DEAD`.

Notes:

- A publish success and finalize failure must be distinguishable.
- `mark_dead` is a target lifecycle stage; current code may leave exhausted rows as `PENDING`.

### 2.10 Outbox Backlog Gauges

Change points:

- add a scheduled collector that queries DB periodically and stores values in memory-backed gauges;
- do not query DB inside Prometheus scrape callbacks;
- expose pending, dead, exhausted pending, and oldest pending age;
- expose age in seconds.

Metrics:

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `zexchange.outbox.pending` | Gauge | `event_type` | Current number of pending outbox rows. |
| `zexchange.outbox.dead` | Gauge | `event_type` | Current number of dead outbox rows. |
| `zexchange.outbox.pending.exhausted` | Gauge | `event_type` | Current pending rows whose attempt count has reached max retries. Mandatory while exhausted rows may not transition to `DEAD`. |
| `zexchange.outbox.pending.oldest.age` | Gauge | `event_type` | Age in seconds of the oldest pending row. Backlog health and async delay signal. |

Allowed values:

```text
event_type = ledger_post_reply
```

Notes:

- `dead=0` is not enough to prove health if exhausted rows remain `PENDING`.
- `pending.oldest.age=0` means no current pending backlog; it does not describe historical completed-message delay.

### 2.11 Outbox Dispatch Metrics

Change points:

- instrument scheduled retry dispatch;
- record dispatch duration;
- record per-dispatch record counts by result.

Metrics:

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `zexchange.outbox.dispatch.duration` | Timer | `outcome` | Duration of one scheduled outbox retry dispatch run. |
| `zexchange.outbox.dispatch.records` | Distribution summary | `event_type`, `result` | Number of records claimed/succeeded/failed in each dispatch run. |

Allowed values:

```text
outcome = success | error
event_type = ledger_post_reply
result = claimed | sent | failed
```

Notes:

- `dispatch.records` is per run, not a total counter.
- Use dispatch duration with backlog gauges to understand whether retry work is keeping up.

### 2.12 DB Client-Side Metrics

Change points:

- instrument stable DB operation phases and transaction phases;
- do not instrument every raw SQL statement in application code;
- use operation names that map to ledger workflow steps.

Metrics:

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `zexchange.db.operation.duration` | Timer | `operation`, `outcome` | Client-observed latency for a bounded DB operation phase. |
| `zexchange.db.transaction.duration` | Timer | `transaction`, `outcome` | Client-observed latency for a bounded DB transaction block. |

Allowed values:

```text
operation = account_ref_lookup | ledger_txn_lookup_by_id | ledger_txn_lookup_by_ref | ledger_entry_lookup | ledger_txn_insert | ledger_entry_batch_insert | outbox_insert | outbox_claim | outbox_finalize | outbox_backlog_collect
transaction = ledger_post_transaction | outbox_claim
outcome = success | error
```

Notes:

- Hikari pool metrics may already be emitted by Actuator and should be verified from `/actuator/prometheus`.
- MySQL statement-level details belong to Performance Schema/exporters in a later phase.

## 3. Excluded From Phase One

The following are intentionally excluded from this application-code phase:

- MySQL exporter setup;
- Redis exporter setup;
- Kafka exporter setup;
- Kafka JMX exporter setup;
- host/container exporter setup;
- Grafana dashboards;
- wallet-service metrics;
- outbox completed-message delivery delay timer;
- `zexchange.ledger.entries.created`;
- high-cardinality request identifiers in labels.

## 4. Cardinality Rules

Allowed labels:

- `application`;
- `service`;
- `method`;
- `operation`;
- `ingress`;
- `outcome`;
- `error_code`;
- `error_type`;
- `cache_type`;
- `listener_name`;
- `topic`;
- `source`;
- `event_type`;
- `lifecycle`;
- `stage`;
- `result`;
- `grpc_status`;
- `retriable`.

Forbidden labels:

- ledger transaction ID;
- reference ID;
- account ID;
- command ID;
- idempotency key;
- Redis key;
- SQL text;
- raw exception class unless normalized;
- raw exception message;
- request payload or detail message;
- arbitrary Kafka destination from payload.

## 5. Validation Checklist

Before using these metrics for a load test, validate with a small deterministic run:

1. A successful gRPC post records one gRPC duration count, one business success, one new transaction, and one entries-per-transaction sample.
2. Replaying the same request records business success plus idempotent replay, but does not increment `transactions.created`.
3. Invalid input records business error with a bounded `error_code`; gRPC status may still be `OK`.
4. Cache value hit, miss, negative hit, tombstone hit, and cache error each increment the expected `zexchange.cache.ops` result.
5. Kafka listener success and failure paths record listener duration and active count returns to zero.
6. Kafka publish failure records `zexchange.kafka.publish.duration` with failure/timeout and increments the relevant `zexchange.outbox.failures` stage.
7. Outbox finalize failure is visible separately from publish failure.
8. Outbox pending, dead, exhausted, and oldest age gauges refresh from the scheduled collector, not from scrape-time DB queries.
9. Dispatch duration and dispatch record summaries change during scheduled retry work.
10. No metric contains transaction, reference, account, command, Redis key, raw SQL, or raw error-message labels.
