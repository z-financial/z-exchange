# Ledger Metrics Design Follow-Up Evaluation

## 1. Verdict

Your objections are mostly reasonable for a phase-one implementation. The core direction is good: keep the metric set lean, avoid overly fine-grained labels, and rely on existing business outcome metrics where they already explain user-visible errors.

I would accept these changes:

1. Keep active work and ledger processing duration.
2. Replace detailed idempotency outcome labels with a smaller idempotency error metric.
3. Skip `entries.created` for phase one if DB insert count/latency already covers entry write pressure.
4. Use one cache result metric plus a separate cache error metric.
5. Use only `pending.oldest.age` for async outbox delay in phase one.
6. Keep dispatch duration and per-dispatch record counts.
7. Add Kafka client-side publish latency.
8. Keep the corrected business entry naming.

I would still push back on one point:

- Outbox lifecycle as only `created | sent | dead` is probably too coarse, even for phase one, because it hides publish success versus finalization failure. If you want to stay lean, use a compromise metric rather than relying only on logs.

## 2. Active Work / Saturation Metrics

Your position: agree.

Evaluation: correct.

Keep:

```text
zexchange.grpc.server.active{service,method}
zexchange.kafka.listener.active{listener_name}
```

Reason: these are essential saturation signals. They are cheap to collect and directly answer whether the service is accumulating active work.

No change needed.

## 3. Ledger Processing Duration

Your position: agree.

Evaluation: correct.

Keep:

```text
zexchange.ledger.processing.duration{operation,ingress,outcome}
```

Reason: gRPC duration does not cover Kafka ingress, and it includes transport overhead. A processor-level timer helps compare gRPC and Kafka paths and isolate business logic cost.

No change needed.

## 4. Idempotency Outcome Metrics

Your position:

- Do not tag idempotency metrics too finely.
- Use business metrics to observe propagated error codes.
- Add a smaller metric for idempotency errors because some idempotency errors may not propagate cleanly to the processor endpoint.

Proposed:

```text
idemp_errors{ingress,error_type}
```

Evaluation: mostly reasonable.

The original detailed outcome list was designed for diagnosis, not mandatory phase-one monitoring. For a lean phase one, your version is acceptable if business metrics already expose the request outcome and error code.

Recommended compromise:

```text
zexchange.ledger.idempotency.errors{ingress,error_type}
zexchange.ledger.transactions.idempotent{ingress,source}
```

Where:

```text
source = redis | db
```

Why keep `transactions.idempotent`: idempotent replay is not an error and should be visible separately from `transaction_created`. It helps explain why successful request TPS can be higher than new ledger transaction TPS.

Bounded `error_type` examples:

```text
hash_conflict
parse_error
release_failed
redis_error
unknown_state
```

Do not tag by idempotency key, reference ID, token, or raw Redis value.

Final recommendation: accept your simplification, but keep a replay counter.

## 5. Entry Creation Counter

Your position:

- Do not add `zexchange.ledger.entries.created`.
- Transaction creation is more business-meaningful.
- Entry insertion count/latency in DB metrics is more intuitive.

Evaluation: reasonable for phase one.

`entries.created` is useful for reconciliation, but it is not required if you already have:

```text
zexchange.ledger.transactions.created
zexchange.ledger.entries.per_transaction
zexchange.db.operation.duration{operation="ledger_entry_batch_insert",outcome}
```

Why this is acceptable:

- transaction count represents the business unit;
- entries per transaction captures transaction complexity;
- DB insert metrics capture write pressure;
- a separate entry counter can be added later if reconciliation becomes painful.

Final recommendation: accept your position for phase one.

## 6. Cache DB Fallbacks / Cache Error Result

Your position:

Use:

```text
cache_ops{cache_type,cache_result}
cache_errors{cache_type,error_type}
```

With:

```text
cache_result = hit | tombstone | negative | miss | error
```

Evaluation: reasonable with one naming improvement.

