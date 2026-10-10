-- Schema copied from z-id-generator commit
-- 4d7951272ee3f9679f186d21fad08cc9ad456ef8. Update this bootstrap together
-- with the pinned library artifact when its MySQL schema changes.
CREATE DATABASE IF NOT EXISTS z_id_generator
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

USE z_id_generator;

CREATE TABLE IF NOT EXISTS domains (
    domain_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    domain_name VARCHAR(128) NOT NULL UNIQUE,
    prefix_type VARCHAR(32) NOT NULL,
    time_bit TINYINT NOT NULL,
    worker_bit TINYINT NOT NULL,
    sequence_bit TINYINT NOT NULL,
    epoch_start_ms BIGINT NOT NULL,
    time_unit VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

CREATE TABLE IF NOT EXISTS counters (
    counter_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    counter_ref VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    counter_name VARCHAR(128) NOT NULL,
    next_value BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_counter_ref(counter_ref)
);

CREATE TABLE IF NOT EXISTS generator_configs (
    generator_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    domain_id BIGINT NOT NULL,
    generator_name VARCHAR(128) NOT NULL,
    policy JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_domain_generator_name(domain_id, generator_name),
    KEY idx_domain_id(domain_id)
);

CREATE TABLE IF NOT EXISTS workers (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    domain_id BIGINT NOT NULL,
    generator_id BIGINT NOT NULL,
    worker_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_domain_worker(domain_id, worker_id),
    UNIQUE KEY uk_generator_worker(generator_id, worker_id),
    KEY idx_domain_generator(domain_id, generator_id)
);

CREATE TABLE IF NOT EXISTS leases (
    lease_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    lease_domain VARCHAR(64) NOT NULL,
    lease_ref BIGINT NOT NULL,
    lease_description VARCHAR(255),
    owner_id VARCHAR(128) NOT NULL,
    owner_token VARCHAR(64) NOT NULL,
    fencing_token BIGINT NOT NULL,
    renewed_at DATETIME(3) NOT NULL,
    expired_at DATETIME(3) NOT NULL,
    reusable_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_lease_reusable_after_expiry CHECK (reusable_at > expired_at),
    UNIQUE KEY uk_lease_resource(lease_domain, lease_ref),
    KEY idx_owner(owner_id),
    KEY idx_expired(expired_at, lease_id),
    KEY idx_reusable(reusable_at, lease_id)
);

-- Domain IDs are deployment metadata, not encoded ID components. Fixed values
-- keep the counter references deterministic in this disposable environment.
INSERT INTO domains (
    domain_id, domain_name, prefix_type, time_bit, worker_bit,
    sequence_bit, epoch_start_ms, time_unit)
VALUES
    (1, 'z-exchange-wallet', 'POSITIVE_SIGNAL', 41, 10, 12, 1790812800000, 'MILLISECOND'),
    (2, 'z-exchange-ledger', 'POSITIVE_SIGNAL', 41, 10, 12, 1790812800000, 'MILLISECOND');

INSERT INTO counters (counter_ref, counter_name, next_value)
VALUES
    ('worker-id:domain:1', 'Domain worker IDs', 0),
    ('worker-id:domain:2', 'Domain worker IDs', 0);

INSERT INTO generator_configs (domain_id, generator_name, policy)
VALUES
    (1, 'internal-id', JSON_OBJECT(
        'maximumRetry', 0,
        'idRunOutPolicy', 'NONE',
        'clockRollbackPolicy', 'NONE')),
    (2, 'internal-id', JSON_OBJECT(
        'maximumRetry', 0,
        'idRunOutPolicy', 'NONE',
        'clockRollbackPolicy', 'NONE'));
