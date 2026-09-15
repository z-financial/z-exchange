# Ledger Service Phase-One Metrics Evaluation

## 1. Verdict

The plan is a reasonable phase-one baseline, but it needs several corrections before implementation:

1. Separate **gRPC transport outcomes** from **ledger business outcomes**. This service normally returns business errors inside protobuf replies and completes gRPC with status `OK`; a gRPC error counter alone will miss them.
2. Treat `kafka-exporter`, broker JMX, and application Kafka client metrics as three different views. None replaces the others.
3. Define outbox metrics by lifecycle phase. A single success/fail count cannot distinguish publish failure from database finalization failure.
4. Add **oldest pending age** and **retry-exhausted pending count** to the outbox backlog count. A small but very old backlog is still a failure.
5. Treat cache `hot` versus `normal` as a test workload dimension, not a cache-result label generated from keys.
6. Add application saturation and Kafka-consumer processing metrics. Infrastructure health without service work-rate and saturation signals will not explain a throughput ceiling.

Proceed ledger-first, but use the corrected metric set below.

## 2. Current Ledger Observability

- `ledger-service` already depends on Spring Boot Actuator and `micrometer-registry-prometheus`.
- `/actuator/prometheus` is exposed and Prometheus currently scrapes the service every five seconds.
- No custom Micrometer meters are currently present.
- `management.metrics.tags.application` references `${spring.application.name}`, but `spring.application.name` is not defined in the checked-in ledger configuration. Define it before relying on the common tag.
- The current Docker Compose stack has Prometheus and Grafana, but no MySQL, Redis, Kafka, host, or container exporters.
- Existing JVM, Hikari, and Kafka client meters must be verified from the running `/actuator/prometheus` endpoint. Do not assume every expected binder is active merely because Actuator is present.

## 3. Corrected Phase-One Scope

| Layer | Required phase-one signals | Source |
|---|---|---|
| Load generator | offered rate, completed rate, client p50/p95/p99, timeouts, connection errors | Test client |
| gRPC server | requests, duration histogram, active requests, transport status | gRPC server interceptor |
| Ledger business | outcomes by bounded error code, newly created transactions, idempotent replays, entries per transaction, processing duration | Manual Micrometer instrumentation |
| MySQL | connection/pool usage, transaction rate, slow-query rate, statement digest latency, row-lock wait/deadlock, buffer/I/O, database host CPU | Hikari binder, `mysqld_exporter`, Performance Schema, host/container exporter |
| Redis | server command/memory/eviction health, client-observed operation latency/errors, cache result, idempotency result | `redis_exporter` plus manual/client instrumentation |
| Kafka broker | request queues, handler/network utilization, bytes/records, request latency, ISR/partition health | Kafka JMX Exporter on every broker |
| Kafka consumer groups | lag by group/topic/partition | `kafka-exporter` or another lag exporter |
| Kafka application | producer send latency/error, consumer processing latency/outcome, retries/DLQ, rebalance count | Kafka client binders plus manual listener/publisher metrics |
| JVM/process | CPU, heap, allocation, GC pause, threads, file descriptors, uptime | Micrometer JVM/process binders |
| Outbox | created, publish attempt result, finalization result, retry work, pending/exhausted/dead count, oldest pending age | Manual counters plus periodic database query |

The load generator remains the authoritative source for user-visible latency. Server timers exclude network and client-side queuing.

## 4. Database Metrics

### 4.1 Exporters and Performance Schema

Use `mysqld_exporter` with global status and selected Performance Schema collectors. Do not scrape statement text or unbounded SQL labels into Prometheus.

Minimum database signals:

- MySQL uptime and availability;
- current/max connections and connection errors;
- Hikari active, idle, pending, max, acquisition duration, and timeout count;
- queries and transactions per second;
- InnoDB row-lock wait count/time, deadlocks, and lock timeouts;
- buffer-pool usage and hit ratio;
- data/log I/O, fsync activity, disk latency, and disk saturation;
- temporary tables created on disk;
- statement count, total time, average time, and rows examined by normalized digest;
- MySQL process/container CPU and memory.

`mysqld_exporter` does not provide database CPU by itself. Add `node_exporter`, cAdvisor, or the platform's container/host collector.

### 4.2 Slow Query Count

Expose MySQL's cumulative `Slow_queries` status as a counter and graph its rate or increase, not its raw value. Configure and record the test's `long_query_time`; otherwise the result is not comparable between runs.

