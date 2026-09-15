# Table Sharding Evaluation for Ledger / Wallet Services

## Executive verdict

Your design is thinking in the right direction on one point: for many OLTP financial tables, owner-local or ID-hash routing is usually better than pure time routing.

But your current proposal is still too optimistic in three places:

1. `hash(internal-id)` is not viable yet for several tables because the current code does not look them up by internal ID first. It looks them up by `reference_id`, `idempotency_key`, `wallet_ref`, `reservation_ref`, and queue state.
2. Since this is **table sharding inside one MySQL/InnoDB instance**, “avoid hot table first” is **not** the right universal priority. You are not distributing load across machines. You are mostly trading query simplicity for routing complexity inside the same instance.
3. Your strongest blind spot is assuming “a txn only touches a few accounts/wallets, so cross-shard is fine.” That is only true if the router knows which few shards to hit. Today it usually does not.

The biggest disagreement is `outbox`: sharding it by `event_id` is the wrong access model for the code you have.

## Code evidence from the current implementation

The current code paths matter more than abstract theory:

1. `wallet-service/src/main/java/com/exchange/app/wallet/processor/transaction/step/IdempPrecheckProcessor.java:121`
   `wallet_transactions` is checked by `(initiator, idempotency_key)`, not by internal ID.
2. `wallet-service/src/main/java/com/exchange/app/wallet/processor/transaction/step/BeforePostLedgerProcessor.java:81,90`
   `balance_snapshots` is loaded by wallet refs, and `wallet_reservations` is loaded by reservation refs.
3. `wallet-service/src/main/java/com/exchange/app/wallet/processor/transaction/step/AfterPostLedgerProcessor.java:89-110`
   follow-up processing starts from `txnId`, then loads `wallet_actions` by `txnId`, then snapshots by `walletId`, then reservations by reservation refs.
4. `ledger-service/src/main/java/com/exchange/app/ledger/processor/post/PostLedgerProcessor.java:72,136`
   `accounts` are loaded by account refs, and `ledger_transactions` is checked by `reference_id`.
5. `ledger-service/src/main/java/com/exchange/app/ledger/processor/post/GetLedgerTxnProcessor.java:73-74`
   `ledger_entries` is fetched by `txnId`.
6. `common-util/src/main/java/com/exchange/common/outbox/dao/repository/OutboxRepository.java:57`
   `outbox` is polled by `outbox_status + next_attempt_at + id`, not mainly by `event_id`.

This means a shard key is only good if the request can route to the right shard from the keys the application actually has at query time.

## Strategy comparison

### 1. Sharding by time

Good for:

1. retention and archival
2. bounded time-range scans
3. operational drop/truncate of old data

Bad for:

1. hot current partition
2. point lookup when caller does not know the time partition
3. retries, replay, and late-arriving/backdated events
4. owner-centric history queries

Production warning:

For financial systems, “business time” is often not stable enough to be your only route key. Backfill, replay, correction, and delayed messages will hit old partitions. Day-boundary logic also becomes a source of routing bugs.

### 2. Sharding by entity ID

Good for:

1. evenly distributed inserts
2. point lookup by entity ID
3. stable resharding with virtual buckets

Bad for:

1. lookup by alternative unique keys unless you add locators/global indexes
2. time-range scans
3. hot single owner/entity, because one owner still maps to one shard

Production warning:

`hash(internal-id)` is only good after you have a routing-ready ID/ref design. Without that, every lookup by `reference_id` or `idempotency_key` becomes a broadcast.

### 3. Sharding by parent / owner entity

Good for:

1. owner-local reads
2. owner-local state updates
3. forward compatibility with future DB sharding by owner
4. child-table co-location with parent state

Bad for:

1. skew from hot wallets/accounts/system omnibus accounts
2. transaction-centric reads unless you store a route manifest
3. transactions that touch many owners

Production warning:

Owner-locality is usually the right long-term model for stateful financial systems, but it does not solve hot-owner contention. A single hot wallet or hot account still becomes hot.

### 4. Sharding by transaction ID

Good for:

1. reconstructing one transaction and its children
2. follow-up workflows that start from txn ID
3. even write spread

Bad for:

1. wallet/account statements
2. reconciliation by owner
3. forward compatibility with owner-based DB sharding

