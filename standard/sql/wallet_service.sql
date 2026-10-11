drop database if exists trade_walletservice;

create database trade_walletservice;

use trade_walletservice;

drop table if exists wallet_transactions;
create table wallet_transactions (
	internal_id bigint unsigned not null primary key,
    /* Base62 references are case-sensitive; utf8mb4_bin preserves their exact spelling. */
    wallet_txn_ref varchar(32) character set utf8mb4 collate utf8mb4_bin not null,
    wallet_txn_business_ref varchar(64) not null,
    initiator tinyint not null,
    txn_status tinyint not null,
    txn_type tinyint not null,
    business_type tinyint not null,
    extra_data text,
    created_at timestamp(3) not null,
    updated_at timestamp(3) not null,
    unique key(wallet_txn_ref),
    /* The upstream request reference is the command-level idempotency identity. */
    unique key uk_wallet_txn_business_ref(initiator, wallet_txn_business_ref)
);

drop table if exists wallet_actions;
create table wallet_actions (
	internal_id bigint unsigned not null primary key,
	wallet_action_ref varchar(32) character set utf8mb4 collate utf8mb4_bin not null,
    wallet_txn_ref varchar(32) character set utf8mb4 collate utf8mb4_bin not null,
    wallet_ref varchar(32) character set utf8mb4 collate utf8mb4_bin not null,
    asset_id varchar(64) not null,
    action_type tinyint not null,
    bucket tinyint not null,
    amount bigint not null,
    wallet_reservation_ref varchar(32) character set utf8mb4 collate utf8mb4_bin default null comment 'RESERVE/CONSUME/RELEASE have this field',
    unique key(wallet_action_ref),
    key(wallet_txn_ref),
    key(wallet_ref),
    key(wallet_reservation_ref)
);

drop table if exists wallet_reservations;
create table wallet_reservations (
	internal_id bigint unsigned not null primary key,
    wallet_reservation_ref varchar(32) character set utf8mb4 collate utf8mb4_bin not null,
    wallet_ref varchar(32) character set utf8mb4 collate utf8mb4_bin not null,
    asset_id varchar(64) not null,
    total bigint not null,
    remaining bigint not null,
	consumed bigint default 0 not null,
    pending_settle bigint default 0 not null,
    released bigint default 0 not null,
    reservation_status tinyint not null comment 'ACTIVE, FINISHED, CLOSED',
    reservation_outcome tinyint not null comment 'the resolution of asset: NOT_DONE when status=ACTIVE, CONSUMED when fully consumed, RELEASED when fully releasd, PARTIAL when partially consumed and released for the rest, CANCELLED when status=CLOSED',
    initiator tinyint not null,
    wallet_reservation_business_ref varchar(64) not null,
    reserve_wallet_txn_ref varchar(32) character set utf8mb4 collate utf8mb4_bin not null,
    unique key(wallet_reservation_ref),
    key(wallet_ref),
    unique key uk_wallet_reservation_business_ref(initiator, wallet_reservation_business_ref),
    key(reserve_wallet_txn_ref)
);

create table wallets (
	internal_id bigint unsigned not null primary key,
    wallet_ref varchar(32) character set utf8mb4 collate utf8mb4_bin not null,
    service_id tinyint not null,
    wallet_business_ref varchar(64) not null,
    asset_id varchar(64) not null,
    wallet_status tinyint not null,
    owner_type tinyint not null,
    owner_id varchar(64) not null,
    unique key(wallet_ref),
    /* One service-owned business reference identifies exactly one wallet. */
    unique key uk_wallet_service_business_ref(service_id, wallet_business_ref),
    key(owner_type, owner_id)
);

drop table if exists balance_snapshots;
create table balance_snapshots (
	id bigint auto_increment primary key,
    wallet_ref varchar(32) character set utf8mb4 collate utf8mb4_bin not null,
	service_id tinyint not null,
    wallet_business_ref varchar(64) not null,
    asset_id varchar(64) not null,
    wallet_status tinyint not null,
	owner_type tinyint not null,
    owner_id varchar(64) not null,
    available bigint default 0 not null,
    reserved bigint default 0 not null,
	last_wallet_txn_ref varchar(32) character set utf8mb4 collate utf8mb4_bin,
    version bigint not null,
    created_at timestamp(3) not null,
    updated_at timestamp(3) not null,
    unique key(wallet_ref),
	unique key(service_id, wallet_business_ref, wallet_ref),
	key(owner_type, owner_id)
);

create table wallet_account_mappings (
	id bigint auto_increment primary key,
	wallet_ref varchar(32) character set utf8mb4 collate utf8mb4_bin not null,
    ledger_account_ref varchar(32) character set utf8mb4 collate utf8mb4_bin not null,
	unique key(wallet_ref, ledger_account_ref),
    unique key(ledger_account_ref)
);

create table outbox (
	id bigint auto_increment primary key,
	event_id varchar(64) not null,
    event_type tinyint not null,
    command_id varchar(64) not null,
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
    unique key(event_id),
    key(command_id),
    /* Oldest-due-first claim filtering, ordering, and tuple-cursor pagination. */
    key idx_outbox_claim(outbox_status, next_attempt_at, id)
);