This is a clean phase-one structure. It avoids a separate fallback metric while still making DB fallback inferable:

```text
fallback ~= miss + tombstone + error
```

Recommended names:

```text
zexchange.cache.ops{cache_type,result}
zexchange.cache.errors{cache_type,error_type}
```

Recommended result values:

```text
value_hit
tombstone_hit
negative_hit
miss
error
```

Why use more explicit names: `hit` alone is ambiguous because tombstone and negative values are technically cache hits but behaviorally different from value hits.

Bounded `error_type` examples:

```text
redis_error
decode_error
timeout
unknown
```

Final recommendation: accept your structure, but use explicit result names.

## 7. Kafka Publisher Outcome / Outbox Lifecycle

Your position:

The previous proposal:

```text
phase = create | claim | publish | finalize | mark_dead
outcome = success | failure
```

is too fine-grained for phase one.

You prefer:

```text
event_type
source = immediate | retry
lifecycle = created | sent | dead
```

and use logs if more detail is needed.

Evaluation: partially reasonable, but risky.

The simplification is understandable, but `created | sent | dead` hides an important failure mode in this codebase:

```text
Kafka publish succeeds, but DB finalization to SENT fails.
```

In that case, the message may already be delivered, but the row remains pending and may be retried. A lifecycle-only metric cannot tell whether the issue was publish failure or finalization failure.

Logs are helpful for root cause, but they are not enough for capacity testing because:

- logs are expensive under load;
- logs are harder to aggregate into rates;
- logs may be sampled or disabled;
- dashboards need a low-cost signal to show which phase is failing.

Recommended compromise:

Keep your lifecycle metric:

```text
zexchange.outbox.lifecycle{event_type,source,lifecycle}
```

Where:

```text
lifecycle = created | sent | dead
```

Add one low-cardinality failure metric:

```text
zexchange.outbox.failures{event_type,source,stage,error_type}
```

Where:

```text
stage = create | claim | publish | finalize | mark_dead
error_type = db_error | kafka_error | timeout | duplicate | unknown
```

Why this compromise works:

- normal success path stays simple;
- failures remain diagnosable without high-cardinality labels;
- dashboards can distinguish Kafka publish failure from DB finalize failure;
- logs remain supporting detail, not the only signal.

Final recommendation: do not rely only on `created | sent | dead`; add a compact failure-stage metric.

## 8. Kafka Listener Duration and Deserialize Errors

Your position:

- Active listener metric is valuable.
- Do not split errors into `success | business_error | retriable_error | non_retriable_error | deserialize_error`.
- Business errors can be retriable or non-retriable.
- Prefer:

```text
listener_name
error = processing-error | deserialize-error
retriable = true | false
```

Evaluation: reasonable, with naming correction.

The model is better because `retriable` is a separate dimension from error class.

Recommended:

```text
zexchange.kafka.listener.duration{listener_name,outcome,error_type,retriable}
zexchange.kafka.listener.active{listener_name}
```

Where:

```text
outcome = success | error
error_type = none | processing_error | deserialize_error
retriable = true | false | none
```

Why not use label name `error`: it becomes awkward for success cases. `outcome` plus `error_type` is clearer.

Important: keep `retriable` bounded as string values. Do not use exception class names unless normalized to a short fixed list.

Final recommendation: accept your model, but use `outcome/error_type/retriable` naming.

## 9. Outbox Exhausted Pending Count

Your position: agree to add `pending_exhausted_count`.

Evaluation: correct.

Keep:

```text
zexchange.outbox.pending.exhausted{event_type}
```

Reason: current retry behavior may leave rows `PENDING` after max retries. `dead_count = 0` is not enough.

No change needed.

## 10. Outbox Delivery Delay

Your position:

Use only:

```text
zexchange.outbox.pending.oldest.age{event_type}
```

for now.

Evaluation: reasonable for phase one.

