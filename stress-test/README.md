# Service Stress Tests

This directory contains k6 gRPC smoke, write, and query stress tests and is not
a Maven or Spring module. The smoke verifies one dependent workflow:

1. `PostService/postTransaction`
2. `PostService/getTxnByRefId`
3. `PostService/getTxnById`

The transaction reference is unique per run. Both query calls validate the
transaction created by the write call and require the two ledger entries.

The wallet smoke test verifies these wallet RPCs and the asynchronous ledger
settlement path:

1. `getSnapshotByWalletId` and `getSnapshotByRefId`
2. `reserveTransaction`
3. `applyReserveTransaction` with a full release
4. `atomicTransaction`, followed by snapshot polling until the Kafka/ledger
   reply settles the debit and credit

The write stress test exercises `postTransaction` with open arrival-rate
profiles for capacity discovery, overload recovery, and stable-load soak. See
`doc/stress-test/ledger-write-stress-test-operations.md` for its commands,
configuration, metrics, and reconciliation flow.

The query stress test exercises `getTxnById` and `getTxnByRefId` separately
with `includeEntries=true`. Its RPC-discovery, working-set-discovery, and soak
profiles keep load and data-cardinality experiments isolated. See
`doc/stress-test/ledger-query-stress-test-flow.md` and
`doc/stress-test/ledger-query-stress-test-operations.md`.

Wallet stress tests exercise each write or query API independently. Write runs
support distributed or hot-wallet cardinality, configurable exact duplicates,
bounded asynchronous settlement, and wallet/ledger reconciliation. Query runs
control lookup, hit rate, working set, miss pattern, and owner type. See
`doc/stress-test/wallet-stress-test-design.md` and
`doc/stress-test/wallet-stress-test-operations.md`.

## Fixture Provisioning

`run-smoke.sh` connects directly to the ledger MySQL database before starting
k6 and applies `fixtures/ledger-smoke-fixture.sql`. The SQL idempotently creates
these dedicated rows:

| Fixture | Value |
| --- | --- |
| Asset ID | `k6-smoke-asset` |
| Account ID | `k6-smoke-account-id` |
| Account reference | `k6-smoke-account` |

Fixture setup is completed before k6 starts, so it is excluded from gRPC
latency. Run this only against a test database. The database credentials must
permit `INSERT` and `UPDATE` on `assets` and `accounts`.

Write and query stress runners can start from an empty ledger database by
setting `LEDGER_STRESS_TRUNCATE_DB_BEFORE_RUN=true`. It defaults to `false`.
This deletes all rows from ledger-service tables, including outbox data, before
fixtures are applied. Use it only on isolated test infrastructure; the MySQL
user also needs the `DROP` privilege required by `TRUNCATE TABLE`.

`run-wallet-smoke.sh` creates run-specific wallets, snapshots, account
mappings, and matching ledger accounts. Unique fixture IDs avoid stale Redis
cache state and interference from earlier asynchronous replies. The runner
needs write access through both the wallet and ledger database users. It must
target a complete test deployment containing wallet-service, Redis, Kafka,
ledger-service, and MySQL.

## Run With Docker

From the repository root:

```bash
COMPOSE_MENU=false COMPOSE_ANSI=never \
docker compose --progress plain \
  -f deploy/stress-test/docker-compose.stress-test.yml up --build \
  --abort-on-container-exit --exit-code-from stress-test
```

For the AWS VPC deployment, provide the same database settings used by
ledger-service:

```bash
INFRA_PRIVATE_IP=172.31.18.211 \
LEDGER_GRPC_HOST=172.31.16.37 \
LEDGER_DB_USERNAME=ledger \
LEDGER_DB_PASSWORD='replace-with-the-infrastructure-value' \
COMPOSE_MENU=false COMPOSE_ANSI=never \
docker compose --progress plain \
  -f deploy/stress-test/docker-compose.stress-test.yml up --build \
  --abort-on-container-exit --exit-code-from stress-test
```

`COMPOSE_MENU` and `COMPOSE_ANSI` control the host-side Compose terminal UI.
Disabling them prevents the `Enable Watch / Detach` menu and ANSI redraws from
overwriting the k6 summary. `--progress plain` also keeps image-build output
line-oriented.

`LEDGER_GRPC_HOST` overrides the gRPC target directly. If it is not set, the
Compose default is `172.31.16.37`.

Run the wallet smoke test with:

```bash
INFRA_PRIVATE_IP=172.31.18.211 \
WALLET_GRPC_HOST=172.31.23.255 \
WALLET_DB_USERNAME=wallet \
WALLET_DB_PASSWORD='replace-with-the-infrastructure-value' \
LEDGER_DB_USERNAME=ledger \
LEDGER_DB_PASSWORD='replace-with-the-infrastructure-value' \
COMPOSE_MENU=false COMPOSE_ANSI=never \
docker compose --progress plain \
  -f deploy/stress-test/docker-compose.wallet-smoke-test.yml up --build \
  --abort-on-container-exit --exit-code-from wallet-smoke-test
```

`WALLET_SMOKE_RUN_ID` is optional and defaults to a unique short value. If set,
it must be unique, contain only letters, digits, and hyphens, and be at most 20
characters.

Run the standalone query workload with:

```bash
docker compose -f deploy/stress-test/docker-compose.ledger-query-stress-test.yml up --build \
  --abort-on-container-exit --exit-code-from ledger-query-stress-test
```

Set `LEDGER_QUERY_PROFILE`, `LEDGER_QUERY_LOOKUP`, and a unique
`LEDGER_QUERY_RUN_ID` before running. The complete configuration is documented
in `doc/stress-test/ledger-query-stress-test-operations.md`.

