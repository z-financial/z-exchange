# Wallet-Service Stress-Test Operations

Run one API operation per test so throughput, latency, and errors remain
attributable to that operation.

## Workloads

| Test | Selector |
| --- | --- |
| Atomic write | `WALLET_WRITE_OPERATION=atomic` |
| Reserve write | `WALLET_WRITE_OPERATION=reserve` |
| Apply release | `WALLET_WRITE_OPERATION=apply-release` |
| Apply earmark/consume | `WALLET_WRITE_OPERATION=apply-earmark` |
| Query by wallet ID | `WALLET_QUERY_LOOKUP=wallet-id` |
| Query by reference ID | `WALLET_QUERY_LOOKUP=ref-id` |

Write profiles are `discovery` and `soak`. Query profiles are
`rpc-discovery`, `working-set-discovery`, and `soak`.

## Write Command

```bash
export WALLET_STRESS_RUN_ID=wwrite01
export WALLET_WRITE_OPERATION=atomic
export WALLET_STRESS_PROFILE=discovery
export WALLET_STRESS_WALLET_COUNT=100
export WALLET_STRESS_DUPLICATE_PERCENT=5

docker compose --progress plain \
  -f deploy/stress-test/docker-compose.wallet-write-stress-test.yml up --build \
  --abort-on-container-exit --exit-code-from wallet-write-stress-test
```

The runner calculates fixture request capacity and balance headroom from the
load profile. Atomic and apply-earmark runs wait for asynchronous ledger
processing before wallet and ledger reconciliation.

Write rates count new logical transactions. Identical duplicate attempts are
additional RPCs; use `wallet_write_offered` for the actual offered RPC count.

## Query Command

```bash
export WALLET_QUERY_RUN_ID=wquery01
export WALLET_QUERY_LOOKUP=wallet-id
export WALLET_QUERY_PROFILE=rpc-discovery
export WALLET_QUERY_FIXTURE_SIZE=10000
export WALLET_QUERY_WORKING_SET_SIZE=1000
export WALLET_QUERY_HIT_RATE_PERCENT=95
export WALLET_QUERY_MISS_PATTERN=repeated
export WALLET_QUERY_MISS_CARDINALITY_PERCENT=10
export WALLET_QUERY_OWNER_TYPE=mixed
export WALLET_QUERY_SYSTEM_PERCENT=25

docker compose --progress plain \
  -f deploy/stress-test/docker-compose.wallet-query-stress-test.yml up --build \
  --abort-on-container-exit --exit-code-from wallet-query-stress-test
```

For a cold-miss run, the configured miss-key pool must be at least the total
number of expected misses, otherwise the run fails the cold-miss reuse
threshold. SYSTEM and USER fixtures exercise different wallet cache paths.

## Reset Configuration

| Variable | Default | Allowed values | Explanation |
| --- | --- | --- | --- |
| `WALLET_STRESS_TRUNCATE_DB_BEFORE_RUN` | `false` | `true`, `false` | Truncate test databases before provisioning. Write runs truncate wallet and ledger tables; query runs truncate wallet tables only. Use only in an isolated environment. |
| `WALLET_STRESS_TRUNCATE_CACHE_BEFORE_RUN` | `false` | `true`, `false` | Remove wallet snapshot and wallet idempotency Redis keys before provisioning. This uses scoped `SCAN` plus `UNLINK`, not `FLUSHDB`. |

When truncating the database, also clear the cache or restart wallet-service so
cached snapshots cannot outlive deleted database rows.

## Shared gRPC Configuration

| Variable | Default | Explanation |
| --- | --- | --- |
| `WALLET_GRPC_HOST` | `172.31.23.255` | Wallet-service gRPC host reached by k6. |
| `WALLET_GRPC_PORT` | `9192` | Wallet-service gRPC port. |
| `WALLET_GRPC_DEADLINE_SECONDS` | `5` | Positive integer timeout for each gRPC call. Also contributes to k6 graceful-stop time. |

## Write Configuration

### Workload and fixtures

