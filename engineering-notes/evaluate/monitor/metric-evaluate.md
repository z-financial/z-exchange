# Performance Metrics and Test Plan Evaluation

## 1. Executive evaluation

The proposed scenarios cover the main workload classes, and the proposed metrics are a good starting point. The plan is not yet sufficient for a reliable performance conclusion because it mixes synchronous API acceptance with asynchronous business completion, treats hot/normal wallets as standalone flows instead of workload dimensions, and omits several correctness and backlog measurements.

Recommended structure:

1. Test `ledger-service` in isolation: direct gRPC write, Kafka ingestion/reply, and reads.
2. Test `wallet-service` acceptance paths: reserve-only and atomic acceptance, with controlled downstream behavior.
3. Test the full wallet-ledger round trip.
4. Apply hot-wallet, distributed-wallet, cache-state, payload-size, and error/idempotency distributions to each applicable flow.

Do not publish a TPS result unless the run also proves money invariants, idempotency, outbox drainage, and eventual wallet completion.

## 2. Findings from the current code

### Existing observability

- Both services include Actuator and `micrometer-registry-prometheus`.
- Both expose `/actuator/prometheus`; Prometheus scrapes every 5 seconds.
- No custom `MeterRegistry`, `Timer`, `Counter`, `Gauge`, `@Timed`, or gRPC interceptor instrumentation exists.
- JVM, process, Hikari, and some Kafka client metrics may be auto-configured, but the actual `/actuator/prometheus` output must be checked before relying on them.
- `${spring.application.name}` is used as a metric tag, but no `spring.application.name` is defined in the checked-in service configuration.
- MySQL, Redis, Kafka broker, host, and container exporters are not in `docker-compose.yml`.
- The checked-in ledger and wallet configurations point to different Redis/Kafka addresses. A performance profile must use one controlled environment.

### Actual transaction flows

**Reserve-only is not `API -> Wallet DB -> Outbox`.**

`ReserveTransactionProcessor` calls `beforePostingLedger(..., false)`. The wallet transaction is marked `COMPLETED`, wallet/reservation rows are committed, and no outbox is created. The path also includes Redis idempotency and best-effort cache refresh.

**Atomic transfer API latency is not DB-only latency.**

The wallet commits its state and outbox in one DB transaction, then calls `DefaultPublisher.publish()`. That publisher waits for `KafkaTemplate.send(...).get(...)` for up to three seconds before the gRPC reply is returned. Therefore measure these separately:

- wallet DB acceptance duration;
- Kafka publish duration;
- total gRPC response duration;
- wallet acceptance-to-`COMPLETED` duration.

**Full completion is a two-message asynchronous flow.**

`Wallet -> wallet-post topic -> Ledger -> reply outbox -> ledger-reply topic -> Wallet -> COMPLETED`.

The wallet service has no public get-wallet-transaction API. Scenario D can query ledger transactions and wallet balance snapshots through gRPC, but wallet transaction status must currently be observed by a test probe/DB query or by application metrics.

### Correctness/reliability risks that affect a performance test

These should be fixed or explicitly isolated before using Scenario C for capacity claims:

1. **Ledger state and reply outbox are separate transactions.** `PostLedgerProcessor` commits the ledger transaction, then `ledger-service/.../DefaultListener` inserts the reply outbox. Redelivery plus DB idempotency can recover many crash cases, but this does not satisfy the project's same-transaction outbox invariant. Add a crash-window test between those operations.
2. **Ledger retry classification appears inverted.** In `ledger-service/.../DefaultListener`, a non-`ERROR_INTERNAL` result is treated as retriable while `ERROR_INTERNAL` is sent down the non-retriable path. This can retry business failures and DLQ transient internal failures.
3. **Outbox exhaustion never becomes `DEAD`.** Selection stops at `attempt_count >= maxRetries`, but no code transitions the record to `OutboxStatus.DEAD`. Such records remain `PENDING` and are no longer selectable. A `DEAD` gauge would stay zero while delivery is permanently stalled.
4. **The status names are `PENDING/SENT/DEAD`, not `NEW/SENT/DEAD`; wallet status is `PENDING/COMPLETED/CLOSED`, not `COMPLETE`.** Dashboards and SQL must use the actual enums.
5. **The main balance mutation path uses `SELECT ... FOR UPDATE`, application validation, and an equality-guarded update.** The SQL schema has no non-negative `CHECK` constraints. This differs from the stated DB atomic-guard invariant and materially affects hot-row test behavior.
6. **Per-message INFO logging is on hot Kafka paths.** Payload logging and scheduler logging can dominate allocation and I/O during a load test. Use a production-equivalent logging profile and record the setting with the result.
7. **Current client tests are not a performance harness.** They use busy waits, have no warmup or coordinated arrival model, do not record latency histograms, can wait forever after worker failure, and do not verify asynchronous completion or financial invariants.

