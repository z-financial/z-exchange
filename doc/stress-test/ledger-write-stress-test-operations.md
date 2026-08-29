# Ledger Write Stress-Test Operations

## 1. Scope

This runbook operates the k6 gRPC write test implemented from
`doc/ledger-write-stress-test-flow.md`. It calls only
`PostService/postTransaction` and supports these profiles:

- `discovery`: find the sustainable-capacity knee.
- `recovery`: overload the running server and then reduce traffic without a
  restart.
- `soak`: hold 80% of the discovered sustainable rate for two minutes.

The test creates ledger journal rows directly through gRPC. It does not test
the Kafka listener, reply outbox, wallet balances, or hot-account contention.

## 2. Files And Execution Flow

| File | Responsibility |
| --- | --- |
| `stress-test/scripts/ledger-grpc-write-stress.js` | Generates deterministic balanced writes, duplicates, workload profiles, and client metrics. |
| `stress-test/fixtures/ledger-write-fixture.sql` | Idempotently creates 10 assets and 100 distributed accounts. |
| `stress-test/fixtures/ledger-write-verification.sql` | Reconstructs expected committed entries and checks payload and double-entry integrity. |
| `stress-test/run-write-stress.sh` | Waits for MySQL, provisions fixtures, runs k6, and executes SQL reconciliation. |
| `stress-test/Dockerfile` | Packages k6, MySQL client, protobuf files, scripts, and fixtures. |
| `deploy/stress-test/docker-compose.ledger-write-stress-test.yml` | Runs the write entrypoint and supplies environment configuration. |

The container flow is:

```text
wait for MySQL
  -> upsert 10 assets and 100 accounts
  -> generate/export a run ID
  -> run the selected k6 profile
  -> stop new traffic and wait for k6 graceful stop
  -> reconstruct expected entries for committed references
  -> verify entry payload and debit/credit equality
  -> exit nonzero on k6 threshold or SQL reconciliation failure
```

Fixture setup and reconciliation occur outside k6, so neither is included in
gRPC latency.

## 3. Workload Details

The entry-count distribution is deterministic over every 200 new logical
transactions. By default:

```text
45%: 2 entries
45%: 4 entries
10%: 20 entries
```

Set `LEDGER_STRESS_BIG_TXN_PERCENT` to a whole-number percentage from `0` to
`100`. That percentage uses 20 entries; the remaining transactions are split
equally between 2 and 4 entries.

Every transaction uses one asset and balanced debit/credit pairs. Asset and
account selection rotate using the logical sequence. This spreads traffic over
all 10 assets and 100 accounts.

Every complete cycle of 95 logical transactions adds five duplicate attempts:

```text
95 original attempts + 5 duplicate attempts = 100 RPC attempts
```

One duplicate group has one concurrent and one delayed duplicate. Another has
two concurrent and one delayed duplicate. The delayed attempt waits one gRPC
deadline by default after the immediate group completes.

The arrival-rate executor schedules **logical transactions per second**. The
actual offered RPC rate is approximately:

```text
logical arrival rate * 100 / 95
```

Always use `ledger_write_offered`, rather than only the configured scenario
target, when reporting actual server traffic.

`REQUEST_IN_PROCESSING` is accepted only for the deliberately concurrent
duplicate group. It is counted separately and excluded from the unexpected
error rate. The delayed duplicate must return the persisted result; a delayed
`REQUEST_IN_PROCESSING` is unexpected.

## 4. Prerequisites

Before running:

1. Deploy ledger-service, infrastructure, and monitoring.
2. Confirm the stress-test EC2 can reach ledger gRPC port `9191`, MySQL port
   `3306`, and Prometheus remote-write port `9290` over the VPC.
3. Confirm Prometheus scrapes ledger-service and accepts remote writes.
4. Confirm no earlier ledger stress request is active.
5. Run the existing smoke test once.
6. Ensure the MySQL user can insert/update `assets` and `accounts`, create
   temporary tables, and select ledger tables.