| Variable | Default | Allowed values or limits | Explanation |
| --- | --- | --- | --- |
| `WALLET_STRESS_RUN_ID` | Generated UTC `MMDDHHMMSS` | 1-12 letters, digits, or hyphens | Identifies the run in fixture IDs, request IDs, logs, and metrics. Set it explicitly for easier correlation and use a new value for each run. |
| `WALLET_WRITE_OPERATION` | `atomic` | `atomic`, `reserve`, `apply-release`, `apply-earmark` | Selects the only transaction workload executed by the run. Apply-release and apply-earmark fixtures include pre-created reservations. |
| `WALLET_STRESS_PROFILE` | `discovery` | `discovery`, `soak` | Selects a stepped rate-discovery run or a sustained soak run. |
| `WALLET_STRESS_WALLET_COUNT` | `100` | 1-5,000,000 | Number of fixture wallets. A small value creates a hot-wallet test; a large value distributes traffic. Atomic and apply-earmark require at least two wallets. |
| `WALLET_STRESS_FIXTURE_REQUESTS` | Calculated | 1-5,000,000 | Maximum logical transaction sequences provisioned for the run. Leave empty normally. The conservative calculation includes profile duration, maximum rate, 20% request margin, and a max-VU margin. |
| `WALLET_STRESS_INITIAL_BALANCE` | Calculated | Positive integer minor units | Initial available balance per wallet. Leave empty normally. It is calculated from requests per wallet, maximum request amount, and the balance safety percentage. |
| `WALLET_STRESS_BALANCE_SAFETY_PERCENT` | `120` | Integer >= 100 | Multiplier used only when calculating the initial balance. `120` provisions 20% balance headroom. |
| `WALLET_STRESS_BASE_AMOUNT` | `100` | Positive integer minor units | Minimum transaction or reservation amount. Monetary values remain integer minor units. |
| `WALLET_STRESS_AMOUNT_SPAN` | `900` | Positive integer minor units | Produces deterministic amounts in `[base, base + span - 1]`. |
| `WALLET_STRESS_DUPLICATE_PERCENT` | `5` | Integer 0-50 | Percentage of all RPC attempts that are byte-for-byte identical idempotency replays. The workload mixes concurrent and delayed duplicates. |
| `WALLET_STRESS_DUPLICATE_DELAY_SECONDS` | `5` | Integer >= 0 | Delay before the delayed half of duplicate replays. `0` sends them immediately after the original completes. |

### Write load shape

| Variable | Default | Explanation |
| --- | --- | --- |
| `WALLET_STRESS_STAGE_SECONDS` | `30` | Duration of each warm-up, discovery, transition, and ramp-down stage. |
| `WALLET_STRESS_WARM_RATE` | `5` | Initial logical new-transaction arrival rate per second. |
| `WALLET_STRESS_DISCOVERY_RATE_STEP` | `10` | Logical transactions/second added at each discovery step. |
| `WALLET_STRESS_DISCOVERY_STEPS` | `8` | Number of discovery increments. The last target is `warm rate + step size * steps`. |
| `WALLET_STRESS_SUSTAINABLE_RATE` | `50` | Discovered sustainable logical rate. The soak target is intentionally 80% of this value, rounded to the nearest integer. |
| `WALLET_STRESS_SOAK_SECONDS` | `1800` | Time maintained at the write soak target, excluding warm-up, transition, and ramp-down. |
| `WALLET_STRESS_PREALLOCATED_VUS` | `100` | k6 VUs allocated before the test. Increase if k6 cannot generate the requested arrival rate. |
| `WALLET_STRESS_MAX_VUS` | `500` | Maximum k6 VUs. Must be greater than or equal to preallocated VUs. Dropped iterations indicate that this or generator capacity may be insufficient. |

Discovery targets are:

```text
warm rate
-> warm rate + rate step
-> ...
-> warm rate + rate step * steps
-> 0
```

Soak targets are:

```text
warm rate -> 80% of sustainable rate -> hold -> 0
```

### Write thresholds and settlement

| Variable | Default | Explanation |
| --- | --- | --- |
| `WALLET_STRESS_MAX_UNEXPECTED_ERROR_RATE` | Disabled | Optional decimal ratio strictly between 0 and 1, for example `0.01`. Fails the k6 run when unexpected errors reach the configured limit. Expected `request_in_processing` responses are tracked separately. |
| `WALLET_STRESS_MAX_P95_MS` | Disabled | Optional p95 upper limit in milliseconds for the selected write API. |
| `WALLET_STRESS_SETTLEMENT_TIMEOUT_SECONDS` | `300` | Maximum wait after atomic or apply-earmark load for wallet transactions to complete and outbox rows to be sent. |
| `WALLET_STRESS_SETTLEMENT_POLL_SECONDS` | `2` | Interval between settlement database checks. |
| `WALLET_STRESS_SETTLEMENT_QUIET_SECONDS` | `10` | Required continuous period with no pending transaction or outbox work before reconciliation starts. |