## 3. Scenario evaluation and corrected definitions

| Scenario | Correct measured path | Primary result | Required variants |
|---|---|---|---|
| A. Reserve only | gRPC -> Redis idempotency -> wallet DB transaction -> cache refresh -> response | synchronous accepted/completed TPS and latency | success, insufficient funds, duplicate key; hot/distributed; cache warm/cold |
| B. Atomic transfer acceptance | gRPC -> Redis idempotency -> wallet DB + outbox commit -> blocking Kafka send attempt -> response | API acceptance TPS/latency and DB-accept latency | broker healthy/unavailable; hot/distributed; 2/N lines; duplicate key |
| C. Full async ledger flow | B plus ledger consume/write/reply and wallet completion | acceptance-to-completion latency, completion TPS, pending age/backlog | normal load, saturation, duplicate delivery, restart/retry recovery |
| D. Reads | ledger get by txn/ref, optional entries; wallet snapshot by wallet/ref | read TPS/latency and cache effectiveness | hit/miss/negative/tombstone; warm/cold; with/without entries; read-after-write |
| E. Hot wallet/snapshot | contention distribution applied to A, B, C, and balance reads | single-key capacity and tail latency | 100%, 80/20, and explicit system-account concentration |
| F. Distributed wallets | key distribution applied to A, B, C, and reads | scalable capacity | uniform or production-like Zipf distribution across a stated cardinality |

E and F are workload dimensions, not independent business scenarios. Also add these dimensions to the test matrix:

- entry/line count: 2, 10, and expected maximum;
- cache state: warm, cold, negative hit, Redis unavailable;
- request mix: success, expected business rejection, duplicate retry, malformed request;
- arrival rate: below capacity, ramp, saturation, and recovery;
- data size: small and production-representative table/index size.

## 4. Recommended ledger-first execution plan

### Phase 0: environment and correctness baseline

- Use a dedicated load generator on a different host/process from the services.
- Pin image/software versions and fixed CPU/memory limits; do not use `latest` images.
- Externalize all endpoints and confirm both services use the same Kafka/Redis environment.
- Preload production-representative accounts, transactions, entries, wallets, mappings, balances, and indexes.
- Record JVM flags, heap, DB buffer pool, Hikari size, Kafka partitions, listener concurrency, Redis pool size, log level, and commit SHA.
- Run a functional invariant suite before load.

### Phase 1: ledger direct API

1. Benchmark `postTransaction` through gRPC with unique reference IDs.
2. Run an explicit duplicate/idempotency workload separately; do not mix it into headline write TPS.
3. Benchmark `getTxnById` and `getTxnByRefId`, each with `includeEntries=false/true` and controlled entry counts.
4. Separate cold-cache, warm-cache, cache-miss, and Redis-down results.

This isolates ledger validation, Redis idempotency, account lookup, and DB insert performance without Kafka.

### Phase 2: ledger Kafka path

1. Produce valid `POST_LEDGER` envelopes directly to the wallet-post topic.
2. Consume replies with a measurement probe from the reply topic.
3. Measure ingress rate, ledger consumer processing, ledger commit rate, reply outbox creation/publish, reply rate, lag, retries, DLQ, and end-to-end envelope latency.
4. Confirm every accepted command has exactly one ledger transaction and a reply, allowing duplicate deliveries but no duplicate effects.

### Phase 3: wallet acceptance paths

1. Reserve-only with no dependency on ledger.
2. Atomic transfer with Kafka healthy and ledger consumption paused to isolate wallet acceptance. Bound the run so intentional `PENDING` rows do not exhaust storage.
3. Drain or recreate the dataset between runs; do not carry a large backlog into later tests.
4. Test hot and distributed key distributions at the same offered rates.

### Phase 4: full system

1. Run atomic and reservation-consume flows with both services enabled.
2. Measure both API acceptance and business completion.
3. End the steady-state load, then continue observing until queues/outboxes drain or a fixed completion SLO expires.
4. Run failure recovery separately: Redis outage, Kafka outage, consumer restart/rebalance, service crash in outbox windows, duplicate delivery, and DB lock timeout/deadlock.

Do not combine chaos and baseline capacity in one headline number.

## 5. Robust load-test protocol

