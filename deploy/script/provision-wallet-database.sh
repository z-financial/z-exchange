#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
env_file="${INFRA_ENV_FILE:-${repo_root}/deploy/env/infra-test.env}"
compose_file="${repo_root}/deploy/docker-compose.infrastructure.yml"
schema_file="${repo_root}/standard/sql/wallet_service.sql"

if [[ ! -f "${env_file}" ]]; then
    echo "Infrastructure environment file not found: ${env_file}" >&2
    exit 1
fi

cd "${repo_root}"
docker compose --env-file "${env_file}" -f "${compose_file}" config >/dev/null

schema_exists="$({
    docker compose --env-file "${env_file}" -f "${compose_file}" exec -T mysql \
        sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -Nse "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = '\''trade_walletservice'\''"'
} | tr -d '\r')"

if [[ "${schema_exists}" != "trade_walletservice" ]]; then
    echo "Creating trade_walletservice schema..."
    docker compose --env-file "${env_file}" -f "${compose_file}" exec -T mysql \
        sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD"' < "${schema_file}"
else
    echo "trade_walletservice already exists; preserving its data."
fi

# User creation and grants are idempotent and must also run for an existing
# mysql-data volume because image initialization scripts only run once.
docker compose --env-file "${env_file}" -f "${compose_file}" exec -T mysql \
    bash /docker-entrypoint-initdb.d/003-grant-service-users.sh

echo "Wallet database and service-user grants are ready."
