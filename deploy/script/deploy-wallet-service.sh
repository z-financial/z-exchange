#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
env_file="${WALLET_ENV_FILE:-${repo_root}/deploy/env/wallet-test.env}"
compose_file="${repo_root}/deploy/docker-compose.wallet-service.yml"

if [[ ! -f "${env_file}" ]]; then
    echo "Wallet environment file not found: ${env_file}" >&2
    exit 1
fi

cd "${repo_root}"

# Validate interpolation before building so missing or malformed deployment
# values fail without replacing the currently running wallet container.
docker compose --env-file "${env_file}" -f "${compose_file}" config >/dev/null
docker compose --env-file "${env_file}" -f "${compose_file}" up --build -d
docker compose --env-file "${env_file}" -f "${compose_file}" ps

echo "Wallet deployment started. Verify /actuator/health before sending traffic."