Use Performance Schema statement digests to identify the expensive query families. The slow-query counter answers "how many crossed the threshold"; digest summaries answer "which normalized statements consumed the time." If detailed slow logging is enabled during a run, measure its overhead and keep the configuration identical across comparisons.

Recommended dashboard expressions conceptually:

```promql
rate(mysql_global_status_slow_queries[5m])
increase(mysql_global_status_slow_queries[15m])
```

Confirm the exact exported series name from the selected exporter version.

### 4.3 Application Database Timers

Exporter data cannot attribute time to the ledger workflow. Add bounded timers for:

- account-reference lookup;
- ledger database transaction;
- ledger transaction insert;
- ledger-entry batch insert;
- ledger query with and without entries.

Prefer timing the repository or processor phase rather than every SQL statement. Performance Schema already supplies statement-level detail.

## 5. Redis and Cache Metrics

### 5.1 Redis Server

`redis_exporter` should expose:

- commands/operations per second;
- command duration/statistics where available;
- connected and blocked clients;
- memory used, fragmentation, max-memory utilization;
- evictions, expirations, rejected connections, and keyspace hit/miss totals;
- persistence/fork state if persistence is enabled;
- Redis process/container CPU and network traffic.

Redis server keyspace hits and misses do not describe ledger cache behavior precisely. They include idempotency and other Redis traffic.

### 5.2 Client-Side Metrics

Add a client-observed timer and outcome counter at the stable application abstraction, with bounded tags such as:

```text
operation = cache_get | cache_write | cache_delete | idemp_claim | idemp_get | idemp_done | idemp_release
outcome   = success | timeout | error
cache     = ledger_txn | ledger_ref | idempotency
```

If Lettuce command metrics are available in this Spring Boot version, enable and verify them. They are useful but do not replace the application-operation timer because a single logical operation may execute multiple Redis commands or Lua scripts.

### 5.3 Cache Result Classification

The code already exposes these exact read states through `CacheReadResult`:

- `value_hit`;
- `cache_miss`;
- `negative_hit`;
- `tombstone_hit`;
- `error` when Redis/cache decoding fails and the flow falls back to MySQL.

Use a counter such as:

```text
zexchange.cache.reads{cache="ledger_txn|ledger_ref",result="value_hit|cache_miss|negative_hit|tombstone_hit|error"}
```

Also count database fallbacks. A tombstone hit is not a successful data hit; in the current store it falls through to the database just like a cache miss.

### 5.4 Hot Versus Normal Cache

Do not inspect a cache key and tag it `hot` or `normal`. That introduces unstable classification and risks high cardinality.

Run separate, named load-test scenarios:

- `hot`: a small fixed set of transaction/reference IDs;
- `normal`: requests distributed across a large, fixed dataset.

Record the workload name in the load-generator result or Grafana annotation. Compare cache hit ratio, Redis command rate, client latency, database fallback rate, and service latency between the two runs.

## 6. Kafka Metrics

### 6.1 Broker JMX Exporter

Run JMX Exporter for every Kafka broker. Collect at least:

- request-handler and network-processor idle percentage;
- request queue size and response queue size;
- produce/fetch request rate and latency;
- bytes and records in/out;
- log flush time;
- active controller, offline partitions, under-replicated partitions, and ISR changes;
- broker JVM and process CPU/memory.

Some replication-health metrics are less informative in the current single-broker environment, but keep them so the dashboard remains valid when brokers are added.

### 6.2 Kafka Exporter

Use `kafka-exporter` for consumer-group offsets and lag. Required views:

- current lag by consumer group, topic, and partition;
- total lag by group/topic;
- maximum partition lag;
- lag growth rate during the steady-state load;
- time to drain lag after load stops.

A zero total lag can hide one stuck partition if only an aggregate is shown, so retain a maximum-partition panel.

### 6.3 Ledger Kafka Client

Broker JMX and lag do not measure time inside `ledger-service`. Add or verify:

- producer record/send rate, error rate, retry rate, request latency, record queue time, and buffer availability;
- `zexchange.kafka.publish.duration` tagged by bounded `topic`, `phase=immediate|retry`, and `outcome`;
- consumer records received and processed;
- listener processing duration and active processing count;
- listener outcome `success|business_error|retriable_error|non_retriable_error`;
- retry attempts, records recovered, DLQ publications, and consumer rebalances.

`DefaultPublisher` currently blocks on `KafkaTemplate.send(...).get(3 seconds)`, so its timer measures both producer queuing and broker acknowledgment wait. Preserve that interpretation in phase-one results.

