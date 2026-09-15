# Ledger JVM and DB Monitoring Change

## Scope

This change adds first-phase JVM and database correlation signals for ledger
write stress tests. It is limited to:

- client-observed DB transaction duration;
- client-observed MyBatis statement duration and errors;
- existing gRPC, business, JVM GC, and Hikari metrics;
- provisioned Grafana panels for correlation.

Redis dashboards/exporters, MySQL exporters, and new business-flow behavior
are excluded.

## Code Changes

### DB Transaction Timer

```text
zexchange.db.transaction.duration
type: Timer
tag: outcome = success | error
```

Transaction execution and measurement use separate components:

- `DbTxnExecutor` defines the application-facing contract.
- `DefaultDbTxnExecutor` owns transaction execution and rollback behavior.
- `MeteredDbTxnExecutor` decorates the default executor and records duration.

The registered application bean is the metered decorator. The undecorated
executor remains internal to configuration so business code cannot
accidentally bypass observability. The metric covers the full outer transaction
call, including connection acquisition, transaction begin, callback execution,
commit, and rollback.

- A successful committed `Result` records `success`.
- A failed `Result`, callback exception, or commit failure records `error`.
- Success and error timers are registered once and reused on the hot path.
- No SQL, request, transaction ID, or exception labels are introduced.

The timer currently aggregates all `DbTxnExecutor` calls for the selected
ledger instance. No transaction-name tag is added in this narrow phase.

## Dashboard Changes

The `Ledger Service Overview` dashboard adds a `JVM and Database` row:

| Panel | Signals | Meaning |
|---|---|---|
| Ledger Write gRPC Latency | successful p95/p99 | Shows transport-successful write latency. |
| Ledger Write Business Latency | successful p95/p99 | Shows successful processor latency. |
| DB Transaction Latency | successful p95/p99 | Shows full transaction-boundary latency. |
| DB Operation Latency | successful p95/p99 by statement | Locates slow MyBatis mapper operations. |
| DB Operation Errors | error rate by statement, command, and error type | Classifies statement failures without mixing them into the normal latency view. |
| JVM GC Pause | rolling maximum | Correlates GC pauses with latency spikes. |
| Hikari Connections | active, max, pending | Shows pool utilization and waiting callers. |
| Hikari Acquisition Latency | rolling average and max | Shows time spent obtaining a pooled connection. |
| Hikari Connection Timeouts | timeout rate | Shows requests that could not obtain a connection in time. |

All latency series are displayed in milliseconds and filtered by the selected
ledger instance.

The API and business overview sections retain their existing overall comparisons. The JVM and database correlation panels show successful p95/p99:

- gRPC `OK` means transport success and may still contain a protobuf business error.
- Business `success` represents a successful processor result.
- DB `success` represents a transaction that returned a successful `Result` and committed.

DB operation error latency remains recorded with `outcome="error"` for temporary diagnostic panels.

## Configuration And Deployment

- No dependency or Prometheus scrape change is required.
- JVM and Hikari metrics already come from the installed Actuator.
- Ledger already configures histogram buckets for
  `zexchange.db.transaction.duration` and `zexchange.db.operation.duration`.
- Ledger-service must be rebuilt/redeployed to emit the new DB timer.
- Grafana polls the provisioned dashboard directory every 30 seconds; the
  bind-mounted JSON does not require an image rebuild.

## Verification

Unit coverage separately verifies core transaction behavior and metric outcome
classification. Dashboard JSON is parsed during local verification.