Production warning:

Transaction-locality is attractive early because code paths often start from `txnId`, but it becomes painful once statement, audit, or owner-level reconciliation becomes first-class.

## On your priority: “avoid hot spot first, accept harder time-range query later”

This is **sometimes** right, but not as a blanket rule.

My judgment:

1. For `wallet_transactions` and `ledger_transactions`, avoiding time-shard hot partitions is reasonable.
2. For `ledger_entries`, `wallet_actions`, and `wallet_reservations`, you should optimize for the dominant business read model, not only insert spread.
3. For `outbox`, hot-spot avoidance is the wrong first principle. Queue polling semantics dominate.

The key challenge to your reasoning is this:

When you only do **table sharding in one DB**, a hot “today table” is not the same as a hot database node. CPU, buffer pool, redo log, disk, and lock manager are still shared. You gain some B-tree/index fan-out, but not true horizontal scale. Because of that, routing correctness and query shape matter more than abstract shard-balance purity.

## Table-by-table evaluation

### `Account`

Your options:

1. time
2. hash(internal-id)

Evaluation:

1. **Write distribution / hotspot risk**
   Low risk. This table is create-once, low-churn master data. It is not your hot path.
2. **Read/query efficiency**
   Current reads are by `service_id + reference_id` and by account ref batch lookup.
3. **Cross-shard query cost**
   If you hash by internal ID without a locator, `reference_id` lookup becomes a broadcast.
4. **Operational complexity**
   High relative to the value gained.
5. **Future scalability**
   Fine either unsharded or owner-routed later.
6. **Failure modes / hidden edge cases**
   Duplicate enforcement on `service_id + reference_id` becomes hard without a locator/global unique index.

Assessment of your preference:

1. Preferring hash over time is directionally right.
2. Sharding `Account` now is weak.

Recommendation:

1. **Do not shard yet.**
2. If you must shard later, route by a locator-bearing `account_ref` / route-bearing account identity, not by time.

### `LedgerTxn`

Your options:

1. time
2. hash(internal-id)

Evaluation:

1. **Write distribution / hotspot risk**
   Moderate but not severe. This is append-heavy but still metadata-sized.
2. **Read/query efficiency**
   Current code reads by `reference_id` and by `txn_id`.
3. **Cross-shard query cost**
   Hash-by-internal-ID is fine only if `ledger_txn_ref` or a locator lets you route from `reference_id`.
4. **Operational complexity**
   Medium.
5. **Future scalability**
   Good if routed by logical bucket/ref, weak if routed only by raw generated ID.
6. **Failure modes / hidden edge cases**
   Retries and idempotency lookup will broadcast unless `reference_id -> shard` is resolvable.

Assessment of your preference:

1. Preferring hash over time is reasonable.
2. Your reasoning is weak because it ignores the `reference_id` lookup path.

Recommendation:

1. **Do not shard yet unless volume demands it.**
2. If you shard, use **route-bearing `ledger_txn_ref` / locator-based routing**, not raw “hash internal ID and hope.”

### `LedgerEntry`

Your options:

1. time
2. account.internal-id
3. ledger-txn.internal-id

Evaluation:

1. **Write distribution / hotspot risk**
   `account` sharding is usually acceptable, but hot omnibus/system accounts can skew badly.
   `txn` sharding spreads inserts well.
   time sharding creates a hot current partition.
2. **Read/query efficiency**
   For a real ledger, owner/account reads are more important than transaction drilldown alone.
   `account` routing is best for account statement and balance audit.
   `txn` routing is best for “show one ledger txn with its entries.”
3. **Cross-shard query cost**
   `account` routing makes txn drilldown multi-shard.
   `txn` routing makes account statements multi-shard.
   time routing makes owner queries and txn lookup expensive unless time is known.
4. **Operational complexity**
   `account` routing needs a txn-to-entry-shard manifest if you want efficient txn drilldown.
5. **Future scalability**
   `account` routing aligns best with future ledger/account DB sharding.
6. **Failure modes / hidden edge cases**
   Hot clearing/house accounts can destroy shard balance.
   Without a route manifest, “few entries means few shards” is not enough. The code still only has `txnId` during `GetLedgerTxn`.