- Prefer an open arrival-rate model for capacity tests; a closed loop can hide queue growth as latency rises.
- Use explicit stages: 5-10 minute warmup, 15-30 minute steady state, then cooldown/drain. Use at least three repeat runs.
- Set client deadlines and count timeout, transport error, business rejection, and internal failure separately.
- Generate deterministic unique idempotency keys. Run duplicate-key behavior as a separate controlled percentage.
- Capture offered TPS and achieved TPS. "Successful TPS" alone hides load-generator or queue throttling.
- Keep the load generator below 50% CPU saturation and monitor its network and connection limits.
- Avoid per-request DB polling for Scenario C. Record completion in the wallet consumer and validate statuses in batch after the run.
- Reset or version test data between runs. Cache-warm runs must use the same documented warmup procedure.
- Report confidence/range across repeats, not only the best run.

### Post-run correctness gates

A run is invalid if any of these fail:

- per ledger transaction and asset, debit sum equals credit sum;
- one effect per idempotency key and matching request hash;
- accepted ledger-required wallet transactions reach `COMPLETED` within the completion SLO;
- no unexpected or exhausted `PENDING` outbox rows and no unexplained DLQ records;
- balance amounts are non-negative;
- reservation `total = remaining + consumed + pending_settle + released`;
- expected aggregate funds are conserved;
- all queues drain after offered load stops.

## 6. Metric coverage evaluation

### Keep, but define precisely

- **successful TPS:** successful business responses per second at the client and service; for Scenario C also completed transactions per second.
- **failed TPS:** split into transport, timeout, internal/infrastructure, expected business rejection, and idempotency conflict.
- **p50/p95/p99:** report client API latency, server API latency, DB acceptance phase, and async completion separately.
- **error rate:** never derive it only from gRPC status because the services return business errors inside protobuf replies while transport status remains OK.
- **DB CPU, pool usage, row lock wait:** retain all three, with server-side DB metrics and application-side Hikari metrics.
- **Redis latency:** split client-observed command time from Redis server latency.
- **Kafka producer latency and consumer lag:** retain; add producer errors/retries and consumer processing latency.
- **JVM GC time:** retain; use pause duration/count and allocation/heap metrics.
- **thread pool queue size:** retain only for executors that actually have queues and are owned/instrumented. Kafka consumer threads do not expose a conventional task queue.
- **outbox and wallet status:** use actual enum names and avoid expensive full-table counts on every Prometheus scrape.

### Missing high-priority metrics

**Business lifecycle**

- wallet acceptance-to-completion latency histogram;
- accepted TPS versus completed TPS;
- oldest `PENDING` wallet transaction age;
- pending completion backlog and backlog growth rate;
- idempotency claim outcome: new, duplicate accepted, in-progress, hash conflict, Redis fallback/error;
- business rejection counters by bounded error category.

**Outbox/Kafka**

- outbox created, immediate publish success/failure, retry, and dead transition counters;
- current pending count, exhausted-pending count, and oldest pending age;
- dispatcher duration, selected batch size, claim failures, finalization failures;
- Kafka send errors, retries, record queue time, request latency, batch size, compression ratio;
- consumer records/sec, processing duration, failures/retries/DLQ, rebalance count, assigned partitions;
- topic partition lag, not only group-wide aggregate lag.

**Database**

- DB transaction duration, commit/rollback/deadlock/lock-timeout rates;
- Hikari active, idle, pending, max, acquire time, and timeout count;
- query/phase latency for account lookup, ledger insert, balance lock/update, and completion update;
- InnoDB buffer pool hit/read activity, disk I/O latency/IOPS, rows examined/updated, temporary tables, and slow query count;
- optimistic/equality guard conflict rate and `SELECT FOR UPDATE` wait duration on hot wallets.

**Cache/Redis**

- value hit, negative hit, tombstone, miss, decode failure, fallback-to-DB, write/CAS success/failure;
- client command error/timeout and pool saturation;
- client-side cache hit/miss for promoted hot snapshots;
- promotion count and hot/normal strategy selection.

**JVM/host**

- process/system CPU, heap/non-heap, allocation rate, live threads, blocked threads, class loading;
- GC pause count/duration and max pause, not only cumulative GC time;
- host/container CPU throttling, memory, disk, network, and file descriptors;
- gRPC in-flight requests and active connections.

## 7. Implementation approach

### A. Configuration and automatic binders

Keep Actuator/Prometheus and add explicit service names plus histogram configuration. Prefer Prometheus histograms for aggregate p50/p95/p99; client-side percentiles cannot be correctly aggregated across instances.