## 7. JVM and Service Saturation

Micrometer should expose, after verification:

- process and system CPU;
- heap/non-heap usage and committed memory;
- allocation/promotion rate where supported;
- GC pause count, total time, and max/p99 pause;
- live, peak, daemon, and state-distributed threads;
- loaded classes;
- file descriptors;
- process uptime.

Add saturation signals that are not covered by the generic JVM binders:

- active gRPC calls;
- Hikari pending threads and connection-acquisition latency;
- Kafka listener active processing and lag;
- owned executor active count, pool size, and queue size, but only where the service actually owns a bounded executor.

Do not invent a generic "thread pool queue size" for Kafka consumers. Consumer lag and processing duration are the meaningful queue/saturation signals there.

## 8. gRPC Metrics

Use a gRPC server interceptor for transport-level metrics:

```text
zexchange.grpc.server.requests{service,method,grpc_status}
zexchange.grpc.server.duration{service,method,grpc_status}
zexchange.grpc.server.active{service,method}
```

Enable histogram buckets aligned to the test SLO so p50/p95/p99 can be computed across instances. Do not calculate percentiles in each process and then average them.

The current service catches exceptions and usually sends a protobuf reply followed by `onCompleted()`. Therefore many internal and business failures still have gRPC status `OK`. Instrument the reply outcome separately:

```text
zexchange.ledger.requests{operation="post|get_by_id|get_by_ref",outcome="success|error",error_code}
```

Use the stable ledger error-code name or numeric code. Never use the error detail/message as a label.

## 9. Business Metrics

### 9.1 Required Counters and Timers

| Meter | Type | Required tags/meaning |
|---|---|---|
| `zexchange.ledger.requests` | Counter | operation, outcome, bounded error code, ingress `grpc|kafka` |
| `zexchange.ledger.processing.duration` | Timer | operation and outcome |
| `zexchange.ledger.transactions.created` | Counter | increment only after a newly inserted ledger transaction commits |
| `zexchange.ledger.transactions.idempotent` | Counter | existing DB transaction or accepted Redis idempotency result |
| `zexchange.ledger.entries.created` | Counter | committed ledger-entry count |
| `zexchange.ledger.entries.per_transaction` | Distribution summary | number of entries in a committed transaction |
| `zexchange.cache.reads` | Counter | cache scope and exact read result |
| `zexchange.cache.db_fallbacks` | Counter | cache scope and reason `miss|tombstone|error` |
| `zexchange.outbox.events` | Counter | phase and outcome described below |
| `zexchange.outbox.dispatch.duration` | Timer | scheduled retry run duration and outcome |
| `zexchange.outbox.dispatch.records` | Distribution summary | claimed/succeeded/failed records per run |

`ledger created count` must mean newly committed rows, not successful requests. An idempotent replay is a successful request but does not create another ledger transaction.

### 9.2 Outbox Lifecycle Metrics

Replace "outbox success/fail" with explicit phases:

```text
phase = create | publish | finalize
source = immediate | retry
outcome = success | failure
```

This distinction matters because the current flow can publish successfully and then fail to update the row to `SENT`. Counting that as a generic publish failure would be false.

Also track:

- retry records claimed;
- retry publish success/failure;
- finalization success/failure;
- exhausted rows;
- transition to `DEAD` once that lifecycle is implemented.

### 9.3 Periodic Outbox State

"Outbox not complete" should be three gauges, not one:

```text
zexchange.outbox.pending
zexchange.outbox.pending.oldest.age
zexchange.outbox.pending.exhausted
```

Optionally expose `zexchange.outbox.dead`. Do not use the total `SENT` row count as a throughput metric because it grows forever; use the finalization counter/rate.

Refresh these values on a scheduled task every 15-30 seconds and store them in `AtomicLong`-backed gauges. Do not execute SQL during every Prometheus scrape. Keep the last successful collection time and a collection-error counter so a stale zero is not mistaken for health.

The current outbox index is optimized for dispatch polling, not oldest-created lookup. Before frequent backlog-age collection at scale, validate the query plan and consider an index such as `(event_type, outbox_status, created_at, id)` for the ledger reply event, or run the query from an external test collector.

## 10. Labels and Cardinality

Allowed bounded labels:

- service, method, operation;
- ingress;
- stable error code/category;
- cache scope and cache result;
- fixed topic and consumer group;
- outbox phase/source/outcome;
- success/error/timeout/retry outcome.

Never use these as metric labels:

- ledger transaction ID;
- reference ID;
- account ID;
- command/event/idempotency key;
- raw exception or error message;
- SQL text;
- Redis key;
- arbitrary destination supplied by data.

Use logs or traces for per-request identifiers.

## 11. Code-Specific Measurement Caveats

These issues do not block metric installation, but they affect interpretation of phase-one results:

1. `PostLedgerProcessor` commits ledger rows before `DefaultListener` inserts the reply outbox. The business change and reply outbox are not in the same database transaction. Monitor ledger-created minus reply-outbox-created rates and test the crash window explicitly.
2. The listener's retry classification appears inverted: non-`ERROR_INTERNAL` replies are treated as retriable while `ERROR_INTERNAL` is sent to the non-retriable path. Retry and DLQ metrics will be misleading until this behavior is confirmed or corrected.
3. Outbox rows at `attempt_count >= maxRetries` stop being selected but are not changed to `DEAD`. Therefore `DEAD=0` does not imply health; `pending.exhausted` is mandatory.
4. INFO logging occurs per consumed message and includes the envelope/request. Disable or reduce per-message logging during capacity runs, otherwise log formatting and I/O become part of the measured service cost.
5. The current outbox page limit is `2` and dispatch runs every five seconds. Backlog-drain performance will primarily reflect these settings, so record them with every test result.

## 12. Minimal Dashboards and Alerts

### Ledger Capacity Dashboard

Show on one time axis:

- offered/completed TPS and client p50/p95/p99;
- gRPC server TPS, p50/p95/p99, active calls, transport status;
- ledger new-commit rate, idempotent replay rate, and business errors;
- ledger processing and DB transaction p95/p99;
- Hikari active/max/pending and acquisition p99;
- MySQL CPU, slow-query rate, row-lock wait, deadlock, I/O latency;
- Redis client p99, cache-result rates, Redis CPU/memory/evictions;
- Kafka inbound rate, consumer processing p99, lag total/max partition;
- reply producer p99/error/retry;
- outbox pending, oldest age, exhausted, and publish/finalization failures;
- process CPU, heap, allocation, GC pause, and threads.

### Initial Alert Conditions

Use sustained windows rather than single-scrape thresholds:

- business internal-error ratio above the agreed SLO;
- any outbox exhausted or dead row;
- oldest pending outbox age above the delivery SLO;
- outbox backlog or Kafka lag continuously growing;
- Hikari pending threads or acquisition timeouts;
- MySQL deadlock/lock-timeout increase;
- Redis eviction or client timeout increase;
- Kafka producer errors or DLQ records;
- sustained process/database CPU saturation or long GC pauses;
- metrics collector stale or failing.

## 13. Recommended Implementation Order

1. Define `spring.application.name=ledger-service`, standard common tags, histogram/SLO buckets, and verify current Actuator output.
2. Add MySQL, Redis, Kafka JMX, Kafka lag, and host/container exporters with pinned image versions and dedicated least-privilege credentials.
3. Add the gRPC server interceptor and ledger business outcome metrics.
4. Instrument `PostLedgerProcessor` for processing, database transaction, new commit, replay, and entry counts.
5. Instrument cache read outcomes, database fallbacks, idempotency outcomes, and client-side Redis latency/errors.
6. Instrument Kafka listener processing and reply publisher duration/outcomes; verify Kafka client binders.
7. Instrument outbox create/publish/finalize/retry phases and add scheduled backlog count/age/exhaustion collection.
8. Build the capacity dashboard, then run a low-rate validation test that reconciles metric counters with database row deltas before trusting a full load test.

## 14. Phase-One Acceptance Criteria

The phase-one observability setup is ready when:

- every Prometheus target is healthy and has an explicit service/instance identity;
- a controlled successful post increments request success and exactly one new-ledger counter;
- replaying the same request increments replay success without incrementing new-ledger creation;
- an invalid balanced-entry request produces the expected bounded business error code while gRPC transport may remain `OK`;
- a forced Redis failure records client errors and database fallback while the business path remains correct;
- a forced Kafka publish failure records the publish failure and increases pending age/count;
- an outbox retry success records retry publish plus finalization and drains the backlog;
- offered TPS, completed TPS, client latency, server latency, database time, Kafka lag, and resource saturation can be aligned on the same timeline;
- no metric contains transaction, account, reference, command, Redis-key, raw-SQL, or error-message labels.

With those corrections, the proposed plan is sufficient for a useful ledger phase-one capacity study and leaves a clean path to reuse the infrastructure patterns in `wallet-service` later.