Assessment of your preference:

1. Your instinct toward owner-locality is strong.
2. Your assumption that “few entries means cross-shard is okay” is weak because routing metadata is missing.
3. Your “ES for large query” idea is weak for reconciliation. ES is not a ledger-of-record.

Recommendation:

1. **Best long-term key: owner/account locality**, not txn locality.
2. I agree with **B more than C**, but only with a major caveat:
   you need a **txn route manifest** or `ledger_txn` metadata storing involved account refs/shards.
3. If you expect very hot system accounts, the better production design is often:
   **account bucket + time partition (for example month)**, not pure account only.

### `Outbox`

Your option:

1. event-id / internal-id of source entity

Evaluation:

1. **Write distribution / hotspot risk**
   Moderate append rate, but queue polling semantics dominate more than insert distribution.
2. **Read/query efficiency**
   Current code mainly polls by `outbox_status`, `attempt_count`, `next_attempt_at`, and `id`.
3. **Cross-shard query cost**
   Hash by `event_id` forces the dispatcher to poll every shard and merge due work.
4. **Operational complexity**
   Very high if sharded. The dispatcher and retry flow must change.
5. **Future scalability**
   Better solved by queue redesign, native CDC, or time-based retention strategy than by event-id sharding.
6. **Failure modes / hidden edge cases**
   Missed retries, uneven worker polling, duplicate claims, and operational blind spots.

Assessment of your preference:

1. I disagree.
2. Your reasoning is weak because it uses a rare lookup path (`event_id`) instead of the dominant one (due work polling).

Recommendation:

1. **Do not shard outbox by `event_id`.**
2. **Best recommendation: do not shard outbox yet.**
3. If it must evolve, choose one of these:
   keep one table and optimize the due-work index and retention
   or shard by `partition_key` / worker-owned shard with a redesigned dispatcher
   or replace the table-polled outbox with CDC/binlog-based delivery

This recommendation applies to both services even though you only listed ledger outbox. The code uses the same shared outbox repository model.

### `Wallet`

Your options:

1. time
2. hash(internal-id)

Evaluation:

1. **Write distribution / hotspot risk**
   Low. This is master data, not the hottest transactional table.
2. **Read/query efficiency**
   Current reads are by `wallet_id` and `service_id + reference_id`.
3. **Cross-shard query cost**
   Internal-ID sharding without a `wallet_ref`/locator makes create/idempotent read-by-reference awkward.
4. **Operational complexity**
   High relative to value.
5. **Future scalability**
   Better to make routing-ready first, then decide if sharding is necessary.
6. **Failure modes / hidden edge cases**
   Duplicate wallet creation semantics depend on `service_id + reference_id`.

Assessment of your preference:

1. Preferring hash over time is directionally right.
2. Sharding `Wallet` now is weak.

Recommendation:

1. **Do not shard yet.**
2. If forced later, route by `wallet_ref` / route-bearing owner identity, not by time.

### `WalletTransaction`

Your options:

1. time
2. hash(internal-id)

Evaluation:

1. **Write distribution / hotspot risk**
   High enough that time sharding is unattractive.
2. **Read/query efficiency**
   Current reads are by `txn_id` and, critically, by `(initiator, idempotency_key)`.
3. **Cross-shard query cost**
   Hash-by-internal-ID alone breaks idempotency lookup unless you add a locator/global index.
4. **Operational complexity**
   Medium to high.
5. **Future scalability**
   Good only if you introduce stable route-bearing refs or a locator table.
6. **Failure modes / hidden edge cases**
   Retry path broadcasts.
   Unique enforcement on `(initiator, idempotency_key)` becomes non-local.

Assessment of your preference:

1. Preferring non-time routing is correct.
2. Your reasoning is weak because it ignores the most important alternate key in the table: idempotency.

Recommendation:

1. **If you shard, do not route solely by generated internal ID.**
2. Better production choice:
   route by a stable create-time locator such as `wallet_txn_ref`
   and keep an unsharded or globally-routable locator on `(initiator, idempotency_key)`
3. If you are not ready to build that locator, **do not shard yet**.

### `WalletAction`

Your options:

1. time
2. wallet.internal-id
3. wallet-txn.internal-id

Evaluation:

1. **Write distribution / hotspot risk**
   Wallet-local routing can skew on hot wallets.
   Txn routing spreads better.
   Time creates a hot current partition.
2. **Read/query efficiency**
   Wallet-local routing is better for wallet history and statement-like reads.
   Txn routing is better for current code because `AfterPostLedgerProcessor` starts from `txnId`.
3. **Cross-shard query cost**
   Wallet-local routing makes txn-follow-up multi-shard unless you store the route set on `wallet_transaction`.
4. **Operational complexity**
   High if owner-routed without a manifest.
5. **Future scalability**
   Wallet-local aligns better with future owner-sharded wallet state.
6. **Failure modes / hidden edge cases**
   A hot wallet remains hot.
   Without route metadata, `selectByTxnId` becomes a broadcast.

Assessment of your preference:

1. Your argument that wallet-based reads are frequent is strong.
2. Your argument that “txn only has few actions” is weak because the system still only knows `txnId` at follow-up time.

Recommendation:

1. **Long-term best key: wallet locality.**
2. **Short-term, do not shard yet** unless you first add:
   a `wallet_txn -> affected wallet shard list` manifest
   or equivalent route metadata on `wallet_transaction`
3. If you refuse to add a manifest and must shard immediately, `wallet-txn` routing is operationally simpler with the current code, but it is the worse long-term model.

### `WalletReservation`

Your options:

1. balance-snapshot.internal-id
2. wallet-transaction.internal-id
3. time

Evaluation:

1. **Write distribution / hotspot risk**
   Reservations are not just append history. They are mutable lifecycle state.
2. **Read/query efficiency**
   They are logically wallet-owned state.
   Current reads are by reservation ref and sometimes by reserve txn.
3. **Cross-shard query cost**
   Txn routing helps creation-time lookup by txn, but later reservation-ref lookup becomes ugly.
4. **Operational complexity**
   High if routed by txn while wallet state is routed by wallet.
5. **Future scalability**
   Best aligned with wallet-local state.
6. **Failure modes / hidden edge cases**
   Sharding by snapshot internal ID is the wrong abstraction. Snapshot is effectively wallet state here.
   Reservation refs must route, or request-time reservation lookup broadcasts.

Assessment of your preference:

1. I partly agree with the owner-local instinct.
2. I disagree with using `balance_snapshot` identity as the formal route key.

Recommendation:

1. **Best key: wallet locality**, not snapshot ID and not txn ID.
2. Since `balance_snapshot` is 1:1 with wallet, just say that directly:
   shard by `wallet.internal-id` / `wallet_ref`
3. If you cannot make `reservation_ref` route to the wallet shard, **do not shard yet**.

### `BalanceSnapshot`

Your option:

1. wallet.internal-id

Evaluation:

1. **Write distribution / hotspot risk**
   This is the real wallet-state hot table.
   Wallet-local routing is correct, but it does **not** solve hot-wallet row contention.
2. **Read/query efficiency**
   Excellent. Reads and updates are by wallet.
3. **Cross-shard query cost**
   Low for wallet reads, high for cross-wallet batch reads.
4. **Operational complexity**
   Medium.
5. **Future scalability**
   Best-aligned with future wallet DB sharding.
6. **Failure modes / hidden edge cases**
   A single hot wallet still serializes on one row/shard.

Assessment of your preference:

1. This is reasonable.
2. The weak part is expecting it to fix hotspot problems that are actually row-level wallet contention problems.

Recommendation:

1. **Wallet locality is the right route key.**
2. But I would still ask whether this table needs sharding yet.
3. If your real problem is hot-wallet throughput, you need wallet-level serialization / actor model / partitioned command processing, not just table sharding.

### `WalletAccountMapping`

Your option:

1. wallet.internal-id

Evaluation:

1. **Write distribution / hotspot risk**
   Negligible.
2. **Read/query efficiency**
   Wallet routing is correct.
3. **Cross-shard query cost**
   Low if wallet-routed.
4. **Operational complexity**
   Not worth early sharding.
5. **Future scalability**
   Fine either way.
6. **Failure modes / hidden edge cases**
   Very small table; operational overhead can outweigh the benefit.

Assessment of your preference:

1. Reasonable if this table ever gets sharded.
2. But it almost certainly should not be sharded yet.

