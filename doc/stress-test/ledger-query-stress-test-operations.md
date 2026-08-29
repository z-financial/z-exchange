# Ledger Query Stress-Test Operations

## Files

| File | Purpose |
| --- | --- |
| `stress-test/scripts/ledger-grpc-query-stress.js` | k6 gRPC query workload and result classification |
| `stress-test/fixtures/ledger-query-fixture.sql` | Deterministic transactions and two entries per transaction |
| `stress-test/run-query-stress.sh` | Database readiness, fixture provisioning, and k6 startup |
| `deploy/stress-test/docker-compose.ledger-query-stress-test.yml` | Standalone query-test deployment |

Run only against a test database. The database user needs permission to insert
and read the dedicated fixture rows. Setting
`LEDGER_STRESS_TRUNCATE_DB_BEFORE_RUN=true` first deletes all ledger-service
table rows, including outbox data, and requires MySQL `DROP` privilege. It
defaults to `false`; do not enable it with concurrent business traffic.

## Common Command

From the repository root:

```bash
export LEDGER_QUERY_PROFILE=rpc-discovery
export LEDGER_QUERY_LOOKUP=txn-id
export LEDGER_QUERY_RUN_ID=query-txn-rpc-01
export LEDGER_QUERY_FIXTURE_SIZE=10000
export LEDGER_QUERY_WORKING_SET_SIZE=1000
export LEDGER_GRPC_HOST=172.31.16.37
export INFRA_PRIVATE_IP=172.31.18.211
export LEDGER_DB_USERNAME=ledger
export LEDGER_DB_PASSWORD=123

COMPOSE_MENU=false COMPOSE_ANSI=never \
docker compose --progress plain \
  -f deploy/stress-test/docker-compose.ledger-query-stress-test.yml up --build \
  --abort-on-container-exit \
  --exit-code-from ledger-query-stress-test
```

Change `LEDGER_QUERY_LOOKUP` to `ref-id` and use a new run ID for the reference
lookup test. Remove an old stopped test container with `--remove-orphans` when
Compose reports it as an orphan.

## Profiles

### RPC Discovery

```bash
export LEDGER_QUERY_PROFILE=rpc-discovery
export LEDGER_QUERY_WARM_RATE=25
export LEDGER_QUERY_RATE_STEP=25
export LEDGER_QUERY_RATE_STEPS=8
export LEDGER_QUERY_STAGE_SECONDS=30
export LEDGER_QUERY_WORKING_SET_SIZE=1000
```

This holds the working set fixed and increases only RPC.

### Working-Set Discovery

```bash
export LEDGER_QUERY_PROFILE=working-set-discovery
export LEDGER_QUERY_RATE=150
export LEDGER_QUERY_WORKING_SET_START=100
export LEDGER_QUERY_WORKING_SET_STEP=500
export LEDGER_QUERY_WORKING_SET_STEPS=8
export LEDGER_QUERY_STAGE_SECONDS=30
```

The largest set is `start + step * steps` and must not exceed the fixture size.
RPC remains fixed for the entire profile.

At the maximum fixture size, provisioning creates 5,000,000 transactions and
10,000,000 ledger entries. Allow adequate database disk and setup time.

### Soak

```bash
export LEDGER_QUERY_PROFILE=soak
export LEDGER_QUERY_WARM_RATE=25
export LEDGER_QUERY_RATE=150
export LEDGER_QUERY_WORKING_SET_SIZE=5000
export LEDGER_QUERY_STAGE_SECONDS=30
export LEDGER_QUERY_SOAK_SECONDS=600
```

The configured RPC and working set remain fixed during the soak hold.

## Miss Tests

Repeated miss example:

```bash
export LEDGER_QUERY_MISS_RATE_PERCENT=10
export LEDGER_QUERY_MISS_CARDINALITY_PERCENT=5
export LEDGER_QUERY_MISS_PATTERN=repeated
```

Cold miss example:

```bash
export LEDGER_QUERY_MISS_RATE_PERCENT=10
export LEDGER_QUERY_MISS_CARDINALITY_PERCENT=100
export LEDGER_QUERY_MISS_PATTERN=cold
```

For cold tests, ensure the expected number of miss requests does not exceed
`ceil(working_set * miss_cardinality_percent / 100)`. Otherwise k6 fails the
`ledger_query_cold_miss_reuse` threshold. Set miss rate to `0` for hit-only
tests.

## Configuration

| Variable | Default | Meaning |
| --- | --- | --- |
| `LEDGER_QUERY_PROFILE` | `rpc-discovery` | `rpc-discovery`, `working-set-discovery`, or `soak` |
| `LEDGER_QUERY_LOOKUP` | `txn-id` | `txn-id` or `ref-id`; run separately |
| `LEDGER_QUERY_RUN_ID` | UTC timestamp | Run and Prometheus `testid`; maximum 24 characters |
| `LEDGER_STRESS_TRUNCATE_DB_BEFORE_RUN` | `false` | When `true`, truncate all ledger-service tables before fixture setup |
| `LEDGER_QUERY_FIXTURE_SIZE` | `10000` | SQL fixture rows; range 1-5000000 |
| `LEDGER_QUERY_WORKING_SET_SIZE` | `1000` | Fixed set for RPC discovery and soak |
| `LEDGER_QUERY_WORKING_SET_START` | `100` | Initial set for working-set discovery |
| `LEDGER_QUERY_WORKING_SET_STEP` | `100` | IDs added per working-set stage |
| `LEDGER_QUERY_WORKING_SET_STEPS` | `8` | Number of working-set increases |
| `LEDGER_QUERY_STAGE_SECONDS` | `30` | Warm/hold duration per discovery step |
| `LEDGER_QUERY_WARM_RATE` | `5` | Warm-up RPC for RPC discovery and soak |
| `LEDGER_QUERY_RATE` | `100` | Fixed RPC for working-set discovery and soak |
| `LEDGER_QUERY_RATE_STEP` | `25` | RPC added per RPC-discovery step |
| `LEDGER_QUERY_RATE_STEPS` | `8` | Number of RPC increases |
| `LEDGER_QUERY_SOAK_SECONDS` | `120` | Fixed-load soak duration |
| `LEDGER_QUERY_PREALLOCATED_VUS` | `100` | VUs allocated before traffic |
| `LEDGER_QUERY_MAX_VUS` | `500` | Maximum VUs k6 may allocate |
| `LEDGER_QUERY_MISS_RATE_PERCENT` | `0` | Whole-number expected miss percentage |
| `LEDGER_QUERY_MISS_CARDINALITY_PERCENT` | `10` | Missing-key set relative to working set |
| `LEDGER_QUERY_MISS_PATTERN` | `repeated` | `repeated` or `cold` |
| `LEDGER_QUERY_MAX_UNEXPECTED_ERROR_RATE` | unset | Optional k6 failure-rate threshold |
| `LEDGER_QUERY_MAX_P95_MS` | unset | Optional overall query p95 threshold |

The Compose file also accepts the existing gRPC, database, VU, and Prometheus
remote-write connection variables used by the write stress test.

## Result Check

A valid run has:

- no unexpected query failures
- no dropped iterations at the measured capacity point
- offered and completed TPS tracking closely
- expected misses matching the configured percentage
- zero cold-miss reuse for a cold run
- stable latency and server saturation metrics during each hold

The terminal k6 summary and container exit code are authoritative. Filter
remote-write metrics by `testid`, `lookup`, and `test_profile`.