Reserve and apply-release runs reconcile wallet state immediately because they
do not require ledger settlement. Atomic and apply-earmark runs wait and then
verify both wallet and ledger invariants.

## Query Configuration

### Workload and data distribution

| Variable | Default | Allowed values or limits | Explanation |
| --- | --- | --- | --- |
| `WALLET_QUERY_RUN_ID` | Generated UTC `MMDDHHMMSS` | 1-12 letters, digits, or hyphens | Identifies fixture rows, miss keys, logs, and metrics. Set it explicitly and use a new value for each run. |
| `WALLET_QUERY_LOOKUP` | `wallet-id` | `wallet-id`, `ref-id` | Selects `getSnapshotByWalletId` or `getSnapshotByRefId`. |
| `WALLET_QUERY_PROFILE` | `rpc-discovery` | `rpc-discovery`, `working-set-discovery`, `soak` | Selects rate discovery, cache working-set discovery, or sustained load. |
| `WALLET_QUERY_FIXTURE_SIZE` | `10000` | 1-5,000,000 | Number of immutable wallets and snapshots provisioned. Must cover the largest configured working set. |
| `WALLET_QUERY_WORKING_SET_SIZE` | `1000` | Positive integer <= fixture size | Number of fixture wallets addressed by rpc-discovery and soak runs. Small values create hot-key traffic; large values distribute queries. |
| `WALLET_QUERY_HIT_RATE_PERCENT` | `100` | Integer 0-100 | Deterministic percentage of queries sent to existing fixtures. Remaining requests are expected not-found misses. |
| `WALLET_QUERY_MISS_PATTERN` | `repeated` | `repeated`, `cold` | Repeated mode cycles through a bounded missing-key pool. Cold mode requires every expected miss to use a new key. |
| `WALLET_QUERY_MISS_CARDINALITY_PERCENT` | `10` | Integer 0-100 | Missing-key pool size as a percentage of the active working set, rounded up. Must be greater than zero when hit rate is below 100. For cold misses, make this pool large enough to prevent reuse. |
| `WALLET_QUERY_OWNER_TYPE` | `user` | `user`, `system`, `mixed` | Chooses USER wallets, SYSTEM wallets using the promoted hot-cache path, or a deterministic mixture. |
| `WALLET_QUERY_SYSTEM_PERCENT` | `50` | Integer 0-100 | SYSTEM percentage when owner type is `mixed`. It has no effect when owner type is `user` or `system`. |

### Query load shape

| Variable | Default | Explanation |
| --- | --- | --- |
| `WALLET_QUERY_STAGE_SECONDS` | `30` | Duration of each query rate or working-set stage. |
| `WALLET_QUERY_WARM_RATE` | `5` | Initial query arrival rate per second for rpc-discovery and soak. |
| `WALLET_QUERY_RATE` | `100` | Fixed rate for working-set-discovery and the exact sustained target for soak. |
| `WALLET_QUERY_RATE_STEP` | `25` | Queries/second added at each rpc-discovery step. |
| `WALLET_QUERY_RATE_STEPS` | `8` | Number of rpc-discovery increments. The last target is `warm rate + rate step * steps`. |
| `WALLET_QUERY_WORKING_SET_START` | `100` | Initial active wallet count for working-set-discovery. |
| `WALLET_QUERY_WORKING_SET_STEP` | `100` | Wallets added to the active working set after each stage. |
| `WALLET_QUERY_WORKING_SET_STEPS` | `8` | Number of working-set increments. The largest set is `start + step size * steps` and must not exceed fixture size. |
| `WALLET_QUERY_SOAK_SECONDS` | `1800` | Time maintained at `WALLET_QUERY_RATE`, excluding warm-up, transition, and ramp-down. |
| `WALLET_QUERY_PREALLOCATED_VUS` | `100` | k6 VUs allocated before the test. |
| `WALLET_QUERY_MAX_VUS` | `500` | Maximum k6 VUs; must be greater than or equal to preallocated VUs. |