Recommendation:

1. **Keep unsharded for now.**
2. If later sharded, align with wallet.

## Where your reasoning is strong

1. You correctly distrust time-based sharding for high-write OLTP tables.
2. You correctly prefer owner-locality over txn-locality for some child tables.
3. You correctly recognize that a few-wallet / few-account query is common and important.
4. You correctly note that wallet/ledger txns usually fan out to a small set of children.

## Where your reasoning is weak

1. You are underestimating the cost of alternate-key routing.
   `reference_id`, `idempotency_key`, `wallet_ref`, and `reservation_ref` are not secondary concerns. They are core query keys.
2. You are overestimating how much same-DB table sharding solves “hot spot” problems.
3. You are relying too much on “query all shards for now” for financial reconciliation/reporting.
   That becomes expensive exactly when incident handling and month-end operations happen.
4. You are leaning on ES as a fallback for large financial queries.
   ES is acceptable for search/exploration, not as the authoritative answer for reconciliation.
5. You are not yet separating:
   metadata tables
   owner state tables
   append-only history tables
   queue tables

Those categories want different sharding strategies.

## Where this design can break at real production scale

1. **Broadcast reads by non-shard keys**
   This will hit `wallet_transactions`, `ledger_transactions`, `wallet_actions`, `wallet_reservations`, `wallets`, and `accounts` unless you add locators.
2. **Shard skew from hot owners**
   House accounts, omnibus accounts, fee wallets, or platform wallets can dominate one owner shard.
3. **Outbox polling fan-out**
   A sharded queue table without a redesigned dispatcher becomes noisy and fragile.
4. **Operational overhead explosion**
   More tables, more DDL, more migrations, more monitoring, more backup/restore complexity.
5. **Future DB sharding dead-end**
   If you route mutable wallet state by txn while balances route by wallet, you are creating future cross-DB transaction pain.
6. **Incident-time reconciliation pain**
   Broadcast scans across many shards are survivable in theory but painful under pressure.

## When to align child tables with parent entity shards

Do it when:

1. the child is usually read/updated with the parent
2. the child is part of the parent’s consistency boundary
3. future DB sharding should colocate them

That strongly applies to:

1. `balance_snapshots`
2. `wallet_reservations`
3. probably `wallet_actions` long-term
4. probably `ledger_entries` with `account` long-term

## When transaction-locality is better than owner-locality

Use transaction-locality when:

1. nearly all reads start from txn ID
2. owner history is secondary
3. the child rows are mostly ephemeral attachments to one txn

That is more defensible for:

1. `wallet_actions` in the short term, given the current callback flow
2. not very defensible for `ledger_entries` in a real accounting system

## Tables that should probably not be sharded yet

I would delay sharding for:

1. `Account`
2. `Wallet`
3. `WalletAccountMapping`
4. `Outbox`
5. likely `BalanceSnapshot` unless table size, not row hotness, is already a problem
6. possibly `LedgerTxn` and `WalletTransaction` until routing locators exist

## Tables that need composite routing logic, not just one shard key

The current model needs more than “pick one hash key”:

1. `LedgerEntry`
   If routed by account, `ledger_txn` needs a manifest of involved account shards.
2. `WalletAction`
   If routed by wallet, `wallet_transaction` needs a manifest of affected wallet shards.
3. `WalletReservation`
   `reservation_ref` must resolve to the wallet shard.
4. `WalletTransaction`
   idempotency lookup needs a locator on `(initiator, idempotency_key)`.
5. `LedgerTxn`
   retry lookup needs a locator on `reference_id`.

## Whether secondary indexes / global indexes / middleware change the conclusion

Yes, but only partially.

1. **Local secondary indexes**
   Useful only after the router already knows the target shard.
   They do not solve broadcast lookup.
2. **Global indexes / locator tables**
   These change the conclusion a lot.
   They are what make ID-hash sharding practical for `wallet_transactions`, `ledger_transactions`, `wallets`, and `accounts`.
3. **Sharding middleware**
   Can hide some routing complexity, but it adds another failure and operational layer.
4. **ES / search middleware**
   Good for search UI and exploratory reporting.
   Not sufficient as the source of truth for reconciliation.

