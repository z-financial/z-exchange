# Ledger Split Docker Deployment

## Topology

Ledger and wallet use five EC2 instances in one AWS VPC:

```text
ledger EC2       wallet EC2       infrastructure EC2    Kafka EC2       monitoring EC2
172.31.16.37     172.31.23.255    172.31.18.211         172.31.28.6     172.31.28.170
---------------  ---------------  -------------------    -------------   ------------------
ledger :8081 <----------------------------------------------------------- Prometheus :9290
wallet :8082 <----------------------------------------------------------- Prometheus :9290
ledger :9191 <--- wallet              MySQL :3306          Kafka :9092    Grafana :3000
       |              |------------> Redis :6379              ^
       +--------------+------------> MySQL/Redis               |
       +--------------------------- wallet/ledger -------------+
```

The VPC security groups are the network boundary. Compose binds published ports
to each host's private IP; Docker DNS is used only between containers on the
same EC2 instance.

## Deployment Files

| File | Host | Responsibility |
| --- | --- | --- |
| `deploy/docker-compose.infrastructure.yml` | `172.31.18.211` | MySQL, Redis, and MySQL data. |
| `deploy/docker-compose.kafka.yml` | `172.31.28.6` | Kafka, persistent broker data, and idempotent topic initialization. |
| `deploy/docker-compose.ledger-service.yml` | `172.31.16.37` | Ledger application and ledger logs. |
| `deploy/docker-compose.wallet-service.yml` | `172.31.23.255` | Wallet application and wallet logs. |
| `deploy/docker-compose.monitoring.yml` | `172.31.28.170` | Prometheus, Grafana, and monitoring data. |
| `deploy/env/infra-test.env` | Infrastructure host | Infrastructure IP and database credentials. |
| `deploy/env/kafka-test.env` | Kafka host | Kafka advertised address, memory, and topic settings. |
| `deploy/env/ledger-test.env` | Ledger host | Ledger and infrastructure endpoints. |
| `deploy/env/wallet-test.env` | Wallet host | Wallet and remote service endpoints. |
| `deploy/env/monitor-test.env` | Monitoring host | Monitoring IP and container memory budgets. |
| `ledger-service/src/main/resources/application-docker.yml` | Ledger image | Docker profile defaults, overridable by Compose. |
| `monitoring/prometheus.yml` | Monitoring host | Scrapes ledger and wallet actuator metrics. |

The root `docker-compose.yml` remains the legacy all-in-one configuration for
local development. Do not use it for this AWS deployment.

## Connection Configuration

| Variable | Default | Used for |
| --- | --- | --- |
| `INFRA_PRIVATE_IP` | `172.31.18.211` | MySQL and Redis host. |
| `KAFKA_PRIVATE_IP` | `172.31.28.6` | Address advertised by Kafka to VPC clients. |
| `LEDGER_PRIVATE_IP` | `172.31.16.37` | Ledger HTTP and gRPC bindings. |
| `WALLET_PRIVATE_IP` | `172.31.23.255` | Wallet HTTP and gRPC bindings. |
| `MONITOR_PRIVATE_IP` | `172.31.28.170` | Prometheus and Grafana bindings. |
| `LEDGER_DB_USERNAME` | `ledger` | MySQL application user. |
| `LEDGER_DB_PASSWORD` | `ledger-local` | MySQL application password. |
| `WALLET_DB_USERNAME` | `wallet` | Wallet MySQL application user. |
| `WALLET_DB_PASSWORD` | `wallet-local` | Wallet MySQL application password. |
| `KAFKA_BOOTSTRAP_SERVERS` | `172.31.28.6:9092` | Kafka client bootstrap endpoint. |
| `K6_PROMETHEUS_RW_SERVER_URL` | `http://172.31.28.170:9290/api/v1/write` | k6 metric destination. |

Kafka must advertise its assigned private IP or VPC DNS name because clients on
other EC2 hosts cannot resolve the Docker-only `kafka:29092` listener.

## Infrastructure EC2

Use `deploy/env/infra-test.env`:

```dotenv
INFRA_PRIVATE_IP=172.31.18.211
LEDGER_DB_USERNAME=ledger
LEDGER_DB_PASSWORD=replace-with-a-long-random-password
WALLET_DB_USERNAME=wallet
WALLET_DB_PASSWORD=replace-with-a-different-long-random-password
MYSQL_ROOT_PASSWORD=replace-with-a-different-long-random-password
MYSQL_MEMORY_LIMIT=2560m
MYSQL_MEMORY_RESERVATION=2048m
MYSQL_INNODB_BUFFER_POOL_SIZE=1536M
REDIS_MEMORY_LIMIT=512m
REDIS_MEMORY_RESERVATION=256m
REDIS_MAXMEMORY=384mb
```

Start the infrastructure services from the repository root:

```bash
docker compose --env-file deploy/env/infra-test.env -f deploy/docker-compose.infrastructure.yml up -d
docker compose --env-file deploy/env/infra-test.env -f deploy/docker-compose.infrastructure.yml ps
docker compose --env-file deploy/env/infra-test.env -f deploy/docker-compose.infrastructure.yml logs -f mysql redis
```

MySQL initialization runs only while creating an empty `mysql-data` volume. It
creates ledger schema, wallet schema, and service-user grants in numbered order.
For an existing volume, use `bash deploy/script/provision-wallet-database.sh`; the
mounted initialization files do not run on ordinary restarts.

## Kafka EC2

Use the Kafka host's private IP in `deploy/env/kafka-test.env`:

```dotenv
KAFKA_PRIVATE_IP=172.31.28.6
KAFKA_EXTERNAL_PORT=9092
KAFKA_MEMORY_LIMIT=3072m
KAFKA_MEMORY_RESERVATION=2048m
KAFKA_HEAP_OPTS=-Xms1g -Xmx1g
KAFKA_TOPIC_INIT_MEMORY_LIMIT=256m
KAFKA_TOPIC_INIT_MEMORY_RESERVATION=64m
KAFKA_TOPIC_INIT_HEAP_OPTS=-Xms64m -Xmx128m
KAFKA_TOPIC_PARTITIONS=3
KAFKA_TOPIC_REPLICATION_FACTOR=1
KAFKA_RETRY_TOPIC_COUNT=6
```

Start Kafka and run the idempotent topic initializer:

```bash
docker compose --env-file deploy/env/kafka-test.env -f deploy/docker-compose.kafka.yml up -d
docker compose --env-file deploy/env/kafka-test.env -f deploy/docker-compose.kafka.yml ps
docker compose --env-file deploy/env/kafka-test.env -f deploy/docker-compose.kafka.yml logs kafka-topic-init
docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --list
```

The broker has automatic topic creation disabled. `kafka-topic-init` waits for
Kafka health, creates missing topics with `--if-not-exists`, and then exits with
code `0`. It creates both base topics:

```text
wallet.ledger.command.posting
ledger.wallet.reply.posting
```

For each base topic it also creates `.retry-0` through `.retry-5` and `.dlq`.
Six retry topics match the current Spring Kafka configuration of seven total
attempts with fixed-delay `MULTIPLE_TOPICS`. If that listener configuration
changes, update `KAFKA_RETRY_TOPIC_COUNT` and verify the generated topic naming.

Kafka data persists in the `kafka-data` named volume. Re-running the initializer
does not delete topics, change existing partition counts, or erase messages.

### Kafka Cutover

The dedicated broker is a new Kafka cluster. Creating its topics does not copy
records, consumer offsets, or the old broker's KRaft metadata. For a test
environment, use this controlled cutover:

1. Stop wallet and ledger producers/consumers after their outbox backlogs and
   Kafka consumer lag reach zero.
2. Start the dedicated Kafka stack and verify `kafka-topic-init` exits with code
   `0`.
3. Configure `172.31.28.6:9092` in every ledger/wallet deployment and restart
   both applications.
4. Verify publishing, consumption, retry topics, and DLT handling before
   removing the old infrastructure-host Kafka container.

Use Kafka replication tooling such as MirrorMaker when existing records or
consumer offsets must be preserved. Do not assume switching the bootstrap
address migrates broker state.

## Ledger EC2

Use `deploy/env/ledger-test.env`. Its database password must match the infrastructure
host:

```dotenv
LEDGER_PRIVATE_IP=172.31.16.37
INFRA_PRIVATE_IP=172.31.18.211
LEDGER_DB_USERNAME=ledger
LEDGER_DB_PASSWORD=replace-with-the-infrastructure-value
KAFKA_BOOTSTRAP_SERVERS=172.31.28.6:9092
```

Build and start ledger-service:

```bash
docker compose --env-file deploy/env/ledger-test.env -f deploy/docker-compose.ledger-service.yml up --build -d
docker compose --env-file deploy/env/ledger-test.env -f deploy/docker-compose.ledger-service.yml ps
docker compose --env-file deploy/env/ledger-test.env -f deploy/docker-compose.ledger-service.yml logs -f ledger-service
```

Verify the service:

```bash
curl http://172.31.16.37:8081/actuator/health
curl http://172.31.16.37:8081/actuator/prometheus
```

## Monitoring EC2

`deploy/env/monitor-test.env` configures the 2 GiB monitoring host:

```dotenv
MONITOR_PRIVATE_IP=172.31.28.170
PROMETHEUS_MEMORY_LIMIT=512m
PROMETHEUS_MEMORY_RESERVATION=384m
GRAFANA_MEMORY_LIMIT=768m
GRAFANA_MEMORY_RESERVATION=512m
```

Start Prometheus and Grafana:

```bash
docker compose --env-file deploy/env/monitor-test.env -f deploy/docker-compose.monitoring.yml up -d
docker compose --env-file deploy/env/monitor-test.env -f deploy/docker-compose.monitoring.yml ps
docker compose --env-file deploy/env/monitor-test.env -f deploy/docker-compose.monitoring.yml logs -f prometheus grafana
```

Verify:

```text
Prometheus targets: http://172.31.28.170:9290/targets
Grafana:            http://172.31.28.170:3000
```

Grafana reaches Prometheus through `http://prometheus:9090` on their shared
Docker network. Prometheus scrapes ledger-service through the VPC. k6 sends
remote-write data to `http://172.31.28.170:9290/api/v1/write`.

The named Prometheus and Grafana volumes are local to the monitoring EC2. A
fresh deployment does not copy history from `172.31.18.211`. Dashboard JSON is
reprovisioned from the repository; migrate or snapshot the old Prometheus volume
separately only when its history must be retained.

After the new targets and dashboards are verified, stop and remove the old
monitoring containers on the infrastructure EC2. This leaves their volumes
intact for rollback:

```bash
docker stop prometheus grafana
docker rm prometheus grafana
```

## Stress-Test Endpoint

The stress-test Compose files default to the new monitoring host. An explicit
override remains supported:

```bash
export K6_PROMETHEUS_RW_SERVER_URL=http://172.31.28.170:9290/api/v1/write
```

Database fixture and reconciliation traffic still uses
`INFRA_PRIVATE_IP=172.31.18.211`; gRPC traffic still uses
`LEDGER_GRPC_HOST=172.31.16.37`.

## Security Groups

Permit only the required flows:

| Source | Destination | TCP port | Purpose |
| --- | --- | ---: | --- |
| Ledger EC2 | Infrastructure EC2 | `3306`, `6379` | MySQL and Redis. |
| Ledger EC2 | Kafka EC2 | `9092` | Kafka commands and replies. |
| Wallet EC2 | Kafka EC2 | `9092` | Kafka commands and replies. |
| Wallet EC2 | Infrastructure EC2 | `3306`, `6379` | MySQL and Redis. |
| Wallet EC2 | Ledger EC2 | `9191` | Current account/ledger gRPC clients. |
| Monitoring EC2 | Ledger EC2 | `8081` | Prometheus scrape. |
| Monitoring EC2 | Wallet EC2 | `8082` | Prometheus scrape. |
| Stress-test EC2 | Ledger EC2 | `9191` | gRPC load. |
| Stress-test EC2 | Infrastructure EC2 | `3306` | Fixture and reconciliation SQL. |
| Stress-test EC2 | Monitoring EC2 | `9290` | k6 remote write. |
| Approved operator network | Monitoring EC2 | `3000` | Grafana UI. |
| Approved operator network, optional | Monitoring EC2 | `9290` | Prometheus UI/API. |

Do not expose MySQL, Redis, Kafka, Prometheus, Grafana, or the ledger actuator
to the public internet.