Use test infrastructure only. The fixture SQL writes dedicated `k6-write-*`
records into the configured ledger database.

Set `LEDGER_STRESS_TRUNCATE_DB_BEFORE_RUN=true` to delete all ledger-service
table rows before provisioning fixtures. The default is `false`. This includes
outbox rows and requires MySQL `DROP` privilege, so enable it only for an
isolated test database with no concurrent business traffic.

## 5. Common Command

Run commands from the repository root on the stress-test EC2:

```bash
COMPOSE_MENU=false COMPOSE_ANSI=never \
docker compose --progress plain \
  -f deploy/stress-test/docker-compose.ledger-write-stress-test.yml up --build \
  --abort-on-container-exit \
  --exit-code-from ledger-write-stress-test
```

Set variables before the command with shell exports or an environment file.
At minimum, supply the VPC endpoints and real database password:

```bash
export LEDGER_GRPC_HOST=172.31.16.37
export INFRA_PRIVATE_IP=172.31.18.211
export LEDGER_DB_USERNAME=ledger
export LEDGER_DB_PASSWORD='replace-with-the-infrastructure-value'
```

The runner creates a UTC timestamp run ID when `LEDGER_STRESS_RUN_ID` is empty.
For repeatable dashboard filtering, set it explicitly using at most 24 letters,
digits, or hyphens:

```bash
export LEDGER_STRESS_RUN_ID=discovery-20260731-01
```

Do not reuse a run ID. Reuse would make the requests idempotent replays and
merge SQL and Prometheus results from separate runs.

## 6. Capacity Discovery

The default profile starts at 5 logical transactions per second and adds 10
every 30 seconds for eight steps. Adjust the ceiling before starting if this
cannot cross the expected saturation knee:

```bash
export LEDGER_STRESS_PROFILE=discovery
export LEDGER_STRESS_RUN_ID=discovery-20260731-01
export LEDGER_STRESS_WARM_RATE=5
export LEDGER_STRESS_STAGE_SECONDS=30
export LEDGER_STRESS_DISCOVERY_RATE_STEP=10
export LEDGER_STRESS_DISCOVERY_STEPS=8
```

Watch Grafana during the run. Record the last stable actual offered TPS before
completed TPS flattens or overall/successful p95 rises sharply. Repeat discovery
at least three times, using smaller rate steps around the candidate knee.

Do not configure strict p95/error thresholds for discovery unless deliberately
required. This profile is expected to cross into overload.

## 7. Overload Recovery

Set `LEDGER_STRESS_SUSTAINABLE_RATE` to the sustainable **logical transaction
rate** inferred from discovery. If discovery was recorded as actual offered RPC
TPS, multiply it by `0.95` before configuring this value.

```bash
export LEDGER_STRESS_PROFILE=recovery
export LEDGER_STRESS_RUN_ID=recovery-20260731-01
export LEDGER_STRESS_SUSTAINABLE_RATE=75
```

The profile runs, in one continuous server session:

```text
30s warm-up
30s ramp to 80%
30s hold at 80%
30s ramp to 115%
30s hold at 115%
30s reduce to 60%
60s recovery observation at 60%
30s ramp to zero
30s idle observation in k6 teardown
```

Verify that completed TPS follows offered TPS again, p95 and unexpected errors
return near baseline, and active gRPC requests return to baseline.

## 8. Stable-Load Soak

```bash
export LEDGER_STRESS_PROFILE=soak
export LEDGER_STRESS_RUN_ID=soak-20260731-01
export LEDGER_STRESS_SUSTAINABLE_RATE=75
export LEDGER_STRESS_SOAK_SECONDS=120
```

This runs a 30-second warm-up, ramps to 80% of the sustainable logical rate,
holds it for two minutes, drains to zero, and observes 30 idle seconds in k6
teardown. After initial validation, increase `LEDGER_STRESS_SOAK_SECONDS` for
leak, GC, and periodic-job testing.