Wallet write and query stress tests use their standalone Compose files:

```bash
docker compose -f deploy/stress-test/docker-compose.wallet-write-stress-test.yml up --build \
  --abort-on-container-exit --exit-code-from wallet-write-stress-test

docker compose -f deploy/stress-test/docker-compose.wallet-query-stress-test.yml up --build \
  --abort-on-container-exit --exit-code-from wallet-query-stress-test
```

`WALLET_STRESS_TRUNCATE_DB_BEFORE_RUN` and
`WALLET_STRESS_TRUNCATE_CACHE_BEFORE_RUN` both default to `false`.

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `LEDGER_GRPC_HOST` | `172.31.16.37` | Ledger gRPC host |
| `LEDGER_GRPC_PORT` | `9191` | Ledger gRPC port |
| `LEDGER_GRPC_DEADLINE_SECONDS` | `5` | Connection timeout, per-RPC timeout, and latency threshold |
| `LEDGER_TEST_AMOUNT` | `100` | Debit and credit amount; passed as protobuf `int64` |
| `INFRA_PRIVATE_IP` | `172.31.18.211` | Fixture database host |
| `DB_PORT` | `3306` | Fixture database port |
| `LEDGER_DB_NAME` | `trade_ledgerservice` | Ledger schema |
| `LEDGER_DB_USERNAME` | `ledger` | Fixture database user |
| `LEDGER_DB_PASSWORD` | `ledger-local` | Fixture database password |
| `DB_READY_ATTEMPTS` | `30` | Two-second database readiness attempts |
| `DB_CONNECT_TIMEOUT_SECONDS` | `5` | Maximum duration of each database connection attempt |
| `K6_OUT` | `experimental-prometheus-rw` | Streams granular k6 metrics to Prometheus during the run |
| `K6_PROMETHEUS_RW_SERVER_URL` | `http://172.31.28.170:9290/api/v1/write` | Prometheus remote-write receiver on the monitoring EC2 |
| `K6_PROMETHEUS_RW_TREND_STATS` | `p(95),p(99),max` | Trend series retained for the provisioned stress dashboard |

Wallet smoke-specific configuration:

| Variable | Default | Purpose |
| --- | --- | --- |
| `WALLET_GRPC_HOST` | `172.31.23.255` | Wallet gRPC host |
| `WALLET_GRPC_PORT` | `9192` | Wallet gRPC port |
| `WALLET_GRPC_DEADLINE_SECONDS` | `5` | Connection and per-RPC timeout |
| `WALLET_SMOKE_SETTLEMENT_TIMEOUT_SECONDS` | `20` | Maximum wait for the async ledger reply |
| `WALLET_SMOKE_POLL_INTERVAL_SECONDS` | `0.25` | Snapshot polling interval during settlement |
| `WALLET_SMOKE_INITIAL_BALANCE` | `1000` | Seeded outgoing available balance |
| `WALLET_SMOKE_RESERVE_AMOUNT` | `100` | Amount reserved and fully released |
| `WALLET_SMOKE_ATOMIC_AMOUNT` | `25` | Atomic debit and credit amount |
| `WALLET_DB_NAME` | `trade_walletservice` | Wallet schema |
| `WALLET_DB_USERNAME` | `wallet` | Wallet fixture database user |
| `WALLET_DB_PASSWORD` | `wallet-local` | Wallet fixture database password |

The load-generator EC2 requires outbound VPC access to:

- ledger EC2 port `9191`
- wallet EC2 port `9192`
- infrastructure EC2 port `3306`
- monitoring EC2 port `9290`

No inbound port is required on the load-generator EC2.

## Files

- `scripts/ledger-grpc-smoke.js`: commented k6 workflow and assertions.
- `scripts/wallet-grpc-smoke.js`: wallet API and async settlement smoke workflow.
- `scripts/wallet-grpc-write-stress.js`: isolated wallet write capacity and duplicate workload.
- `scripts/wallet-grpc-query-stress.js`: configurable wallet snapshot query workload.
- `scripts/ledger-grpc-write-stress.js`: commented distributed write workload.
- `scripts/ledger-grpc-query-stress.js`: commented query and miss workload.
- `fixtures/ledger-smoke-fixture.sql`: idempotent Asset and Account fixture.
- `fixtures/wallet-smoke-fixture.sql`: run-specific Wallet and Snapshot fixtures.
- `fixtures/wallet-smoke-ledger-fixture.sql`: matching ledger Asset and Accounts.
- `fixtures/ledger-write-fixture.sql`: 10 assets and 100 distributed accounts.
- `fixtures/ledger-write-verification.sql`: deterministic journal reconciliation.
- `fixtures/ledger-query-fixture.sql`: immutable transactions for query tests.
- `fixtures/truncate_ledger_service.sql`: optional full ledger test-data reset.
- `run-smoke.sh`: waits for MySQL, applies the fixture, then starts k6.
- `run-wallet-smoke.sh`: provisions both schemas, then starts the wallet smoke.
- `run-wallet-write-stress.sh`: provisions write fixtures, waits for settlement, and reconciles both schemas.
- `run-wallet-query-stress.sh`: provisions immutable snapshots and runs query load.
- `run-write-stress.sh`: provisions, runs a write profile, and reconciles rows.
- `run-query-stress.sh`: provisions and verifies query fixtures, then runs k6.
- `Dockerfile`: combines the pinned k6 binary with MySQL 8.4's native client.
