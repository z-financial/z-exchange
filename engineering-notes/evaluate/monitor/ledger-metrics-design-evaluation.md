# Ledger Metrics Design Evaluation

## 1. Verdict

The design is directionally sound for phase one. It keeps transport metrics, business outcomes, cache behavior, Redis latency, Kafka flow, outbox state, and DB latency as separate views, which is the right structure for a capacity test.

The main corrections are:

1. Do not rely only on API duration for all business timing. gRPC duration is useful, but Kafka ingress and internal processing need their own processing timer.
2. Add active/in-flight metrics for saturation. Latency and status alone tell you what happened, not whether the service is building a queue.
3. Outbox `created/sent/dead` is not enough. You need explicit phases such as create, claim, publish, and finalize, each with success/failure.
4. Add idempotency misses, conflicts, parse errors, and DB replays, not only `ACCEPTED/PENDING` hits.
5. Add cache error and DB fallback metrics. Cache misses and tombstones are not equivalent to successful hits because they fall through to MySQL.
6. Keep metric labels bounded. Do not use transaction ID, reference ID, account ID, Redis key, raw SQL, exception message, or arbitrary payload values as labels.

External exporters such as `mysqld_exporter`, `redis_exporter`, `kafka-exporter`, and Kafka JMX exporter remain out of scope for this phase, but the application metrics should leave room to correlate with them later.

## 2. Parts That Are Already Sound

### API Metrics

Your API metric shape is sound:

```text
grpc method, duration, grpc_status
```

Reason: this cleanly describes gRPC transport behavior. It also keeps gRPC status separate from protobuf business errors, which is important because this service often returns business errors in a normal `OK` gRPC response.

Recommended small addition:

```text
active/in_flight calls
```

This is needed for saturation detection. A server can have acceptable status codes while active requests and p99 latency climb.

### Separate Business Entry Point Counter

The proposed business entry point metric is sound as a counter:

```text
ingress, errorCode
```

Reason: business success/error rate must be measured separately from gRPC status. Use a bounded operation label such as:

```text
operation = post_transaction | get_ledger_txn_by_id | get_ledger_txn_by_ref
ingress   = grpc | kafka
outcome   = success | error
error_code = OK | ledger_not_found | account_not_found | ...
```

Do not use error detail strings as labels.

### Redis Client-Side Latency

Client-side Redis latency is the right application-level metric for this phase.

Reason: Redis server metrics will later show server health, but application timers show what ledger actually experienced, including client-side queuing, network, timeouts, Lua calls, decoding, and fallback behavior.

### Periodic Outbox Collector

The proposed worker thread that periodically queries DB for outbox backlog is sound.

Reason: backlog state should not be queried during every Prometheus scrape. A scheduled collector with cached gauges protects the DB and makes scrape cost predictable.

### DB Client-Side Latency

Recording DB operation and transaction latency on the client side is sound.

Reason: DB exporters and Performance Schema can explain database internals later, but ledger still needs workflow-level timings such as account lookup, ledger transaction insert, entry insert, and read path lookup.

## 3. Missing Metrics

### Active Work / Saturation Metrics

Add:

```text
zexchange.grpc.server.active{service,method}
zexchange.kafka.listener.active{listener}
```

Why helpful: saturation usually shows up as rising active work, queue depth, backlog, or lag before outright failures. Without active work metrics, you may only see p99 latency after the service is already overloaded.

Hikari active/idle/pending/acquisition metrics should also be verified from Actuator. If already exposed by Spring Boot, no custom code is needed.

### Ledger Processing Duration

Your design says business entry point should not record duration because API duration already records it. That is only partly true.

Add:

```text
zexchange.ledger.processing.duration{operation,ingress,outcome}
```

Why helpful:

- Kafka ingress has no gRPC API duration.
- gRPC duration includes transport/interceptor/serialization overhead, not just business processing.
- Processing duration lets you compare `grpc` versus `kafka` ingress for the same ledger operation.
- It helps isolate whether latency comes from ledger logic or from API transport.

Keep this timer low-cardinality. Do not tag it by error message, reference ID, or account ID.

### Idempotency Outcome Metrics

Current design:

```text
idemp_status = ACCEPT/PENDING
```

Add these outcomes:

```text
claim_success
accepted_replay
pending_hit
db_replay
hash_conflict
parse_error_recovered
release_success
release_failure
```

Why helpful:

- `accepted_replay` is a successful request but must not increment `transaction_created`.
- `pending_hit` indicates hot duplicate pressure or slow in-flight processing.
- `hash_conflict` is a correctness signal and can indicate client idempotency misuse.
- `db_replay` catches the case where Redis misses but DB idempotency still prevents duplicate ledger creation.
- `release_failure` matters because failed release can leave a request stuck behind a Redis pending key until TTL.

Use actual code wording `ACCEPTED`, not `ACCEPT`, unless the implementation intentionally normalizes it.

### Entry Creation Counter

Current design has:

```text
transaction_created
entry_number_under_one_transaction
```

Add:

```text
zexchange.ledger.entries.created
```

Why helpful: `entry_number_under_one_transaction` should be a distribution summary, but a total created-entry counter makes it easy to reconcile with DB row deltas and detect partial/inconsistent instrumentation.

Rules:

- Increment `transaction_created` only after a newly inserted ledger transaction commits.
- Do not increment it for idempotent replays.
- Record `entry_number_under_one_transaction` only for newly committed transactions.

### Cache DB Fallbacks

Current design records cache result. Add:

```text
zexchange.cache.db_fallbacks{cache_type,reason}
```

Reasons:

```text
miss | tombstone | cache_error | inconsistent_ref
```

Why helpful: cache miss/tombstone rates explain DB load. A tombstone hit is not a successful data hit in this service; it falls through to DB like a miss.

### Cache Error Result

Current cache result set:

```text
hit | tombstone | negative | miss
```

Add:

```text
error
```

Why helpful: the design expects cache failure to degrade to DB. Without an `error` result, you cannot tell the difference between normal cache misses and Redis/cache decode failures.

### Kafka Listener Duration and Deserialize Errors

Current listener metric:

```text
listener_name, error = success | retriable | nonretriable
```

Add:

```text
duration
active
deserialize_error
business_error
```

Why helpful:

- `duration` shows consumer-side processing cost.
- `active` helps identify saturation.
- `deserialize_error` is separate from business failure and usually means bad data or schema mismatch.
- `business_error` matters because a protobuf business failure can still be a successfully consumed Kafka message.

### Outbox Exhausted Pending Count

Current backlog gauges:

```text
pending_count
dead_count
oldest
```

Add:

```text
pending_exhausted_count
```

Why helpful: the current outbox retry code stops selecting rows after `attempt_count >= maxRetries`, but those rows may remain `PENDING` instead of moving to `DEAD`. Therefore `dead_count = 0` does not prove the outbox is healthy.

### Outbox Collector Health

Add:

```text
zexchange.outbox.collector.last_success_time
zexchange.outbox.collector.errors
```

Why helpful: if the collector query fails and the gauges stay at their previous values, dashboards can show stale health. Collector health prevents a stale zero from looking safe.

### Outbox Delivery Delay

Add one of:

```text
zexchange.outbox.delivery.delay{event_type,source,outcome}
zexchange.outbox.pending.oldest.age{event_type}
```

You already proposed `oldest`, which is the minimum required signal. Prefer exposing it as age in seconds, not only a timestamp.

Why helpful: DB operation duration does not measure async delivery delay. Outbox insert may take 5 ms while the message waits 30 seconds to publish.

### Load Generator Metrics

This is outside ledger-service code, but should be part of the performance design:

```text
offered TPS
completed TPS
client p50/p95/p99
client timeouts
client connection errors
```

Why helpful: server-side metrics do not show client-side queueing, network delay, or whether the load generator is actually offering the intended rate.

## 4. Metrics Needing Correction or Improvement

### Business Entry Point Naming

Current design:

```text
ingress (postTransaction, getLedgerTxn-id, getLedgerTxn-ref)
```

Correction:

`ingress` should describe where the request entered:

```text
ingress = grpc | kafka
```

The operation should describe what work was requested:

```text
operation = post_transaction | get_ledger_txn_by_id | get_ledger_txn_by_ref
```

This avoids mixing two dimensions into one label.

### Kafka Publisher Outcome

Current design:

```text
target_topic, published/pending_retry, duration
```

Correction:

`pending_retry` is an outbox state, not a Kafka publish result. Use:

```text
topic
source = immediate | retry
outcome = success | failure | timeout | invalid_event
duration
```

If publish fails and the outbox remains pending, count that separately in outbox lifecycle/backlog metrics.

### Outbox Lifecycle

Current design:

```text
eventType, lifecycle = created | sent | dead
```

Correction:

Use lifecycle phase plus outcome:

```text
event_type
source = immediate | retry
phase = create | claim | publish | finalize | mark_dead
outcome = success | failure
```

Why: `SENT` means the row was finalized after publish; it is not the same as "Kafka publish succeeded". A publish can succeed and finalization can fail, leaving the row pending for retry.

### Outbox Duration Assumption

Current design says outbox duration is generally equal to DB operation cost, so it is not recorded.

Correction: DB operation cost and outbox delivery delay are different metrics.

Measure DB cost with DB timers. Measure outbox behavior with:

```text
dispatch duration
records claimed/succeeded/failed per dispatch
oldest pending age
optional delivery delay on finalize
```

Why: outbox backlog and delay are usually caused by Kafka failure, low page limit, retry interval, consumer lag, or publish timeout, not only DB insert/update latency.

### Cache Result Semantics

Current design:

```text
cache_result = hit | tombstone | negative | miss
```

Improvement:

```text
cache_result = value_hit | tombstone_hit | negative_hit | cache_miss | error
```

Why: `hit` is ambiguous. A tombstone hit and negative hit behave differently from a value hit.

### Kafka Listener Error Label

Current design:

```text
error = success | retriable | nonretriable
```

Correction:

Use `outcome`, not `error`, because `success` is not an error:

```text
outcome = success | business_error | retriable_error | non_retriable_error | deserialize_error
```

## 5. Suggested Metric Structure

Use a consistent namespace:

```text
zexchange.<domain>.<name>
```

Recommended phase-one app metrics:

| Metric | Type | Tags |
|---|---|---|
| `zexchange.grpc.server.requests` | Counter | service, method, grpc_status |
| `zexchange.grpc.server.duration` | Timer | service, method, grpc_status |
| `zexchange.grpc.server.active` | Gauge | service, method |
| `zexchange.ledger.requests` | Counter | operation, ingress, outcome, error_code |
| `zexchange.ledger.processing.duration` | Timer | operation, ingress, outcome |
| `zexchange.ledger.transactions.created` | Counter | none or operation |
| `zexchange.ledger.transactions.idempotent` | Counter | ingress, source |
| `zexchange.ledger.entries.created` | Counter | none |
| `zexchange.ledger.entries.per_transaction` | Distribution summary | none |
| `zexchange.ledger.idempotency.events` | Counter | ingress, outcome |
| `zexchange.cache.reads` | Counter | cache_type, result |
| `zexchange.cache.db_fallbacks` | Counter | cache_type, reason |
| `zexchange.redis.operation.duration` | Timer | operation, cache_type, outcome |
| `zexchange.kafka.listener.duration` | Timer | listener, outcome |
| `zexchange.kafka.listener.active` | Gauge | listener |
| `zexchange.kafka.publish.duration` | Timer | topic, source, outcome |
| `zexchange.outbox.events` | Counter | event_type, source, phase, outcome |
| `zexchange.outbox.dispatch.duration` | Timer | outcome |
| `zexchange.outbox.dispatch.records` | Distribution summary | event_type, outcome |
| `zexchange.outbox.pending` | Gauge | event_type |
| `zexchange.outbox.dead` | Gauge | event_type |
| `zexchange.outbox.pending.exhausted` | Gauge | event_type |
| `zexchange.outbox.pending.oldest.age` | Gauge | event_type |
| `zexchange.db.operation.duration` | Timer | operation, outcome |
| `zexchange.db.transaction.duration` | Timer | transaction, outcome |

For success cases, set:

```text
error_code = OK
```

Do not omit the label only on success, because missing labels make Prometheus queries harder.

## 6. Performance Concerns of Metric Measurement

### Avoid High-Cardinality Labels

Never tag metrics with:

- transaction ID;
- reference ID;
- account ID;
- command ID;
- idempotency key;
- Redis key;
- SQL text;
- exception message;
- request detail;
- arbitrary destination from payload.

These labels can create unbounded time series and make Prometheus slow or unstable.