`pending.oldest.age` is enough to detect current backlog health. It does not answer completed-message delivery latency, but that can wait until you define an async delivery SLO such as "wallet reply must arrive within 2 seconds p99."

Final recommendation: accept your position.

## 11. Outbox Duration Assumption

Your position:

Agree to keep:

```text
dispatch duration
records claimed/succeeded/failed per dispatch
oldest pending age
```

And maybe add Kafka publish latency on the client side.

Evaluation: correct.

Recommended phase-one set:

```text
zexchange.outbox.dispatch.duration{outcome}
zexchange.outbox.dispatch.records{event_type,result}
zexchange.outbox.pending.oldest.age{event_type}
zexchange.kafka.publish.duration{topic,source,outcome}
```

Kafka publish latency is valuable because `DefaultPublisher` blocks on send acknowledgment. That timer captures producer queueing plus broker acknowledgment wait from the application's point of view.

No additional outbox delivery delay timer is required in phase one.

## 12. Business Entry Point Naming

Your position: agree.

Evaluation: correct.

Use:

```text
operation = post_transaction | get_ledger_txn_by_id | get_ledger_txn_by_ref
ingress = grpc | kafka
outcome = success | error
error_code = OK | ...
```

Reason: operation and ingress are separate dimensions. Combining them makes dashboard queries and comparisons awkward.

No change needed.

## 13. Revised Lean Phase-One Metric Set

This is the revised set I would implement after your feedback.

### API / gRPC

```text
zexchange.grpc.server.active{service,method}
zexchange.grpc.server.duration{service,method,grpc_status}
```

Use the timer count for request rate. A separate gRPC request counter is optional and not needed if every call records duration exactly once.

### Business

```text
zexchange.ledger.requests{operation,ingress,outcome,error_code}
zexchange.ledger.processing.duration{operation,ingress,outcome}
zexchange.ledger.transactions.created
zexchange.ledger.transactions.idempotent{ingress,source}
zexchange.ledger.entries.per_transaction
```

Skip `entries.created` for phase one.

### Idempotency

```text
zexchange.ledger.idempotency.errors{ingress,error_type}
```

Keep this narrow. Use business request error codes and replay counters for the rest.

### Cache

```text
zexchange.cache.ops{cache_type,result}
zexchange.cache.errors{cache_type,error_type}
```

Recommended results:

```text
value_hit | tombstone_hit | negative_hit | miss | error
```

### Redis

```text
zexchange.redis.operation.duration{operation,cache_type,outcome}
```

Keep operation names logical, not raw Redis commands.

### Kafka

```text
zexchange.kafka.listener.active{listener_name}
zexchange.kafka.listener.duration{listener_name,outcome,error_type,retriable}
zexchange.kafka.publish.duration{topic,source,outcome}
```

### Outbox

Lean success lifecycle:

```text
zexchange.outbox.lifecycle{event_type,source,lifecycle}
```

Recommended failure companion:

```text
zexchange.outbox.failures{event_type,source,stage,error_type}
```

Backlog:

```text
zexchange.outbox.pending{event_type}
zexchange.outbox.dead{event_type}
zexchange.outbox.pending.exhausted{event_type}
zexchange.outbox.pending.oldest.age{event_type}
```

Dispatch:

```text
zexchange.outbox.dispatch.duration{outcome}
zexchange.outbox.dispatch.records{event_type,result}
```

### DB

```text
zexchange.db.operation.duration{operation,outcome}
zexchange.db.transaction.duration{transaction,outcome}
```

## 14. Final Recommendation

Your leaner design is reasonable and probably better for a first implementation pass. It reduces cardinality and implementation surface while preserving the most important capacity signals.

The one change I would strongly keep from my earlier recommendation is failure-stage visibility for outbox. You do not need to tag every success path by every phase, but failures should identify whether they happened at create, claim, publish, finalize, or mark-dead. Otherwise the dashboard can tell you the outbox is unhealthy but not whether the bottleneck is DB or Kafka.