```yaml
spring:
  application:
    name: ledger-service # wallet-service in wallet config

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  metrics:
    tags:
      application: ${spring.application.name}
      environment: perf
    distribution:
      percentiles-histogram:
        zexchange.grpc.server.duration: true
        zexchange.ledger.post.duration: true
        zexchange.wallet.accept.duration: true
        zexchange.wallet.completion.duration: true
      slo:
        zexchange.grpc.server.duration: 5ms,10ms,25ms,50ms,100ms,250ms,500ms,1s,3s
        zexchange.wallet.completion.duration: 50ms,100ms,250ms,500ms,1s,2s,5s,10s,30s
```

Verify these families at `/actuator/prometheus` rather than assuming they exist:

- `jvm_*`, `process_*`, `system_*`;
- `hikaricp_connections_*` and acquire/usage/creation timers;
- Kafka producer/consumer client metrics and Spring Kafka listener timers.

If Spring Boot does not bind the custom Kafka factories automatically, register Micrometer producer/consumer listeners explicitly and enable Micrometer on the listener container. Pin dashboard queries to the names actually exposed by this Spring Boot 2.7/Spring Kafka version.

### B. Annotation-based timing

Use `@Timed` only for stable internal phase names where dynamic business outcome tags are not required, for example ledger post processing, balance snapshot lookup, and outbox dispatcher execution.

Register the aspect:

```java
@Bean
TimedAspect timedAspect(MeterRegistry registry) {
    return new TimedAspect(registry);
}
```

Example:

```java
@Timed(value = "zexchange.ledger.post.duration", histogram = true)
public PostTransactionReplyPb postTransaction(PostTransactionRequestPb request) {
    // existing implementation
}
```

Do not put wallet IDs, transaction IDs, account IDs, reference IDs, exception messages, or idempotency keys in tags. Annotation timing alone is insufficient because it cannot reliably classify protobuf business outcomes.

### C. Manual Micrometer instrumentation

Create service-owned `LedgerMetrics` and `WalletMetrics` components. Keep generic cache/outbox/Kafka infrastructure metrics in `common-util`; do not move wallet or ledger domain decisions there.

Use bounded tags such as:

- `service`: wallet, ledger;
- `operation`: reserve, atomic, apply_reservation, ledger_post, get_snapshot, get_ledger_txn;
- `ingress`: grpc, kafka;
- `outcome`: success, business_reject, internal_error, timeout;
- `error_category`: validation, insufficient_funds, idempotency_conflict, dependency, internal;
- `cache_result`: value_hit, negative_hit, tombstone, miss, error;
- `status` and `event_type`: enum values only.

Recommended custom meters:

| Meter | Type | Instrumentation point |
|---|---|---|
| `zexchange.grpc.server.duration` | Timer | global gRPC server interceptor; method and transport status |
| `zexchange.business.requests` | Counter | service reply construction; protobuf business outcome |
| `zexchange.wallet.accept.duration` | Timer | around wallet pre-ledger processing, with DB phase recorded separately |
| `zexchange.wallet.completion.duration` | Timer | in `AfterPostLedgerProcessor`, from persisted wallet `created_at` to successful `COMPLETED` commit |
| `zexchange.wallet.completions` | Counter | successful/failed finalization transition |
| `zexchange.ledger.post.duration` | Timer | `PostLedgerProcessor`, tagged by gRPC/Kafka caller through an explicit context/tag |
| `zexchange.kafka.publish.duration` | Timer | existing publisher around the blocking send result |
| `zexchange.kafka.consumer.duration` | Timer | each listener parse/process/ack path |
| `zexchange.outbox.events` | Counter | create, immediate publish, retry, finalize, dead |
| `zexchange.outbox.dispatch.duration` | Timer | `OutboxRetryHandler.retry()` |
| `zexchange.outbox.dispatch.batch` | DistributionSummary | records claimed/published per run |
| `zexchange.cache.operations` | Counter/Timer | generic cache strategy get/write/CAS/fallback paths |
| `zexchange.idempotency.operations` | Counter/Timer | claim/get/mark/release outcomes |
| `zexchange.db.transaction.duration` | Timer | named wallet/ledger transaction boundaries, not only generic `DbTxnExecutor` |

For async completion, do not keep `Timer.Sample` objects in memory across Kafka hops. Compute elapsed time from the persisted wallet transaction `created_at` when the reply successfully changes the row to `COMPLETED`. This survives retries and service restarts.