### Be Careful With Histograms

Histograms are useful for p95/p99, but each tag combination creates many time series.

Recommended:

- enable histograms only for important timers;
- keep tags bounded;
- define SLO buckets intentionally;
- do not add `error_code` to high-volume duration histograms unless the error-code set is small and stable.

### Do Not Query DB On Scrape

For outbox gauges, keep your collector design:

```text
scheduled query -> AtomicLong gauges -> Prometheus scrape reads cached values
```

Recommended interval:

```text
15s to 30s
```

or at least slower than the outbox dispatcher interval unless you are validating behavior at very low load.

### Validate Outbox Gauge Query Plans

Backlog count and oldest pending age need indexes to stay cheap at scale.

Before load testing, validate query plans for:

```text
pending count by event_type
dead count by event_type
exhausted pending count by event_type
oldest pending created_at by event_type
```

The current dispatch-oriented index may not be ideal for oldest-created lookup.

### Keep Redis Metrics At Logical Operation Level

A single logical idempotency/cache operation may involve Lua, multiple Redis commands, decode, and fallback. Measuring logical operation latency is more useful than producing a separate metric for every low-level command in application code.

### Do Not Over-Instrument Every SQL Statement

Measure stable DB phases:

```text
account_ref_lookup
ledger_txn_lookup_by_id
ledger_txn_lookup_by_ref
ledger_entry_lookup
ledger_post_transaction
ledger_txn_insert
ledger_entry_batch_insert
outbox_insert
outbox_finalize
```

Let MySQL Performance Schema or future exporters handle statement-level detail.

## 7. Other Valuable Evaluation

### Validate Counter Reconciliation

Before a real load test, run a small deterministic test and verify:

- one successful post increments `transactions.created` by 1;
- `entries.created` equals inserted ledger-entry rows;
- replaying the same request increments idempotency/replay metrics but not `transactions.created`;
- a forced Kafka publish failure increments publish failure and grows pending outbox age;
- a retry success increments retry publish/finalize metrics and drains pending count.

### Interpret Dead Count Carefully

If code does not transition exhausted rows to `DEAD`, then:

```text
dead_count = 0
```

does not mean healthy. `pending_exhausted_count` is mandatory until dead-letter state transition is implemented.

### Keep Hot/Normal Workload Out Of Labels

Do not add `hot` or `normal` as a label inferred from wallet/account/reference IDs.

Use separate test scenarios or Grafana annotations:

```text
scenario = hot
scenario = normal
```

Reason: workload identity is controlled by the load generator, not by service code.

### Use Derived Error Rates

Do not create a separate `error_rate` metric. Derive it from counters:

```promql
sum(rate(zexchange_ledger_requests_total{outcome="error"}[5m]))
/
sum(rate(zexchange_ledger_requests_total[5m]))
```

Same idea for gRPC transport errors and Kafka listener failures.

### Keep App Metrics Compatible With Future Exporters

This phase should not add external exporter dependencies, but the labels should align with future dashboards:

- use fixed `event_type` values for outbox;
- use fixed `topic` values for Kafka publish;
- use fixed `listener` names;
- use stable `operation` names for DB and Redis timers.

That makes it easy to correlate later with MySQL, Redis, Kafka, JVM, and host metrics without changing app metric names.

## 8. Corrected Phase-One Design Summary

Keep your current structure, with these adjustments:

1. API: keep method, duration, gRPC status; add active calls.
2. Business entry: use `operation + ingress + outcome + error_code`; add processing duration for Kafka/internal comparison.
3. Business creation: keep transaction created and entries per transaction; add entries-created total and idempotent replay count.
4. Idempotency: include miss/claim/replay/pending/conflict/parse/release outcomes.
5. Cache: use precise result names and add error plus DB fallback reason.
6. Redis: measure client-side logical operation latency with outcome.
7. Kafka: separate listener outcome/duration/active from publisher duration/outcome.
8. Outbox: replace status-only lifecycle with phase/source/outcome events; keep backlog gauges and add exhausted/staleness.
9. DB: keep client-side operation and transaction timers with bounded operation names.

With these corrections, the design is robust enough for ledger-service phase-one capacity testing and will still compose cleanly with external MySQL, Redis, Kafka, and host exporters in a later phase.