My practical bias:

For this system, a few **small, explicit locator tables** are likely better than heavy sharding middleware.

## Phased strategy

### Phase 0: make the system routing-ready before sharding

Implement first:

1. `internal_id`
2. route-bearing `*_ref`
3. `route_version + virtual_bucket`
4. routing config indirection
5. locator tables for alternate keys

Suggested locators:

1. `wallet_locator(service_id, reference_id -> wallet_ref)`
2. `account_locator(service_id, reference_id -> account_ref)`
3. `wallet_txn_locator(initiator, idempotency_key -> wallet_txn_ref)`
4. `ledger_txn_locator(reference_id -> ledger_txn_ref)`
5. `reservation_locator(reservation_ref -> wallet_ref)`

### Phase 1: shard only the tables that actually need it

First candidates:

1. `ledger_entries`
2. `wallet_reservations`
3. maybe `wallet_actions`

Do not shard first:

1. `outbox`
2. `accounts`
3. `wallets`
4. `wallet_account_mappings`

### Phase 2: redesign query paths that are currently broadcast-prone

Before sharding child tables:

1. add `wallet_transaction -> affected wallet shard list`
2. add `ledger_transaction -> affected account shard list`
3. stop relying on “query all shards for now” for correctness-sensitive flows

### Phase 3: decide whether metadata tables even need sharding

After route-ready IDs and locators exist, revisit:

1. `wallet_transactions`
2. `ledger_transactions`
3. `balance_snapshots`

## Forward compatibility for future DB sharding

If DB sharding is likely later, make today’s design support that future:

1. Use **logical buckets**, not physical shard numbers, in refs.
2. Keep **virtual buckets > physical shards**.
3. Make routing depend on **stable owner/entity locators**, not current table counts.
4. Store **route-bearing refs** in cross-service messages and relational references.
5. Prefer owner-local routing for mutable wallet state.
6. Accept that some metadata tables may stay global or semi-global.

Practical future model:

1. Wallet domain:
   colocate `wallet`, `balance_snapshot`, `wallet_reservation`, `wallet_account_mapping`, and probably `wallet_action` by wallet route
2. Ledger domain:
   colocate `ledger_entry` by account route
   keep `ledger_txn` as metadata + manifest if one txn can touch many accounts
3. Keep locator tables or a route service for alternate-key lookups

## Overall verdict

Your current preferences are **not bad**, but they are **not production-ready as stated**.

The main corrections are:

1. do not overvalue “avoid hot table” inside one DB
2. do not shard `outbox` by `event_id`
3. do not assume “few related shards” is enough without route metadata
4. do not shard by raw internal ID until alternate-key locators exist
5. do not use ES as the primary answer for authoritative reconciliation

## Table-by-table recommendation

1. `Account`
   do not shard yet
2. `LedgerTxn`
   do not shard yet; later route by `ledger_txn_ref` / locator, not time
3. `LedgerEntry`
   shard by account locality; consider account bucket + month if hot system accounts appear
4. `Outbox`
   do not shard by `event_id`; ideally keep unsharded for now
5. `Wallet`
   do not shard yet
6. `WalletTransaction`
   do not shard yet unless you add idempotency locator; later route by `wallet_txn_ref`
7. `WalletAction`
   long-term shard by wallet; short-term delay until txn-to-wallet route manifest exists
8. `WalletReservation`
   shard by wallet locality, not snapshot ID, not txn ID
9. `BalanceSnapshot`
   wallet locality is correct, but sharding does not solve hot-wallet contention
10. `WalletAccountMapping`
    keep unsharded now; later align with wallet if needed

## Revised sharding plan

1. First build routing foundations: `internal_id`, `*_ref`, virtual buckets, locators.
2. Keep `Account`, `Wallet`, `WalletAccountMapping`, and `Outbox` unsharded for now.
3. Keep `LedgerTxn` and `WalletTransaction` unsharded until alternate-key locators exist.
4. First real sharding candidate: `LedgerEntry` by account locality.
5. Next: `WalletReservation` by wallet locality.
6. Then `WalletAction` by wallet locality, but only after adding txn route manifest.
7. Treat `BalanceSnapshot` as wallet-routed state if it must shard, but solve hot-wallet throughput separately.
