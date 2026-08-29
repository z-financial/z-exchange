# Wallet Cloud Docker Deployment

## Topology

Wallet runs on its own EC2 instance and uses the same remote infrastructure,
Kafka, ledger, and monitoring hosts as ledger-service:

```text
wallet EC2             infrastructure EC2    Kafka EC2       ledger EC2       monitoring EC2
172.31.23.255          172.31.18.211         172.31.28.6     172.31.16.37     172.31.28.170
-------------------    -------------------    -------------   ---------------  ----------------
wallet HTTP :8082 ---> MySQL :3306
wallet gRPC :9192 ---> Redis :6379
        |------------> Kafka :9092
        |----------------------------------> ledger gRPC :9191
        <--------------------------------------------------- Prometheus scrape from :9290
```

The services are separate Compose projects. Wallet has no Docker `depends_on`
relationship with ledger or infrastructure containers; it uses VPC endpoints.

## Files

| File | Purpose |
| --- | --- |
| `deploy/docker-compose.wallet-service.yml` | Builds and runs wallet-service on its EC2 host. |
| `deploy/env/wallet-test.env` | Wallet host, database, Kafka, ledger, and memory settings. |
| `deploy/script/deploy-wallet-service.sh` | Validates Compose, builds the image, and starts wallet-service. |
| `deploy/script/provision-wallet-database.sh` | Safely provisions the wallet schema/user on an existing MySQL volume. |
| `wallet-service/src/main/resources/application-docker.yml` | Resolves Docker-profile settings from deployment variables. |
| `monitoring/prometheus.yml` | Scrapes wallet metrics at `172.31.23.255:8082`. |

## Endpoint Configuration

`deploy/env/wallet-test.env` contains the test-environment defaults:

```dotenv
WALLET_PRIVATE_IP=172.31.23.255
INFRA_PRIVATE_IP=172.31.18.211
WALLET_DB_USERNAME=wallet
WALLET_DB_PASSWORD=replace-with-the-infrastructure-value
KAFKA_BOOTSTRAP_SERVERS=172.31.28.6:9092
LEDGER_GRPC_ADDRESS=static://172.31.16.37:9191
ACCOUNT_GRPC_ADDRESS=static://172.31.16.37:9191
WALLET_MEMORY_LIMIT=3g
WALLET_MEMORY_RESERVATION=2g
WALLET_JAVA_TOOL_OPTIONS=-Xms1g -Xmx2g
```

The two gRPC client addresses currently point to ledger-service because it
hosts both PostService and AccountService. Kafka remains the asynchronous
wallet-to-ledger transaction path. Replace test passwords before deployment.

## Database Provisioning

The infrastructure Compose now mounts initialization files in this order:

```text
001-ledger-service.sql
002-wallet-service.sql
003-grant-service-users.sh
```

These files run automatically only when MySQL creates an empty `mysql-data`
volume. Before a fresh infrastructure deployment, set matching wallet values in
`deploy/env/infra-test.env`:

```dotenv
WALLET_DB_USERNAME=wallet
WALLET_DB_PASSWORD=replace-with-the-wallet-password
```

For the current non-empty infrastructure volume, update the repository and
recreate MySQL so the new mounts and environment are present, then run the
provisioner:

```bash
docker compose --env-file deploy/env/infra-test.env \
  -f deploy/docker-compose.infrastructure.yml up -d mysql

bash deploy/script/provision-wallet-database.sh
```

The provisioner creates `trade_walletservice` only when absent. If it already
exists, the script preserves its tables and data, then reapplies idempotent user
creation and grants.

## Wallet Deployment

On the wallet EC2 instance, install Docker with the Compose plugin, clone the
repository, configure `deploy/env/wallet-test.env`, and run from the repository root:

```bash
bash deploy/script/deploy-wallet-service.sh
docker compose --env-file deploy/env/wallet-test.env \
  -f deploy/docker-compose.wallet-service.yml logs -f wallet-service
```

The script performs `docker compose config` before replacing the running
container. The named `wallet-service-logs` volume preserves Logback files across
container recreation.

Verify the application endpoints:

```bash
curl http://172.31.23.255:8082/actuator/health
curl http://172.31.23.255:8082/actuator/prometheus
```

## Monitoring Update

Prometheus must reload the updated configuration on the monitoring EC2. A
container recreation is sufficient; rebuilding the image is unnecessary:

```bash
docker compose --env-file deploy/env/monitor-test.env \
  -f deploy/docker-compose.monitoring.yml up -d --force-recreate prometheus
```

Verify the `wallet-service` target at:

```text
http://172.31.28.170:9290/targets
```

## Security Groups

Permit only these private VPC flows:

| Source | Destination | TCP port | Purpose |
| --- | --- | ---: | --- |
| Approved API clients | Wallet EC2 | `8082`, `9192` | Wallet HTTP/gRPC APIs. |
| Wallet EC2 | Infrastructure EC2 | `3306`, `6379` | MySQL and Redis. |
| Wallet EC2 | Kafka EC2 | `9092` | Commands, replies, and outbox publishing. |
| Wallet EC2 | Ledger EC2 | `9191` | Current ledger/account gRPC clients. |
| Monitoring EC2 | Wallet EC2 | `8082` | Prometheus scrape. |

Do not expose MySQL, Redis, Kafka, or the wallet actuator to the public internet.

## Operational Checks

Before sending traffic, verify:

1. `trade_walletservice` exists and the wallet user can connect.
2. Redis and Kafka are reachable from `172.31.23.255`.
3. Kafka contains `wallet.ledger.command.posting`,
   `ledger.wallet.reply.posting`, retry topics, and DLT topics.
4. Wallet health is `UP` and Prometheus reports the wallet target as `UP`.
5. A test transaction creates wallet state and its outbox record atomically,
   then receives the ledger reply through Kafka.