Optional acceptance thresholds are most useful for recovery and soak:

```bash
export LEDGER_STRESS_MAX_UNEXPECTED_ERROR_RATE=0.01
export LEDGER_STRESS_MAX_P95_MS=500
```

If either threshold fails, k6 exits nonzero after still allowing SQL
reconciliation to run.

## 9. Configuration Reference

| Variable | Default | Meaning |
| --- | ---: | --- |
| `LEDGER_STRESS_PROFILE` | `discovery` | `discovery`, `recovery`, or `soak` |
| `LEDGER_STRESS_RUN_ID` | UTC timestamp | Unique run and Prometheus `testid` |
| `LEDGER_STRESS_TRUNCATE_DB_BEFORE_RUN` | `false` | When `true`, truncate all ledger-service tables before fixture setup |
| `LEDGER_STRESS_WARM_RATE` | `5` | Initial logical transactions/s |
| `LEDGER_STRESS_STAGE_SECONDS` | `30` | Standard stage duration |
| `LEDGER_STRESS_DISCOVERY_RATE_STEP` | `10` | Logical rate added per discovery stage |
| `LEDGER_STRESS_DISCOVERY_STEPS` | `8` | Discovery increments after warm-up |
| `LEDGER_STRESS_SUSTAINABLE_RATE` | `50` | Discovered sustainable logical rate |
| `LEDGER_STRESS_RECOVERY_OBSERVATION_SECONDS` | `60` | Reduced-load recovery hold |
| `LEDGER_STRESS_SOAK_SECONDS` | `120` | Stable-load hold |
| `LEDGER_STRESS_PREALLOCATED_VUS` | `100` | VUs allocated before load starts |
| `LEDGER_STRESS_MAX_VUS` | `500` | Maximum VUs k6 may allocate |
| `LEDGER_DUPLICATE_DELAY_SECONDS` | `5` | Delay after immediate duplicate group |
| `LEDGER_STRESS_BASE_AMOUNT` | `100` | Minimum entry amount in minor units |
| `LEDGER_STRESS_AMOUNT_SPAN` | `900` | Deterministic amount variation range |
| `LEDGER_STRESS_BIG_TXN_PERCENT` | `10` | Percentage of 20-entry transactions; the remainder is split equally between 2 and 4 entries |
| `LEDGER_STRESS_MAX_UNEXPECTED_ERROR_RATE` | unset | Optional k6 rate threshold |
| `LEDGER_STRESS_MAX_P95_MS` | unset | Optional overall attempt p95 threshold |
| `LEDGER_GRPC_DEADLINE_SECONDS` | `5` | Per-RPC deadline |

Database, endpoint, VU, and Prometheus variables are defined in
`deploy/stress-test/docker-compose.ledger-write-stress-test.yml`.

Delayed duplicate iterations sleep while the open model continues scheduling
new work. Increase preallocated/max VUs if k6 reports insufficient VUs or
dropped iterations. Do not interpret a run with generator-side dropped
iterations as a valid server-capacity result.

## 10. Grafana And PromQL

Grafana provisions the **Ledger Stress Test Overview** dashboard from
`monitoring/grafana/dashboards/ledger-stress-test-overview.json`. Select one
`test run` and `test profile` before interpreting a result. Its primary panels
combine offered, completed, and successful RPC TPS, and compare overall with
successful p95/p99. Unexpected error rate and active ledger gRPC requests are
shown beside them.

Filter every k6 query by `testid`. Example queries below use `$testid` as a
Grafana variable.

Actual offered RPC TPS:

```promql
sum(rate(k6_ledger_write_offered_total{testid="$testid"}[15s]))
```

Completed RPC TPS:

```promql
sum(rate(k6_ledger_write_completed_total{testid="$testid"}[15s]))
```

Successful response TPS:

```promql
sum(rate(k6_ledger_write_successful_total{testid="$testid"}[15s]))
```

Overall attempt p95 latency:

