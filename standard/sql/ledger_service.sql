drop database if exists trade_ledgerservice;

create database trade_ledgerservice;

use trade_ledgerservice;

create table assets (
	id bigint auto_increment primary key,
    asset_id varchar(64) not null,
    asset_type tinyint not null,
    symbol varchar(64) not null,
    decimals int not null,
    extra_data text,
    unique key(asset_id)
);
insert into assets (asset_id, asset_type, symbol, decimals) values ("asset-1", 1, "USD", 1), ("asset-2", 1, "CNY", 1);

create table accounts (
	internal_id bigint unsigned not null primary key,
    /* Base62 references are case-sensitive; utf8mb4_bin preserves their exact spelling. */
    ledger_account_ref varchar(32) character set utf8mb4 collate utf8mb4_bin not null,
    source_service_id tinyint not null,
    source_account_ref varchar(32) character set utf8mb4 collate utf8mb4_bin not null,
    category tinyint not null,
    normal_side tinyint not null,
    owner_id varchar(64) not null,
    owner_type tinyint not null,
    asset_id varchar(64) not null,
    account_status tinyint not null,
    extra_data text,
    unique key(ledger_account_ref),
    unique key(source_service_id, source_account_ref),
    key(owner_type, owner_id)
);
create table ledger_entries (
	internal_id bigint unsigned not null primary key,
    /* Base62 references are case-sensitive; utf8mb4_bin preserves their exact spelling. */
    ledger_entry_ref varchar(32) character set utf8mb4 collate utf8mb4_bin not null,
    ledger_txn_ref varchar(32) character set utf8mb4 collate utf8mb4_bin not null,
    asset_id varchar(64) not null,
    ledger_account_ref varchar(32) character set utf8mb4 collate utf8mb4_bin not null,
    amount bigint not null,
    direction tinyint not null,
    unique key(ledger_entry_ref),
    key(ledger_txn_ref, internal_id),
    key(ledger_account_ref, direction)
);

create table ledger_transactions (
	internal_id bigint unsigned not null primary key,
    /* Base62 references are case-sensitive; utf8mb4_bin preserves their exact spelling. */
    ledger_txn_ref varchar(32) character set utf8mb4 collate utf8mb4_bin not null,
    /* TODO: Populate this when the Ledger transaction contract supports multiple upstream services. */
    source_service_id tinyint,
    /* The upstream transaction identity is distinct from the generated Ledger identity. */
    source_txn_ref varchar(32) character set utf8mb4 collate utf8mb4_bin not null,
    metadata json not null default (JSON_OBJECT()),
    version bigint not null,
    created_at timestamp(3) not null,
    updated_at timestamp(3) not null,
    unique key(ledger_txn_ref),
    unique key(source_txn_ref)
);

create table outbox (
	internal_id bigint unsigned not null primary key,
	/* Base62 event references are case-sensitive; utf8mb4_bin preserves their exact spelling. */
	event_ref varchar(32) character set utf8mb4 collate utf8mb4_bin not null,
    event_type tinyint not null,
    command_ref varchar(32) character set utf8mb4 collate utf8mb4_bin not null,
    outbox_status tinyint not null,
    destination varchar(64) not null,
    partition_key varchar(64) not null,
    payload blob not null,
    attempt_count int not null,
    last_attempt_at timestamp(3),
    next_attempt_at timestamp(3) not null,
    last_error varchar(128),
    finalized_at timestamp(3),
    metadata json,
    created_at timestamp(3) not null,
    updated_at timestamp(3) not null,
    unique key(event_ref),
    key(command_ref),
    /* Oldest-due-first claim filtering, ordering, and tuple-cursor pagination. */
    key idx_outbox_claim(outbox_status, next_attempt_at, internal_id)
);

