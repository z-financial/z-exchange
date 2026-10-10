drop database if exists trade_walletservice;

create database trade_walletservice;

use trade_walletservice;

drop table if exists wallet_transactions;
create table wallet_transactions (
	id bigint auto_increment primary key,
    txn_id varchar(64) not null,
    reference_id varchar(64) not null,
    initiator tinyint not null,
    idempotency_key varchar(64) not null,
    txn_status tinyint not null,
    txn_type tinyint not null,
    business_type tinyint not null,
    extra_data text,
    created_at timestamp(3) not null,
    updated_at timestamp(3) not null,
    unique key(txn_id),
    unique key(initiator, idempotency_key),
    key(reference_id)
);

drop table if exists wallet_actions;
create table wallet_actions (
	id bigint auto_increment primary key,
	action_id varchar(64) not null,
    txn_id varchar(64) not null,
    wallet_id varchar(64) not null,
    asset_id varchar(64) not null,
    action_type tinyint not null,
    bucket tinyint not null,
    amount bigint not null,
    reservation_id varchar(64) default null comment 'RESERVE/CONSUME/RELEASE have this field',
    unique key(action_id),
    key(txn_id),
    key(wallet_id),
    key(reservation_id)
);

drop table if exists wallet_reservations;
create table wallet_reservations (
	id bigint auto_increment primary key,
    reservation_id varchar(64) not null,
    wallet_id varchar(64) not null,
    wallet_reference_id varchar(64) not null,
    asset_id varchar(64) not null,
    total bigint not null,
    remaining bigint not null,
	consumed bigint default 0 not null,
    pending_settle bigint default 0 not null,
    released bigint default 0 not null,
    reservation_status tinyint not null comment 'ACTIVE, FINISHED, CLOSED',
    reservation_outcome tinyint not null comment 'the resolution of asset: NOT_DONE when status=ACTIVE, CONSUMED when fully consumed, RELEASED when fully releasd, PARTIAL when partially consumed and released for the rest, CANCELLED when status=CLOSED',
    initiator tinyint not null,
    reference_id varchar(64) not null,
    reserve_txn_id varchar(64) not null,
    unique key(reservation_id),
    key(wallet_id),
    unique key uk_wallet_reservation_initiator_ref(initiator, reference_id),
    key(reserve_txn_id)
);

create table wallets (
	id bigint auto_increment primary key,
    wallet_id varchar(64) not null,
    service_id tinyint not null,
    reference_id varchar(64) not null,
    asset_id varchar(64) not null,
    wallet_status tinyint not null,
    owner_type tinyint not null,
    owner_id varchar(64) not null,
    unique key(wallet_id),
    /* One service-owned business reference identifies exactly one wallet. */
    unique key uk_wallet_service_reference(service_id, reference_id),
    key(owner_type, owner_id)
);

drop table if exists balance_snapshots;
create table balance_snapshots (
	id bigint auto_increment primary key,
    wallet_id varchar(64) not null,
	service_id tinyint not null,
    wallet_reference_id varchar(64) not null,
    asset_id varchar(64) not null,
    wallet_status tinyint not null,
	owner_type tinyint not null,
    owner_id varchar(64) not null,
    available bigint default 0 not null,
    reserved bigint default 0 not null,
	last_txn_id varchar(64),
    version bigint not null,
    created_at timestamp(3) not null,
    updated_at timestamp(3) not null,
    unique key(wallet_id),
	unique key(service_id, wallet_reference_id, wallet_id),
	key(owner_type, owner_id)
);

create table wallet_account_mappings (
	id bigint auto_increment primary key,
	wallet_id varchar(64) not null,
    account_ref_id varchar(64) not null,
	unique key(wallet_id, account_ref_id),
    unique key(account_ref_id)
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