Use a global gRPC interceptor for transport latency/in-flight requests, but record protobuf error codes in the service methods or a response-wrapping interceptor. The transport status alone reports these business failures as successful RPCs.

### D. State gauges without scrape-time DB scans

Do not execute full `COUNT(*)` queries inside a Prometheus gauge callback. Update `AtomicLong` gauge values from a scheduled collector every 15-30 seconds, or use transition counters plus periodic external SQL validation.

Required states:

- outbox current `PENDING`, `SENT` if operationally useful, `DEAD`, and exhausted-pending;
- oldest pending outbox age;
- wallet current `PENDING`, `COMPLETED`, `CLOSED` and oldest pending age.

The outbox composite index supports grouping by `event_type, outbox_status`, but wallet transactions have no status/age index. Add an appropriate `(txn_status, created_at, id)` index before frequent status/age collection, or keep those counts in an external test collector. Prefer counters/rates for historical `SENT` and `COMPLETED` throughput because those table counts grow forever.

### E. Middleware and host integration

Application annotations cannot provide DB CPU, Redis server latency, or broker lag. Add exporters:

| Component | Integration | Key measurements |
|---|---|---|
| MySQL | `mysqld_exporter` plus Performance Schema | connections, InnoDB row lock waits/time, deadlocks, buffer pool, engine I/O, slow queries |
| Redis | `redis_exporter` plus client-side timers | command duration/count, ops/sec, blocked/connected clients, memory/evictions; app-observed latency/timeouts |
| Kafka broker | JMX Exporter | request/produce/fetch latency, bytes/records, under-replicated partitions, broker errors |
| Kafka consumer groups | Kafka Exporter or equivalent lag exporter | lag by group/topic/partition |
| JVM/application | Actuator/Micrometer | JVM, process, Hikari, custom business timers/counters |
| Host/container | node_exporter and cAdvisor/OS collector | DB/service CPU, CPU throttling, memory, disk, network, file descriptors |

Instrument an executor queue only when the application owns a bounded `ThreadPoolExecutor`. Use `ExecutorServiceMetrics.monitor(...)` for that executor. For the gRPC server, expose in-flight calls and configure/instrument its actual executor; for Kafka listeners, prefer consumer lag, processing duration, active consumer count, and poll metrics over a nonexistent generic queue gauge.

## 8. Dashboard and query semantics

Use the same time window and labels for all panels. Example semantics after Micrometer name normalization:

```promql
# Accepted business TPS
sum by (application, operation) (
  rate(zexchange_business_requests_total{outcome="success"}[1m])
)

# Internal/transport error rate; show business rejection separately
sum(rate(zexchange_business_requests_total{outcome=~"internal_error|timeout"}[5m]))
/
sum(rate(zexchange_business_requests_total[5m]))

# p99 server latency
histogram_quantile(
  0.99,
  sum by (le, application, operation) (
    rate(zexchange_grpc_server_duration_seconds_bucket[5m])
  )
)

# Accepted minus completed rate: early sign of async backlog growth
sum(rate(zexchange_business_requests_total{operation="atomic",outcome="success"}[5m]))
-
sum(rate(zexchange_wallet_completions_total{outcome="success"}[5m]))
```

The primary Scenario C dashboard should show offered TPS, accepted TPS, completed TPS, API p99, completion p99, wallet pending count/age, both outbox pending count/age, Kafka lag, DB lock wait, pool pending, and error/retry/DLQ rates on one timeline.

## 9. Implementation order

1. Fix/decide the ledger reply-outbox transaction boundary, retry classification, and outbox `DEAD` transition.
2. Add explicit service names and verify the existing actuator endpoint output.
3. Add gRPC boundary metrics and protobuf business outcome counters.
4. Add ledger post/read and Kafka consumer/producer phase timers.
5. Add outbox transition counters, backlog/age collectors, and dispatcher metrics.
6. Add wallet acceptance/completion metrics and status/age collection.
7. Add cache/idempotency result metrics.
8. Integrate MySQL, Redis, Kafka, and host exporters.
9. Build the ledger load suite, validate it, then build wallet and full-flow suites.

## 10. Final verdict

Proceed with the ledger-first approach, but do not treat the original six scenarios as a complete test model. Correct Scenario A, split Scenario B acceptance phases, make async completion a first-class latency/TPS measurement, and apply E/F as contention distributions across the flows. The original metric list covers basic RED and resource signals; adding completion/backlog age, error classification, correctness gates, cache behavior, retry/DLQ, DB transaction/lock detail, and infrastructure exporters makes it adequate for a defensible performance evaluation.