```promql
1000 * k6_ledger_write_request_duration_p95{
  testid="$testid",
  operation="postTransaction"
}
```

Successful attempt p95 latency:

```promql
1000 * k6_ledger_write_success_duration_p95{
  testid="$testid",
  operation="postTransaction"
}
```

Use the corresponding `_p99` series for p99. Successful latency contains
validated new and idempotent successes; overall latency contains every attempt.
Neither primary Trend is split by workload or outcome tags, so no `max()`
aggregation is required.

The Prometheus remote-write values for time Trends are in seconds. Multiplying
by `1000` converts them to milliseconds for the dashboard's `ms` unit.

Unexpected error rate:

```promql
max(k6_ledger_write_unexpected_error_rate{
  testid="$testid"
})
```

Expected and unexpected processing responses:

```promql
sum by (expected) (
  rate(k6_ledger_write_request_in_processing_total{
    testid="$testid"
  }[15s])
)
```

Dropped iterations:

```promql
sum(rate(k6_dropped_iterations_total{testid="$testid"}[15s]))
```

Ledger active gRPC requests:

```promql
sum(zexchange_grpc_server_active{
  application="ledger-service",
  service="ledger.PostService",
  method="postTransaction"
})
```

The Prometheus remote-write adapter exports counters with `k6_` and `_total`
and exports the configured Trend statistics as `_p95`, `_p99`, and `_max`.
Confirm the exact ledger `service` label in Prometheus before saving the active
request panel.

## 11. Terminal And SQL Results

The maximum duration printed by k6 includes `gracefulStop`. If all scheduled
stages finish and no iterations remain in flight, k6 can finish without using
the reserved grace period, so elapsed time can be lower than that maximum.
Each profile contains one final ramp-to-zero stage. The subsequent idle
observation runs in `teardown()` because a redundant zero-to-zero arrival-rate
stage has nothing to schedule and may be rendered by k6 as an interrupted
scenario. The test configures `teardownTimeout` to the observation duration plus
a small scheduling margin; this is required when
`LEDGER_STRESS_STAGE_SECONDS=60`, because k6's default teardown timeout is also
60 seconds. The runner's printed k6 exit status remains the authoritative
process result; `0 interrupted iterations` separately confirms that no active
iteration was forcibly cut off.

The runner prints the k6 end summary, followed by output similar to:

```text
k6 finished with status 0; starting ledger reconciliation...
persisted transactions=1234
persisted entries=5678
0
Ledger write stress run and reconciliation completed successfully.
```

The final zero is the SQL mismatch count. A nonzero value is preceded by one or
more categories:

- `unbalanced_transaction_asset`
- `missing_or_different_expected_entry`
- `unexpected_persisted_entry`

The reconciliation checks only transactions that committed for the current
run ID. Transport timeouts remain ambiguous at the client boundary; inspect
sampled k6 failure logs and query `getTxnByRefId` or MySQL when individual
resolution is needed. Do not assume that a timed-out write rolled back.

The automatic SQL check verifies:

- Debit equals credit per transaction and asset.
- Persisted asset, account, direction, amount, and multiplicity match the
  deterministic request algorithm.
- Direction comparison uses ledger database enum codes (`DEBIT=1`,
  `CREDIT=2`), which differ from the protobuf enum numbers.
- Duplicate attempts did not create an alternative transaction for the same
  reference; the database unique reference constraint provides the final
  durable guard.

It deliberately does not verify account balances because ledger-service does
not maintain them.

## 12. Cleanup And Result Retention

After collecting logs:

```bash
docker compose \
  -f deploy/stress-test/docker-compose.ledger-write-stress-test.yml down
```

Retain these together:

- Run ID and profile variables
- Git revision and deployment sizing
- k6 terminal summary and container exit code
- Grafana time range or dashboard snapshot
- SQL reconciliation output
- Relevant sampled application errors

Fixture and ledger rows are intentionally retained for audit. Remove them only
through an explicitly reviewed test-data cleanup procedure.
