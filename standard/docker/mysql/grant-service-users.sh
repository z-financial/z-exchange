#!/bin/bash
set -euo pipefail

mysql --protocol=socket -uroot -p"${MYSQL_ROOT_PASSWORD}" <<-EOSQL
    CREATE USER IF NOT EXISTS '${WALLET_DB_USERNAME:-wallet}'@'%' IDENTIFIED BY '${WALLET_DB_PASSWORD:-wallet-local}';
    CREATE USER IF NOT EXISTS '${ID_GENERATOR_DB_USERNAME:-zid_runtime}'@'%' IDENTIFIED BY '${ID_GENERATOR_DB_PASSWORD:-zid-local}';
    GRANT ALL PRIVILEGES ON trade_walletservice.* TO '${WALLET_DB_USERNAME:-wallet}'@'%';
    GRANT ALL PRIVILEGES ON trade_ledgerservice.* TO '${LEDGER_DB_USERNAME:-ledger}'@'%';
    GRANT SELECT ON z_id_generator.domains TO '${ID_GENERATOR_DB_USERNAME:-zid_runtime}'@'%';
    GRANT SELECT ON z_id_generator.generator_configs TO '${ID_GENERATOR_DB_USERNAME:-zid_runtime}'@'%';
    GRANT SELECT, UPDATE ON z_id_generator.counters TO '${ID_GENERATOR_DB_USERNAME:-zid_runtime}'@'%';
    GRANT SELECT, INSERT ON z_id_generator.workers TO '${ID_GENERATOR_DB_USERNAME:-zid_runtime}'@'%';
    GRANT SELECT, INSERT, UPDATE ON z_id_generator.leases TO '${ID_GENERATOR_DB_USERNAME:-zid_runtime}'@'%';
    FLUSH PRIVILEGES;
EOSQL