`rpc-discovery` increases request rate while the configured working set remains
fixed. `working-set-discovery` holds request rate fixed while increasing the
working set. This separates service capacity from cache-locality effects.

### Query thresholds

| Variable | Default | Explanation |
| --- | --- | --- |
| `WALLET_QUERY_MAX_UNEXPECTED_ERROR_RATE` | Disabled | Optional decimal ratio strictly between 0 and 1. Expected not-found responses are excluded from this error rate. |
| `WALLET_QUERY_MAX_P95_MS` | Disabled | Optional p95 upper limit in milliseconds for the selected query API. |

Every run also requires at least one offered and one completed query. Cold-miss
runs with misses enabled require `wallet_query_cold_miss_reuse` to remain zero.

## Database Configuration

The Compose files use `INFRA_PRIVATE_IP` for both MySQL and Redis hosts. When
invoking a runner directly, `DB_HOST` and `REDIS_HOST` can be set separately;
Compose derives both container variables from `INFRA_PRIVATE_IP`.

| Variable | Default | Used by | Explanation |
| --- | --- | --- | --- |
| `INFRA_PRIVATE_IP` | `172.31.18.211` | Write and query | Infrastructure host passed to the containers as both `DB_HOST` and `REDIS_HOST`. |
| `DB_HOST` | `172.31.18.211` | Direct runner | MySQL host accepted by the shell runners. With Compose, configure `INFRA_PRIVATE_IP` instead. |
| `DB_PORT` | `3306` | Write and query | MySQL TCP port. |
| `WALLET_DB_NAME` | `trade_walletservice` | Write and query | Wallet-service schema used for provisioning and verification. |
| `WALLET_DB_USERNAME` | `wallet` | Write and query | Wallet schema user. It requires fixture write/read access and truncate permission when reset is enabled. |
| `WALLET_DB_PASSWORD` | `wallet-local` | Write and query | Wallet schema password; override outside local development. |
| `LEDGER_DB_NAME` | `trade_ledgerservice` | Write | Ledger-service schema used for account fixtures and reconciliation. |
| `LEDGER_DB_USERNAME` | `ledger` | Write | Ledger schema user. It requires fixture write/read access and truncate permission when reset is enabled. |
| `LEDGER_DB_PASSWORD` | `ledger-local` | Write | Ledger schema password; override outside local development. |
| `DB_READY_ATTEMPTS` | `30` | Write and query | Number of MySQL readiness attempts before fixture setup fails. |
| `DB_CONNECT_TIMEOUT_SECONDS` | `5` | Write and query | MySQL connection timeout for readiness, provisioning, settlement, and verification commands. |

## Redis Configuration

Redis is contacted by the runner only when cache reset is enabled. Wallet-service
itself must be configured separately to use the same Redis instance.

| Variable | Default | Explanation |
| --- | --- | --- |
| `REDIS_HOST` | `172.31.18.211` | Redis host accepted by the cache-reset helper. With Compose, configure `INFRA_PRIVATE_IP` instead. |
| `REDIS_PORT` | `6379` | Redis TCP port. The host comes from `INFRA_PRIVATE_IP`. |
| `REDIS_USERNAME` | Empty | Optional Redis ACL username. Used only when a password is also set. |
| `REDIS_PASSWORD` | Empty | Optional Redis password. When set without a username, single-argument `AUTH` is used. |
| `REDIS_DB` | `0` | Redis logical database selected before scoped key deletion. |
| `REDIS_READY_ATTEMPTS` | `30` | Number of Redis connection attempts before cache reset fails. |

## k6 Output Configuration

| Variable | Default | Explanation |
| --- | --- | --- |
| `K6_OUT` | `experimental-prometheus-rw` | k6 output backend. Change it when another output strategy is intended. |
| `K6_PROMETHEUS_RW_SERVER_URL` | `http://172.31.28.170:9290/api/v1/write` | Prometheus remote-write endpoint for k6 metrics. |
| `K6_PROMETHEUS_RW_TREND_STATS` | `p(95),p(99),max` | Trend statistics exported through Prometheus remote write. |

Use the wallet-service overview dashboard for JVM, gRPC, database, Redis,
outbox, Kafka, and business metrics. k6 exports offered, completed, success,
expected outcome, unexpected error, and latency metrics.
