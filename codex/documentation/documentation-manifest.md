# z-exchange Documentation Manifest

> Persistent working state for the documentation project.
>
> Read this file before every documentation task. Update it when designs are discovered, documents are created/merged, implementation evidence changes, or verification status changes.

## Status legend

| Status | Meaning |
|---|---|
| `DISCOVERED` | Relevant design identified; document not started |
| `IN_PROGRESS` | Current bounded task is investigating/writing it |
| `DRAFTED` | Document exists but has not completed an independent verification pass |
| `VERIFIED` | Important claims checked against current implementation/evidence |
| `BLOCKED` | Important unresolved uncertainty prevents responsible completion |
| `NEEDS_REVERIFY` | Previously verified doc may be stale after implementation change |
| `DEFERRED` | Intentionally postponed |
| `MERGED` | Topic is covered canonically by another design document |

## Priority legend

- `P0` — central to the project/interview story; must be documented deeply
- `P1` — important supporting engineering design
- `P2` — useful supporting/reference material
- `P3` — low-value or completeness-only topic

---

# 1. Proposed documentation tree

> Discovery should update this tree. Do not create files merely because a path is proposed here.

```text
README.md                                    # project documentation entry point

design/
  business/
    system-overview.md                        # completed cross-system synthesis
    end-to-end-transaction-flow.md            # completed Wallet/Ledger flow synthesis

  wallet/
    wallet-service-overview.md                # completed module synthesis
    transaction-lifecycle-and-settlement.md   # D-013
    balance-and-concurrency-correctness.md     # D-014
    reservation-lifecycle.md                  # D-015
    transaction-idempotency-and-replay.md     # D-016
    balance-query-and-cache.md                 # D-017

  ledger/
    ledger-service-overview.md              # completed module synthesis; D-012 is deferred
    posting-and-accounting-correctness.md   # D-007
    posting-idempotency-and-replay.md       # D-008
    async-posting-and-reply.md              # D-009
    transaction-query-and-cache.md          # D-010

  consistency/
    wallet-ledger-consistency.md              # completed synthesis from both services

  infrastructure/
    common-util-overview.md                 # common-util module synthesis
    result-and-error-contract.md            # D-005
    database-foundation.md                  # D-004
    redis-idempotency-coordination.md       # D-002 current implementation; planned document
    redis-cache-coherency.md                # D-003
    kafka-outbox-reliability.md             # D-001

  proposals/                               # future designs; not implemented
    redis-idempotency-db-authoritative.md   # D-002 target protocol
    public-infrastructure-error-boundary-policy.md # D-005 accepted policy; implementation pending
    ledger-reply-event-identity.md          # D-001 candidate optimization
    inbox-outbox-processing.md             # D-001 alternative architecture
    async-wallet-immediate-outbox-publish.md # D-001 API-latency optimization

  operations/
    observability.md                        # D-006
    ledger-performance.md                   # D-011
    wallet-performance.md                   # D-018
```

Existing documents outside `./design` are research-only inputs. Their useful claims must be validated and restated inside the broader canonical owners; generated `./design` documents must not link readers to them:

- `doc/db/outbox-claim-index.md` informs D-001 during research only.
- `doc/monitor/mybatis-statement-metrics-design.md` informs D-004/D-006 during research only.
- `engineering-notes/**` contains useful history and evaluations, but it is non-canonical and sometimes describes superseded class names or adoption state.

---

# 2. Design inventory

> D-001–D-006 came from the earlier `common-util` pass. D-007–D-012 came from the `ledger-service` pass. D-013–D-019 are the `wallet-service`-only discovery records added by this pass; other modules were inspected only where they define Wallet's real dependencies or cross-service contract.

| ID | Design/domain | Priority | Modules | Proposed canonical doc | Depends on | Status |
|---|---|---:|---|---|---|---|
| D-001 | Kafka transport, listener durability, and transactional-outbox recovery | P0 | common-util; adopted by wallet-service and ledger-service | `design/infrastructure/kafka-outbox-reliability.md` | D-002, D-004, D-005, D-006; D-008, D-009, D-013, D-016 for adopter guarantees | VERIFIED |
| D-002 | Layered Redis idempotency coordination | P0 | common-util; adopted by wallet-service and ledger-service | `design/infrastructure/redis-idempotency-coordination.md` | D-004, D-005, D-006; D-008, D-016 | VERIFIED |
| D-003 | Redis cache-coherency and hot-data strategies | P1 | common-util; adopted by wallet-service and ledger-service | `design/infrastructure/redis-cache-coherency.md` | D-004, D-005, D-006 | VERIFIED |
| D-004 | Database transaction, persistence, exception, and SQL-metrics foundation | P1 | common-util; adopted by wallet-service and ledger-service | `design/infrastructure/database-foundation.md` | D-005, D-006 | VERIFIED |
| D-005 | Shared Result, error taxonomy, exception, and boundary-mapping contract | P1 | common-util; adopted by wallet-service and ledger-service | `design/infrastructure/result-and-error-contract.md` | — | VERIFIED |
| D-006 | Shared application observability instrumentation | P1 | common-util, monitoring; adopted by wallet-service and ledger-service | `design/operations/observability.md` | D-001, D-002, D-003, D-004, D-005 | VERIFIED |
| D-007 | Ledger posting and accounting correctness | P0 | ledger-service; ledger-proto | `design/ledger/posting-and-accounting-correctness.md` | D-004, D-005; D-008 for duplicate/replay behavior | VERIFIED |
| D-008 | Ledger posting idempotency, concurrency, and replay | P0 | ledger-service; common-util idempotency/DB | `design/ledger/posting-idempotency-and-replay.md` | D-002, D-004, D-005, D-007 | VERIFIED |
| D-009 | Ledger asynchronous command processing and durable replies | P0 | ledger-service; common-util Kafka/outbox; Wallet as caller/consumer | `design/ledger/async-posting-and-reply.md` | D-001, D-005, D-007, D-008; `design/consistency/wallet-ledger-consistency.md` for synthesis | VERIFIED |
| D-010 | Ledger transaction query and cache read model | P1 | ledger-service; common-util cache/DB | `design/ledger/transaction-query-and-cache.md` | D-003, D-004, D-005, D-007 | VERIFIED |
| D-011 | Ledger observability and performance evidence | P1 | ledger-service; stress-test; monitoring | `design/operations/ledger-performance.md` | D-004, D-006, D-007, D-008, D-009, D-010 | VERIFIED |
| D-012 | Account and asset reference-data bootstrap | P3 | ledger-service; ledger-proto; Wallet account-creation caller | No document currently planned | D-004, D-005 | DEFERRED |
| D-013 | Wallet transaction lifecycle and asynchronous settlement | P0 | wallet-service; wallet-proto; Ledger command/reply contract | `design/wallet/transaction-lifecycle-and-settlement.md` | D-001, D-005, D-007, D-009, D-014, D-015, D-016 | VERIFIED |
| D-014 | Wallet balance model and concurrency correctness | P0 | wallet-service; common-util DB/cache support | `design/wallet/balance-and-concurrency-correctness.md` | D-003, D-004, D-005 | VERIFIED |
| D-015 | Wallet reservation lifecycle and no-double-spend state | P0 | wallet-service; wallet-proto | `design/wallet/reservation-lifecycle.md` | D-004, D-005, D-014 | VERIFIED |
| D-016 | Wallet transaction idempotency, concurrency, and replay | P0 | wallet-service; common-util idempotency/DB | `design/wallet/transaction-idempotency-and-replay.md` | D-002, D-004, D-005, D-014 | VERIFIED |
| D-017 | Wallet balance query and cache read model | P1 | wallet-service; common-util cache/DB | `design/wallet/balance-query-and-cache.md` | D-003, D-004, D-005, D-014 | VERIFIED |
| D-018 | Wallet observability and performance evidence | P1 | wallet-service; stress-test; monitoring | `design/operations/wallet-performance.md` | D-001, D-004, D-006, D-013, D-014, D-015, D-016, D-017 | VERIFIED |
| D-019 | Wallet and Ledger-account provisioning | P3 | wallet-service; ledger AccountService; wallet/ledger proto | No detailed document currently planned | D-004, D-005, D-012, D-017 | DEFERRED |

## 2.1 Current adoption snapshot

| Capability | Adoption | Current evidence / qualification |
|---|---|---|
| `Result` and namespaced error model | `CURRENTLY USED` | Pervasive in common-util and both services; service-local boundary/proto mappers keep common infrastructure errors off public APIs. |
| `DbTxnExecutor`, `DbBaseRepository`, DB translation, MyBatis metrics | `CURRENTLY USED` | Both servers import `CommonDbComponentRegister`; service repositories extend `DbBaseRepository`; transaction processors and outbox retry use `DbTxnExecutor`. |
| Raw and versioned Redis cache strategies | `CURRENTLY USED` | Ledger uses raw reference and versioned transaction caches; Wallet uses raw reference and versioned balance-snapshot caches. Both choose exception-suppressing, metric-decorated strategies. |
| Redisson server-assisted client-side cache | `PARTIALLY ADOPTED` | Wallet promotes system-owned balance/ref keys to client-side read strategies; Ledger uses only normal Redis reads. |
| Negative-cache and tombstone features | `PARTIALLY ADOPTED` | Both use negative cache for external reference lookups; Wallet also enables it for promoted hot snapshots. Versioned tombstones protect cache-aside updates. |
| `IdempotencyClient` Result-returning wrapper | `CURRENTLY USED` | Both `PostLedgerProcessor` and Wallet transaction pre/post-processing use it. An older engineering note saying Wallet remains on raw `IdempRedisClient` is stale. |
| Outbox repository, publisher, and retry handler | `CURRENTLY USED` | Both services wire `DefaultPublisher` + `MeteredPublisher`, `OutboxRetryHandler`, a scheduled dispatcher, and the common outbox mapper/table shape. Atomic insertion with business state remains a service call-site responsibility. |
| Common Kafka listener workflow | `CURRENTLY USED` | Ledger uses `OutboxReplyDefaultMessageHandler`; Wallet uses `AckOnlyDefaultMessageHandler` with an explicit DLT failure policy. Both use the retry-topic container factory. |
| Wallet direct Ledger posting gRPC client | `AVAILABLE BUT CURRENTLY UNUSED` | `PostServiceClient` exists, but current Wallet transaction code creates a durable command outbox and uses Kafka. The only active synchronous Ledger dependency is account provisioning during `createWallet`. |
| Wallet arithmetic conditional-update helpers | `AVAILABLE BUT CURRENTLY UNUSED` | `reserveFromWalletId`, `releaseFromWalletId`, `transferOutFromWalletId`, and `transferInToWalletId` contain SQL arithmetic guards, but the active transaction path uses `SELECT ... FOR UPDATE`, application mutation/invariant checks, and guarded updates through `UpdateBalanceSnapshotProcessor`. |
| Shared Micrometer instrumentation | `CURRENTLY USED` | Both services register the common gRPC interceptor and common DB/Redis/cache/idempotency/Kafka metrics; Prometheus and provisioned Grafana dashboards consume those metrics. |
| Blocking `DefaultErrorHandler` listener factory | `AVAILABLE BUT CURRENTLY UNUSED` | `concurrentCommandKafkaListenerContainerFactory` is registered, but current service listeners use `@RetryableTopic` and `concurrentRetryTopicCommandKafkaListenerContainerFactory`. |
| `CacheExceptionToResultAop` | `AVAILABLE BUT CURRENTLY UNUSED` | Source comment says it was replaced by explicit exception downgrade wrappers in `StrategyFactory`; no active annotation usage was found. |
| Caffeine dependency | `AVAILABLE BUT CURRENTLY UNUSED` | Declared by `common-util/pom.xml`; no active main-source usages found. It is not the implementation behind the current Redisson client-side cache. |

---

# 3. Detailed design records

## D-001 — Kafka transport, listener durability, and transactional-outbox recovery

**Priority:** P0
**Status:** VERIFIED
**Canonical document:** `design/infrastructure/kafka-outbox-reliability.md`
**Modules/services:** `common-util`, with focused adoption evidence from `wallet-service` and `ledger-service`
**Owner topic:** Shared outbox state/claim mechanics, Kafka envelope publishing, listener acknowledgment/retry/DLT policy, and the limits of the resulting delivery guarantee.

### Why this deserves documentation

- It is the reusable reliability mechanism between Wallet and Ledger and contains the most important cross-process failure windows.
- It deliberately implements at-least-once delivery: durable outbox state and idempotent consumers matter more than a source-code tour of Kafka classes.
- The common listener supports two materially different service contracts: durable reply-outbox creation and ack-only terminal consumption.

### Questions the document must answer

- Which layer owns creation of an outbox row, atomic co-commit with business state, immediate publishing, retry claiming, and finalization?
- What exactly constitutes publisher success, what does the three-second timeout mean, and what happens if the broker acknowledges after the timeout?
- Why are records acknowledged only after a durable listener action, and how do retryable, non-retryable, poison-envelope, retry-exhausted, and ack-failure cases differ?
- How do `event_id` uniqueness and `insertWithClaimAndVerify` distinguish same-intent replay, ambiguity, and conflict?
- What ordering is provided by `partitionKey`, and what ordering is not guaranteed by retry topics or across transactions?
- How do the oldest-due-first tuple cursor, `FOR UPDATE SKIP LOCKED`, fixed right boundary, and page-wide concurrent publishing behave under multiple workers?
- Which guarantees come from common-util itself, and which require correct service transaction boundaries and consumer idempotency?

### Implementation evidence

| Evidence type | Path / symbol / artifact | What it supports |
|---|---|---|
| Source | `common-util/src/main/java/com/exchange/common/outbox/po/Outbox.java`, `common-util/src/main/java/com/exchange/common/outbox/po/enums/OutboxStatus.java` | Shared PENDING/SENT/DEAD data model and routing/lifecycle fields. |
| Source | `common-util/src/main/java/com/exchange/common/outbox/dao/repository/OutboxRepository.java` | Duplicate-intent decision, guarded finalization, tuple-cursor claim query, claim update. |
| Source | `common-util/src/main/java/com/exchange/common/outbox/retry/OutboxRetryHandler.java` | Short claim transaction, concurrent page sends, acknowledged-only finalization, at-least-once semantics, missing DEAD transition TODO. |
| Source | `common-util/src/main/java/com/exchange/common/kafka/producer/DefaultPublisher.java`, `common-util/src/main/java/com/exchange/common/kafka/producer/IPublisher.java`, `common-util/src/main/java/com/exchange/common/kafka/producer/metrics/MeteredPublisher.java` | Common protobuf envelope, partition key, broker-ack completion, timeout/error Result, immediate/retry metrics. |
| Source | `common-util/src/main/java/com/exchange/common/kafka/listener/DefaultListener.java` and `listener/**` | Durable-action-before-ack workflow, retry threshold, same-intent reply outbox, ack-failure marker, async immediate reply publishing. |
| Source | `common-util/src/main/java/com/exchange/common/kafka/container/CommandContainerRegister.java` | Manual acknowledgment, delivery-attempt headers, listener concurrency, and the dedicated reply-publish callback executor. |
| Wire contract | `grpc-proto/common-proto/src/main/proto/com/exchange/proto/common/event_envelope.proto` | Shared envelope identity, correlation, string event type, raw payload, and `occurred_at` fields. |
| Source | `ledger-service/src/main/java/com/exchange/app/ledger/kafka/config/LedgerListenerConfig.java`, `wallet-service/src/main/java/com/exchange/app/wallet/kafka/config/WalletListenerConfig.java` | Current request/reply versus ack-only adapters and service-owned failure policy. |
| Source | `ledger-service/src/main/java/com/exchange/app/ledger/kafka/consumer/DefaultListener.java`, `wallet-service/src/main/java/com/exchange/app/wallet/kafka/consumer/DefaultListener.java` | Current `@RetryableTopic` include policy, `.retry`/`.dlq` topics, DLT handlers. |
| Source | `wallet-service/src/main/java/com/exchange/app/wallet/processor/transaction/step/BeforePostLedgerProcessor.java`, `ledger-service/src/main/java/com/exchange/app/ledger/kafka/consumer/LedgerCommandMessageHandler.java`, `ledger-service/src/main/java/com/exchange/app/ledger/processor/post/PostLedgerProcessor.java` | Atomic Wallet command-outbox creation versus separate Ledger business/reply-outbox transactions; Redis `PENDING` can outlive the listener retry window after a post-commit crash. |
| Source | `wallet-service/src/main/java/com/exchange/app/wallet/kafka/consumer/WalletLedgerReplyMessageHandler.java`, `wallet-service/src/main/java/com/exchange/app/wallet/processor/transaction/step/AfterPostLedgerProcessor.java`, `WalletTransactionRepository.updateTransactionStatus` | Ack-only consumption, explicit failed-reply DLT policy, sequential replay shortcut, and rollback-protected but non-convergent concurrent duplicate handling. |
| Source | `wallet-service/src/main/java/com/exchange/app/wallet/utils/OutboxHelper.java`, `ledger-service/src/main/java/com/exchange/app/ledger/utils/OutboxHelper.java`, `ledger-service/src/main/java/com/exchange/app/ledger/result/PostTransactionResultConverter.java` | Current request/reply identities, routing, initial delivery fields, and the reply payload difference between creation and idempotent replay. |
| Config | `wallet-service/src/main/resources/application.yml`, `ledger-service/src/main/resources/application.yml` | Manual acknowledgments, producer idempotence, retry settings, outbox schedule/page size, histogram/SLO configuration. |
| Schema/SQL | `standard/sql/wallet_service.sql`, `standard/sql/ledger_service.sql` | Unique `event_id` and `idx_outbox_claim(outbox_status,next_attempt_at,id)` in both service databases. |
| Deployment | `deploy/docker-compose.kafka.yml`, `standard/docker/kafka/create-topics.sh` | Pre-provisioned base/retry/DLT topics, three partitions, disabled auto-creation, and single-broker/replication-factor-one defaults. |
| Test | `common-util/src/test/java/com/exchange/common/kafka/listener/DefaultListenerTest.java` | 25 cases around durable reply, duplicate intent, retry threshold, ack recovery, immediate publish/finalize failures. |
| Test | `common-util/src/test/java/com/exchange/common/kafka/producer/DefaultPublisherTest.java`, `common-util/src/test/java/com/exchange/common/outbox/dao/repository/OutboxRepositoryTest.java`, `common-util/src/test/java/com/exchange/common/outbox/retry/OutboxRetryHandlerTest.java` | Broker-ack boundary/timeout, insert decisions/claim SQL, retry source and cursor movement. |
| Test | `ledger-service/src/test/java/com/exchange/app/ledger/kafka/**`, `wallet-service/src/test/java/com/exchange/app/wallet/kafka/**` | Service retry thresholds, deterministic reply IDs, failed-reply policy, and Wallet retry/non-retryable classifications. |
| External research doc | `doc/db/outbox-claim-index.md` | Focused explanation of claim-index and pagination intent; revalidate and restate supported content in D-001 without linking to this file. |
| Monitoring | `monitoring/grafana/dashboards/kafka-messaging-overview.json` | Publisher/listener outcomes and latency, delivery delay, confirmed DLT panels. |

### Important invariants / guarantees discovered

- Publisher success is returned only after the Kafka send future acknowledges; the publisher never changes outbox state.
- Common listener reply flows persist or verify a reply outbox before calling source-record acknowledgment; manual `acknowledge()` does not itself prove that the broker has durably committed the offset before return.
- Ack-only durability is a service-handler obligation: common-util acknowledges only after the handler returns but does not create or control its transaction.
- Effect-level idempotency is also a service-handler obligation. The common listener deliberately retries and may redeliver work, so every listener adopter must make repeated handling safe; common-util cannot infer or enforce the service's business identity or state transition.
- A duplicate reply outbox is accepted only when stable intent fields match; missing-after-conflict is retryable and different intent goes to DLT.
- Retry workers claim pages inside a DB transaction, publish outside it, and mark only acknowledged rows `SENT`.
- Delivery is at least once: broker success followed by DB finalization failure can republish the same event.
- `SENT` records producer acknowledgment, not consumer processing.
- The outbox claim budget starts at one for the intended immediate attempt and increments before each scheduled send; it is not a complete count of actual publishes.
- `event_id` uniqueness is schema-enforced; service-specific identity gives that uniqueness its deduplication meaning.
- Wallet request event IDs are sequence-based (`{commandId}-{process-local sequence}`), while Ledger reply IDs are deterministic for the request and outcome: one canonical success identity or a stable namespace/error identity. The database rejects collisions, but current service generators do not establish horizontal multi-instance uniqueness.
- Duplicate verification also requires byte-identical payloads; stable event identity alone is insufficient.
- The project owner confirms that raw serialized payload equality is not a sound idempotency contract because it makes successful deduplication depend on business code reproducing diagnostic detail exactly.
- Current envelopes regenerate `occurred_at` for every publish attempt, so duplicate envelopes retain identity and payload but not that timestamp.
- `partition_key = commandId` co-locates a transaction's records per topic; common-util provides neither global ordering nor original-order preservation across retry topics.
- Kafka DLT and outbox `DEAD` are distinct terminal concepts: DLT delivery is implemented, while the outbox `DEAD` transition is not.
- Ledger's business commit and reply-outbox persistence intentionally remain separate. A reply-path failure does not roll back an otherwise successful Ledger business operation; this accepts a post-commit failure window that recovery and reconciliation must treat explicitly.
- Wallet sequentially reprocessing a successful reply after completion is a no-op. Concurrent duplicate consumers cannot both commit effects because the losing transaction fails the guarded status transition and rolls back, but the loser currently reaches failure/DLT instead of returning idempotent success.

### Failure scenarios discovered

- `DEAD` exists but no current code transitions exhausted rows to it; `PENDING` rows at `attempt_count >= maxRetries` become invisible to the claim query.
- `last_attempt_at` and `last_error` exist but are not updated by the shared retry path, weakening diagnostics.
- Kafka timeout does not prove broker rejection and the underlying send is not cancelled, so retry can duplicate delivery.
- Immediate publish is best effort after durability; any publish or finalization failure leaves the outbox for retry.
- The common listener can guarantee durable action before ack, but cannot guarantee that a service committed its business mutation and originating/reply outbox in the same DB transaction.
- A Ledger crash or retryable reply-insert failure after business commit can be recovered through source redelivery and Ledger idempotency only when Redis state permits replay and no conflicting reply row exists; a non-retryable/DLT outcome can still leave Wallet unfinalized because no automatic reconciliation exists.
- If Ledger crashes after its posting commit but before Redis completion/reply creation, an existing Redis `PENDING` claim can remain for the current 120-second TTL while the seven roughly one-second listener attempts exhaust. Attempt five can therefore persist a retry-exhausted failure reply for an already-committed posting; current Kafka redelivery is not a guaranteed success-reconstruction path.
- Ledger created-success and idempotent-replay results currently use the same success event ID but different serialized detail (`success` versus `already exists`). If a reply row exists before an unmarked redelivery, byte-level same-intent verification can classify the replay as a conflict and route it to DLT.
- A valid Ledger failure reply is deliberately routed by Wallet to DLT and does not currently compensate the pending Wallet transaction.
- The protobuf envelope parser checks syntax but common-util does not validate nonblank `event_id`, `command_id`, or `event_type` before delegating or producing a parseable failure reply.
- `attempt_count`, `last_attempt_at`, `last_error`, and `finalized_at` are not an exact delivery-attempt journal: immediate replays may not increment the count, shared retries do not update the last-attempt/error fields, and current finalization timestamps can precede the actual acknowledgment.
- The ack-failure marker path is unit-tested but lacks a real broker/retry-topic integration test.
- DLT handlers emit a metric and structured log but do not re-drive or reconcile records.
- Checked-in Kafka deployment defaults use one broker and replication factor one, so the application-level outbox does not provide broker high availability.
- Retry topics are not auto-created by the applications and must match the provisioned topic count and naming.
- Multi-worker `SKIP LOCKED`, timeout/late-ack, and crash/finalization windows have no real MySQL/Kafka failure-injection coverage.
- Focused tests emit the protobuf runtime's pre-22 generated-code denial-of-service warning; current proto modules still configure `protoc` 3.12.0 against a newer runtime.

### Dependencies / related designs

- D-004 owns transaction execution, repository exception translation, and SQL timing.
- D-005 owns Result/error/retryability semantics used by publisher and listener decisions.
- D-006 owns publisher/listener/outbox metrics and dashboards.
- D-002 owns Redis coordination, including the stale-`PENDING` window that can block immediate Ledger reconstruction. D-008/D-016 own service business equivalence and effect-level duplicate handling; D-001 states their required transport boundary.
- D-009 owns Ledger command/reply convergence across the intentional transaction split; D-013 owns Wallet settlement, valid-failure compensation, stuck-transaction recovery, and concurrent reply convergence.
- Future Wallet/Ledger transaction documents must own their business/outbox transaction boundaries and state-transition idempotency.
- `design/consistency/wallet-ledger-consistency.md` synthesizes D-001 with both service transaction designs without duplicating D-001 internals.
- `design/proposals/ledger-reply-event-identity.md` and `design/proposals/inbox-outbox-processing.md` are competing future directions: tolerate repeated reply creation or deduplicate intake through durable local processing.
- `design/proposals/async-wallet-immediate-outbox-publish.md` proposes removing the broker-acknowledgment wait from Wallet's post-commit API path while preserving the durable retry boundary.

### Document boundary

**Include here:**

- Shared outbox entity/repository/state machine, publisher contract, retry worker, listener template, acknowledgment boundary, duplicate-intent decisions, delivery guarantee, common failure windows, and current adoption modes.

**Link elsewhere instead of duplicating:**

- Wallet transaction state changes, Ledger posting/accounting rules, and end-to-end compensation/reconciliation.
- Full SQL schema descriptions beyond the outbox table/index.
- Metric implementation details beyond the signals required to operate messaging; D-006 owns them.

### Uncertainty / TODO

- `TODO: PROPOSAL / NOT IMPLEMENTED` — Move Wallet's immediate Ledger-command publish off the API wait path without changing the atomic business/outbox commit or at-least-once recovery model; follow `design/proposals/async-wallet-immediate-outbox-publish.md` for callback isolation and acceptance criteria.
- `TODO: FUTURE DESIGN / IMPLEMENTATION` — Define and implement the intended terminal handling/alerting for retry-exhausted outbox rows.
- `DECIDED / FUTURE SERVICE IMPLEMENTATION` — Wallet will run a scheduled recovery job that finds stuck transactions and reposts the same logical Ledger command with the same business identity. Ledger business idempotency makes the accounting effect safe to retry. DLT/retry-exhaustion detection and metrics are operational alerts only and must not be the correctness trigger. The Wallet job and clean reply convergence are not implemented yet, and the current Ledger reply-payload conflict still requires resolution.
- `DECIDED / ADOPTER CONTRACT` — Every user of the common listener must provide effect-level idempotency because retry and redelivery are part of the listener contract; common-util supplies transport coordination, not business deduplication.
- `TODO: FUTURE OPTIMIZATION / VALIDATION` — Evaluate `design/proposals/ledger-reply-event-identity.md`: assign a UUID to each newly persisted Ledger reply, keep it stable across publishing retries, retain exact-payload conflict checks only for reuse of that UUID, and make Wallet state the outcome-convergence boundary. Validate concurrent success handling, failure/DLT semantics, no-success recovery, correlation, and additional traffic before implementation.
- `TODO: ALTERNATIVE REFACTOR / EVALUATION` — Evaluate `design/proposals/inbox-outbox-processing.md` as the broader alternative: persist and deduplicate intake before Kafka acknowledgment, retry through a durable inbox worker, and choose between atomic business/outbox completion and a decoupled `BUSINESS_COMPLETED → REPLY_CREATED` lifecycle.
- `TODO: TEST GAP` — Verify ack-failure marker propagation and idempotent recovery with a real Kafka retry-topic integration test.
- `TODO: INTEGRATION EVIDENCE` — Validate concurrent `SKIP LOCKED` claims, timeout/late acknowledgment, broker failure, crash-after-send, and partial finalization against real MySQL/Kafka.
- `TODO: FUTURE DESIGN` — The candidate UUID-per-reply model permits failure-then-success and repeated responses. Define monotonic Wallet handling, the terminal no-success path, and the distinction between valid negative replies and poison-message/DLT failures before adopting it.
- `TODO: CURRENT CORRECTNESS GAP` — Resolve the post-commit stale Redis-`PENDING` window: current listener retries can produce a retry-exhausted failure for an already-committed Ledger posting before the pending key expires. The accepted Wallet stuck-transaction re-drive is not implemented and must preserve the original business identity.
- `TODO: CURRENT CONVERGENCE GAP` — Make repeated and concurrent successful Ledger replies return idempotent success after exactly one Wallet finalization. The current status guard protects committed effects, but a concurrent loser fails and can be routed to DLT.
- `TODO: FUTURE DESIGN / IMPLEMENTATION` — Implement Wallet compensation for a valid Ledger rejection; current DLT routing leaves the Wallet transaction pending.
- `TODO: NEED CONFIRMATION` — Decide whether `EventEnvelopePb.occurred_at` intentionally represents each publish attempt or should remain stable as the original event creation/occurrence time.
- `TODO: OPERATIONS` — Add outbox backlog/oldest-age/exhaustion/finalization signals and Kafka consumer lag after terminal semantics are defined.
- `TODO: DEPENDENCY / SECURITY` — Regenerate protobuf message classes with a supported compiler and remove the runtime's pre-22 generated-code denial-of-service warning.
- `TODO: VALIDATION / IDENTITY` — Require nonblank envelope identifiers before creating durable failure replies, and replace or qualify process-local generated IDs before multi-instance collision-free identity is claimed.

### Verification record

**Last verified:** `2026-09-09 — independent adversarial source/config/schema/test pass`
**Verified against:**

- Current common publisher/listener/repository/retry code, both service adapters and transaction paths, Redis-backed Ledger replay behavior, the event envelope, SQL schemas/indexes, Spring Kafka 2.8.11 retry-attempt header bytecode, topic provisioning, deployment defaults, and related canonical dependency docs.
- `mvn -f common-util/pom.xml test` passed 86 tests with zero failures/errors/skips on 2026-09-09.
- Focused standalone service checks passed 10 Ledger tests and 8 Wallet tests with zero failures/errors/skips on 2026-09-09. The root reactor attempt failed before test execution because the root POM references a missing `client-test` child module.
- Link/asset verification corrected the Wallet async-publish proposal path from singular `proposal` to `proposals`; every D-001 reader dependency now resolves within `design`.

**Known gaps after verification:**

- No real Kafka/MySQL retry-topic, multi-worker, timeout/late-ack, or crash/failure-injection test was run; those behaviors remain source/schema reasoned and explicitly bounded in the canonical document.
- Ack-failure marker propagation is unit-tested only, and current tests do not establish durable offset timing after manual `acknowledge()`.
- The protobuf runtime still reports generated pre-22 message classes as vulnerable; focused tests pass but do not remove that dependency risk.
- D-001 is verified as an accurate description of current guarantees and gaps, not as proof of eventual delivery or production-hardened end-to-end recovery.

---

## D-002 — Layered Redis idempotency coordination

**Priority:** P0
**Status:** VERIFIED
**Canonical document:** `design/infrastructure/redis-idempotency-coordination.md`
**Future proposal:** `design/proposals/redis-idempotency-db-authoritative.md` (not implemented)
**Modules/services:** `common-util`, with focused adoption evidence from `wallet-service` and `ledger-service`
**Owner topic:** Redis claim/read/complete/release protocol, wrapper failure contract, and its role as an acceleration layer over service-owned DB idempotency.

### Why this deserves documentation

- It is on both transaction hot paths and is intended to reduce duplicate concurrent work without replacing database correctness.
- The ownership-token Lua release and request-hash comparison are correctness-relevant concurrency mechanisms.
- The reusable API deliberately downgrades Redis failures to `Result`, leaving business fallback and replay semantics to each service.

### Questions the document must answer

- What are the key and value formats, states, TTLs, request hash, and owner token?
- Which operations are atomic, which are ordinary Redis writes, and what races remain?
- Which outcomes are normal (`claim=false`, missing get, empty release) versus errors?
- How does the wrapper translate/cache exceptions, parse values, verify hashes, sanitize logs, and emit metrics?
- What do Wallet and Ledger do when Redis is unavailable, malformed, or stale, and what DB uniqueness/hash checks remain authoritative?
- Which behavior belongs to common-util and which belongs to the service transaction/idempotency design?

### Implementation evidence

| Evidence type | Path / symbol / artifact | What it supports |
|---|---|---|
| Source | `common-util/src/main/java/com/exchange/common/redis/idemp/IdempotencyClient.java`, `DefaultIdempotencyClient.java` | Result-returning API, exception downgrade, hash verification, normalized error metrics/logs. |
| Source | `common-util/src/main/java/com/exchange/common/redis/idemp/IdempRedisClient.java` | NX claim, unconditional mark-done write, get, conditional release, force delete. |
| Source | `common-util/src/main/java/com/exchange/common/redis/idemp/utils/CommonIdempHelper.java`, `common-util/src/main/java/com/exchange/common/redis/idemp/utils/IdempValue.java`, `common-util/src/main/java/com/exchange/common/redis/idemp/constant/CommonIdempStatus.java` | `idemp_k:{service}:{scope}:{id}` and `P/A:{hash}:{token}[:content]` formats and parsing. |
| Source | `common-util/src/main/resources/script/lua/release-idemp-if-owned.lua` | Atomic exact-value delete prevents a stale owner from releasing another request's pending claim. |
| Source | `common-util/src/main/java/com/exchange/common/redis/BaseRedisSupport.java`, `common-util/src/main/java/com/exchange/common/redis/component/ScriptExecutor.java`, `common-util/src/main/java/com/exchange/common/redis/aop/CacheExceptionTranslateAop.java` | Atomic Redis primitives, shared Lua execution, and client-exception classification. |
| Source | `common-util/src/main/java/com/exchange/common/redis/register/BaseRegister.java`, `common-util/src/main/java/com/exchange/common/redis/idemp/register/RedisIdempRegister.java`, `common-util/src/main/java/com/exchange/common/redis/metrics/MeteredRedisValueSupport.java`, `MeteredRedisScriptExecutor.java` | Runtime wiring through metered Redis and script adapters. |
| Source | `common-util/src/main/java/com/exchange/common/utils/StableHashHelper.java`, `TokenHelper.java`, `JitterHelper.java` | Murmur3 `asLong()` hash representation, UUID owner tokens, and positive TTL jitter. |
| Source | `ledger-service/src/main/java/com/exchange/app/ledger/processor/post/PostLedgerProcessor.java`, `ledger-service/src/main/java/com/exchange/app/ledger/kafka/consumer/LedgerCommandMessageHandler.java` | Redis-first claim/replay, DB fallback, malformed-value deletion, post-commit completion, token-owned release, and retry of in-processing Kafka requests. |
| Source | `wallet-service/src/main/java/com/exchange/app/wallet/processor/transaction/model/RequestInfo.java`, `wallet-service/src/main/java/com/exchange/app/wallet/processor/transaction/step/IdempPrecheckProcessor.java`, `BeforePostLedgerProcessor.java` | Wallet hash construction, wrapper adoption, DB fallback, transaction creation, and cleanup. |
| Source | `common-util/src/main/java/com/exchange/common/db/utils/DefaultDbTxnExecutor.java`, service transaction repositories/entities | Failed-Result rollback behavior and the absence of a persisted request hash. |
| Config | `wallet-service/src/main/resources/application.yml`, `ledger-service/src/main/resources/application.yml` | Pending 120s, done 24h, and jitter configuration. |
| Schema/SQL | `standard/sql/wallet_service.sql`, `standard/sql/ledger_service.sql` | DB uniqueness backstops: Wallet `(initiator,idempotency_key)` and Ledger `reference_id`. |
| Test | `common-util/src/test/java/com/exchange/common/cache/client/IdempRedisClientIT.java` | Conditional release behavior against Redis; not included in default Surefire and bound to a fixed external Redis address. |
| Test | `wallet-service/src/test/java/com/exchange/app/wallet/processor/transaction/step/IdempPrecheckProcessorTest.java` | Wallet fallback on Redis claim failure, visible hash-conflict mapping, and malformed-value deletion before DB fallback. |
| Existing doc | `standard/design/idemp.txt` | Intended layered flow; some details must be reconciled with current source. |
| Historical note | `engineering-notes/feature/monitor/idempotency-redis-wrapper-change-scope.md` | Wrapper rationale and metrics; its statement that Wallet is not migrated is now stale. |

### Important invariants / guarantees discovered

- Claim uses Redis `SET ... NX` with a pending TTL, so only one concurrent caller normally obtains a live claim.
- Same Redis key with a different request hash returns `HASH_CONFLICT` only while the original parseable Redis value remains visible.
- Release is an atomic Lua compare-and-delete against the full pending value, including token.
- Redis failures are returned as typed failures by `DefaultIdempotencyClient`; both current services choose DB fallback rather than failing the business flow.
- Redis is not the durable source of truth; service database uniqueness is the final duplicate-effect backstop, and a failed transaction `Result` rolls back related writes through `DefaultDbTxnExecutor`.
- A committed database result remains durable when post-commit Redis completion fails, but a surviving `PENDING` value can mask it from duplicate requests until expiry; only then can the normal DB replay path repair `ACCEPTED`.
- Pending expiry provides automatic abandoned-claim cleanup but permits overlapping work when the original owner is still running.
- Wallet's Redis identity omits `initiator`, although Wallet database uniqueness includes it; current Redis and DB scopes are not equivalent.
- The current colon-delimited accepted-value format is intentionally constrained for simple Lua-side handling. Its internal-only use makes invalid content unlikely, but does not make delimiter-containing content representable.

### Confirmed future requirements (not implemented)

- Completion must become an atomic pending-owner transition: `markIdempDoneIfOwned` may write `ACCEPTED` only when the supplied token still owns the current `PENDING` value. Expired, missing, or moved ownership must not overwrite Redis or invalidate an already committed DB result.
- Wallet and Ledger must persist a canonical request hash in their authoritative DB idempotency records. Every DB fallback, replay, and duplicate-insert race must replay the same request and reject the same key with a different request, including after Redis loss or expiry.
- Wallet's Redis identity must include the same initiator/business scope as its durable unique key.
- A duplicate-insert loser must load the durable winner and return replay or conflict instead of a generic duplicate error.
- Because the system is pre-launch, the target Redis key will not carry a `v2` compatibility segment and the implementation will not add dual-read or legacy-data migration machinery.
- The target behavior and implementation sequence are specified in `design/proposals/redis-idempotency-db-authoritative.md`; current code does not provide these guarantees yet.

### Failure scenarios discovered

- `markIdempDone` is an unconditional `SET`; it accepts a token parameter but does not compare current ownership. A slow worker can potentially overwrite a newer claim after the old pending TTL expires.
- Accepted-value parsing uses unrestricted `split(":")` and requires exactly four parts, so reusable `content` cannot contain `:`. Pending parsing accepts extra segments and ignores them. Current internal values are compatible with the intentionally constrained protocol.
- Malformed-value recovery uses `forceDeleteIdemp`, which may delete a concurrently replaced value; services accept this as a rare risk because DB idempotency is the backstop.
- Redis failure or accepted-entry expiry loses request-equivalence validation. Both services return an existing DB transaction without comparing the current request, because neither transaction table persists the hash.
- Concurrent requests that both reach an absent DB row rely on the unique constraint; the loser rolls back but receives a duplicate error rather than the winner's result.
- Wallet can falsely conflict across initiators because its Redis key is less specific than its DB unique key.
- A failed claim followed by key expiry before `GET` proceeds through the DB path without acquiring a replacement Redis claim.
- A failed post-commit `markIdempDone` can leave the original claim `PENDING`; matching duplicates then return in-processing until expiry, and a transport retry window can end before the DB replay path becomes reachable.
- Service hash builders concatenate unescaped fields before hashing; a canonical, unambiguous persisted request representation is not defined or tested.
- No unit tests cover `DefaultIdempotencyClient` downgrade/metrics/hash behavior; the fixed-address Redis integration test is not part of the default Surefire run.

### Dependencies / related designs

- D-004 owns authoritative DB transaction/unique-key behavior.
- D-005 owns Result and common error semantics.
- D-006 owns Redis/idempotency measurements.
- D-008 and D-016 own Ledger- and Wallet-specific DB replay equivalence, response reconstruction, and business outcomes.
- `design/proposals/redis-idempotency-db-authoritative.md` owns the accepted target protocol; D-002 documents current behavior.

### Document boundary

**Include here:**

- Redis protocol and data format, raw versus Result-returning clients, Lua ownership release, TTLs, exception downgrade, metrics, generic guarantees, and documented limitations.
- Focused Wallet/Ledger adoption needed to establish DB fallback, unique-key scope, retry behavior, and the actual layered guarantee.

**Link elsewhere instead of duplicating:**

- Exact Wallet/Ledger request hashing, DB unique keys, transaction creation, replay responses, and business state effects.
- General Redis cache coherency; link D-003.
- Detailed metric/dashboard walkthrough; link D-006.

### Uncertainty / TODO

- `TODO: FUTURE IMPLEMENTATION` — Implement the accepted DB-authoritative request hash, token-owned completion, Wallet scope alignment, and duplicate-winner resolution specified by the future proposal.
- `TODO: DESIGN DECISION` — Select an unambiguous canonical request serialization/hash algorithm before persisting hashes; current delimiter concatenation and 64-bit hash representation are not a durable contract.
- `TODO: DESIGN DECISION` — Decide whether exact replay requires only the transaction ID/latest state or a persisted historical response snapshot.
- `TODO: VALIDATION` — Measure legitimate processing duration before deciding whether pending lease renewal/fencing is warranted and before treating the current TTL as suitable.
- `TODO: TEST COVERAGE` — Add direct `DefaultIdempotencyClient` unit tests plus isolated Redis and deterministic DB-race integration tests. Remove the fixed external Redis address from the current IT setup.
- `TODO: TARGET ENCODING` — The current colon-delimited constraint is intentional; choose whether the future protocol retains a constrained Lua-friendly format or introduces escaping/length delimiting.

### Verification record

**Last verified:** `2026-09-09 — quick adversarial verification pass`
**Verified against:**

- Rechecked the raw/Result-returning clients, value parser, token-owned release Lua, unconditional completion write, both service claim/DB fallback/post-commit paths, transaction rollback behavior, schemas, configuration, and focused tests.
- `mvn -f common-util/pom.xml test` passed 75 tests; `IdempRedisClientIT` remained undiscovered by the default Surefire pattern.
- The focused Wallet verification run passed 11 tests in total, including all 3 `IdempPrecheckProcessorTest` cases.
- Material correction: clarified that stale `PENDING` is the underlying failure condition regardless of DB outcome and can temporarily prevent a committed row from being rediscovered.

**Known gaps after verification:**

- No live Redis, concurrent database-race, expiry, or lost-owner completion test was run in this pass.
- No isolated Ledger idempotency unit suite exists; Ledger adoption is source-verified in this quick pass.

---

## D-003 — Redis cache-coherency and hot-data strategies

**Priority:** P1
**Status:** VERIFIED
**Canonical document:** `design/infrastructure/redis-cache-coherency.md`
**Modules/services:** `common-util`, with focused adoption evidence from `wallet-service` and `ledger-service`
**Owner topic:** Typed cache read outcomes, raw versus versioned encodings, cache-aside/post-refresh behavior, CAS/tombstone/negative-cache safeguards, soft failure, and optional client-side reads.

### Why this deserves documentation

- The module contains a real coherency design rather than a thin Redis wrapper: explicit read states, version-aware Lua writes, tombstones, negative cache, corruption recovery, and hot-key promotion.
- Mutable balances use a different write contract from stable reference mappings; confusing them could reintroduce stale overwrite bugs.
- Wallet and Ledger both rely on cache failure falling back to MySQL without changing business correctness.

### Questions the document must answer

- How do raw (`marker:content`) and versioned (`version|marker:content`) formats encode value, negative, and tombstone states?
- When should callers query MySQL for `VALUE_HIT`, `CACHE_MISS`, `NEGATIVE_HIT`, and `TOMBSTONE_HIT`?
- How do CACHE_ASIDE and POST_REFRESH differ after insert/update/DB hit?
- What stale-write race does `set-if-absent-or-newer.lua` prevent, and what are equal-version/tombstone semantics?
- Why is a tombstone needed after a cache-aside insert or update, what in-flight stale fill does it block, and why is its TTL a bounded race-window control rather than a complete freshness guarantee?
- How are malformed/serialization errors self-recovered, and which failures are suppressed versus propagated?
- Which caches use negative values, version CAS, normal Redis reads, or Redisson client-side caching in each service?

### Implementation evidence

| Evidence type | Path / symbol / artifact | What it supports |
|---|---|---|
| Source | `common-util/src/main/java/com/exchange/common/redis/cache/model/CacheReadResult.java`, `CacheReadStatus.java` | Four-state read contract and DB-query decision. |
| Source | `common-util/src/main/java/com/exchange/common/redis/cache/component/codec/impl/ValueCodec.java`, `common-util/src/main/java/com/exchange/common/redis/cache/component/codec/impl/VersionCodec.java`, `common-util/src/main/java/com/exchange/common/redis/cache/constant/CacheType.java` | Wire formats, type markers, validation, malformed/serialization failure handling. |
| Source | `common-util/src/main/java/com/exchange/common/redis/cache/strategy/impl/DefaultRawCacheStrategy.java`, `common-util/src/main/java/com/exchange/common/redis/cache/strategy/impl/DefaultVersionCacheStrategy.java`, `common-util/src/main/java/com/exchange/common/redis/cache/strategy/impl/StrategyFactory.java` | Cache-aside/post-refresh hooks, optional features, fail-fast versus CacheException-suppressing variants. |
| Source | `common-util/src/main/java/com/exchange/common/redis/cache/component/support/VersionCacheWriter.java`, `common-util/src/main/java/com/exchange/common/redis/cache/component/support/RawCacheWriter.java`, `common-util/src/main/java/com/exchange/common/redis/cache/component/support/RawCacheDeleter.java` | Raw writes, negative/tombstone writes, version-CAS integration. |
| Source | `common-util/src/main/resources/script/lua/set-if-absent-or-newer.lua` | Atomic absent-or-newer update and equal-version overwrite behavior. |
| Source | `common-util/src/main/java/com/exchange/common/redis/cache/strategy/feature/impl/DefaultNegativeCacheFeature.java`, `common-util/src/main/java/com/exchange/common/redis/cache/strategy/feature/impl/RawDeleteSelfRecoverFeature.java` | Optional negative caching and best-effort delete-on-corruption. |
| Source | `common-util/src/main/java/com/exchange/common/redis/register/RedissonRegister.java`, `BaseClientSideCacheSupport.java`, `CacheSupportRegister.java`, `RedissonProperties.java` | Separate lazy RESP3 client-side reader, local-lifetime default, and common reader/writer registration. |
| Source | `common-util/src/main/java/com/exchange/common/component/DefaultPromotionClassifier.java`, `wallet-service/src/main/java/com/exchange/app/wallet/register/PromotionClassifierRegister.java` | Process-local, one-way, unbounded promotion state for Wallet hot keys. |
| Source | `wallet-service/src/main/java/com/exchange/app/wallet/dao/cache/BalanceSnapshotCacheRegister.java`, `wallet-service/src/main/java/com/exchange/app/wallet/dao/store/BalanceSnapshotStore.java` | Wallet normal/hot, raw/versioned, cache-aside/post-refresh compositions, scoped ref/entity composition, lazy system-owner promotion, and DB fallback. |
| Source | `wallet-service/src/main/java/com/exchange/app/wallet/processor/transaction/step/BeforePostLedgerProcessor.java`, `AfterPostLedgerProcessor.java`, `UpdateBalanceSnapshotProcessor.java`, `BalanceSnapshotRepository.java` | Cache update hooks run after successful Wallet transaction completion; locked guarded updates advance the entity version supplied to cache actions. |
| Source | `ledger-service/src/main/java/com/exchange/app/ledger/dao/cache/LedgerCacheRegister.java`, `ledger-service/src/main/java/com/exchange/app/ledger/dao/store/LedgerTxnStore.java` | Ledger reference negative cache and versioned transaction cache adoption. |
| Source | `ledger-service/src/main/java/com/exchange/app/ledger/processor/post/PostLedgerProcessor.java`, `LedgerTxnRepository.updateMetadataByPk` | Post-insert cache action follows Ledger posting commit; metadata update orders DB mutation before its cache hook but has no current production caller. |
| Source | `common-util/src/main/java/com/exchange/common/redis/cache/strategy/metrics/*`, `MeteredRedisValueSupport.java`, `MeteredClientSideCacheReadSupport.java`, `MeteredRedisScriptExecutor.java` | Cache outcome/error counters and low-level Redis/client-side/Lua latency. |
| Config | Both service `application.yml` files, `app.cache.data` | Current entity/index/negative/tombstone TTLs and jitter; 120 seconds is the checked-in tombstone snapshot, while adequacy for the request/in-flight cache-fill window remains unvalidated. |
| Config | `common-util/src/main/java/com/exchange/common/redis/config/RedissonProperties.java`; absence of a main-service override | Current client-side local lifetime defaults to 100 ms; it is not a validated freshness bound. |
| Schema | `standard/sql/wallet_service.sql`, `standard/sql/ledger_service.sql`; `BalanceSnapshot.java`, `LedgerTxn.java`; `CommonDbComponentRegister.java` | Persisted entity versions, stable lookup identities, and MyBatis optimistic-version support. |
| Test | `wallet-service/src/test/java/com/exchange/app/wallet/dao/cache/BalanceSnapshotCacheRegisterTest.java`, `ledger-service/src/test/java/com/exchange/app/ledger/dao/store/LedgerTxnStoreUnitTest.java`, `GetLedgerTxnProcessorIntegrationTest.java` | Strategy decoration, negative-hit non-renewal, stale reference fallback, and intended Ledger tombstone/refill exercise; Wallet store test source is currently fully commented out. |
| Historical note | `engineering-notes/refactor/error-handling/cache-code-contract-violation-implementation.md` | Rationale for explicit soft-fail strategy wrappers; requires source-first validation. |
| Monitoring | `monitoring/grafana/dashboards/service-data-access-overview.json` | Cache read-result/error and Redis operation panels. |

### Important invariants / guarantees discovered

- Mutable cached entities can only be overwritten by an absent, equal-version, or newer-version write in one Redis Lua execution.
- Equal-version overwrite is intentional: equal database versions represent the same database revision, and allowing equality lets version-0 data or tombstones replace a version-0 negative entry.
- This rule requires every cache-visible database mutation to advance the entity version; a versioned-cache adopter that permits different payloads at the same version would violate the CAS contract.
- CACHE_ASIDE versioned updates write a versioned tombstone, preventing an older concurrent DB read from restoring stale data.
- A tombstone temporarily occupies the key after a cache-aside insert/update so an earlier in-flight miss cannot later repopulate a negative entry, and an earlier versioned DB read cannot overwrite the newer revision while the barrier exists.
- Tombstones reduce the stale-fill race; they do not eliminate it. A delayed fill can succeed after expiry, so the TTL must comfortably cover the expected request and cache-fill lifetime while allowing obsolete tombstone keys to be removed from Redis instead of remaining indefinitely.
- Negative writes are `setIfAbsent`, avoiding replacement of an existing positive value.
- Both services deliberately choose strategies that downgrade translated `CacheException`s and continue to the DB; arbitrary runtime and metric failures are outside that wrapper's soft-failure guarantee.
- Self-recovery only deletes on malformed/serialization read errors; it does not broadly hide arbitrary runtime bugs.
- Hot Wallet reads add a local Redisson tier but share the same Redis keyspace and authoritative MySQL rows as normal reads.
- Wallet's active balance mutations invoke cache maintenance only after `DbTxnExecutor` returns successfully; cache failure cannot roll back the committed balance change.
- A raw reference mapping is safe only under the service invariant that its reference-to-entity identity is stable after creation.

### Failure scenarios discovered

- Core codec/strategy/Lua behavior has no direct common-util unit suite; current tests mostly cover service wiring/store decisions, and the Wallet store test is commented out.
- Equal versions overwrite (`>=`); correctness therefore depends on the confirmed invariant that equal database versions cannot represent different cache-visible revisions.
- The Lua script only treats a missing `|` as defensively overwriteable; a non-numeric version with a separator can produce a script error.
- Tombstone effectiveness depends on the configured TTL covering the real maximum in-flight stale-read/cache-fill window; the current 120-second pre-launch value is not a confirmed duration and has not been validated by retained timing evidence.
- A failed post-commit refresh/tombstone can leave an older Redis value until expiry; version CAS prevents older successful writes from winning but cannot compensate for a missing write.
- Concurrent miss/tombstone readers are not coalesced and can issue duplicate database queries.
- Client-side cache use requires RESP3/server support and is only adopted for Wallet-promoted system keys; no runtime/integration evidence proves invalidation across RedisTemplate/Lua writes, disconnects, or reconnects.
- `DefaultPromotionClassifier` is an unbounded process-local set with no demotion; the first access can use the normal strategy, instances can disagree, and an unpromoted write uses a tombstone rather than direct hot-cache refresh.
- Normal Wallet snapshot reads may return stale cached data by accepted design. A real-time DB-bypass mode is intended but not exposed by the inspected public API.
- Malformed-value recovery reads and deletes in separate operations, so it can delete a valid concurrent replacement.
- Cache metric decorators are outside the `CacheException` downgrade wrapper. An unguarded Micrometer failure can therefore change the caller-visible outcome after a DB commit, although it cannot roll back the committed data.

### Dependencies / related designs

- D-004 owns the database transaction/version boundary used before post-commit cache actions.
- D-005 owns cache error/Result semantics.
- D-006 owns cache/Redis metrics.
- D-002 shares low-level Redis support and Lua execution but owns idempotency semantics.
- D-010, D-014, and D-017 own Ledger/Wallet data-specific query, concurrency, and freshness contracts built on this mechanism.

### Document boundary

**Include here:**

- Common cache model/formats, strategy contracts, Lua invariant, tombstone/negative/recovery features, client-side option, failure modes, and adoption matrix.
- The shared distinction between stable mappings, mutable entities, normal paths, and promoted Wallet system-key paths.

**Link elsewhere instead of duplicating:**

- Wallet balance concurrency rules and Ledger transaction mutation semantics.
- General idempotency protocol; link D-002.
- Dashboard operations; link D-006.

### Uncertainty / TODO

- `TODO: TEST GAP` — Add focused tests for codecs, strategy transitions, Lua CAS (including malformed/equal/older/newer values), negative cache, and tombstones.
- `TODO: ENVIRONMENT VALIDATION` — Validate Redisson client-side caching against the deployed Redis/Redisson versions and document observed invalidation behavior.
- `DEFERRED / PRE-LAUNCH CONFIGURATION VALIDATION` — When representative timing requirements exist, validate that the tombstone TTL covers the expected upper bound for request and delayed cache-fill completion; document it as risk reduction, not proof that stale repopulation is impossible.
- `FUTURE ENHANCEMENT` — Bound or expire process-local promotion state if the system-key population can grow materially; evaluate deterministic routing or demotion rather than treating local observation as cluster-wide hotness.
- `FUTURE CAPABILITY` — Expose and test the intended Wallet real-time DB-bypass read mode and define safe callers/rate controls.
- `FUTURE HARDENING` — Make non-numeric existing versions take a deterministic Lua recovery path; consider cache-fill coalescing only if metrics show DB bursts.

### Verification record

**Last verified:** `2026-09-09 — quick adversarial verification pass`
**Verified against:**

- Rechecked codecs/strategy composition, Redis Lua, negative/tombstone behavior, Redisson registration, Wallet normal/hot routing and post-commit calls, Ledger stores, entity versions, configuration, and selected tests.
- `mvn -f common-util/pom.xml test` passed 75 tests; the focused Wallet run included the passing cache-register test, and `LedgerTxnStoreUnitTest` passed 3 tests.
- Material correction: narrowed cache soft failure from all cache/instrumentation failures to translated `CacheException`s; the existing observability limitation now appears at this boundary too.

**Known gaps after verification:**

- No live Redis/client-side tracking test or common cache correctness suite was run.
- No live Redis/client-side invalidation or direct Lua/codec correctness suite was run; these remain evidence gaps rather than contradictions in the bounded document.

---

## D-004 — Database transaction, persistence, exception, and SQL-metrics foundation

**Priority:** P1
**Status:** VERIFIED
**Canonical document:** `design/infrastructure/database-foundation.md`
**Modules/services:** `common-util`, with focused adoption evidence from `wallet-service` and `ledger-service`
**Owner topic:** Shared Spring/MyBatis transaction execution, repository primitives, timestamps/optimistic locking, DB exception normalization, and SQL/transaction instrumentation.

### Why this deserves documentation

- Failed `Result` rollback is a project-specific transaction contract used by financial state transitions and outbox claims.
- DB exception classification controls retryability at higher layers and intentionally distinguishes transient lock, timeout, unavailable, and internal/ambiguous failures.
- SQL and full-transaction timers provide different operational boundaries and are implemented with hot-path/cardinality safeguards.

### Questions the document must answer

- What begins/ends a transaction, why are Spring's default propagation/isolation/timeout settings currently sufficient, and which results/exceptions cause rollback?
- Why is a fresh `TransactionTemplate` created per call?
- Which repository and executor calls are covered by DB exception AOP, and which exception types/error codes are classified?
- How are commit/rollback failures with an unknown database outcome surfaced, including exceptions not classified by `DbExceptionTranslator`?
- What does `insertIgnore` guarantee, and when does a specific caller's requirement demand same-intent verification rather than duplicate-as-failure handling?
- What do the MyBatis statement timer and transaction timer include/exclude, and how is metric cardinality bounded?
- Which common MyBatis features are actually adopted (`BaseEntity`, auto-fill, optimistic lock)?

### Implementation evidence

| Evidence type | Path / symbol / artifact | What it supports |
|---|---|---|
| Source | `common-util/src/main/java/com/exchange/common/db/utils/DefaultDbTxnExecutor.java`, `MeteredDbTxnExecutor.java` | Fresh template, rollback-on-failed-Result/exception, commit-inclusive timer. |
| Source | `common-util/src/main/java/com/exchange/common/db/exception/DbExceptionTranslator.java`, `common-util/src/main/java/com/exchange/common/db/aop/DbExceptionTranslateAop.java` | Conservative typed/root-cause classification and repository/executor boundary advice. |
| Source | `common-util/src/main/java/com/exchange/common/db/manager/DbBaseRepository.java` | Mapper wrapper and duplicate-key-to-zero `insertIgnore`. |
| Source | `common-util/src/main/java/com/exchange/common/db/metrics/MybatisStatementMetricsInterceptor.java` | JDBC-adjacent timing, one-shot ThreadLocal metadata handoff, bounded timer admission, best-effort metrics. |
| Source | `common-util/src/main/java/com/exchange/common/db/register/CommonDbComponentRegister.java`, `common-util/src/main/java/com/exchange/common/db/po/BaseEntity.java`, `common-util/src/main/java/com/exchange/common/db/handler/AutofillMetaObjectHandler.java` | Bean wiring, optimistic-lock plugin, timestamps, metrics and translation registration. |
| Source | `wallet-service/src/main/java/com/exchange/app/wallet/processor/transaction/step/BeforePostLedgerProcessor.java`, `AfterPostLedgerProcessor.java`, `UpdateBalanceSnapshotProcessor.java`, `UpdateReservationProcessor.java` | Multi-write transaction composition, failed-Result rollback, balance lock ordering, the reservation lock-order gap, conditional updates, and post-commit work. |
| Source | `ledger-service/src/main/java/com/exchange/app/ledger/processor/post/PostLedgerProcessor.java`, `common-util/src/main/java/com/exchange/common/outbox/retry/OutboxRetryHandler.java` | Ledger transaction/entry atomicity and short outbox claim transactions. |
| Source | Wallet and Ledger repository/mapper packages; `BalanceSnapshot.java`, `LedgerTxn.java`, `Outbox.java` | Service-owned query/lock/CAS methods, multi-value batch inserts, affected-row checks, selective `BaseEntity` adoption, and current `@Version` entities. |
| Source | Wallet/Ledger boundary mappers and gRPC service implementations; `common-util/src/main/java/com/exchange/common/kafka/listener/DefaultListener.java` | DB-coded failed-Result collapse, generic exception-to-server-error guards, and retryable-versus-terminal listener treatment. |
| Source | Both service server classes and repository/processor imports | Both services explicitly import the configuration; all inspected service repositories extend `DbBaseRepository`; core transactions use `DbTxnExecutor`. |
| Config | Both service `application.yml` files | Hikari sizing/timeouts, MyBatis statement timeout, DB histogram/SLO configuration. |
| Schema | `standard/sql/wallet_service.sql`, `standard/sql/ledger_service.sql` | Current PK/UK/index/timestamp/version safeguards and absence of additional FK/`CHECK` domain enforcement. |
| Test | `common-util/src/test/java/com/exchange/common/db/utils/DefaultDbTxnExecutorTest.java`, `common-util/src/test/java/com/exchange/common/db/utils/MeteredDbTxnExecutorTest.java` | Commit, rollback, exception/commit propagation, and outcome timing. |
| Test | `common-util/src/test/java/com/exchange/common/db/exception/DbExceptionTranslatorTest.java`, `common-util/src/test/java/com/exchange/common/db/aop/DbExceptionTranslateAopPointcutTest.java` | Error classification and transaction-executor pointcut matching; repository advice is source-validated but has no equivalent focused pointcut test. |
| Test | `common-util/src/test/java/com/exchange/common/db/metrics/MybatisStatementMetricsInterceptorTest.java` | Statement metadata, timing/error classification, context cleanup and overflow behavior. |
| Test | `wallet-service/src/test/java/com/exchange/app/wallet/dao/repository/WalletReservationRepositoryTest.java`, `ledger-service/src/test/java/com/exchange/app/ledger/dao/repository/LedgerEntryRepositoryTest.java` | Focused repository predicate/pagination evidence; the Ledger test requires a full external-service-coupled Spring bootstrap. |
| Monitoring | `monitoring/grafana/dashboards/service-data-access-overview.json`, both service overview dashboards | Current DB transaction/statement/error and Hikari queries consuming the emitted metrics. |
| External research doc | `doc/monitor/mybatis-statement-metrics-design.md` | Focused timing/cardinality description; revalidate and restate supported content in D-004 without linking to this file. |
| Historical note | `engineering-notes/bugfix/db-txn-executor-evaluation.md` | Rationale for per-call `TransactionTemplate`; current code confirms the fix. |

### Important invariants / guarantees discovered

- A failed `Result<?>` marks the Spring transaction rollback-only; success is returned only after transaction completion.
- A thrown exception marks rollback-only and is rethrown.
- A fresh `TransactionTemplate` is created for every execution, avoiding shared mutable propagation configuration.
- Spring's default `REQUIRED` propagation, resource-default isolation, default timeout, and read-write mode are intentionally sufficient for all current `DbTxnExecutor` call sites; no current call nests one executor invocation inside another.
- The callback must return a non-null `Result`; `null` fails during result inspection and is rolled back as an exception path.
- MySQL deadlock 1213, lock-wait timeout 1205, and SQL state 40001 normalize to retryable `DB_TRANSIENT`; typed timeout/unavailable failures have separate retryable codes.
- Existing `DbException` is not double-wrapped; non-DB exceptions are not deliberately reclassified.
- A commit/rollback exception with an unknown result is treated as a failed operation, not evidence of rollback. Common-util propagates the failure; the upstream workflow owns an idempotent retry or an authoritative status check.
- `insertIgnore` intentionally reports only inserted-versus-duplicate. Same-intent verification is caller- and requirement-specific rather than a universal repository convention.
- `insertIgnore` catches a Spring `DuplicateKeyException`; it does not issue SQL `INSERT IGNORE` and does not identify which unique key collided.
- The optimistic-lock plugin, row-lock clauses, and conditional predicates protect only service-selected fields; every caller must interpret affected-row counts and preserve its lock order. Wallet balance locking is PK-ordered, while reservation locking currently is not deterministically ordered despite its source comment.
- `BaseEntity` timestamp fill is opt-in, not a universal persistence convention.
- MyBatis statement-metric failures are suppressed so that interceptor does not change SQL results. `MeteredDbTxnExecutor` does not guard timer construction or recording, so transaction metrics are not fully failure-isolated. Statement-duration timer admission is approximately bounded at 512 named series plus overflow, while the separate error counter has no equivalent admission cap.
- SQL text, parameters, error messages, request IDs, and business IDs are not metric labels; statement IDs are shortened to `MapperName.method`.

### Failure scenarios discovered

- Transaction begin/commit/rollback normalization depends on the thrown type; an arbitrary commit failure can escape unclassified even though `TransactionSystemException` maps to non-retryable `DB_INTERNAL`. The accepted policy is still to surface it as failure and leave retry/status confirmation to the upstream workflow.
- `insertIgnore` treats any duplicate key as zero. Callers that require idempotent same-intent replay or conflict detection must implement that domain-specific check; other callers may correctly map the duplicate directly to a business failure.
- Transaction execution intentionally uses Spring defaults today. It exposes no transaction name, timeout, isolation, or explicit propagation variants; only an optional custom-name parameter is currently considered a useful future enhancement.
- The optimistic-lock plugin is shared, but whether an affected-row result is interpreted correctly remains service-owned.
- Spring transaction context is thread-bound; work moved to another thread is outside the current transaction unless separately coordinated.
- Deadlock/timeout classification marks retryability but triggers no automatic DB retry; the surrounding request/listener and idempotency flow must decide whether replay is safe.
- The statement timer excludes connection acquisition, preparation/binding, and commit/rollback; the transaction timer has only an outcome label and cannot attribute latency to a named workflow.
- Timer cardinality overflow is bounded approximately under concurrent first registration, but error-counter cardinality relies on the practical finiteness of mapper IDs.
- Wallet reservation IDs are neither sorted before `selectInIdForUpdate` nor ordered by the query, so overlapping requests with different reservation order can still depend on DB deadlock detection and higher-layer retry.
- A Micrometer failure in `MeteredDbTxnExecutor` can mask the caller-visible result after the delegate finishes; if the database already committed, instrumentation cannot reverse that commit.

### Dependencies / related designs

- D-005 owns Result/error/retryability contracts.
- D-006 owns DB/Hikari observability.
- D-001 uses this foundation for outbox persistence, claiming, and duplicate translation.
- D-002 uses DB uniqueness and failed-Result rollback as the final duplicate-effect boundary after Redis coordination.
- Future service documents own SQL guards, update ordering, and domain invariants.

### Document boundary

**Include here:**

- Common transaction executor, translation pointcuts/classifier, repository/timestamp/optimistic-lock helpers, SQL and transaction metric boundaries, and adoption.

**Link elsewhere instead of duplicating:**

- Wallet/Ledger schemas, monetary constraints, domain update SQL, lock ordering, or accounting correctness.
- Outbox query/state details; link D-001.
- Dashboard interpretation beyond DB/Hikari signal definitions; link D-006.

### Uncertainty / TODO

- `FUTURE ENHANCEMENT` — Add an optional bounded transaction-name parameter for `DbTxnExecutor` while retaining the currently sufficient Spring defaults and per-call template isolation. This is not implemented.
- `TEST GAP` — Add a focused repository-AOP proxy test plus live MySQL coverage for deadlock, lock-wait timeout, optimistic-lock loss, duplicate handling, nested `REQUIRED` behavior, and begin/commit/rollback failure semantics.
- `FUTURE VALIDATION` — Introduce explicit timeout/isolation/propagation variants only when a concrete workflow demonstrates a need beyond the current defaults.
- `CROSS-DESIGN CORRECTNESS GAP` — Enforce and test primary-key ordering in Wallet reservation locking; route the business-level fix and invariant proof to D-015.
- `FUTURE HARDENING` — Add bounded admission for DB error-counter statement labels if mapper identities become dynamic or the finite-mapper assumption stops being safe.
- `FUTURE DIAGNOSTICS` — Consider rate-limited reporting for suppressed metric failures if runtime evidence shows silent loss is difficult to diagnose.

### Verification record

**Last verified:** `2026-09-09 — quick adversarial verification pass`
**Verified against:**

- Rechecked the transaction executor/decorator, exception translator/AOP pointcuts, repository helper, MyBatis interceptor, service transaction call sites, lock/order assumptions, schema safeguards, configuration, and selected tests.
- `mvn -f common-util/pom.xml test` passed 75 tests. The focused Wallet run passed 11 tests, including DB AOP wiring; the previously verified reservation-query evidence remains unchanged.
- Material correction: only MyBatis statement metrics suppress instrumentation failures; the transaction timer can affect the caller-visible outcome.

**Known gaps after verification:**

- No live MySQL transaction/lock/failure test was run.
- The focused Ledger repository test was interrupted because its full Spring context waited on the configured external Redisson dependency; it produced no test result.
- No live MySQL transaction/lock/failure test was run; real resource-manager semantics remain a full-pass evidence gap.

---

## D-005 — Shared Result, error taxonomy, exception, and boundary-mapping contract

**Priority:** P1
**Status:** VERIFIED
**Canonical document:** `design/infrastructure/result-and-error-contract.md`
**Accepted decision note:** `design/proposals/public-infrastructure-error-boundary-policy.md`
**Modules/services:** `common-util`, `wallet-service`, `ledger-service`
**Owner topic:** Transport-neutral operation results, namespaced/category-based errors, retryability metadata, typed exceptions, and the service boundary that converts shared failures into public errors.

### Why this deserves documentation

- This is the control-flow vocabulary shared by DB, Redis, cache, idempotency, outbox, Kafka, and both services.
- `Result` constructor invariants prevent contradictory success/error/value states and make failure propagation explicit.
- The implementation intentionally separates internal common errors from public service/protobuf error surfaces.

### Questions the document must answer

- When does current code return `Result.failure` versus throw `CustomizedException`/`DbException`/listener exceptions, and what single policy best describes that implemented behavior?
- What invariants do Result factories enforce, and how do `failure(IResult)`, `from`, and `is` preserve/inspect failure context?
- What are namespace, numeric code, message, category, scope, detail, and retryable marker each for?
- Where are common errors translated into Wallet/Ledger errors and protobuf categories?
- Which common infrastructure failures are deliberately downgraded to Result and which are allowed to escape as exceptions?
- How should callers handle a null Result or an unknown/unmapped error family?

### Implementation evidence

| Evidence type | Path / symbol / artifact | What it supports |
|---|---|---|
| Source | `common-util/src/main/java/com/exchange/common/result/Result.java`, `IResult.java` | Immutable/factory-only result and state invariants, propagation helpers, detail/scope. |
| Source | `common-util/src/main/java/com/exchange/common/result/error/**` | Namespaced error families, categories, and retryable marker. |
| Source | `common-util/src/main/java/com/exchange/common/exception/**` | Error-carrying runtime exceptions and DB/listener specializations. |
| Source | `common-util/src/main/java/com/exchange/common/db/aop/DbExceptionTranslateAop.java`, `common-util/src/main/java/com/exchange/common/redis/aop/**`, `common-util/src/main/java/com/exchange/common/redis/cache/strategy/impl/StrategyFactory.java`, `common-util/src/main/java/com/exchange/common/redis/idemp/DefaultIdempotencyClient.java` | Boundary-specific exception preservation versus deliberate downgrade to failed Result. |
| Source | `common-util/src/main/java/com/exchange/common/kafka/listener/DefaultListener.java`, `listener/exception/**`, both service Kafka message handlers | Conversion from service outcomes/error metadata to retry, durable failure reply, or DLT control flow. |
| Source | `wallet-service/src/main/java/com/exchange/app/wallet/result/WalletBoundaryErrorMapper.java`, `ledger-service/src/main/java/com/exchange/app/ledger/result/LedgerBoundaryErrorMapper.java` | Shared-to-service normalization and stable public surfaces. |
| Source | Both service `ProtoErrorMapper.java` and `PbErrorBuilder.java` files; both gRPC service implementations | Category-to-protobuf mapping, detail forwarding, exception guards, and business errors embedded in normally completed replies. |
| Wire contract | `grpc-proto/common-proto/src/main/proto/com/exchange/proto/common/error.proto` | Public category/message/detail shape; namespace, numeric service code, and scope are absent. |
| Source | `wallet-service/src/main/java/com/exchange/app/wallet/result/WalletRemoteErrorWrapper.java` | Re-owning a remote service error while retaining upstream facts only as diagnostic detail. |
| Usage | Current common-util and service Java sources | 276 Result/IResult factory/helper references found in the focused scan; the model is actively adopted, not scaffolding. |
| Retired historical doc | `standard/design/error-handling.txt` (removed 2026-09-09) | Superseded exception-only infrastructure policy that conflicted with implemented soft-dependency downgrade paths. |
| Historical note | `engineering-notes/refactor/result-util/**` | Migration rationale and older intermediate states; source must remain authoritative. |
| Accepted policy | `design/proposals/public-infrastructure-error-boundary-policy.md` | Selective future mapping of temporary, safely retryable infrastructure failures to service-owned `UNAVAILABLE`; implementation remains pending. |
| Test | Common DB/Kafka/outbox tests; Wallet processor/business-metric tests; focused Ledger Kafka-handler tests | Indirectly exercise Result propagation, rollback meaning, exception classification, and boundary outcomes; no focused complete Result/taxonomy/mapper suite exists. |

### Important invariants / guarantees discovered

- Success cannot carry an error code; failure must carry an error code and cannot carry a value.
- Failure conversion preserves error code, detail, and scope; attempting to construct failure from success is rejected.
- Error categories are transport-neutral; services own mapping to `ErrorCodePb` and their public error enums.
- Retryability is opt-in through `RetryableErrorCode`, not a property every business error must define.
- Retryable metadata alone does not establish replay safety; commit/ack outcome and idempotency coverage also matter.
- Expected business outcomes use Result or a use-case result object; exceptions escape rollback or adapter-control boundaries until an explicit layer converts them.
- Common infrastructure namespaces are generally collapsed to service-owned internal errors at public boundaries.
- Public protobuf errors carry shared category, service-owned message, and detail; they omit common namespace, numeric code, and Result scope.
- Current gRPC services embed failures in reply payloads and normally complete the RPC with transport status `OK`, so clients must inspect `ErrorPb`.
- A successful Result may contain `null`; a null Result itself is outside the effective contract even though some boundaries defend against it.
- The historical `standard/design/error-handling.txt` is retired; D-005 now owns the current Result-versus-exception policy.
- The accepted future public-boundary policy is selective rather than category passthrough: only temporary failures remaining after fallback/retry, with a known-safe replay contract, may become service-owned `UNAVAILABLE` errors.

### Failure scenarios discovered

- The retired historical error policy said infrastructure problems always throw, while current cache, idempotency, and publisher layers intentionally downgrade selected failures to Result; keeping both would have created conflicting authorities.
- Wallet and Ledger boundary mappings are similar but not identical (for example Kafka namespace handling), which may be intentional based on reachable paths but is not documented.
- Both boundary mappers currently lack a service-owned `UNAVAILABLE` error and blanket-map the affected infrastructure namespaces to `INTERNAL_ERROR`; detailed code TODOs now preserve the accepted eligibility and safety constraints for the future change.
- No direct tests lock Result constructor/factory invariants, error taxonomy uniqueness, category coverage, or boundary-mapper completeness.
- `detail` and `scope` are free-form and their safe/loggable/public exposure policy is not explicit.
- `PbErrorBuilder` forwards detail to public replies while scope is dropped; no redaction or size rule is enforced.
- Result failures depend on callers checking them, and null-Result behavior is defensive but inconsistent rather than type-enforced.
- Wallet and Ledger namespace handling is not fully symmetric; unknown foreign codes fall back to internal error, with logging only for non-null unmapped codes.

### Dependencies / related designs

- D-001 through D-004 use this model and should link here rather than redefine Result/error semantics; D-004 owns the transaction effect of a failed Result.
- D-006 uses error categories/codes to build bounded metric outcomes.
- Future service docs own public business error meanings.

### Document boundary

**Include here:**

- Result state model/helpers, common error metadata/namespaces, retryability, exception hierarchy, service boundary ownership, and current inconsistencies.

**Link elsewhere instead of duplicating:**

- Detailed DB/Redis/Kafka classification tables; keep them in D-001–D-004 and summarize only ownership here.
- Every Wallet/Ledger business error code.
- The detailed future `UNAVAILABLE` eligibility/mapping policy; summarize current-versus-target behavior here and link `design/proposals/public-infrastructure-error-boundary-policy.md`.

### Uncertainty / TODO

- `COMPLETED 2026-09-09` — Retired `standard/design/error-handling.txt` after D-005 established the canonical implementation-derived policy.
- `VERIFIED 2026-09-09` — The implementation-derived Result-versus-exception policy was checked in a quick adversarial pass; focused complete contract tests remain desirable.
- `DECIDED / NOT IMPLEMENTED` — Selectively map temporary infrastructure failures to service-owned `UNAVAILABLE` only after fallback is exhausted and replay is known safe; retain internal defects and unknown-result cases as `INTERNAL`. See the accepted decision note.
- `TODO: DEFERRED ALIGNMENT` — Later define whether `detail`/`scope` may cross RPC/event boundaries and what redaction/cardinality rules apply.
- `TODO: TEST COVERAGE` — Add focused Result factory/invariant, taxonomy uniqueness, protobuf category, mapper fallback/parity, detail/scope, and null-contract tests.
- `TODO: STATIC SAFETY` — Consider a propagation helper or static-analysis rule for ignored failed Results and accidental null returns.

### Verification record

**Last verified:** `2026-09-09 — quick adversarial verification pass`
**Verified against:**

- Rechecked `Result` factories/invariants, error families and retryability, exception translation/downgrade boundaries, both service boundary/protobuf mappers, gRPC reply behavior, and representative Kafka/DB/cache consumers.
- `mvn -f common-util/pom.xml test` passed 75 tests; the focused Wallet run passed 11 tests including business-metric and DB-boundary wiring checks.
- No material contract contradiction was found; current public `INTERNAL_ERROR` collapse and free-form detail/scope limitations remain explicitly bounded.

**Known gaps after verification:**

- No direct Result/taxonomy/boundary coverage test exists.
- Current Wallet/Ledger boundary code still collapses the affected common infrastructure errors to `INTERNAL_ERROR`; the accepted policy is not yet implemented.
- No focused complete Result/taxonomy/boundary suite exists; this is a test-coverage gap for a later full pass.

---

## D-006 — Shared application observability instrumentation

**Priority:** P1
**Status:** VERIFIED
**Canonical document:** `design/operations/observability.md`
**Modules/services:** `common-util`, `monitoring`, with focused registration/config evidence from both services
**Owner topic:** Common Micrometer measurement boundaries, low-cardinality conventions, application scrape/export, and shared Grafana operational views.

### Why this deserves documentation

- The repository contains implemented instrumentation and provisioned dashboards across gRPC, DB, Redis/cache/idempotency, Kafka listeners/publishers, outbox delivery, JVM, and Hikari—not merely a monitoring dependency.
- Measurement boundaries encode useful system knowledge: broker acknowledgment versus enqueue, SQL statement versus whole transaction, listener work versus immediate reply publishing.
- Interview claims must separate available metric code/dashboards from runtime observations and missing infrastructure exporters.

### Questions the document must answer

- Which metrics exist, where do their timers begin/end, and which outcomes/tags are bounded?
- Which metric failures are isolated from business behavior, and are any decorators able to affect a hot path?
- How are gRPC active calls/duration registered in both services?
- How do cache result metrics differ from low-level Redis operation latency?
- How do Kafka listener processing delay, listener duration, publisher duration, outbox delivery delay, and confirmed DLT differ?
- Which dashboards consume the metrics, and what bottleneck/failure question does each important panel answer?
- Which promised operational signals do not yet exist (for example outbox backlog/exhaustion), and which MySQL/Redis/Kafka server exporters are absent?

### Implementation evidence

| Evidence type | Path / symbol / artifact | What it supports |
|---|---|---|
| Source | `common-util/src/main/java/com/exchange/common/metrics/CommonMetrics.java`, `CommonMetricTags.java`, `CommonMetricTagValues.java` | Shared metric names/descriptions and bounded vocabularies. |
| Source | `common-util/src/main/java/com/exchange/common/metrics/GrpcServerMetricsInterceptor.java` | Per-method active gauge and once-only duration/status recording. |
| Source | `common-util/src/main/java/com/exchange/common/db/utils/MeteredDbTxnExecutor.java`, `common-util/src/main/java/com/exchange/common/db/metrics/MybatisStatementMetricsInterceptor.java` | Whole transaction versus JDBC-adjacent statement boundaries. |
| Source | `common-util/src/main/java/com/exchange/common/redis/metrics/**`, `common-util/src/main/java/com/exchange/common/redis/cache/strategy/metrics/**`, `common-util/src/main/java/com/exchange/common/redis/idemp/DefaultIdempotencyClient.java` | Low-level Redis latency, strategy outcomes/errors, idempotency failure counter. |
| Source | `common-util/src/main/java/com/exchange/common/kafka/producer/metrics/MeteredPublisher.java`, `common-util/src/main/java/com/exchange/common/kafka/listener/metrics/**` | Broker-completed publisher attempts, delivery age, listener outcome/delay/duration, confirmed DLT. |
| Source | `wallet-service/src/main/java/com/exchange/app/wallet/config/WalletMetricsConfig.java`, `ledger-service/src/main/java/com/exchange/app/ledger/config/LedgerMetricsConfig.java`, and service Kafka/cache registers | Current registration and metric decorators in Wallet and Ledger. |
| Source | Both service `*BusinessMetrics.java`, metric definition/tag classes, gRPC services, and Kafka handlers | Service-owned business outcome/duration layer, ingress separation, embedded-error visibility, and selective adoption. |
| Config | Both service `application.yml` files | Actuator Prometheus exposure, application tag, histograms and SLO buckets. |
| Build/config | `common-util/pom.xml`, both service POMs, both `logback-spring.xml` files | Micrometer core versus service Actuator/Prometheus ownership; plain-text local logging; no tracing dependency. |
| Config | `monitoring/prometheus.yml`, `deploy/docker-compose.monitoring.yml` | Five-second app scraping, Prometheus retention/remote-write receiver, Grafana provisioning. |
| Dashboard | `monitoring/grafana/dashboards/service-grpc-overview.json` | gRPC traffic, status, latency, and active-call panels. |
| Dashboard | `monitoring/grafana/dashboards/service-data-access-overview.json` | DB/Hikari, Redis, cache, and idempotency panels. |
| Dashboard | `monitoring/grafana/dashboards/kafka-messaging-overview.json` | Kafka publisher/listener/outbox delay and DLT panels. |
| Dashboard | `monitoring/grafana/dashboards/service-runtime-overview.json` | JVM/process/GC/thread panels from Actuator. |
| Owner verification | Actual `/actuator/prometheus` captures from both services | Project owner confirms that the provisioned dashboards were validated against emitted series; the captures were not retained as repository evidence in this discovery pass. |
| Test | Common DB transaction/statement, Kafka publisher/listener/DLT tests; Wallet business-metric and gRPC-registration tests | Selected outcome, timing, cardinality, registration, and isolation behavior. Ledger business metrics and the gRPC lifecycle lack equivalent direct tests. |
| External research doc | `doc/monitor/mybatis-statement-metrics-design.md` | One focused implemented metric design; revalidate and restate supported content in D-006 without linking to this file. |

### Important invariants / guarantees discovered

- Common instrumentation uses bounded enum/constant outcomes on intended paths and substitutes `unknown` for null/blank values.
- MyBatis metric recording is best effort and suppresses instrumentation failures.
- Publisher metric observation returns the original future and attempts not to change publish completion semantics.
- Listener metrics are outside best-effort immediate reply publishing, so publisher latency does not contaminate listener duration.
- DLT count is emitted only from service `@DltHandler`, where delivery to the dead-letter handler is known.
- Both services expose `/actuator/prometheus` and configure histograms for shared timers.
- The project owner confirms that the provisioned dashboards have been validated against actual `/actuator/prometheus` output from both services.
- gRPC status and business outcome are separate signals because service failures are normally embedded in a reply completed with transport status `OK`.
- Request/business identifiers, Redis keys, SQL text/parameters, exception messages, and error detail are excluded from metric labels.
- The SQL metadata ThreadLocal and Kafka original-timestamp header are local measurement mechanisms, not distributed tracing context.
- Framework auto-instrumentation owns JVM/process/Hikari series; common-util owns the `zexchange.*` infrastructure meters; services own Wallet/Ledger business meters.
- No common tracing, span propagation, MDC correlation context, centralized log pipeline, or alert rules are implemented.

### Failure scenarios discovered

- No shared outbox pending/dead/exhausted count, oldest-age gauge, claim/finalization counter, or dispatcher budget metric exists.
- Prometheus currently scrapes only service endpoints; no MySQL, Redis, Kafka broker/consumer-lag, node, or container exporter target is configured.
- Dashboard validation is owner-confirmed, but its endpoint captures are not retained in the repository for independent revalidation.
- Metrics verification is partial and non-blocking: selected DB/Kafka paths have focused tests and several decorators defensively isolate recording failures, while `GrpcServerMetricsInterceptor` still has no direct lifecycle/concurrency test and complete decorator failure-isolation coverage is absent.
- Failure isolation is not universal: gRPC, transaction, Redis, cache, idempotency, and service business instrumentation contain registry/recording calls that are not all guarded and can affect the observed call if Micrometer throws.
- `MetricTagSanitizer` normalizes only null/blank values; it does not enforce an allowlist or admission limit for non-blank service-provided tags.
- Kafka processing delay relies on producer/broker and consumer wall clocks being synchronized; negative samples are clamped but skew is not corrected.
- Ledger enables histogram/SLO configuration for `zexchange.outbox.dispatch.duration`, but no current implementation emits that metric.

### Dependencies / related designs

- D-001–D-005 own the behavior being measured and the precise meaning of their outcomes.
- D-005 owns service error normalization used by business metric error-code tags.
- Future Wallet/Ledger docs own business metrics and state/backlog interpretation.
- Stress-test/performance documents must own measured results and environment evidence; D-006 must not claim benchmark outcomes.

### Document boundary

**Include here:**

- Common instrumentation architecture, timing/outcome semantics, registration, dashboard navigation/diagnostic intent, observed coverage gaps, and deployment boundary.

**Link elsewhere instead of duplicating:**

- Full subsystem business metrics, stress-test results, or infrastructure-specific tuning.
- Detailed cache/idempotency/DB/Kafka behavior; link D-001–D-004.

### Uncertainty / TODO

- `CONFIRMED COMPLETE (OWNER-VERIFIED)` — The provisioned dashboards have already been validated against actual `/actuator/prometheus` captures for both services.
- `TODO: FUTURE WORK` — Add infrastructure exporters and Kafka consumer-lag collection; these are explicitly outside current scope.
- `TODO: REQUIRED FUTURE WORK (LOW PRIORITY)` — Define and implement outbox backlog/exhaustion/finalization metrics after the `DEAD`-state policy is resolved. This is required operational visibility, but is lower priority because it does not affect business correctness or performance behavior.
- `TODO: PARTIAL / NON-BLOCKING` — Complete direct gRPC interceptor lifecycle/concurrency tests and the remaining metric-decorator failure-isolation review/coverage. Existing partial work is not a blocker for design or other changes.
- `TODO: CARDINALITY` — Audit every service-supplied tag and add allowlists or bounded admission where configuration/constants do not already constrain values.
- `TODO: CONFIG ALIGNMENT` — Remove the unused Ledger `zexchange.outbox.dispatch.duration` distribution configuration or implement the timer with the future outbox lifecycle signals.
- `TODO: REPRODUCIBLE EVIDENCE` — Retain live scrape fixtures or add automated metric/dashboard contract checks for later independent validation.
- `CURRENT LIMITATION` — Distributed tracing, MDC correlation propagation, centralized logging, and alert rules are not part of the implemented shared observability layer.

### Verification record

**Last verified:** `2026-09-09 — quick adversarial verification pass`
**Verified against:**

- Rechecked common instrumentation boundaries, metric names/tags, service business metrics and registration, service error interaction, Actuator/Prometheus/logging configuration, provisioned scrape/dashboard configuration, and selected tests.
- Project owner confirms prior validation of the dashboards against actual `/actuator/prometheus` captures from both services; this is recorded as owner verification rather than independently reproduced evidence.
- `mvn -f common-util/pom.xml test` passed 75 tests, including DB, Kafka publisher/listener, DLT, and listener-metric coverage. The focused Wallet run passed 11 tests, including all 5 business-metric/registration cases.
- No material contradiction was found; the document already distinguishes explicitly isolated metric paths from decorators that can affect application calls.

**Known gaps after verification:**

- The prior dashboard-validation captures are not retained in this repository/current pass, so later independent revalidation requires a fresh capture.
- Direct gRPC lifecycle/concurrency tests and complete metric-decorator failure-isolation coverage remain partial, non-blocking test debt.
- Infrastructure exporters, Kafka consumer lag, and the required low-priority outbox backlog/exhaustion/finalization signals remain future work.
- Metric tag allowlists/admission outside SQL timers, equivalent Ledger business-metric tests, and retained scrape evidence remain absent.
- Direct gRPC lifecycle/concurrency and complete decorator failure-isolation tests remain a later full-pass evidence gap.

---

## D-007 — Ledger posting and accounting correctness

**Priority:** P0
**Status:** VERIFIED
**Canonical document:** `design/ledger/posting-and-accounting-correctness.md`
**Modules/services:** `ledger-service`, `ledger-proto`
**Owner topic:** Ledger's passive journal model, posting validation, account resolution, transaction/entry persistence, and the exact boundary of accounting guarantees.

### Why this deserves documentation

- This is the core Ledger business design: a multi-entry, per-asset balanced journal rather than a balance-owning business service.
- The header and entries commit atomically, but several accounting rules are application validations rather than database invariants; the distinction is interview-critical.
- The implementation reveals material schema/validation gaps that must not be presented as guarantees.

### Questions the document must answer

- What does Ledger own, and why does it not enforce Wallet spend/reservation rules or maintain account balances?
- How are debit and credit totals validated per asset, and what values/identities are accepted or insufficiently checked?
- How are external account references resolved to internal account IDs, and which account attributes are not validated during posting?
- What commits atomically, what uniqueness constraints exist, and what integrity is not DB-enforced?
- Are entries append-only/final after posting, and what is the status of metadata updates?

### Compact implementation evidence

| Evidence type | Path / symbol / artifact | What it supports |
|---|---|---|
| Source | `ledger-service/src/main/java/com/exchange/app/ledger/processor/post/PostLedgerProcessor.java` | Per-asset debit/credit aggregation, account resolution, entry creation, and one DB transaction for header plus batch entries. |
| Source | `ledger-service/src/main/java/com/exchange/app/ledger/dao/repository/LedgerEntryRepository.java`, `dao/mapper/LedgerEntryMapper.java` | Batch insert and keyset retrieval of journal entries. |
| Source | `ledger-service/src/main/java/com/exchange/app/ledger/po/ledger/LedgerTxn.java`, `LedgerEntry.java`, `po/account/Account.java` | Persisted journal/account model; transaction metadata/version and entry dimensions. |
| Source | `ledger-service/src/main/java/com/exchange/app/ledger/result/PostTransactionResult.java` | Successful posting/replay requires nonblank reference and transaction IDs, exposing the post-commit blank-reference defect. |
| Contract | `grpc-proto/ledger-proto/src/main/proto/com/exchange/proto/ledger/post_service.proto`, `ledger_common.proto` | Public posting request, entry directions, integer minor-unit amount, and response identity. |
| Schema | `standard/sql/ledger_service.sql` | Unique transaction/reference/entry IDs, indexes, bigint amounts, and absence of FK/CHECK/trigger enforcement for accounting relationships. |
| Test | `ledger-service/src/test/java/com/exchange/app/ledger/processor/PostLedgerProcessorTest.java` | One Spring integration happy path persists one transaction and two equal entries. |
| Stress verification | `stress-test/fixtures/ledger-write-verification.sql` | Run-scoped SQL detects per-transaction/per-asset imbalance and exact entry-set mismatches. |
| Caller | `wallet-service/src/main/java/com/exchange/app/wallet/processor/transaction/util/ObjectBuilder.java`, `processor/CreateWalletProcessor.java` | Wallet maps transfer actions to Ledger debit/credit entries, uses its transaction ID as Ledger reference, and provisions the account-reference mapping used by posting. |
| Shared dependency | `design/infrastructure/database-foundation.md`, `design/infrastructure/result-and-error-contract.md` | Canonical transaction rollback/commit and public error-boundary behavior summarized rather than duplicated in D-007. |

### Important guarantees and limits discovered

- `PostLedgerProcessor` requires at least one debit and one credit and compares debit/credit sums independently for every request `assetId`.
- The `ledger_transactions` insert and all `ledger_entries` inserts run in one `DbTxnExecutor` transaction; a failed `Result` or thrown DB exception rolls the unit back.
- Ledger records journal facts only. There is no balance table or account-balance update; Wallet remains the gatekeeper for spend and reservation rules.
- Ledger's per-asset double-entry validation is application-only. Under the adopted project-wide database policy, the schema intentionally has no additional domain constraint proving debit equals credit and no foreign keys tying entries to transactions, accounts, or assets; PK/UK enforcement and conditional/CAS-like updates remain the permitted domain safeguards. Transaction atomicity still protects the all-or-nothing write unit but is distinct from validation.
- Posting checks `amount == 0`, not `amount > 0`; negative amounts are currently accepted and long summation has no overflow check.
- Posting does not explicitly reject blank `referenceId`, `accountRef`, or `assetId`; protobuf defaults and SQL `NOT NULL` do not make empty strings invalid. A balanced blank-reference request can commit before `PostTransactionResult` rejects its blank success identity, producing a durable journal with a caller-visible failure and no successful replay representation.
- Account resolution selects only `accountId` and `referenceId`. Posting does not validate account status, service scope, owner, normal side, account asset, or even that the entry asset exists.
- The database allows the same account reference in different services (`UNIQUE(service_id, reference_id)`), while posting looks up by reference alone. Multiple matches can make `Collectors.toMap` throw; a unique match can still be semantically wrong for the entry.
- `description` contributes to the Redis request hash but is not persisted in the created transaction (`metadata` defaults to `{}`).
- `LedgerTxnStore.updateMetadataByPk` supports optimistic metadata updates, but no active gRPC/Kafka flow calls it. Entries have no update path, yet the schema does not enforce a posted transaction's entry set as closed.

### Document boundary

**Include here:** Accounting model, journal schema, posting validation, account/asset mapping, atomic journal commit, integrity limits, and inactive metadata extension point.

**Link elsewhere instead of duplicating:** Redis/DB replay protocol (D-008), Kafka reply lifecycle (D-009), generic transaction/exception mechanics (D-004), and query caching (D-010).

### Uncertainty / TODO

- `DEFERRED / FUTURE DECISION` — Decide in a later version whether amounts must be strictly positive and whether overflow-safe per-asset summation belongs in the intended contract.
- `ACCEPTED REQUIREMENT / DEFERRED` — Posting should eventually define account identity scope and validate account service/status/asset plus any required owner dimensions, but this is outside the current version.
- `DECIDED / CURRENT POLICY` — Do not add database `CHECK`/FK enforcement or a posting stored procedure for journal-domain validation in the current project. Complete validation in application code; retain PK/UK checks and conditional/CAS-like updates as the database domain safeguards. Transaction atomicity remains a separate all-or-nothing guarantee. The anticipated throughput/deadlock benefit is rationale, not measured evidence.
- `ACCEPTED FUTURE REQUIREMENT` — Treat a posted entry set as immutable and add a transaction-local sequence/uniqueness contract in a future change; this is desirable but not mandatory for the current version.
- `DEFERRED TEST GAP` — Add focused validation, rollback, negative/overflow amount, multi-asset, mismatched-account-asset, and concurrent-post tests later; the current service suite has only one posting happy path.
- `DECIDED / FUTURE INTEGRATION` — Do not claim safe horizontal multi-instance uniqueness from the current time-plus-process-local-sequence generator. Ledger and Wallet are intended to integrate the project owner's distributed ID generator in a future change.
- `CURRENT CORRECTNESS GAP` — Add required-string validation before idempotency/SQL for posting `referenceId`, entry `accountRef`, and entry `assetId`. A blank `referenceId` can currently commit and then fail success-result construction; accepted and DB replay attempt the same invalid success identity.

### Verification record

**Last verified:** `2026-09-09 — time-boxed adversarial source/schema/test review`
**Verified against:** Rechecked the posting processor, transaction executor, account and journal repositories/mappers/entities, protobuf contract, Ledger schema, Wallet request construction, result construction, retained posting test, and stress reconciliation SQL. The document's guarantees are accurately limited to the active application path and schema keys; no material correction was required.

**Known gaps after verification:** The only posting integration test is JUnit 4 and is not discovered by the current JUnit Platform runner. No live DB rollback/concurrency, malformed-identifier, negative/overflow, multi-asset, account-eligibility, direct-write, or immutability test was performed; these are evidence gaps, while the verified document explicitly avoids claiming those behaviors.

---

## D-008 — Ledger posting idempotency, concurrency, and replay

**Priority:** P0
**Status:** VERIFIED
**Canonical document:** `design/ledger/posting-idempotency-and-replay.md`
**Modules/services:** `ledger-service`, with `common-util` Redis idempotency and DB transaction support
**Owner topic:** Ledger-specific idempotency key/hash semantics, Redis decision flow, authoritative DB uniqueness, duplicate races, and post-commit convergence.

### Why this deserves documentation

- The same processor serves synchronous gRPC and at-least-once Kafka delivery, making duplicate safety a primary Ledger guarantee.
- Redis coordinates the hot path, while MySQL `reference_id` uniqueness is the durable effect backstop; current request-equivalence gaps materially limit the claim.

### Questions the document must answer

- What does `referenceId` mean, which request fields form the stable hash, and is entry ordering significant?
- How do `PENDING`, `ACCEPTED`, Redis failure, malformed state, DB replay, and duplicate-insert races differ?
- Which state is authoritative after Redis expiry/loss, and can same-key/different-request conflict still be detected?
- What happens when DB commit succeeds but cache/idempotency completion fails?
- What concurrency is actually present when Ledger only appends journal rows rather than updating balances?

### Compact implementation evidence

| Evidence type | Path / symbol / artifact | What it supports |
|---|---|---|
| Source | `PostLedgerProcessor.idempClaimAndReqValidate`, `claimIdempCacheIfAbsent`, `getReqStableHash`, `onError` | Redis claim/replay/fallback/release and canonicalized request hashing. |
| Source | `LedgerTxnRepository.getByRefId`, transaction insert in `PostLedgerProcessor` | DB replay lookup and duplicate-insert behavior. |
| Schema | `standard/sql/ledger_service.sql` | Durable unique `ledger_transactions.reference_id` and `txn_id`. |
| Infrastructure | D-002 and `common-util/.../redis/idemp/**` | Atomic NX claim/token-owned release, unconditional current completion, TTLs, and typed failures. |
| Config | `ledger-service/src/main/resources/application.yml` | 120-second pending and 24-hour accepted TTLs plus jitter. |
| Stress code | `stress-test/scripts/ledger-grpc-write-stress.js`, `fixtures/ledger-write-verification.sql` | Exact duplicate scheduling, same-result checks, ambiguous-timeout reconciliation, and one-row/entry-set verification. |
| Shared source | `common-util/src/main/java/com/exchange/common/redis/idemp/{DefaultIdempotencyClient,IdempRedisClient}.java`, `redis/idemp/utils/{CommonIdempHelper,IdempValue}.java`, `utils/{StableHashHelper,TokenHelper}.java`, `src/main/resources/script/lua/release-idemp-if-owned.lua` | Exact Redis value parsing, error downgrade, Murmur-derived hash, UUID token, token-owned release, and unconditional completion. |
| Test | `common-util/src/test/java/com/exchange/common/cache/client/IdempRedisClientIT.java` | External-Redis claim and exact-owner release scenarios; excluded from default Surefire discovery by its `IT` name. |
| Caller/result | `wallet-service/.../transaction/util/ObjectBuilder.java`, `ledger-service/.../result/{PostTransactionResult,PostTransactionResultConverter}.java` | Wallet uses its transaction ID as Ledger reference; creation and replay return the same Ledger ID but different result kind/detail. |

### Important guarantees and limits discovered

- The Redis key is scoped to Ledger posting and request `referenceId`; the current hash covers reference, description, and entries sorted by account reference/direction/amount/asset, so original entry order is currently ignored without establishing the future target semantics.
- A visible same-key/different-hash Redis value returns `REQUEST_HASH_CONFLICT`; a live identical `PENDING` returns `REQUEST_IN_PROCESSING`; accepted state replays its transaction ID.
- Redis failure or malformed state degrades to a DB lookup. MySQL uniqueness prevents two committed transaction headers with the same reference ID.
- A claim rejected just before key expiry can be followed by a null read; Ledger falls back to MySQL without reacquiring Redis ownership, so overlapping work remains possible.
- Current DB rows do not store the request hash. After Redis loss/expiry, any existing `referenceId` is treated as an idempotent replay even if the request differs.
- A duplicate-insert race returns `LEDGER_DUPLICATED` rather than reading/replaying the winner, so concurrent callers do not yet receive one uniform replay outcome.
- Redis `markIdempDone` is post-commit and best effort. Its current unconditional write can overwrite a newer claim after ownership expires; DB correctness survives, but Redis coordination can become misleading.
- A parseable `ACCEPTED` value is trusted without checking MySQL, so the database is authoritative for committed effects but is not consulted for every replay response.
- The current `|`-delimited hash input has no escaping/length framing and stores only Murmur3-128 `asLong()`; canonical encoding and digest/equality strength remain part of the planned format decision.
- A blank `referenceId` can commit before success-result construction throws; Redis/DB replay then reconstructs the same invalid blank-reference success. Unique-key safety prevents another row but does not restore a usable response.
- On pre-commit error, token-owned Lua release is best effort. On post-commit cache/Redis failure, the business result remains committed and later DB replay can recover its transaction ID.

### Document boundary

**Include here:** Ledger request identity/hash, service decision tree, DB uniqueness/races, post-commit Redis behavior, replay responses, and exact current guarantees/gaps.

**Link elsewhere instead of duplicating:** Generic Redis protocol (D-002), journal validity/atomic writes (D-007), messaging duplicates/replies (D-009/D-001), and public error taxonomy (D-005).

### Uncertainty / TODO

- `DECIDED / NOT IMPLEMENTED` — Persist and compare a canonical request hash in MySQL on every replay/fallback/race, as already recorded in D-002 and `design/proposals/redis-idempotency-db-authoritative.md`.
- `DECIDED / NOT IMPLEMENTED` — Make accepted completion token-owned and non-destructive after ownership loss.
- `DEFERRED / IDEMPOTENCY FORMAT REFACTOR` — Entry-order and `description` identity semantics will be decided when the idempotency value format is refactored. Until then, document the current hashing behavior as implementation evidence, not as the confirmed target contract.
- `REQUIRED FORMAT WORK` — Replace the unescaped delimiter construction with an unambiguous canonical representation and consciously select the digest/equality strength; current caller-controlled `|` characters can make field boundaries ambiguous.
- `CURRENT CROSS-BOUNDARY DEFECT` — D-007 must reject blank `referenceId` before idempotency or DB work; otherwise D-008 cannot converge a committed result into a valid creation or replay response.
- `DEFERRED TEST GAP` — Focused processor coverage for Redis loss, hash conflict, pending ownership, malformed values, DB replay, duplicate-insert race, and post-commit completion failure is planned for a later change.

### Verification record

**Last verified:** `2026-09-09 — time-boxed adversarial source/schema/config review`
**Verified against:** Rechecked the Ledger processor/result flow, MySQL uniqueness, shared Redis client/Lua/value encoding, current TTL configuration, Wallet reference construction, external integration-test source, and stress duplicate/reconciliation tooling. Corrected only a stale cross-document reference; the documented guarantee downgrades match the implementation.

**Known gaps after verification:** There are no focused Ledger processor fault/race tests, and no live Redis/MySQL concurrency, expiry, malformed-value replacement, unknown-commit, accepted-content, or post-commit-completion test was run. The document is verified as an accurate bounded account of current behavior, not as runtime proof of those race scenarios.

---

## D-009 — Ledger asynchronous command processing and durable replies

**Priority:** P0
**Status:** VERIFIED
**Canonical document:** `design/ledger/async-posting-and-reply.md`
**Modules/services:** `ledger-service`, common Kafka/outbox infrastructure, Wallet only as command producer/reply consumer
**Owner topic:** Ledger's service-specific Kafka ingress policy, shared posting invocation, failure-reply construction, reply identity, and the deliberate business-commit/reply-outbox transaction separation.

### Why this deserves documentation

- This is the active Wallet settlement integration and the main place where Ledger's local commit becomes an eventually consistent cross-service outcome.
- It contains deliberate and unresolved failure windows that cannot be explained solely by the generic common-util outbox document.

### Questions the document must answer

- How does the Kafka command path reuse the gRPC posting processor, and which transport behaviors differ?
- When is a failure retried, converted to a durable failure reply, or routed to DLT?
- What action must be durable before source acknowledgment, and why is reply persistence outside the Ledger business transaction?
- How are request, command, reply, partition, and business transaction identities related?
- Which failures can leave Wallet waiting after Ledger committed, and what recovery exists today?

### Compact implementation evidence

| Evidence type | Path / symbol / artifact | What it supports |
|---|---|---|
| Source | `ledger-service/.../kafka/consumer/LedgerCommandMessageHandler.java`, `LedgerFailureReplyFactory.java` | Envelope dispatch, protobuf parsing, processor reuse, retry classification, and failed reply mapping. |
| Source | `ledger-service/.../kafka/consumer/DefaultListener.java`, `kafka/config/LedgerListenerConfig.java`, `LedgerListenerRetryProperties.java` | Retry-topic/DLT wiring, manual acknowledgment delegate, and failure-reply threshold. |
| Source | `ledger-service/.../utils/OutboxHelper.java`, `result/PostTransactionResultConverter.java` | Deterministic outcome-specific reply IDs, payload, topic, and partition key. |
| Source/config | `LedgerPublisherConfig.java`, `cronjob/outbox/OutboxDispatcher.java`, `application.yml` | Shared publisher/retry handler, five-second dispatcher, attempts/backoff, and page size. |
| Schema/deployment | `standard/sql/ledger_service.sql`, `standard/docker/kafka/create-topics.sh`, `deploy/docker-compose.kafka.yml` | Durable reply rows, unique event IDs, base/retry/DLT topics, and three-partition single-broker defaults. |
| Tests | `ledger-service/src/test/java/com/exchange/app/ledger/kafka/**` | Success/failure reply identity, invalid payload/type, retryable in-processing, boundary mapping, and retry-threshold validation. |
| Caller interaction | `wallet-service/.../processor/transaction/util/ObjectBuilder.java`, `utils/OutboxHelper.java`, `kafka/consumer/WalletLedgerReplyMessageHandler.java` | Wallet uses its transaction ID as Ledger reference/command/partition key and only applies a successful Ledger reply. |

### Important guarantees and limits discovered

- gRPC and Kafka both call `PostLedgerProcessor`; Kafka adds envelope parsing, durable reply materialization, acknowledgment, retries, and DLT policy.
- `REQUEST_IN_PROCESSING` is the only explicitly retryable Ledger business outcome. Other processor failures throw a non-retryable service exception so the common reply handler can create a failed reply; envelope-level poison data without usable request identity can reach DLT.
- The common listener acknowledges only after a reply outbox is inserted or verified. Immediate publishing is best effort; scheduled outbox retry provides at-least-once delivery.
- Ledger business state commits before reply-outbox persistence in a separate durability phase. The reply insert, or duplicate insert followed by intent verification, runs outside the journal transaction; no explicit transaction wraps that multi-statement sequence. This separation is owner-confirmed and intentional, but a crash/terminal reply failure after business commit can leave Wallet unfinalized.
- The accepted recovery direction is a Wallet-owned scheduled job that finds stuck transactions and reposts the same logical Ledger request. Reuse of the same business identity and payload allows Ledger idempotency to prevent duplicate journal effects. DLT/retry-exhaustion signals are for alerting and metrics, not the correctness dependency. This recovery job is not implemented yet, and the current reply reconstruction/outbox-payload conflict must still be fixed for reliable convergence.
- Replies partition by Wallet `commandId`. Current event IDs are deterministic per request event and outcome, while same-intent verification also compares the serialized payload.
- A newly created success and DB/Redis replay use the same success event ID but different detail text; reconstructed payload inequality can produce an outbox conflict, a known current design flaw already captured in D-001/Q&A.
- Wallet's active write flow uses Kafka. Direct gRPC posting is an expired integration API retained only for test tooling; it is not a supported production path.

### Document boundary

**Include here:** Ledger-specific command/reply sequence, failure classification, reply contract/identity, local transaction boundaries, Wallet-facing assumptions, and current recovery gap.

**Link elsewhere instead of duplicating:** Shared outbox implementation and delivery mechanics (D-001), business posting/idempotency (D-007/D-008), Wallet finalization logic and full end-to-end consistency (future cross-system doc).

### Uncertainty / TODO

- `DECIDED / FUTURE IMPLEMENTATION` — Add a Wallet scheduled job to find stuck transactions and repost the same logical Ledger request. Ledger business idempotency makes the accounting effect retry-safe when identity and payload are reused. DLT/retry-exhaustion detection remains operational alerting/metrics and must not be the business-correctness mechanism. The current reply-payload conflict remains a prerequisite gap for reliable reply convergence.
- `TODO: CURRENT DEFECT` — Resolve deterministic reply ID plus whole-payload equality; the existing UUID-per-reply and durable-inbox documents are alternatives, not implemented behavior.
- `DECIDED / CURRENT SCOPE` — Direct gRPC posting is an expired API retained only for tests, not a supported production integration. Kafka is the active Wallet-to-Ledger posting path.
- `DEFERRED TEST GAP` — Real Kafka/MySQL coverage for crash-after-business-commit, reply-insert ambiguity/conflict, retry-to-failure-reply, source-ack failure, and Wallet convergence will be added later.

### Verification record

**Last verified:** `2026-09-09 — time-boxed adversarial source/config/test review`
**Verified against:** Rechecked current Ledger Kafka adapters/config, common listener and outbox repository implementation, reply construction, schema/topic provisioning, Wallet producer/consumer touchpoints, and focused JUnit 5 tests. Corrected the reply phase from an implied explicit transaction to the actual statement-level persistence/duplicate-read sequence, and qualified the acknowledgment marker as dependent on retry-header propagation. The focused hermetic Ledger run passed 13 tests, including 10 handler/reply/retry-configuration cases.

**Known gaps after verification:** No live broker/DB failure injection was run. Crash-after-journal-commit, reply insert ambiguity/conflict, mutated acknowledgment-header propagation, retry-to-failure reply, and Wallet convergence remain unverified runtime paths; the document classifies them accordingly.

---

## D-010 — Ledger transaction query and cache read model

**Priority:** P1
**Status:** VERIFIED
**Canonical document:** `design/ledger/transaction-query-and-cache.md`
**Modules/services:** `ledger-service`, common-util cache/DB
**Owner topic:** Query-by-transaction/reference behavior, optional journal-entry loading, two-level Redis read model, cache failure/freshness semantics, and API conversion.

### Why this deserves documentation

- The read path intentionally separates an immutable reference index from a versioned transaction entity cache and has explicit negative/tombstone/stale-reference handling.
- Query API semantics expose a material `accountRef`/internal-ID mismatch and depend on entry integrity not enforced by schema.

### Questions the document must answer

- How do lookup by `txnId` and `referenceId` differ, and when are entries loaded?
- What do value, miss, negative, and tombstone outcomes mean for each cache?
- Why does Ledger place a tombstone after inserting a transaction reference, which stale negative-fill race does it mitigate, and why must its TTL be tied to the in-flight request window?
- How does the service behave on Redis failure, stale positive reference, and post-insert cache-maintenance failure?
- How are entry pages retrieved and what is the performance effect of the configured batch size?
- Does the response preserve the public protobuf meaning of every field?

### Compact implementation evidence

| Evidence type | Path / symbol / artifact | What it supports |
|---|---|---|
| Source | `GetLedgerTxnProcessor.java`, `PostServiceImpl.java`, `PbConverter.java` | Lookup selection, optional entries, error behavior, and protobuf conversion. |
| Source | `dao/store/LedgerTxnStore.java`, `dao/cache/LedgerCacheRegister.java` | Raw ref cache, versioned transaction cache, fallback, negative/tombstone and stale-reference paths. |
| Source/schema | `LedgerEntryRepository.java`, `standard/sql/ledger_service.sql` | `(txn_id,id)` keyset pagination and configured page loop. |
| Config | `application.yml`, `CacheTtlConfig.java` | 1-hour reference TTL, 120-second entity/tombstone TTL, 30-second negative TTL, and batch size 3. |
| Tests | `LedgerTxnStoreUnitTest`, `LedgerEntryRepositoryTest`, `GetLedgerTxnProcessorTest`, `GetLedgerTxnProcessorIntegrationTest` | Selected cache fallback, pagination, lookup/not-found/include-entry behavior, and integration paths. |
| Stress tooling | `ledger-grpc-query-stress.js`, `ledger-query-fixture.sql`, query flow/operations docs | Separate txn/ref workloads, working-set/miss controls, two-entry fixtures, and no retained result set. |

### Important guarantees and limits discovered

- `txnId -> LedgerTxn` uses a versioned cache without negative caching; `referenceId -> txnId` uses a raw cache with negative caching for externally supplied misses.
- Cache failures are logged and degrade to MySQL. A stale positive ref pointing to a missing transaction causes an authoritative by-reference DB lookup rather than a false not-found.
- After insert, cache-aside `postInsert` writes a reference tombstone. It replaces any earlier negative value and, while present, prevents a query that missed the DB before the insert from completing later and restoring a stale negative entry.
- The entity cache has negative caching disabled, so cache-aside `afterInsert` performs no Redis write; the transaction entity is populated by a later authoritative DB hit. Transaction-ID lookup is publicly exposed by gRPC even though the no-negative-cache choice follows an internal/low-miss usage assumption.
- The tombstone is deliberately temporary. Its TTL should outlive the relevant request/in-flight cache-fill window, significantly reducing stale repopulation without claiming to prevent every possible delayed write, while ensuring obsolete tombstone keys do not remain indefinitely in Redis. The checked-in 120-second value is a pre-launch snapshot, not a confirmed duration. Protecting MySQL is not the expiry's primary purpose: in the normal read path, a tombstone hit queries MySQL once and the successful `afterDbHit` write replaces the tombstone with a normal cached value.
- Entry retrieval uses keyset pagination by increasing DB `id`; the configured page size is only 3, so larger transactions require many queries.
- When a transaction exists but included entries are missing, the processor throws `DataIntegrityViolationException`, which the gRPC adapter maps to a generic server error.
- `PbConverter` populates protobuf `LedgerEntryPb.accountRef` with stored internal `accountId`; the original external account reference is not persisted, so the current query contract is misleading.
- Metadata update/version-CAS support exists but has no exposed caller, so it must be presented as an unused extension point rather than active mutable-ledger behavior.

### Document boundary

**Include here:** Query API/read model, cache keys/outcomes/fallback/freshness, entry pagination, conversion semantics, and query test evidence.

**Link elsewhere instead of duplicating:** Generic cache encoding/Lua behavior (D-003), journal write invariants (D-007), generic DB/result behavior (D-004/D-005), and measured performance results (D-011).

### Uncertainty / TODO

- `DEFERRED / FUTURE CONTRACT DECISION` — Decide later whether to persist/return original account references or rename the protobuf field to internal `accountId`; the current mismatch remains documented in the meantime.
- `DEFERRED / PRE-LAUNCH CONFIGURATION` — Select and validate a realistic entry page size later. The current value `3` is only a checked-in snapshot and must not be presented as a confirmed tuning choice.
- `DEFERRED EVIDENCE GAP` — Execute and retain query benchmarks later; workload/tooling exists, but no retained query results were found.
- `DEFERRED TEST GAP` — Expand hermetic query/cache coverage later. The current store unit test covers negative-hit behavior and stale positive-reference repair, while Spring processor/repository tests still bind to external Redis/MySQL.
- `DEFERRED / PRE-LAUNCH CONFIGURATION VALIDATION` — Validate the tombstone duration after representative timing requirements exist, including a delayed pre-insert miss test. The design principle is to cover the relevant request/cache-fill window; the current 120 seconds is not a confirmed contract.

### Verification record

**Last verified:** `2026-09-09 — time-boxed adversarial source/config/schema/test review`
**Verified against:** Rechecked query/store/cache strategy and conversion code, schema/config, unit/integration-test source, and query workload/fixture definitions. Corrected the post-insert entity-cache claim: with negative caching disabled, `afterInsert` is a no-op and the entity is filled only after a DB-backed read. The focused hermetic Ledger run passed 13 tests, including all 3 `LedgerTxnStoreUnitTest` cases.

**Known gaps after verification:** No live Redis/MySQL run, delayed stale-fill test, entry-pagination runtime test, or query benchmark was executed. Spring query/repository tests remain environment-coupled; the current JUnit 5 store coverage is narrow but supports the documented negative-hit and stale-reference repair paths.

---

## D-011 — Ledger observability and performance evidence

**Priority:** P1
**Status:** VERIFIED
**Canonical document:** `design/operations/ledger-performance.md`
**Modules/services:** `ledger-service`, `stress-test`, `monitoring`
**Owner topic:** Ledger-specific business metrics, direct-gRPC write/query workloads, retained stress evidence, bottleneck diagnosis, and evidence limitations.

### Why this deserves documentation

- The repository contains repeatable workload generators, reconciliation SQL, dashboards, and retained write-run screenshots/analyses—not just theoretical performance advice.
- Historical results show a useful investigation from tail-latency symptoms through connection-pool amplification to a lower-load periodic DB-side stall, while explicitly not establishing hard saturation.

### Questions the document must answer

- What exactly do business, gRPC, DB transaction/operation, Hikari, cache, and client metrics measure?
- What fixtures, transaction sizes, duplicate patterns, arrival profiles, and reconciliation checks are used?
- Which results were actually observed, under what historical configuration, and which remain hypotheses?
- How did Hikari tuning change observed tail latency, and why is the pool now considered an amplifier rather than the initiating cause?
- What evidence is missing for Kafka ingress, query capacity, soak/recovery, and current-code revalidation?

### Compact implementation evidence

| Evidence type | Path / symbol / artifact | What it supports |
|---|---|---|
| Source | `ledger-service/.../metrics/LedgerBusinessMetrics.java`, `LedgerMetrics.java`, `LedgerMetricTags.java`, `LedgerMetricTagValues.java` | Business request/duration, created/replay and entries-per-transaction metrics plus their bounded dimensions. |
| Config/dashboard | `application.yml`, `monitoring/grafana/dashboards/ledger-service-overview.json`, `ledger-stress-test-overview.json` | Histogram/SLO buckets and service/stress diagnostic panels. |
| Workload | `stress-test/scripts/ledger-grpc-write-stress.js`, `ledger-grpc-query-stress.js`, `ledger-grpc-smoke.js` | Direct gRPC write/query/smoke logic and classification. |
| Fixture/verification | `stress-test/fixtures/ledger-write-fixture.sql`, `ledger-write-verification.sql`, `ledger-query-fixture.sql` | Ten assets/100 accounts, deterministic transaction shapes, SQL reconciliation, and query datasets. |
| Retained evidence | `doc/stress-test/stress-test-spike-research/1...6*.md` and `assets/*.png` | 2026-08 write runs, latency/pool/DB observations, tuning history, and unresolved periodic-stall analysis. |

### Important evidence and limits discovered

- Write stress calls direct gRPC `postTransaction`; it exercises the same business processor but excludes Kafka intake and Ledger reply-outbox cost.
- The designed write mix is 45% two-entry, 45% four-entry, 10% twenty-entry with about 5% exact duplicate attempts; SQL verifies balanced and exact persisted entry sets.
- Retained discovery runs reached approximately 600–625 RPC/s with zero unexpected errors and successful reconciliation in the cited runs, but hard sustainable throughput was explicitly not established.
- Earlier 10-connection runs showed hundreds of pending borrowers and multi-second tails. Later tuning reduced queueing and p99, while a 32-connection diagnostic run still exposed periodic SQL/transaction slowdown; current analysis treats Hikari exhaustion as an amplifier, not root cause.
- Current `application.yml` uses Hikari max 32/min idle 16. Historical results must be tied to their recorded configuration and not generalized as current production capacity.
- Query stress tooling is substantial, but no retained executed query results were found. Recovery and long soak conclusions also remain incomplete.
- Ledger business metric calls are not universally isolated from Micrometer failures. Shared Kafka publisher/listener metric paths are defensive, but a business-meter registration/recording exception can affect the wrapped call.
- Implemented outbox visibility is limited to publisher attempts and delivery age. The configured `zexchange.outbox.dispatch.duration` timer is not emitted, and backlog/exhaustion/claim/finalization metrics do not exist yet.
- Published assets must later be copied under `design/assets`; current `doc/**` paths are research provenance only.

### Document boundary

**Include here:** Ledger business observability, workload/data model, retained results, bottleneck reasoning, configuration history, limitations, and future validation plan.

**Link elsewhere instead of duplicating:** Shared instrumentation implementation (D-006/D-004), business correctness (D-007/D-008), cache mechanics (D-010/D-003), and Kafka reliability metrics (D-001/D-009).

### Uncertainty / TODO

- `DECIDED / DRAFTING BASIS` — D-011 may be drafted from the evidence currently retained, provided every result remains tied to its historical revision/configuration and the document makes no sustainable-capacity or production-readiness claim. Future test data will extend and revise that baseline.
- `DEFERRED / FUTURE EVIDENCE` — Re-run fixed-rate, recovery, and soak experiments on a recorded current revision/configuration before presenting a sustainable capacity number.
- `TODO: ROOT CAUSE` — Periodic low-load DB/SQL stall remains unresolved; correlate k6, MySQL/storage/network/host timers and use an appropriate histogram window.
- `DEFERRED / FUTURE EVIDENCE` — Execute and retain separate txn-ID/ref-ID, working-set, repeated-miss, and cold-miss query results.
- `DEFERRED / FUTURE EVIDENCE` — Add Kafka-ingress/outbox performance evidence if cross-service settlement throughput is later claimed.

### Verification record

**Last verified:** `2026-09-09 — time-boxed adversarial source/dashboard/workload/evidence review`
**Verified against:** Rechecked current Ledger business and shared metric source, application configuration, provisioned dashboard queries, write/query/smoke workloads, fixtures and reconciliation SQL, and all six retained 2026-08 write analyses. Corrected the metric inventory to remove nonexistent dispatcher metrics and qualified business metrics as not universally failure-isolated. Historical figures and causal conclusions match the retained notes and remain explicitly non-current/non-capacity evidence.

**Known gaps after verification:** Historical screenshots were not independently re-rendered or compared pixel-by-pixel, and no current benchmark was run. There is no retained query result, fixed-rate sustainable-capacity proof, recovery/soak result, Kafka settlement benchmark, server-side MySQL/exporter evidence, or root-cause proof for the periodic stall.

---

## D-012 — Account and asset reference-data bootstrap

**Priority:** P3
**Status:** DEFERRED
**Canonical document:** None currently planned
**Modules/services:** `ledger-service`, `ledger-proto`, Wallet caller
**Owner topic:** Supporting account provisioning and static asset/reference data needed before transaction posting.

### Why this was discovered

- Wallet synchronously provisions a Ledger liability account while creating a wallet, so the capability is necessary context for account-reference resolution.
- It is business-completeness context rather than part of the main Ledger engineering story, and the project owner has deferred documentation for it.

### Questions retained if documentation is revisited

- Which account dimensions exist, which category/normal-side combinations are accepted, and where do assets come from?
- How does Wallet derive the Ledger account reference used later in postings?
- What does duplicate creation mean, and what correctness limitations exist?

### Compact implementation evidence

| Evidence type | Path / symbol / artifact | What it supports |
|---|---|---|
| Source/contract | `CreateAccountProcessor.java`, `AccountServiceImpl.java`, `account_service.proto`, `ledger_common.proto` | gRPC provisioning, enum/category/normal-side validation, asset lookup, and reply behavior. |
| Schema | `standard/sql/ledger_service.sql` | Asset seed table, account dimensions, and unique `(service_id, reference_id)`. |
| Caller | `wallet-service/.../processor/CreateWalletProcessor.java`, `client/AccountServiceClient.java` | Synchronous Wallet bootstrap with liability/credit account and Wallet ID as reference. |
| Test | `CreateAccountProcessorTest.java` | One external-DB happy path only. |

### Important limits discovered

- Asset existence is checked and asset data is seeded through SQL; there is no asset-management API in this module.
- Duplicate `insertIgnore` is always returned as successful “account already exists” without loading the row or verifying that its defining attributes match.
- Required string identifiers are not explicitly checked for blank values, and posting does not honor `accountStatus` or `normalSide`.
- Account/asset tables and journal entries have no foreign-key relationships in the checked-in schema.

### Deferred document boundary

**Current decision:** Do not create a D-012 document or planned overview subsection for now. Mention account bootstrap only where another design needs the interaction to explain Ledger posting or Wallet provisioning.

**If revisited later:** Keep the scope to concise domain vocabulary, Wallet provisioning interaction, schema identity, and limitations; link posting account validation to D-007 and general error/DB behavior to D-004/D-005.

### Uncertainty / TODO

- `DEFERRED / NO CURRENT DOCUMENT` — Account and asset bootstrap does not require documentation in the current documentation scope.
- `TODO: IDEMPOTENCY` — Define same-request replay versus conflicting duplicate account creation and verify the existing row before returning success.
- `TODO: VALIDATION` — Define required identifiers and whether closed accounts must be rejected by posting.

### Verification record

**Last verified:** `NOT VERIFIED`
**Verified against:** Current account processor/proto/schema, Wallet caller, and one test were inspected during discovery.

---

## D-013 — Wallet transaction lifecycle and asynchronous settlement

**Priority:** P0
**Status:** VERIFIED
**Canonical document:** `design/wallet/transaction-lifecycle-and-settlement.md`
**Modules/services:** `wallet-service`, `wallet-proto`, the Ledger posting command/reply contract, and shared Kafka/outbox infrastructure
**Owner topic:** Wallet's public transaction operations, normalized action model, pre-Ledger commit, synchronous local-only completion, asynchronous Ledger handoff, and post-Ledger finalization.

### Why this deserves documentation

- Wallet is the business state machine and spend gatekeeper: it converts client lines into durable balance/reservation actions before Ledger records accounting facts.
- The API has two materially different completion models: reserve and pure release finish synchronously, while atomic and transfer-bearing apply requests return accepted `PENDING` state and finalize from a Kafka reply.
- Wallet's local transaction/outbox atomic boundary and later reply-driven transaction are the service half of the project's central eventual-consistency story.

### Questions the document must answer

- How do atomic, reserve, and apply-reservation requests map into `RESERVE`, `CONSUME`, `RELEASE`, `TRANSFER_OUT`, and `TRANSFER_IN` actions?
- Which mutations occur before Ledger, which occur only after Ledger success, and which operations bypass Ledger entirely?
- What commits atomically with the Wallet command outbox, and what post-commit work is only best effort?
- What does gRPC success mean for synchronous versus asynchronous operations, and how are transaction statuses exposed on first execution and replay?
- How are Wallet transaction ID, original client reference, idempotency key, outbox event/command ID, Ledger `referenceId`, and reply identities related?
- What happens for duplicate replies, failed Ledger replies, poison replies, missing replies, or a Wallet crash during finalization?

### Compact implementation evidence

| Evidence type | Path / symbol / artifact | What it supports |
|---|---|---|
| Contract | `grpc-proto/wallet-proto/src/main/proto/wallet/wallet_service.proto`, `wallet_transaction.proto`, `wallet_enum.proto` | Public atomic/reserve/apply operations, line operations, transaction status, and reservation response fields. |
| Source | `AtomicTransactionProcessor`, `ReserveTransactionProcessor`, `ApplyReservationTransactionProcessor` | Entry-specific validation, transaction type, idempotency gate, Ledger/no-Ledger decision, and reply construction. |
| Source | `processor/transaction/util/ObjectBuilder.java`, `TransactionInfo.java`, `TransactionValidateHelper.java` | Line-to-action decomposition, per-asset transfer balancing, Wallet-to-Ledger entry conversion, and in-memory flow model. |
| Source | `processor/transaction/step/BeforePostLedgerProcessor.java` | One pre-Ledger DB transaction for reservations, snapshots, transaction, actions, and optional claimed outbox; post-commit cache/idempotency/publish behavior. |
| Source | `processor/transaction/step/AfterPostLedgerProcessor.java` | Reply-driven reservation/snapshot settlement and guarded `PENDING -> COMPLETED` transition in one DB transaction. |
| Source | `kafka/consumer/WalletLedgerReplyMessageHandler.java`, `kafka/consumer/DefaultListener.java`, `kafka/config/WalletListenerConfig.java` | Success-only finalization, retryable processing classification, non-retryable/DLT behavior, and ack-only terminal-consumer contract. |
| Source | `utils/OutboxHelper.java`, `kafka/constant/WalletTopic.java`, `cronjob/outbox/OutboxDispatcher.java` | Command identity/routing, immediate publish attempt, and scheduled durable retry. |
| Schema | `standard/sql/wallet_service.sql` | Transaction/action/reservation/snapshot/outbox persistence and uniqueness/index boundaries. |
| Tests | `ApplyReservationTransactionProcessorUnitTest`, `WalletLedgerReplyMessageHandlerTest`; legacy `AtomicTransactionProcessorTest`, `ReserveTransactionProcessorTest`, `ApplyReservationTransactionProcessorTest` | Pure-release versus Ledger-bearing routing and reply policy are executed; older happy-path transaction flows are source evidence only under the current runner. |
| End-to-end tooling | `stress-test/scripts/wallet-grpc-smoke.js`, `wallet-grpc-write-stress.js`, wallet reconciliation SQL | Expected synchronous reserve/release behavior, async pending-to-completed settlement, duplicate scheduling, and final-state checks. |
| Research intent | `standard/design/design.txt`, `standard/design/outbox-reply-event-id-design.md` | Action vocabulary and intended eventual-completion model; source remains authoritative where details differ. |

### Important guarantees and limits discovered

- A debit expands to reserve + consume + transfer-out; a credit becomes transfer-in; earmark becomes consume + transfer-out; reserve and release remain local reservation actions.
- Reserve requests accept only `Reserve` lines and complete without Ledger. Apply requests skip Ledger only when every line is `Release`; all other apply requests and every atomic request create a Ledger command.
- The pre-Ledger DB transaction commits all Wallet business mutations, transaction/actions, and the optional outbox together. Cache refresh, Redis accepted-state refresh, immediate publish, and outbox finalization occur after commit; their normal translated/`Result` failures are best effort, while an unexpected runtime failure can still change the caller-visible response after the authoritative commit.
- Ledger receives only transfer-out/in actions and uses Wallet `txnId` as posting `referenceId`, command ID, and partition key. The command event ID is a separate `{txnId}-{process-local sequence}` transport identity that remains stable for retries of its persisted outbox row. The original client reference remains Wallet-local.
- On a successful Ledger reply, Wallet commits reservation settlement, balance finalization, and the guarded transaction-status update atomically. A previously finalized transaction returns success without applying effects again.
- A concurrent duplicate success can observe pre-final state and enter the update path; row locks and guarded state/balance checks prevent duplicate committed effects, but the loser may surface a non-retryable failure and reach DLT rather than converge as a clean no-op.
- A failed Ledger reply never calls finalization. It is currently treated as a terminal consumer failure and routed to DLT, while the Wallet transaction and pre-Ledger held state remain pending. The owner has confirmed this as temporary behavior; a future Wallet compensation design must resolve a valid Ledger rejection.
- Reservation mutations are currently correlated only by Wallet ID after the initial action/reference validation. Multiple affected reservations on the same Wallet are not a supported correctness shape: consume/release actions can be applied to the wrong reservation or make the phase fail and roll back.
- `PostServiceClient` is unused. Kafka/outbox is the active posting path; synchronous gRPC to Ledger is used only for account bootstrap.

### Document boundary

**Include here:** Operation matrix, normalized actions, pre/post-Ledger phases, transaction statuses, transaction boundaries, command/reply correlation, synchronous versus asynchronous API semantics, and current failure/recovery behavior.

**Link elsewhere instead of duplicating:** Balance arithmetic/locking (D-014), reservation field/state invariants (D-015), request deduplication (D-016), generic Kafka/outbox mechanics (D-001), Ledger posting internals (D-007–D-009), and full cross-service synthesis.

### Uncertainty / TODO

- `ACCEPTED REQUIREMENT / FUTURE DESIGN` — A valid failed Ledger reply must trigger a new Wallet compensation design rather than leave the transaction and held/`pendingSettle` funds unchanged. The exact terminal state, balance/reservation reversal, idempotency, and ordering rules remain to be designed and implemented.
- `DECIDED / FUTURE IMPLEMENTATION` — Add Wallet-owned recovery that finds stuck transactions and reposts the same logical Ledger command. Exact state/age selection, claiming, retry budget, stop conditions, and interaction with failed replies remain unspecified.
- `DECIDED / FUTURE OPTIMIZATION` — Make repeated and concurrent successful replies explicitly idempotent: after another consumer completes the transaction, later consumers must return success without modifying state. Routing a losing concurrent attempt to DLT is temporary behavior, not the target correctness or recovery design.
- `DEFERRED / FUTURE OPTIMIZATION` — Validate that envelope `commandId`, reply `referenceId`, and the expected Wallet transaction identity agree; current handler trusts the payload reference and only checks that reply IDs are nonblank.
- `DEFERRED / FUTURE API DESIGN` — Clarify the supported operation combinations for `applyReserveTransaction`. Current code accepts more than earmark/release, including debit/credit combinations, while the API name and comments imply a narrower reservation-application role.
- `DECIDED / FUTURE IMPLEMENTATION` — Idempotent request replays should return the transaction's latest state rather than reproduce its original response. Current `replySuccess(String txnId, ...)` overloads omit status, leaving protobuf `Unknown` even when the stored transaction is `PENDING` or `COMPLETED`.
- `DEFERRED / FUTURE CLEANUP` — Classify or remove inactive `TwoStepTransactionRequestPb/ReplyPb`, `TransactionTypePb`, and `ActionTypePb` before documenting the supported API surface as intentional.
- `IMPLEMENTATION GAP` — Filter phase-1 and phase-2 reservation mutations by `reservationId` and add same-Wallet, multi-reservation tests.

### Verification record

**Last verified:** `2026-09-09 — time-boxed adversarial source/schema/config/test review`
**Verified against:** Current Wallet processors, transaction callbacks, entities/repositories, proto, schema, Kafka/outbox call sites, focused tests, smoke/stress tooling, and verified dependency docs.

**Known gaps after verification:** The default Wallet Maven suite passed 20 JUnit 5 tests, but legacy transaction tests were not discovered. No live Wallet/MySQL/Redis/Kafka end-to-end, concurrent reply, crash-window, or failure-injection run was performed. Recovery, compensation, reply correlation/convergence, and same-Wallet multi-reservation correctness remain explicit implementation gaps.

---

## D-014 — Wallet balance model and concurrency correctness

**Priority:** P0
**Status:** VERIFIED
**Canonical document:** `design/wallet/balance-and-concurrency-correctness.md`
**Modules/services:** `wallet-service`, with common DB transaction/optimistic-lock and cache support
**Owner topic:** Available/reserved bucket semantics, ordered row locking, guarded snapshot updates, non-negative/conservation properties, transaction atomicity, and post-commit cache coherence.

### Why this deserves documentation

- The two-bucket snapshot is Wallet's primary no-overspend mechanism: outgoing funds move from available to reserved before remote accounting and leave reserved only after confirmation.
- Multi-wallet operations deliberately lock and update shared financial rows; the exact active enforcement mechanism matters more than the presence of unused SQL helper methods.
- The implementation combines pessimistic row locks, application validation, old-state predicates, MyBatis versioning, and atomic transaction rollback, with important limits that must not be overstated as schema guarantees.

### Questions the document must answer

- What do `available` and `reserved` mean during reserve, release, debit, credit, and post-Ledger completion?
- Which balance and per-asset conservation rules are checked before persistence, and which are only inferred/tested afterward?
- What rows are locked, in what table and primary-key order, and where can lock-order drift still occur?
- How do `SELECT ... FOR UPDATE`, old-value predicates, and `@Version` interact, and which layer prevents a negative balance?
- What state commits atomically before and after Ledger, and how does rollback protect multi-row transfers?
- How are cache updates kept outside DB transactions, and what stale windows remain if best-effort refresh fails?

### Compact implementation evidence

| Evidence type | Path / symbol / artifact | What it supports |
|---|---|---|
| Source | `po/wallet/BalanceSnapshot.java`, `po/enums/WalletBucket.java`, `WalletStatus.java` | Persisted two-bucket state, version, and OPEN-state eligibility. |
| Source | `UpdateBalanceSnapshotProcessor.java` | Primary-key sorting, `FOR UPDATE` reload, application invariant checks, and guarded update handling. |
| Source | `BalanceSnapshotRepository.selectInIdForUpdate`, `updateBalanceWithInOptimisticLock` | Ordered row locks, old identity/status/amount predicates, and MyBatis version increment. |
| Source | `BeforePostLedgerProcessor.doUpdateSnapshotBeforePosting`, `AfterPostLedgerProcessor.doUpdateSnapshotAfterPosting` | Reserve/release before Ledger and transfer-out/in after Ledger. |
| Source | `TransactionValidateHelper.validateAssetInfo`, `validateSnapshotForAction` | Per-asset transfer equality, wallet/asset match, OPEN state, and positive amount checks. |
| Source | `BalanceSnapshotStore.postDbUpdate` and D-003 cache strategies | Post-commit cache-aside tombstone versus hot post-refresh behavior. |
| Schema | `standard/sql/wallet_service.sql` | Bigint balances/version and absence of balance `CHECK` constraints or foreign keys. |
| Stress verification | `stress-test/fixtures/wallet-write-verification.sql` | Non-negative balances, total fixture-fund conservation, exact action-derived final snapshots, and final async state. |
| Historical evidence | `doc/performance/wallet-stress-test-atomic03.md` | Multi-phase bottleneck diagnosis involving reservation lookup, shared DB pool, foreground gRPC, and Kafka completion. |
| Research intent | `standard/design/db.txt`, `engineering-notes/feature/wallet-snapshot-optimize/balance-snapshot-update-cache-coherency-refactor.md` | Intended table/PK order and cache-after-commit rationale; validate against current source. |

### Important guarantees and limits discovered

- Pre-Ledger reserve/debit subtracts available and adds reserved under locked current rows; release subtracts reserved and restores available. Post-Ledger transfer-out subtracts reserved and transfer-in adds available.
- `UpdateBalanceSnapshotProcessor` reloads target rows with `FOR UPDATE` ordered by primary key, validates non-null/non-negative final buckets, and updates only changed amounts with identity/status/old-value predicates plus versioning.
- Active correctness does not use the repository's arithmetic SQL guard helpers. Those four methods are currently uncalled; documentation must not cite them as the live no-negative mechanism.
- Snapshot/reservation/transaction/action/outbox writes are transactionally atomic within each phase. Application validation is not an independent schema guarantee; the schema has no balance check constraints.
- Transfer actions are balanced per asset in application memory before the pre-Ledger transaction. The summation uses ordinary `long` arithmetic without explicit overflow detection.
- Cache maintenance occurs only after successful DB commit and is soft-fail. Normal snapshots receive a versioned tombstone; promoted system snapshots are refreshed. A crash or Redis failure after commit can leave an older cache entry until expiry or a later successful refresh.
- `balance_snapshots.last_txn_id` exists in SQL but is absent from `BalanceSnapshot` and is never updated, so the snapshot does not currently expose a durable last-applied transaction fence.

### Document boundary

**Include here:** Bucket model, action effects on balances, financial invariants, row/table lock order, guarded updates/versioning, DB transaction boundaries, cache-after-commit coupling, and exact enforcement classification.

**Link elsewhere instead of duplicating:** Reservation counters/status (D-015), overall request lifecycle (D-013), generic DB/cache mechanisms (D-003/D-004), and measured load results (D-018).

### Uncertainty / TODO

- `OPEN / OWNER REVIEW REQUESTED` — Reconcile the project hint claiming DB arithmetic guards with the active path. Evidence: `UpdateBalanceSnapshotProcessor.updateSnapshots` sorts IDs and locks rows, calls the application-side `validateBalanceInvariant`, then invokes `BalanceSnapshotRepository.updateBalanceWithInOptimisticLock`; that repository update compares old balances rather than performing guarded subtraction. `reserveFromWalletId`, `releaseFromWalletId`, `transferOutFromWalletId`, and `transferInToWalletId` contain the arithmetic guards, but no active call sites were found. Until reviewed, document executable behavior and classify those helpers as unused.
- `TODO: CONCURRENCY EVIDENCE` — Add live multi-wallet contention/deadlock/rollback tests proving deterministic snapshot lock order, no lost updates, and fund conservation under concurrent reserve/finalize flows.
- `TODO: OVERFLOW` — Define and test overflow-safe arithmetic for per-asset totals and bucket additions; current positive-amount checks do not explicitly reject `long` overflow.
- `DEFERRED / FUTURE DESIGN` — Decide later whether `last_txn_id` is obsolete schema residue or should become an applied-transaction/cache-recovery fence.
- `DECIDED / CURRENT TRADE-OFF` — Normal reads may tolerate occasional short-lived stale cache data, so best-effort post-commit cache maintenance is acceptable for the current demonstration scope. A real-time read path is intended to bypass cache and query MySQL directly; no such distinct public path was found in the current Wallet API, so it must not yet be documented as implemented.

### Verification record

**Last verified:** `2026-09-09 — time-boxed adversarial source/schema/config/test review`
**Verified against:** Every current balance mutation call site, update processor/repository query construction, phase callbacks, schema, cache strategy behavior, reconciliation SQL, configuration, and the 20-test Wallet suite.

**Known gaps after verification:** No live MySQL concurrency/deadlock/rollback, generated-SQL capture, overflow-boundary, Redis stale-fill, or post-commit crash test was run; legacy transaction tests remain undiscovered by the default test engine.

---

## D-015 — Wallet reservation lifecycle and no-double-spend state

**Priority:** P0
**Status:** VERIFIED
**Canonical document:** `design/wallet/reservation-lifecycle.md`
**Modules/services:** `wallet-service`, `wallet-proto`
**Owner topic:** Reservation creation, consume/earmark, release, pending settlement, final outcomes, initiator-scoped identity, and preservation of `total = remaining + consumed + pendingSettle + released`.

### Why this deserves documentation

- Reservations bridge synchronous holds and asynchronous settlement and are the explicit proof that later consumption is backed by prior reserved funds.
- Five amount fields plus status/outcome encode a real state machine whose intermediate `pendingSettle` state explains failure and recovery behavior.
- Reservation and snapshot rows are mutated together, so identity checks, lock ordering, and atomicity are central to preventing double spend.

### Questions the document must answer

- How are reservation IDs/references created and scoped, and which identity is returned to clients?
- What are the legal transitions for reserve, consume/earmark, release, and successful Ledger completion?
- How is the amount partition invariant maintained and when do status/outcome become `FINISHED`/`CONSUMED`/`RELEASED`/`PARTIAL`?
- How do Wallet ensure a reservation belongs to the initiator, wallet, and asset in the action?
- What locking/update guards prevent two applies from consuming or releasing the same remaining amount?
- What happens to remaining, pending-settle, and reserved balance when Ledger fails or never replies?

### Compact implementation evidence

| Evidence type | Path / symbol / artifact | What it supports |
|---|---|---|
| Source | `po/transaction/WalletReservation.java`, `ReservationStatus.java`, `ReservationOutcome.java` | Amount partition, lifecycle enums, terminal inference, and persisted model. |
| Source | `ObjectBuilder.createReservation`, `createActionsFromLine` | Atomic-debit versus explicit-reserve initial states and consume/release action expansion. |
| Source | `BeforePostLedgerProcessor.updateReservationBeforePosting` | `remaining -> pendingSettle` consume and `remaining -> released` release transitions. |
| Source | `AfterPostLedgerProcessor.updateReservationAfterPosting` | `pendingSettle -> consumed` only after Ledger success. |
| Source | `UpdateReservationProcessor`, `WalletReservationRepository` | Row locking, old amount/status predicates, initiator-scoped reference lookup, and batch insert. |
| Source | `TransactionValidateHelper.validateActionInfo`, `WalletTxnHelper.actionReservationMatched` | Wallet/asset identity matching between actions and reservations. |
| Schema | `standard/sql/wallet_service.sql` | Unique reservation ID and `(initiator, reference_id)`, amount/status columns, and absence of domain checks/FKs. |
| Tests | `WalletReservationRepositoryTest`; legacy reserve/apply processor tests; wallet smoke test | Executed initiator scoping and source-level happy paths for reserve, consume, and pure release. |
| Stress verification | `stress-test/fixtures/wallet-write-verification.sql` | Partition invariant, expected final reservation states, action shapes, and duplicate counts. |

### Important guarantees and limits discovered

- A reserve starts `ACTIVE/NOT_DONE` with all funds in `remaining`; an atomic debit starts with all funds in `pendingSettle` because reserve and consume occur in one Wallet command.
- Consume moves no snapshot bucket before Ledger; it moves reservation `remaining -> pendingSettle`, while its paired transfer-out removes the already reserved snapshot amount after Ledger success.
- Release is a synchronous local move from reservation `remaining` and snapshot `reserved` to reservation `released` and snapshot `available`. A mixed apply may therefore commit releases before its unrelated transfer legs settle.
- Successful Ledger completion moves `pendingSettle -> consumed`. When `consumed + released == total`, status becomes `FINISHED` and the outcome is inferred as consumed, released, or partial.
- Lookups are scoped by `(initiator, reference_id)`, and actions must match reservation wallet and asset. Available code does not explicitly require `ACTIVE/NOT_DONE`; amount availability usually rejects reuse after a well-formed terminal state.
- Reservation updates lock rows and compare old status plus all four mutable partition fields. However, the requested IDs and resulting query have no explicit primary-key sort despite comments claiming PK order.
- Both reservation mutation callbacks select the relevant reservations but then apply actions by Wallet ID rather than reservation reference. Multiple reservations on one Wallet can receive one another's arithmetic or make the phase fail; the exact-settlement guarantee is limited to at most one affected reservation per Wallet.
- The partition invariant is maintained by construction and transition logic and checked by stress reconciliation, but it is neither schema-enforced nor explicitly revalidated before every update.
- `CLOSED` and `CANCELLED` exist in enums but no active cancellation/closure flow was found.

### Document boundary

**Include here:** Reservation identity, fields/invariant, state diagram, operation transitions, snapshot coupling, concurrency guards, terminal outcomes, and failure-held state.

**Link elsewhere instead of duplicating:** Whole transaction/outbox sequence (D-013), generic balance locking (D-014), request idempotency (D-016), and Ledger accounting behavior (D-007/D-009).

### Uncertainty / TODO

- `ACCEPTED REQUIREMENT / FUTURE DESIGN` — A new Wallet compensation design must resolve reservations left in `pendingSettle` after a valid Ledger rejection. Exact release/reversal fields, terminal status/outcome, retry boundary, and failure idempotency remain future design work.
- `OPEN / OWNER REVIEW REQUESTED` — Verify reservation lock ordering. `UpdateReservationProcessor.updateReservations` lines 28–30 collect IDs without sorting while its comment claims PK order; `WalletReservationRepository.selectInIdForUpdate` lines 49–52 adds `FOR UPDATE` but no `ORDER BY`. This differs from the explicitly sorted snapshot path and from `standard/design/db.txt`.
- `TODO: INVARIANT ENFORCEMENT` — Add explicit transition-level invariant/status validation and focused tests, or clearly retain construction-by-code plus stress reconciliation as the accepted current level. Do not imply the schema independently enforces it.
- `DEFERRED / FUTURE OR OPS` — Retain `CLOSED/CANCELLED` as future or operational states. Whether applies must explicitly reject non-`ACTIVE` reservations remains future validation work.
- `DEFERRED / TEMPORARY DESIGN` — Current client-visible reservation references are derived from transaction reference plus wallet ID rather than generated `reservationId`; define the stable collision/identity contract in a future replacement design.
- `IMPLEMENTATION GAP` — Group `CONSUME`/`RELEASE` mutation by reservation reference, not only Wallet ID, and cover same-Wallet multi-reservation requests.

### Verification record

**Last verified:** `2026-09-09 — time-boxed adversarial source/schema/test review`
**Verified against:** Current reservation construction, action matching, both phase mutation callbacks, repository locking/update predicates, schema, protobuf replies, focused initiator-scope test, reconciliation SQL, and the 20-test Wallet suite.

**Known gaps after verification:** The same-Wallet multi-reservation correlation defect is untested and unresolved. No executed state-machine, concurrent consume/release, rollback, terminal-outcome, corrupt-partition, or live database invariant test was run.

---

## D-016 — Wallet transaction idempotency, concurrency, and replay

**Priority:** P0
**Status:** VERIFIED
**Canonical document:** `design/wallet/transaction-idempotency-and-replay.md`
**Modules/services:** `wallet-service`, with common Redis idempotency and DB transaction support
**Owner topic:** Wallet-specific request identity, Redis claim/read/complete flow, database uniqueness, duplicate races, reply replay, and interaction with asynchronous settlement.

### Why this deserves documentation

- All three transaction APIs share the same hot-path idempotency gate while committing financial state across several tables.
- Redis coordinates concurrent work, but MySQL `(initiator,idempotency_key)` uniqueness is the durable effect backstop; the current equivalence and replay-shape gaps materially qualify the guarantee.
- Accepted asynchronous Wallet transactions can be replayed before or after Ledger finalization, so idempotency must distinguish request acceptance from final business completion.

### Questions the document must answer

- Which fields form Wallet's stable request hash, and why is transaction-line order ignored?
- What do Redis claim success, `PENDING`, `ACCEPTED`, hash conflict, malformed state, and Redis failure mean?
- How does DB fallback behave before and after Redis expiry/loss, and what does the unique key prevent?
- What happens when a concurrent duplicate reaches transaction insert after balance/reservation work has begun?
- When and how is the Redis claim released or marked accepted relative to DB commit?
- Does an idempotent replay return the same transaction ID, status, reservation details, and error classification as the original response?

### Compact implementation evidence

| Evidence type | Path / symbol / artifact | What it supports |
|---|---|---|
| Source | `RequestInfo.getStableHash` | Hash inputs, order-insensitive sorted line set, and exclusion of random owner token. |
| Source | `IdempPrecheckProcessor.java` | Validation-before-claim, Redis decisions, hash-conflict mapping, malformed deletion, DB fallback/replay, and best-effort accepted refresh. |
| Source | `BeforePostLedgerProcessor.updateDbToCreateTxn`, `releaseIdempBeforeReturnError` | Multi-table transaction rollback on duplicate, token-owned pending release, and post-commit accepted marking. |
| Source | `AtomicTransactionProcessor`, `ReserveTransactionProcessor`, `ApplyReservationTransactionProcessor` | Shared gate and operation-specific replay response construction. |
| Infrastructure | D-002 and `common-util/.../redis/idemp/**` | NX claim, visible hash verification, token-owned release, unconditional current completion, and TTL behavior. |
| Schema | `standard/sql/wallet_service.sql` | Unique `(initiator,idempotency_key)` and generated Wallet transaction ID. |
| Config | `wallet-service/src/main/resources/application.yml` | Current pending/done TTL and jitter snapshots. |
| Tests | `IdempPrecheckProcessorTest`, `wallet-grpc-write-stress.js`, wallet verification SQL | Executed Redis-fallback/hash/malformed cases and tooling for concurrent/delayed exact duplicates and one-effect reconciliation. |

### Important guarantees and limits discovered

- The current stable hash includes reference ID, initiator, idempotency key, business type, transaction type, protobuf request-message name, and every line sorted by wallet/asset/operation/amount/reservation reference.
- The Redis key omits initiator while the DB unique key includes it. A same textual key from another initiator conflicts while Redis exists but is independent after Redis loss/expiry.
- Caller-controlled hash fields are joined with unescaped `|` delimiters, so the current representation does not unambiguously preserve field boundaries.
- A visible same-key/different-hash Redis value returns Wallet `REQUEST_HASH_CONFLICT`; a live identical pending value returns `REQUEST_IN_PROCESSING`; accepted state replays the stored Wallet transaction ID.
- Redis failures and malformed values degrade to DB lookup. MySQL uniqueness prevents two committed Wallet transaction rows for one `(initiator,idempotency_key)`, and the containing DB transaction rolls back preceding reservation/snapshot changes if transaction insert loses a race.
- The DB row does not persist the request hash. After Redis loss/expiry, a different request reusing the key is treated as the existing transaction rather than a conflict.
- Current accepted completion is not token-owned; a slow worker can overwrite a newer pending owner after TTL expiry. DB state remains authoritative but Redis coordination can mislead later callers.
- Replays return the original transaction ID. Reserve replay reloads reservations created by that transaction, but all three string-based replay reply builders omit transaction status.
- The post-commit path uses the exact configured done TTL while DB-replay refresh applies jitter; this is an implementation inconsistency, not a documented business rule.

### Document boundary

**Include here:** Wallet request hash/identity, service decision tree, DB uniqueness/rollback race, Redis post-commit behavior, operation-specific replay shape, and exact current guarantees/gaps.

**Link elsewhere instead of duplicating:** Generic Redis value/Lua mechanics and target protocol (D-002), business state changes (D-013–D-015), generic DB transactions (D-004), and Kafka delivery duplicates (D-001).

### Uncertainty / TODO

- `DECIDED / NOT IMPLEMENTED` — Persist and compare a canonical request hash in MySQL on every fallback/replay/duplicate race, and make accepted completion token-owned, as already recorded in D-002.
- `DECIDED / FUTURE IMPLEMENTATION` — Replays should return the latest transaction and reservation data, including state changes from `PENDING` to `COMPLETED`, rather than reproduce a stale original response. Add focused response-shape tests when implemented.
- `OUT OF CURRENT SCOPE` — Blank/length constraints for reference IDs, idempotency keys, wallet/asset refs, and reservation refs are not planned for now because this is a non-commercial demonstration project. Document only the validation that currently exists.
- `CONFIRMED DEFECT / FUTURE FIX` — The three Wallet transaction processors use `TokenHelper.generateToken(GlobalServiceId.LEDGER.name())`; this is a copy/paste label defect and should use Wallet in a future fix. Redis keys already use `GlobalServiceId.WALLET.code`, so current ownership comparisons remain internally consistent.
- `IMPLEMENTATION GAP` — Align Redis key scope with the DB initiator scope and replace delimiter concatenation with an unambiguous canonical request representation.
- `DEFERRED TEST WORK` — Add focused concurrent duplicate, duplicate-insert winner replay, Redis expiry/ownership, post-commit mark failure, same-key/different-request DB fallback, and latest-response tests later.

### Verification record

**Last verified:** `2026-09-09 — time-boxed adversarial source/schema/config/test review`
**Verified against:** All three Wallet transaction processors, request hashing, Redis decisions, DB lookup/uniqueness and transaction rollback, replay builders, verified D-002 behavior, and three focused idempotency tests within the passing Wallet suite.

**Known gaps after verification:** No live Redis lease-turnover/cross-initiator test, simultaneous DB duplicate race, post-commit ambiguity test, or replay-shape integration test was run. DB-authoritative equivalence and owner-safe completion remain proposals.

---

## D-017 — Wallet balance query and cache read model

**Priority:** P1
**Status:** VERIFIED
**Canonical document:** `design/wallet/balance-query-and-cache.md`
**Modules/services:** `wallet-service`, common-util cache/DB
**Owner topic:** Query-by-wallet/reference behavior, service-scoped reference index, versioned mutable snapshot cache, system-wallet hot promotion, fallback/freshness semantics, and public snapshot conversion.

### Why this deserves documentation

- Wallet exposes its operational balance through two lookup identities and composes a raw reference index with a versioned mutable entity cache.
- System-owned wallets follow a materially different Redisson client-side/post-refresh path from normal cache-aside wallets.
- Cache failure is deliberately soft, but best-effort writes and schema identity gaps constrain freshness and lookup correctness claims.

### Questions the document must answer

- How do wallet-ID and `(serviceId, referenceId)` lookup paths compose the raw index and versioned entity caches?
- Which cache outcomes return immediately and which query MySQL?
- How are negative values, tombstones, stale positive refs, malformed data, and Redis failures handled?
- When and why are system-owned wallets promoted to client-side caches, and how long does promotion state live?
- What freshness follows from post-insert/post-update behavior for normal and hot snapshots?
- Does the database uniquely support the public reference lookup contract?

### Compact implementation evidence

| Evidence type | Path / symbol / artifact | What it supports |
|---|---|---|
| Contract | `balance_snapshot.proto`, `wallet_service.proto` | Query-by-ID/reference operations and public snapshot fields. |
| Source | `GetBalanceSnapshotProcessor.java`, `WalletServiceImpl.handleSnapshotRequest`, `PbConverter.java` | Input validation, store dispatch, error mapping, metrics, and response conversion. |
| Source | `BalanceSnapshotStore.java` | Two-cache composition, service-scoped ref key, fallback, stale-ref recovery, negative handling, and system promotion. |
| Source | `BalanceSnapshotCacheRegister.java`, `PromotionClassifierRegister.java` | Normal cache-aside versus hot post-refresh, versioned/raw strategies, negative cache, client-side readers, and process-local promotion sets. |
| Config | `CustomCacheProperties`, `CacheTtlConfig`, `application.yml` | Current entity/index/negative/tombstone TTL and jitter snapshots. |
| Schema/repository | `standard/sql/wallet_service.sql`, `BalanceSnapshotRepository.java` | DB identity/lookups, mutable version, and the composite unique-key limitation. |
| Tests | `GetBalanceSnapshotProcessorUnitTest`, `BalanceSnapshotCacheRegisterTest`; commented `BalanceSnapshotStoreUnitTest`; legacy `QueryBalanceTest` | Executed processor/wiring coverage and missing current store behavior coverage. |
| Stress tooling | `wallet-grpc-query-stress.js`, `wallet-query-fixture.sql`, wallet stress design/operations docs | Separate lookup workloads, hit/miss patterns, working sets, and USER/SYSTEM cache-path selection. |

### Important guarantees and limits discovered

- Wallet-ID lookup uses a versioned snapshot cache. Reference lookup first uses a raw `(serviceId.code + ":" + referenceId) -> walletId` cache, then resolves the versioned snapshot.
- Reference misses use a stable negative cache and do not renew its TTL on a negative hit. A stale positive reference whose wallet snapshot is absent falls back to authoritative DB lookup by reference.
- Normal ref/snapshot caches are cache-aside; promoted system-owned refs/snapshots use Redisson client-side reads and post-refresh writes. Promotion occurs after a successful load or wallet insertion and is process-local with no demotion.
- All selected strategies suppress translated `CacheException`s and fall back to MySQL; unexpected runtime/metric failures are outside that wrapper. Cache state is an optimization, not the financial source of truth.
- Post-commit normal updates place a versioned tombstone; hot updates refresh data. Tombstones bound a stale-fill race but do not prove strict freshness, and failed post-commit maintenance can leave stale data until TTL/later refresh.
- On insert, system promotion selects post-refresh and eagerly writes both keys. For a normal Wallet, the reference cache receives a tombstone to clear/protect a prior negative and the snapshot entity is not eagerly cached.
- The SQL unique keys include `wallet_id` in `(service_id, reference_id, wallet_id)` and `(service_id, wallet_reference_id, wallet_id)`. They do not independently enforce one wallet/snapshot per public `(serviceId, referenceId)` lookup, although repositories use `selectOne` for that identity.
- The owner accepts occasional short-lived staleness for normal reads and intends real-time reads to bypass Redis. Both current public query RPCs route through `BalanceSnapshotStore`, so a separate real-time bypass is an intended future capability rather than inspected current behavior.

### Document boundary

**Include here:** Public query semantics, cache key/lookup composition, hot promotion policy, DB fallback, negative/tombstone behavior, service-specific freshness, schema lookup identity, and query evidence.

**Link elsewhere instead of duplicating:** Generic cache codecs/Lua/client-side implementation (D-003), balance mutation/commit semantics (D-014), common Result/DB behavior (D-004/D-005), and measured performance (D-018).

### Uncertainty / TODO

- `TODO: SCHEMA/IDENTITY` — Align schema uniqueness with the public `(serviceId, referenceId)` lookup contract or explicitly support multiple wallets and replace `selectOne`; current create/query assumptions and unique keys do not match.
- `TODO: TEST GAP` — Replace the fully commented store test with active coverage for value/negative/tombstone/miss, stale positive ref, Redis failure, post-insert, post-update, and USER/SYSTEM promotion behavior.
- `TODO: ENVIRONMENT VALIDATION` — Validate Redisson client-side invalidation and required defaults against the deployed Redis environment; no runtime proof was produced in this pass.
- `DECIDED / PARTIALLY UNIMPLEMENTED` — Normal public reads may return occasionally stale cache data; a real-time read must bypass cache and query MySQL directly. The current API has no distinct real-time bypass, and “short-lived” remains unbounded by verified evidence, so document normal reads as best-effort eventual consistency and the bypass as future work.
- `DEFERRED / PRE-LAUNCH CONFIGURATION` — Treat all TTL and promotion-lifetime settings as current snapshots until representative timing/memory tests exist.

### Verification record

**Last verified:** `2026-09-09 — time-boxed adversarial source/schema/config/test review`
**Verified against:** Both public query routes, store composition, all four registered strategies, verified D-003 semantics, create/update hooks, repositories, proto/schema/config, metrics, test sources, and query workload tooling.

**Known gaps after verification:** The store test is fully commented and JUnit 4 query tests are not discovered; the passing suite verifies strategy registration only. No live Redis/MySQL state matrix, delayed-fill CAS race, multi-process promotion, duplicate-reference insert, or retained query benchmark was run.

---

## D-018 — Wallet observability and performance evidence

**Priority:** P1
**Status:** VERIFIED
**Canonical document:** `design/operations/wallet-performance.md`
**Modules/services:** `wallet-service`, `stress-test`, `monitoring`, with Ledger/Kafka/MySQL interaction only where it constrains Wallet throughput
**Owner topic:** Wallet business metrics, write/query workloads, settlement and reconciliation evidence, retained atomic-load diagnosis, and performance-evidence limitations.

### Why this deserves documentation

- The repository contains Wallet-specific business instrumentation, a service dashboard, independent write/query workload generators, settlement polling, and cross-database reconciliation.
- Retained atomic-load evidence demonstrates an engineering progression from an index/query mismatch to shared connection-pool contention between foreground RPCs and Kafka completion.
- Performance claims must distinguish gRPC acceptance throughput from asynchronous completed settlement and tie historical observations to their configuration.

### Questions the document must answer

- What do Wallet request/duration metrics measure across gRPC commands, queries, and Kafka completion?
- How do atomic, reserve, release, and earmark workloads differ in DB/Kafka/Ledger cost and completion semantics?
- How are hot/distributed wallets, exact duplicates, arrival rate, query working set, hit rate, miss pattern, and owner type controlled?
- What settlement and reconciliation checks prevent a fast-but-wrong run from passing?
- What exactly did the retained `atomic03` runs observe before/after the index change and at pool saturation?
- Which conclusions remain historical hypotheses, and what current-code/query/soak/failure evidence is missing?

### Compact implementation evidence

| Evidence type | Path / symbol / artifact | What it supports |
|---|---|---|
| Source | `metrics/WalletBusinessMetrics.java`, `WalletMetrics.java`, `WalletMetricTagValues.java`, `config/WalletMetricsConfig.java` | Bounded business operation/ingress/outcome/error metrics and shared gRPC timing. |
| Config/dashboard | `application.yml`, `monitoring/grafana/dashboards/wallet-service-overview.json`, shared service/Kafka/data-access/runtime dashboards | Current histogram/SLO snapshots and diagnostic panels for business, DB, Redis, Kafka, JVM, and Hikari. |
| Workload | `stress-test/scripts/wallet-grpc-write-stress.js`, `wallet-grpc-query-stress.js`, `wallet-grpc-smoke.js` | Four write modes, two query modes, duplicate injection, arrival profiles, and end-to-end smoke settlement. |
| Fixture/verification | `wallet-write-fixture.sql`, `wallet-write-verification.sql`, `wallet-write-ledger-verification.sql`, `wallet-query-fixture.sql` | Deterministic balances/reservations/accounts, invariant checks, exact action/snapshot reconstruction, and Ledger one-post/balance checks. |
| Operations docs | `doc/stress-test/wallet-stress-test-design.md`, `wallet-stress-test-operations.md` | Research-only workload intent, configuration surface, bounded settlement wait, and acceptance method. |
| Retained evidence | `doc/performance/wallet-stress-test-atomic03.md`, `doc/performance/assets/wallet-stress-atomic03/**` | Three historical atomic discovery runs, index fix, pool change, foreground/completion contention, and screenshots. |

### Important evidence and limits discovered

- Business metrics distinguish gRPC transaction acceptance/query work from Kafka `complete_ledger_transaction`, enabling accepted-versus-finalized throughput comparison.
- Write tooling isolates atomic, reserve, apply-release, and apply-earmark; it mixes concurrent/delayed exact duplicates and waits for quiet settlement before reconciling async operations.
- Reconciliation checks pending transactions, duplicate references, non-negative/conserved balances, reservation partition/final states, exact action/outbox shapes, reconstructed snapshots, and one balanced Ledger posting for ledger-backed commands.
- Retained `atomic03` evidence reports an initial reservation-query/index bottleneck, improved completion after an index fix, and later saturation where foreground gRPC and Kafka completion competed for a shared 32-connection Wallet pool. The final run reports roughly 300+ accepted TPS, zero completion during overload, and prompt drain recovery, but these are historical run observations rather than current capacity guarantees.
- The retained performance note does not consistently identify an exact commit/configuration for every run and does not include current query results, a stable-load soak, or a current revision rerun.
- Current configuration values, including 32/16 Hikari sizing and listener/retry timing, are pre-launch snapshots and must not be presented as optimal or production-safe.

### Document boundary

**Include here:** Wallet-specific metrics, workload/fixture/reconciliation design, retained qualified results, bottleneck reasoning, accepted-versus-completed semantics, evidence limitations, and future experiment plan.

**Link elsewhere instead of duplicating:** Shared instrumentation mechanics (D-006/D-004), transaction correctness (D-013–D-016), cache design (D-017/D-003), and generic Kafka/outbox reliability (D-001).

### Uncertainty / TODO

- `TODO: EVIDENCE QUALIFICATION` — Recover exact revisions, schemas, runtime settings, load-generator health, and time windows for every retained `atomic03` run before publishing its numbers; otherwise retain only explicitly qualified observations.
- `TODO: CURRENT REVERIFY` — Re-run fixed-rate discovery, overload recovery, and soak on a recorded current revision/configuration before claiming sustainable Wallet acceptance or settlement capacity.
- `TODO: QUERY EVIDENCE` — Execute and retain wallet-ID/reference-ID results across working-set, hit/miss, cold/repeated miss, and USER/SYSTEM promotion dimensions.
- `TODO: FAILURE/RECOVERY EVIDENCE` — Add controlled broker/Redis/MySQL failure and stuck-transaction recovery experiments after the business recovery mechanism exists.
- `TODO: RESOURCE ATTRIBUTION` — Retain MySQL/host/container and consumer-lag evidence sufficient to separate service pool contention from database or broker saturation.

### Verification record

**Last verified:** `2026-09-09 — time-boxed adversarial source/dashboard/workload/evidence review`
**Verified against:** Current Wallet metrics/config/dashboard definitions, write/query/smoke scripts, fixtures/reconciliation SQL, retained `atomic03` narrative and available screenshots, and the verified application-flow documents.

**Known gaps after verification:** Exact screenshot-query/revision/configuration provenance remains incomplete. No current fixed-rate, recovery, soak, query, controlled-failure, or resource-attribution benchmark was executed; the document makes no capacity claim.

---

## D-019 — Wallet and Ledger-account provisioning

**Priority:** P3
**Status:** DEFERRED
**Canonical document:** None currently planned
**Modules/services:** `wallet-service`, Wallet/Ledger proto, synchronous Ledger `AccountService`
**Owner topic:** Supporting creation of Wallet identity, Ledger liability account, local mapping, and initial open balance snapshot.

### Why this was discovered

- Every transactional Wallet requires a matching Ledger account reference and zeroed local snapshot, so provisioning is necessary context for later posting and query designs.
- The flow is business completeness rather than a central transaction-engineering highlight and should not become a large standalone document.

### Compact implementation evidence

| Evidence type | Path / symbol / artifact | What it supports |
|---|---|---|
| Source | `CreateWalletProcessor.java`, `AccountServiceClient.java` | `INIT` wallet creation, synchronous Ledger liability/credit account request, and local enable transaction. |
| Source | `WalletRepository`, `WalletAccountMappingRepository`, `BalanceSnapshotRepository`, related entities | Status guard, 1:1 account-ref convention, and zero-balance snapshot. |
| Contract | `create_wallet.proto`, Ledger `account_service.proto`/`ledger_common.proto` | Public creation fields and remote account dimensions. |
| Schema | `standard/sql/wallet_service.sql`, `standard/sql/ledger_service.sql` | Local/remote identity and unique-key boundaries. |
| Test | Legacy `CreateWalletProcessorTest` | One environment-coupled happy path; not executed by the current JUnit 5 default run. |

### Important limits discovered

- Wallet first inserts an `INIT` row outside the later local enable transaction, then calls Ledger synchronously, then atomically moves `INIT -> OPEN` while inserting mapping and snapshot. A failure can leave `INIT` for a later create call to resume.
- An already initiated Wallet returns success without revalidating the current request against the stored asset/owner fields.
- The local unique key `(service_id, reference_id, wallet_id)` includes generated `wallet_id`, so concurrent first creation can insert more than one Wallet for the same public `(serviceId, referenceId)` identity. Later `selectOne` calls can become ambiguous.
- Remote duplicate account creation is treated by Ledger as success without same-intent validation, as already captured by D-012.

### Deferred document boundary

**Current decision:** Do not create a D-019 detailed document. Explain the minimum provisioning/account-mapping context in the future Wallet overview, D-013, and D-017; keep remote account semantics with D-012/D-007.

### Uncertainty / TODO

- `TODO: IDENTITY CORRECTNESS` — Align Wallet/snapshot uniqueness and concurrent create behavior with the public `(serviceId, referenceId)` identity before presenting create as safely idempotent.
- `TODO: SAME-INTENT REPLAY` — Verify stored asset/owner/service fields on local and remote duplicate creation before returning success.
- `DECIDED / FUTURE INTEGRATION` — Replace the current process-local ID generator with the planned distributed generator before claiming multi-instance uniqueness.

### Verification record

**Last verified:** `NOT VERIFIED`
**Verified against:** Current create processor/client/entities/repositories, both schemas/protos, and legacy test source were inspected during discovery.

---

# 4. Module synthesis readiness

A module overview should normally be written only after its important design records are verified or their uncertainty is explicitly understood.

| Module | Required design IDs | Ready for overview? | Overview doc | Notes |
|---|---|---|---|---|
| Wallet Service | D-013, D-014, D-015, D-016, D-017, D-018; relevant D-001–D-006 dependencies | Complete | `design/wallet/wallet-service-overview.md` | Generated on 2026-09-09 from verified D-013–D-018 and relevant verified shared/Ledger dependencies. D-019 remains deferred and appears only as concise provisioning context. |
| Ledger Service | D-007, D-008, D-009, D-010, D-011; relevant D-001–D-006 dependencies | Complete | `design/ledger/ledger-service-overview.md` | Generated on 2026-09-09 from verified D-007–D-011 and their verified shared-infrastructure dependencies. D-012 remains deferred and appears only as concise bootstrap context. |
| common-util | D-001, D-002, D-003, D-004, D-005, D-006 | Complete | `design/infrastructure/common-util-overview.md` | Generated on 2026-09-09 from verified D-001–D-006. The overview preserves the quick-pass evidence limits, classifies active/partial/unused capabilities, and keeps generic helpers as supporting utilities rather than separate design boundaries. |

---

# 5. Cross-system synthesis readiness

| Cross-system document | Required design/module docs | Ready? | Target path | Notes |
|---|---|---|---|---|
| Business/system overview | Verified Wallet/Ledger/common-util overviews | Complete | `design/business/system-overview.md` | Generated on 2026-09-09 as the concise business/domain and system-architecture entry point. |
| Wallet ↔ Ledger consistency | D-001, D-002, D-007, D-008, D-009, D-013, D-014, D-015, D-016 | Complete | `design/consistency/wallet-ledger-consistency.md` | Generated on 2026-09-09 with explicit classification of duplicate, loss, partial-commit, unknown-outcome, concurrency, cache, and reconciliation behavior. P0 recovery/compensation/correlation gaps remain visible. |
| End-to-end transaction flow | D-001, D-007, D-008, D-009, D-013, D-014, D-015, D-016 | Complete | `design/business/end-to-end-transaction-flow.md` | Generated on 2026-09-09 as the operation and durability-boundary walkthrough. |

---

# 6. Root `README.md` readiness

**Ready to generate:** Yes

**Status:** Entry point complete — generated on 2026-09-09, audited on 2026-09-10, and promoted to the repository root on 2026-09-15. All links and embedded assets resolve into `design/`.

**Final documentation-audit status:** `BLOCKED` for final completion while material P0 correctness and recovery issues remain. The reader paths are complete, and the unresolved issues stay explicit rather than being presented as implemented guarantees. The README intentionally retains its lifecycle, balance/reservation, and core data-model diagrams; reader comprehension takes priority over the two-minute length target.

Before changing to Yes, check:

- [x] Wallet overview exists and is sufficiently verified
- [x] Ledger overview exists and is sufficiently verified
- [x] common-util overview exists or its relevant capabilities are otherwise documented
- [x] Wallet ↔ Ledger consistency story exists
- [x] business/system overview exists
- [x] strongest performance claims are verified or explicitly omitted
- [x] observability/performance explanations and required assets exist inside `./design`
- [x] unresolved P0 TODOs are visible and understood

---

# 7. Global terminology / canonical names

| Concept | Canonical term | Do not casually replace with | Notes |
|---|---|---|---|
| Shared operation outcome | `Result<T>` / failed `Result` | response, exception | A failed Result is normal explicit control flow; exceptions are a separate path. |
| Durable duplicate protection | DB idempotency / DB uniqueness backstop | Redis idempotency | Redis is the acceleration/concurrency tier; services currently fall back to MySQL. |
| Redis idempotency states | `PENDING`, `ACCEPTED` | processing, done (without definition) | Stored codes are `P` and `A`; service business responses may use different terms. |
| Cache read outcomes | value hit, cache miss, negative hit, tombstone hit | hit/miss only | Tombstone and negative hits have different DB-query behavior. |
| Versioned cache write | absent-or-newer CAS | lock | Implemented atomically by Lua and allows equal version. |
| Outbox delivery model | at-least-once attempts while retry remains eligible | exactly once, guaranteed eventual delivery | Duplicate delivery remains possible after broker ack/DB finalization failure; exhausted rows have no automatic recovery. |
| Kafka send success | broker acknowledgment | queued locally | `DefaultPublisher` completes success from KafkaTemplate's successful future. |
| Dead-letter handling | DLT / dead-letter topic | outbox `DEAD` | Spring annotations use DLT; topic suffix and exception class use `dlq`. This is distinct from outbox terminal state. |
| Immediate/retry publish | publish source | delivery guarantee | Both sources use the same publisher; source is an operational/metric distinction. |
| Client-side cache | Redisson server-assisted client-side cache | Caffeine cache | Current implementation uses RESP3 Redisson; Caffeine is only an unused dependency. |
| Ledger accounting role | passive double-entry journal | balance service, spend gatekeeper | Ledger validates and records balanced entries; Wallet owns balance/reservation decisions. |
| Ledger transaction identity | `referenceId` (caller idempotency/business key) and generated `ledgerTxnId` | interchangeable transaction IDs | Wallet currently sends its Wallet transaction ID as Ledger `referenceId`; Ledger generates its own journal ID. |
| Wallet command transport identity | Wallet `txnId` as `commandId`, partition key, and Ledger `referenceId`; separate command `eventId` | command ID and event ID are interchangeable | The event ID is generated as `{txnId}-{process-local sequence}` and remains stable for retries of its persisted outbox row. |
| Accounting balance | per-asset debit total equals credit total | account balance correctness | Current code validates request totals in application memory; it does not maintain account balances or DB-enforce the equality. |
| Account reference | external `accountRef` on write; internal `accountId` in storage | one globally unique account key | Current query conversion incorrectly returns internal `accountId` through the protobuf field named `accountRef`. |
| Database domain enforcement | application validation plus PK/UK and conditional/CAS-like SQL updates | schema independently enforces every domain invariant | Current project policy excludes additional domain `CHECK`/FK enforcement and business/posting stored procedures; transaction atomicity remains a separate guarantee. |
| Generated-ID scalability | future distributed ID generator | current process-local generator is horizontally unique | Ledger and Wallet currently use time-plus-process-local sequences and must not claim multi-instance uniqueness until the planned generator is integrated. |
| Wallet command outcome | accepted (`PENDING`) versus completed | gRPC success always means settled | Atomic and transfer-bearing apply requests return after durable Wallet state/outbox acceptance; reserve and pure release complete synchronously. |
| Wallet balance buckets | `available` and `reserved` | one undifferentiated balance | Outgoing value is reserved before Ledger and leaves the reserved bucket only after successful remote posting; releases restore available locally. |
| Reservation amount partition | `total = remaining + consumed + pendingSettle + released` | remaining equals reserved balance in every phase | `pendingSettle` bridges Wallet acceptance and Ledger-confirmed consumption; schema does not independently enforce the equation. |
| Wallet action model | reserve, consume, release, transfer-out, transfer-in | raw protobuf operation as persisted mutation | Client lines are decomposed into durable local actions; only transfer-out/in become Ledger entries. |
| Wallet concurrency mechanism | ordered row lock + application invariant check + guarded/versioned update | active arithmetic SQL guard | Arithmetic conditional-update helpers exist but are not called by the current transaction path. |

---

# 8. Global unresolved questions

| ID | Question | Affected designs/docs | Severity | Status |
|---|---|---|---|---|
| Q-001 | What is the terminal state, operational signal, and recovery procedure for outbox rows that reach `maxRetries`? | D-001, D-006 | P0 | OPEN |
| Q-002 | Ledger posting and reply-outbox persistence intentionally use separate transaction boundaries. The accepted recovery mechanism is a future Wallet scheduled job that finds stuck transactions and reposts the same logical request; Ledger business idempotency protects the accounting effect. Current source retries do not close this gap: stale Redis `PENDING` is the underlying condition and can outlive the Kafka retry window, preventing retries from determining the actual DB result. If posting committed, a retry-exhausted failure reply can contradict it. DLT/retry-exhaustion signals are operational only, not a correctness dependency. Exact job mechanics and the current reply-payload conflict remain implementation work. | D-001, D-002, D-009, D-013; future Wallet/consistency docs | P0 | DECIDED / FUTURE IMPLEMENTATION |
| Q-003 | `markIdempDone` must become an atomic pending-token ownership transition; lost ownership must not overwrite Redis or fail an already committed DB result. | D-002 | P0 | DECIDED / NOT IMPLEMENTED |
| Q-004 | Same-key/different-request conflict must survive Redis loss/expiry through a canonical DB request hash and winner comparison on every fallback/replay/race. | D-002; future Wallet/Ledger idempotency docs | P0 | DECIDED / NOT IMPLEMENTED |
| Q-005 | The current colon-delimited value constraint is intentional; whether the target protocol should retain it instead of the current JSON proposal remains open. | D-002 | P1 | CURRENT DECISION / TARGET ENCODING OPEN |
| Q-006 | Equal-version cache overwrite is intentional because equal DB versions represent the same revision and equality enables version-0 data/tombstone replacement of a version-0 negative entry. | D-003 | P1 | DECIDED |
| Q-007 | Demonstrate Redisson client-side caching and `app.redisson` defaults in the deployed environment; no explicit `app.redisson.*` values were found despite a required-looking `clientName` property. | D-003 | P1 | PLANNED VERIFICATION |
| Q-008 | Expected business outcomes use Result or a use-case result; typed exceptions escape rollback or adapter-control boundaries until an explicit layer converts them. Cache/idempotency soft dependencies deliberately downgrade selected failures, DB faults normally escape, and service boundaries normalize public errors. The conflicting historical specification has been retired. | D-005 and all infrastructure docs | P1 | VERIFIED |
| Q-009 | Select temporary infrastructure failures into service-owned public `UNAVAILABLE` only when fallback is exhausted, the outcome is known, and replay is safe. An unknown transaction-completion result is a failed operation whose idempotent retry or authoritative status confirmation belongs upstream; unknown-result and internal failures remain `INTERNAL` at public boundaries. | D-004, D-005; service boundary docs | P1 | DECIDED / NOT IMPLEMENTED |
| Q-010 | Infrastructure exporters and Kafka consumer-lag collection are explicit future work. Outbox backlog/exhaustion/finalization metrics are required after the `DEAD`-state policy is resolved, but are low priority because they improve observation rather than business correctness or performance behavior. | D-001, D-006 | P2 | DECIDED / FUTURE WORK |
| Q-011 | Should the unused blocking Kafka error-handler factory, unused `CacheExceptionToResultAop`, and unused Caffeine dependency be retired or retained as documented extension points? | D-001, D-003, common-util overview | P2 | OPEN |
| Q-012 | Should the root POM remove/restore the missing `client-test` module so normal reactor-scoped build/test commands work? | Documentation verification workflow | P2 | OPEN |
| Q-013 | Should `EventEnvelopePb.occurred_at` be regenerated as publish-attempt time, as it is today, or remain stable as the original event creation/occurrence time across retries? | D-001; future event-contract docs | P1 | OPEN |
| Q-014 | Raw serialized payload equality is unsound for comparing reconstructed logical replies. Compare the smaller UUID-per-reply optimization with the more promising but larger durable-inbox refactor; validate transaction boundaries, concurrency, terminal failure, DLT, correlation, operational state, and traffic before selecting either. | D-001; `design/proposals/ledger-reply-event-identity.md`; `design/proposals/inbox-outbox-processing.md`; future Wallet/Ledger transaction docs | P0 | ALTERNATIVES DOCUMENTED / SELECTION OPEN |
| Q-015 | Must Ledger reject negative amounts and detect `long` overflow while summing entries? Current code rejects only zero and uses unchecked addition. | D-007 | P1 | DEFERRED / FUTURE DECISION |
| Q-016 | What exact account identity and eligibility must posting validate? The schema scopes references by service, but posting queries reference alone and ignores asset/status/normal-side/owner dimensions. | D-007, D-012 | P1 | ACCEPTED REQUIREMENT / DEFERRED |
| Q-017 | Application code owns domain and cross-row validation; the project will not currently add database `CHECK`/FK enforcement or posting/business stored procedures. PK/UK checks and conditional/CAS-like updates remain DB domain safeguards, while transactions separately provide atomicity. | Global contract; D-004, D-007, future Wallet docs | P1 | DECIDED / CURRENT POLICY |
| Q-018 | When the idempotency format is refactored, decide whether entry order is excluded from request identity and whether `description` remains semantically part of identity despite not being persisted. Also replace the current unescaped `|` field concatenation with an unambiguous canonical representation and consciously select digest/equality strength. Current hashing behavior is not yet the confirmed target contract. | D-008 | P1 | PARTLY REQUIRED / SEMANTICS DEFERRED |
| Q-019 | Direct gRPC `postTransaction` is an expired integration API retained only for tests. It must not be documented as a supported production ingress; Kafka is the active Wallet-to-Ledger posting path. | D-009; future system/API overview | P1 | DECIDED / TEST ONLY |
| Q-020 | Should query replies preserve external account references or explicitly expose internal account IDs? Current `LedgerEntryPb.accountRef` returns `LedgerEntry.accountId`; the contract decision is deferred to a later version. | D-010, D-007 | P0 | DEFERRED / FUTURE CONTRACT DECISION |
| Q-021 | Posted entry sets are intended to be immutable, with a transaction-local sequence/uniqueness contract planned as a future change; it is not mandatory for the current version. | D-007 | P1 | ACCEPTED FUTURE REQUIREMENT |
| Q-022 | What is the root cause of the retained periodic low-load DB/SQL stalls, and what sustainable capacity follows from a current fixed-rate/recovery/soak run? | D-011 | P1 | OPEN / REVERIFY |
| Q-023 | Ledger tombstones are bounded stale-fill barriers, not complete freshness guarantees. Their TTL should cover the relevant request/in-flight cache-fill window and bound obsolete marker lifetime in Redis, but the current 120-second pre-launch value is not a confirmed contract and will be validated later. | D-003, D-010 | P1 | DESIGN PRINCIPLE DECIDED / VALUE DEFERRED |
| Q-024 | Ledger and Wallet will later integrate the project owner's distributed ID generator; until then, their time-plus-process-local-sequence generators must not be described as horizontally multi-instance unique. | D-001, D-007; future Wallet design docs | P1 | DECIDED / FUTURE INTEGRATION |
| Q-025 | All checked-in configuration values are provisional while the project remains pre-launch. Documentation may report them as current snapshots but must not present TTLs, batch/page sizes, retries, schedules, timeouts, concurrency, pools, or similar values as validated or final contracts without representative evidence. | Global contract; all configuration-sensitive designs | P1 | DECIDED / PRE-LAUNCH POLICY |
| Q-026 | A valid Ledger rejection requires a new Wallet compensation design; DLT with indefinitely pending state is temporary and is not the target business outcome. Exact transaction/reservation terminal states, reversal operations, idempotency, and interaction with unknown-outcome reposting remain to be designed. | D-001, D-009, D-013, D-015; future Wallet ↔ Ledger consistency doc | P0 | REQUIREMENT ACCEPTED / FUTURE DESIGN |
| Q-027 | Reservation rows should be locked in deterministic primary-key order, but `UpdateReservationProcessor` does not sort IDs and `WalletReservationRepository.selectInIdForUpdate` adds `FOR UPDATE` without `ORDER BY`. Overlapping requests therefore rely on database deadlock detection. Confirm and implement the intended order, then verify it under contention. | D-004, D-014, D-015 | P0 | IMPLEMENTATION GAP / OWNER REVIEW |
| Q-028 | The root project hints say non-negative Wallet balances are DB-enforced by atomic SQL guards, while `UpdateBalanceSnapshotProcessor` actively uses ordered row locks, application `validateBalanceInvariant`, and old-value/version-guarded updates. The arithmetic helpers in `BalanceSnapshotRepository` have no active call sites. The owner will review which description is intended. | Global contract; D-004, D-014 | P0 | DOCUMENTATION/IMPLEMENTATION MISMATCH / OWNER REVIEW |
| Q-029 | Should Wallet and balance-snapshot uniqueness enforce the public `(serviceId, referenceId)` identity? Current composite unique keys include generated IDs, allowing concurrent duplicate creation and later ambiguous `selectOne` results. | D-017, D-019 | P1 | OPEN / IMPLEMENTATION GAP |
| Q-030 | `applyReserveTransaction` operation narrowing and inactive wire types are deferred future API cleanup. Idempotent replays are intended to return latest transaction/reservation data, but current txn-ID-only builders can return protobuf status `Unknown`. | D-013, D-015, D-016; future Wallet overview | P1 | PARTIALLY DECIDED / FUTURE WORK |
| Q-031 | `CLOSED`/`CANCELLED` are retained for future or operational use. Whether applies must explicitly reject non-`ACTIVE` reservations and whether the partition invariant needs a stronger persistence safeguard remain open; current schema has no `CHECK` and active mutation has no explicit equation assertion. | D-014, D-015; global DB policy | P1 | PARTIALLY DECIDED |
| Q-032 | Should the Wallet test build enable the JUnit Vintage engine or migrate legacy JUnit 4 tests? The default Maven run executes 20 JUnit 5 tests but silently omits the legacy transaction/create integration classes, so compilation alone overstates exercised coverage. | D-013–D-019; documentation verification workflow | P1 | OPEN / TEST-HARNESS GAP |
| Q-033 | Which retained Wallet performance observations remain reproducible on the current revision? The `atomic03` note is useful historical evidence, but exact revision/configuration linkage is incomplete and no current fixed-rate, recovery, soak, or query run was executed. | D-018 | P1 | OPEN / REVERIFY |
| Q-034 | Normal Wallet snapshot reads may tolerate occasional short-lived stale cache data; real-time reads are intended to bypass Redis and query MySQL. The current public API has no distinct bypass path, and the staleness duration has no verified bound. | D-003, D-014, D-017 | P1 | CONTRACT DECIDED / BYPASS AND EVIDENCE PENDING |
| Q-036 | Wallet validates each reservation-backed action against its referenced reservation, but both mutation callbacks then apply all `CONSUME`/`RELEASE` actions for a Wallet to every affected reservation on that Wallet. Filter by reservation reference and test same-Wallet multi-reservation phase-1/finalization; until fixed, that request shape is not a correctness guarantee. | D-013, D-015 | P0 | IMPLEMENTATION GAP / NEEDS FIX AND TEST |

---

# 9. Prioritized detailed-design queue

Dependency-aware order for the next common-util documentation tasks:

1. **D-001–D-006.** `VERIFIED` on 2026-09-09. D-002–D-006 completed a time-boxed quick pass; retain their recorded live-integration, concurrency, cache/Lua, boundary-contract, gRPC lifecycle, and failure-isolation gaps for later focused verification when those claims change or stronger evidence is required.
2. **common-util overview.** Completed at `design/infrastructure/common-util-overview.md` from the six verified detailed designs; future edits should preserve their explicit evidence limits and enhancement boundaries.

Ledger-service detailed-design queue from this discovery pass:

1. **D-007 — Ledger posting and accounting correctness.** `VERIFIED`; bounded to the active application path and current schema, with adversarial/live DB cases retained as evidence gaps.
2. **D-008 — Ledger posting idempotency and replay.** `VERIFIED`; Redis ownership and durable-equivalence limits are explicit; live Redis/MySQL races remain deeper-test work.
3. **D-009 — Ledger asynchronous posting and reply.** `VERIFIED`; reply persistence is accurately described as a later durability phase rather than an explicit multi-statement transaction; live fault cases remain open.
4. **D-010 — Ledger transaction query and cache.** `VERIFIED`; post-insert entity-cache behavior and public transaction-ID exposure are corrected; live cache/pagination evidence remains open.
5. **D-011 — Ledger performance evidence.** `VERIFIED`; historical numbers were audited against retained notes, nonexistent dispatcher metrics were removed, and no current capacity claim is made.
6. **Ledger Service overview.** Completed at `design/ledger/ledger-service-overview.md` from verified D-007–D-011 and relevant verified D-001–D-006 dependencies. D-012 remains deferred and appears only as concise context.

Wallet-service detailed-design queue from this discovery pass:

1. **D-013 — Wallet transaction lifecycle and asynchronous settlement.** `VERIFIED`; current phase boundaries/outbox/reply semantics are bounded, including recovery/compensation and same-Wallet multi-reservation gaps.
2. **D-014 — Wallet balance model and concurrency correctness.** `VERIFIED`; active lock-plus-guard path is established, with live contention, overflow, and cache-crash evidence still open.
3. **D-015 — Wallet reservation lifecycle and no-double-spend state.** `VERIFIED`; transition arithmetic and exact enforcement boundaries are established, including missing lock order and reservation/action correlation.
4. **D-016 — Wallet transaction idempotency and replay.** `VERIFIED`; Redis/DB scoping, durable one-effect boundary, equivalence/ownership gaps, and replay shape are established.
5. **D-017 — Wallet balance query and cache.** `VERIFIED`; cache composition/fallback/promotion/insert-update behavior and public-reference uniqueness gap are established.
6. **D-018 — Wallet performance evidence.** `VERIFIED` as a bounded evidence record; historical observations are qualified and no sustainable capacity is claimed.
7. **Wallet Service overview.** Completed at `design/wallet/wallet-service-overview.md` from verified D-013–D-018 and relevant verified shared/Ledger dependencies. D-019 remains concise provisioning context rather than a standalone detailed document.

---

# 10. Documentation-contract adjustment candidates

- **Adopted 2026-09-07:** treat all checked-in configuration values as provisional pre-launch snapshots. Separate each setting's design purpose from validation of its numeric value; do not claim that TTLs, batch/page sizes, retries, schedules, timeouts, concurrency, or pool sizes are final, optimal, or production-safe without representative evidence.
- **Adopted 2026-09-07:** position z-exchange as a technical demonstration of techniques used in industry-level systems, not as a commercial production system. Focus documentation on implemented techniques and design reasoning, while still exposing material correctness gaps and avoiding unsupported production-maturity claims.
- **Adopted 2026-09-07:** use application-side validation for domain/cross-row rules rather than adding database `CHECK` constraints, foreign keys, or business/posting stored procedures in the current project. Retain PK/UK checks and conditional/CAS-like SQL updates as domain safeguards, with transaction atomicity documented separately; present the throughput/deadlock argument as design rationale rather than measured proof.
- **Adopted 2026-09-05:** publish all generated reader-facing documentation/assets under `./design`; permit links only within that tree; treat documentation elsewhere in the repository as research input whose validated content must be restated internally.
- Split **capability ownership** from **end-to-end guarantee ownership** more explicitly. Common-util provides outbox/idempotency/cache mechanisms, but service call sites own atomic business/outbox commits, DB fallback semantics, and domain state idempotency.
- Refine the common-util capability table from `database MySQL` to `database Spring transactions + MyBatis/MySQL integration`; the library contains MySQL-aware classification and a runtime driver but does not own a standalone database.
- Refine `monitoring Prometheus + Grafana` to distinguish common-util's Micrometer instrumentation, service Actuator export, Prometheus scraping, Grafana dashboards, and optional server/host exporters. Current source supports the first four but not the listed infrastructure exporters.
- Require every outbox design claim to verify the originating service call site's DB transaction, not merely the existence of `OutboxRepository`.
- Require idempotency documentation to separate Redis claim coordination from authoritative DB equivalence/deduplication; `Redis + DB layered idempotency` is not one common-util guarantee.
- Treat `standard/**` as design intent/history under the same evidence precedence as other repository docs. The inconsistent `standard/design/error-handling.txt` was retired after D-005 replaced it.
- Add an explicit distinction between **business/accounting invariants enforced by the service** and **invariants independently enforced by the database**. The current broad phrase “double-entry” could otherwise overstate Ledger's schema guarantee.
- Clarify that the “Ledger correctness” requirement must cover semantic account eligibility (asset/status/scope), identifier/amount validation, and journal immutability—not only equal debit/credit arithmetic.
- Require performance documents to tie every number to a revision/configuration/run and to distinguish direct gRPC processor capacity from Kafka/outbox end-to-end settlement capacity.
- Add API/wire-contract consistency to verification: a field name such as `accountRef` must be checked against the value actually stored and returned, not accepted from proto comments alone.
- Require invariant claims to name the enforcement mechanism actually exercised by the active path: schema constraint, arithmetic conditional SQL, lock plus application validation, version/CAS guard, or reconciliation-only check. The current root hint that Wallet non-negativity relies on atomic SQL guards needs qualification or implementation alignment.
- Require asynchronous API documentation to separate durable acceptance from business completion and to cover success reply, explicit failure reply, missing reply, duplicate reply, and recovery behavior independently.
- Require verification baselines to report test-engine discovery as well as compilation and aggregate counts, so JUnit 4 classes omitted by a JUnit 5-only runner are not mistaken for executed coverage.
- Require identity/uniqueness verification to compare public lookup keys and repository `selectOne` assumptions with the schema's actual unique keys, especially for Wallet provisioning and balance queries.

---

# 11. Discovery/test baseline

- Wallet Step 03 verification on 2026-09-09: `mvn -f wallet-service/pom.xml test` passed **20 JUnit 5 tests**, 0 failures/errors/skips. Legacy JUnit 4 transaction/query/create tests compiled but were not discovered. No live MySQL/Redis/Kafka, concurrency/failure-injection, current stress/soak, or query benchmark was run.
- Ledger Step 03 verification on 2026-09-09: `mvn -f ledger-service/pom.xml "-Dtest=LedgerTxnStoreUnitTest,LedgerCommandMessageHandlerTest,LedgerFailureReplyFactoryTest,LedgerListenerRetryPropertiesTest" test` passed **13 JUnit 5 tests**, 0 failures/errors/skips. No live Redis/MySQL/Kafka or stress/query benchmark was run.
- Quick D-002–D-006 verification on 2026-09-09: `mvn -f common-util/pom.xml test` passed **75 tests**; a focused Wallet run covering idempotency fallback, cache registration, business metrics, gRPC metric registration, and DB exception/AOP wiring passed **11 tests**; `LedgerTxnStoreUnitTest` passed **3 tests**. No live Redis/MySQL/Kafka integration was run.
- `mvn -f common-util/pom.xml test` on 2026-09-09 during D-001 verification: **BUILD SUCCESS**, 86 tests run, 0 failures/errors/skips.
- `mvn -f ledger-service/pom.xml "-Dtest=LedgerCommandMessageHandlerTest,LedgerFailureReplyFactoryTest,LedgerListenerRetryPropertiesTest,PostLedgerProcessorTest" test` on 2026-09-09: **BUILD SUCCESS**, 10 JUnit 5 tests run, 0 failures/errors/skips. The legacy JUnit 4 `PostLedgerProcessorTest` was named but not discovered by the current Surefire/JUnit Platform setup.
- `mvn -f wallet-service/pom.xml "-Dtest=WalletLedgerReplyMessageHandlerTest,WalletListenerConfigTest" test` on 2026-09-09: **BUILD SUCCESS**, 8 tests run, 0 failures/errors/skips.
- The attempted root reactor command for those service tests failed before execution because `pom.xml` still references the missing `client-test` module; standalone module runs supplied the passing evidence above.
- `mvn -f common-util/pom.xml test` on 2026-09-09: **BUILD SUCCESS**, 75 tests run, 0 failures/errors/skips; the suite contains selected D-005/D-006 indirect, DB, Kafka publisher/listener, DLT, and metric tests but no direct gRPC lifecycle test.
- `mvn -f wallet-service/pom.xml "-Dtest=WalletBusinessMetricsTest,WalletMetricsConfigTest" test` on 2026-09-09: **BUILD SUCCESS**, 5 tests run, 0 failures/errors/skips.
- `mvn -f common-util/pom.xml test` on 2026-09-05: **BUILD SUCCESS**, 75 tests run, 0 failures/errors/skips.
- `common-util/src/test/java/com/exchange/common/cache/client/IdempRedisClientIT.java` was not selected by the default Surefire naming pattern and requires an externally reachable Redis at a hard-coded address.
- `mvn -pl common-util test` from the reactor root fails before building because root `pom.xml` references a missing `client-test` child module.
- `mvn -f ledger-service/pom.xml test` on 2026-09-07 compiled 60 main and 10 test source files, then stalled during the first Spring context after Redisson startup; test configuration targets external Redis at `192.168.52.100`. The run was interrupted and is not a passing suite baseline.
- Focused hermetic Ledger tests on 2026-09-07: `LedgerTxnStoreUnitTest`, `LedgerListenerRetryPropertiesTest`, `LedgerCommandMessageHandlerTest`, and `LedgerFailureReplyFactoryTest` passed **13 tests**, 0 failures/errors/skips. The run emitted the existing pre-22 protobuf generated-code security warning.
- Ledger integration tests are environment-coupled to local MySQL and a fixed external Redis/Kafka address; no live integration or concurrency test was completed in this pass.
- Wallet's standalone build initially lacked locally installed `common-proto`/`wallet-proto` artifacts; both were installed from their repository modules with tests skipped before running the Wallet suite.
- `mvn -f wallet-service/pom.xml test` on 2026-09-07: **BUILD SUCCESS**, 20 tests run, 0 failures/errors/skips. These are the JUnit 5-discovered cache/config/processor tests; legacy JUnit 4 transaction and create-wallet tests compiled but were not discovered by the current JUnit Platform configuration.
- The Wallet run emitted the existing pre-22 protobuf generated-code security warning. No live MySQL/Redis/Kafka/Ledger integration, concurrency/stress, or query benchmark was executed in this pass.
- The 2026-09-07 run was the discovery baseline at that time; the later 2026-09-09 Wallet verification record above supersedes its status conclusion.

---

# 12. Change log

| Date | Change |
|---|---|
| 2026-09-15 | Promoted the documentation entry page from `design/README.md` to the repository-root `README.md`, updated every image and document link for the new location, and revised the documentation contract and manifest so the root README is the canonical project entry point. |
| 2026-09-10 | Restored the README lifecycle, balance/reservation, and core data-model diagrams after prioritizing reader comprehension over the two-minute target. |
| 2026-09-10 | Completed Step 07 final consistency audit. Corrected Wallet command-event identity, qualified delivery claims against retry exhaustion, removed an environment-incomplete throughput number from the Ledger overview, strengthened historical-performance provenance limits, consolidated the Ledger overview around its canonical SVG, and merged duplicate reservation lock-order records Q-027/Q-035. All published links and anchors resolve within `design/`; no canonical doc was stale enough to mark `NEEDS_REVERIFY`. Final completion remains `BLOCKED` by the recorded P0 correctness and recovery gaps. |
| 2026-09-09 | Expanded `design/README.md` with a technical transaction-pipeline diagram, the canonical Wallet lifecycle and balance/reservation visuals, and a new cross-service core data-model diagram. The entry page now communicates technical layering, Wallet business behavior, and persistent model relationships without changing the verified guarantee boundaries. |
| 2026-09-09 | Generated the final two-minute documentation entry page at `design/README.md`. It uses the canonical system architecture asset, prioritizes verified correctness/reliability and distributed-system mechanisms, gives bounded observability/performance signals without capacity claims, links overview documents first, and provides focused routes to the main detailed designs. README readiness is complete. |
| 2026-09-09 | Generated Step 05 cross-system documentation at `design/business/system-overview.md`, `design/business/end-to-end-transaction-flow.md`, and `design/consistency/wallet-ledger-consistency.md`. Added rendered architecture, lifecycle, and consistency diagrams; classified requested failure/recovery scenarios; preserved P0 recovery, compensation, concurrent-reply, reservation-locking/correlation, and reconciliation gaps; marked all cross-system synthesis complete and `design/README.md` ready to generate. |
| 2026-09-09 | Generated the Wallet Service module overview at `design/wallet/wallet-service-overview.md` from verified D-013–D-018, with three compact rendered diagrams, overview-depth transaction/balance/reservation/concurrency/idempotency/cache/Ledger/reliability explanations, bounded performance evidence, and reciprocal deep-dive links. Marked Wallet module synthesis complete and business/system overview synthesis ready. |
| 2026-09-09 | Time-boxed Step 03 verification set D-013–D-018 to `VERIFIED` as accurately bounded documents. Added the same-Wallet multi-reservation action-correlation defect; made Redis/DB initiator-scope mismatch and ambiguous hash serialization explicit; qualified cache exception suppression and normal insert behavior; retained Wallet performance observations only as historical evidence. The standalone Wallet suite passed 20 JUnit 5 tests; live dependencies, races, fault injection, and current benchmarks remain open. |
| 2026-09-09 | Generated the Ledger Service module overview at `design/ledger/ledger-service-overview.md` from verified D-007–D-011, with three compact Mermaid summaries, overview-depth accounting/posting/idempotency/Kafka/query/performance explanations, bounded guarantees, and canonical deep-dive links. Marked Ledger module synthesis complete and added reciprocal overview links to all five detailed documents. |
| 2026-09-09 | Time-boxed Step 03 verification set D-007–D-011 to `VERIFIED`. Corrected D-009's implied second explicit transaction and qualified ack-marker propagation; corrected D-010's post-insert entity-cache behavior; removed nonexistent outbox-dispatch metrics and qualified business-metric failure isolation in D-011. Rechecked all retained Ledger performance numbers and passed 13 focused JUnit 5 tests; live dependency, race, fault-injection, and benchmark evidence remains open. |
| 2026-09-09 | Drafted D-018 at `design/operations/wallet-performance.md` with a rendered evidence-layer model; separated gRPC acceptance from asynchronous settlement, documented write/query workload and reconciliation gates, qualified the three retained atomic-load runs, and made no current capacity claim. |
| 2026-09-09 | Drafted D-017 at `design/wallet/balance-query-and-cache.md` with a rendered two-key cache model; documented reference-index/entity composition, normal versus promoted system paths, fallback, negative/tombstone semantics, best-effort freshness, and the public-reference schema mismatch. |
| 2026-09-09 | Drafted D-016 at `design/wallet/transaction-idempotency-and-replay.md` with a rendered Redis/MySQL decision model; documented stable identity, Redis decisions, DB uniqueness/rollback, replay shapes, durable hash-equivalence gap, completion ownership race, and scope questions. |
| 2026-09-09 | Drafted D-015 at `design/wallet/reservation-lifecycle.md` with a rendered amount-state model; documented the reservation partition, creation/apply/settlement transitions, initiator ownership, coupled snapshot atomicity, failure-held state, and missing deterministic reservation lock order. |
| 2026-09-09 | Drafted D-014 at `design/wallet/balance-and-concurrency-correctness.md` with a rendered two-bucket model; documented the active ordered snapshot-lock path, old-value/version guards, phase atomicity, exact non-negative enforcement boundary, cache coupling, overflow gap, and unused arithmetic SQL helpers. |
| 2026-09-09 | Drafted D-013 at `design/wallet/transaction-lifecycle-and-settlement.md` with a rendered two-phase lifecycle; documented operation/action mapping, synchronous versus durable-acceptance semantics, phase transactions, command/reply identity, finalization, and recovery/compensation gaps. |
| 2026-09-09 | Drafted D-011 at `design/operations/ledger-performance.md` with a rendered evidence-layer model; documented Ledger business metrics, dashboard reading order, deterministic write/query workloads, SQL reconciliation, historical connection-pool investigation, the periodic DB-stall diagnosis, and strict limits on capacity claims. |
| 2026-09-09 | Drafted D-010 at `design/ledger/transaction-query-and-cache.md` with a rendered two-key read model; documented transaction/reference lookup paths, cache outcomes and fallback, tombstone stale-fill protection and Redis-lifetime purpose, entry keyset pagination, response-field mismatch, and qualified pre-launch settings/evidence gaps. |
| 2026-09-09 | Drafted D-009 at `design/ledger/async-posting-and-reply.md` with a rendered command/reply lifecycle; separated Ledger-specific command policy and reply construction from shared outbox mechanics, documented the journal/reply transaction split, retry and acknowledgment policy, Wallet-owned stuck-transaction recovery direction, and current reply convergence gap without claiming live-broker verification. |
| 2026-09-09 | Drafted D-007 at `design/ledger/posting-and-accounting-correctness.md` with a rendered validation/atomicity diagram; separated application per-asset balance checks, atomic header/entry persistence, and schema guarantees; documented passive-journal ownership, account-scope/eligibility gaps, blank identifiers, amount/overflow limits, inactive metadata mutation, future entry-set immutability, and distributed-ID work. |
| 2026-09-09 | Drafted D-008 at `design/ledger/posting-idempotency-and-replay.md` with a rendered Redis/MySQL decision diagram; documented current hash/key semantics, all Redis outcomes, DB winner and replay limits, stale-pending/ownership races, accepted-content trust, post-commit convergence, unescaped hash serialization, and decided/deferred protocol work without claiming verification. |
| 2026-09-09 | Generated the common-util module overview at `design/infrastructure/common-util-overview.md` from verified D-001–D-006, with two rendered visual summaries, explicit adoption classifications, compact guarantees/trade-offs, canonical deep-dive links, and proportionate future-enhancement references. Marked common-util module synthesis complete and added reciprocal overview links to all six detailed documents. |
| 2026-09-09 | Quick-verified D-002–D-006 against current shared/service source, schemas/configuration, and 89 passing hermetic tests. Set all five to `VERIFIED` as accurately bounded documents. Clarified stale `PENDING` as D-002's root failure condition, narrowed D-003 soft failure to translated `CacheException`s, and corrected D-004's overstatement that transaction metric failures are suppressed. Live Redis/MySQL/Kafka, cache/Lua, complete boundary-contract, direct gRPC lifecycle, and full metric-isolation coverage remain recorded gaps. |
| 2026-09-09 | Independently verified D-001 against current common-util and service source, Spring Kafka 2.8.11 retry metadata, schemas, provisioning, deployment defaults, and 104 passing tests. Corrected the manual-ack durability wording and proposal link; downgraded stale-Redis post-commit recovery and concurrent Wallet reply convergence; added envelope/generated-ID and outbox-audit limitations. Status set to `VERIFIED` as an accurate bounded description, not an end-to-end delivery proof. |
| 2026-09-09 | Drafted D-005 at `design/infrastructure/result-and-error-contract.md` with a rendered propagation diagram; established the implementation-derived Result/exception policy, taxonomy, retry-safety boundary, service/protobuf normalization, null/detail/scope limitations, and current-versus-proposed `UNAVAILABLE` behavior. Retired the conflicting historical `standard/design/error-handling.txt`; existing Q&A-010 remains the canonical owner-confirmed interview answer. |
| 2026-09-09 | Drafted D-006 at `design/operations/observability.md` with a rendered layered-observability diagram; validated metric boundaries, business-versus-gRPC outcomes, naming/cardinality, framework versus custom instrumentation, dashboard queries, logging/context limits, partial failure isolation, clock assumptions, absent exporters/outbox signals, and the configured-but-unemitted outbox-dispatch timer. Owner answers marked not to document were not added to interview Q&A. |
| 2026-09-08 | Drafted D-004 at `design/infrastructure/database-foundation.md` with a rendered transaction/metrics diagram; validated failed-Result rollback, Spring default and thread-bound assumptions, repository/duplicate/version conventions, exception translation and unknown commit outcomes, service adoption, statement/transaction timing boundaries, and cardinality limits. Recorded the mismatch between Wallet's PK-order reservation-lock comment and its unordered implementation (now consolidated as Q-027). Existing Q&A for database enforcement and the public infrastructure-error proposal remains canonical; D-004-specific answers marked not to document were not added. |
| 2026-09-08 | Drafted D-003 at `design/infrastructure/redis-cache-coherency.md` with a rendered strategy diagram; validated raw versus versioned states, cache-aside versus post-refresh behavior, Wallet hot-key/L1 adoption, DB fallback, post-commit hooks, CAS/tombstone races, TTL qualification, and current evidence gaps. Existing interview Q&A for equal-version overwrites, tombstone purpose, and Wallet freshness remains canonical without duplication. |
| 2026-09-08 | Drafted D-002 at `design/infrastructure/redis-idempotency-coordination.md` with a rendered layered-flow diagram; validated Redis state/TTL/hash/token behavior, service fallback and retry paths, DB uniqueness/rollback boundaries, Wallet scope mismatch, completion and malformed-value races, and the separation from the future DB-authoritative proposal. |
| 2026-09-08 | Reconciled the Wallet confirmation batch: accepted future compensation for explicit Ledger rejection, future idempotent success-reply convergence, latest-state idempotency replay, normal-read cache staleness with an intended DB-bypass real-time mode, and several deferred cleanup/test items; added exact balance-guard and reservation-lock evidence for owner review; recorded interview-useful decisions as Q&A-020–Q&A-022 while excluding answers marked not to document. |
| 2026-09-07 | Step 01 Wallet-only discovery: added D-013–D-019, proposed five Wallet detailed docs plus one performance evidence doc, deferred provisioning as concise overview context, updated synthesis dependencies/terminology/uncertainties/queue/contract candidates, and recorded the 20-test JUnit 5 baseline plus the omitted JUnit 4 coverage without drafting reader-facing documents. |
| 2026-09-07 | Reconciled D-010–D-012: deferred the query field contract, page-size/TTL tuning, query benchmarks, and hermetic tests; adopted the global pre-launch configuration policy; accepted current evidence as the qualified basis for a future D-011 draft; and deferred D-012 documentation entirely for now. |
| 2026-09-07 | Reconciled the D-009 confirmation batch: selected a future Wallet scheduled retry of stuck transactions as the business recovery mechanism, made DLT/exhaustion signals operational-only, required effect-level idempotency from common-listener adopters, classified direct gRPC posting as an expired test-only API, and deferred real Kafka/MySQL recovery tests. |
| 2026-09-07 | Reconciled the D-008 confirmation batch: deferred entry-order/description identity semantics until the planned idempotency-value-format refactor and recorded the focused processor scenarios as later test coverage. |
| 2026-09-07 | Reconciled the D-007 confirmation batch: deferred amount semantics/tests, accepted account eligibility and immutable transaction-local entry sequencing as future requirements, adopted the global application-validation/limited-DB-safeguard policy, and recorded planned distributed-ID integration for both Ledger and Wallet. |
| 2026-09-07 | Recorded the Ledger tombstone rationale: it temporarily blocks delayed stale negative/data cache fills, reduces rather than eliminates the race, and uses a 120-second TTL intended to cover the request/in-flight fill window while bounding obsolete marker lifetime in Redis; a normal tombstone hit reloads and replaces the marker rather than forcing all reads to MySQL for the full TTL. |
| 2026-09-07 | Added the owner-confirmed project-positioning rule: demonstrate industry-level engineering techniques without presenting z-exchange as a commercial production system or requiring exhaustive production hardening; material correctness gaps must still remain explicit. |
| 2026-09-07 | Step 01 Ledger-only discovery: added D-007–D-012, proposed four Ledger detailed docs plus one performance evidence doc and a concise account-bootstrap overview subsection, updated synthesis dependencies/terminology/uncertainties/queue, and recorded the focused 13-test baseline without drafting reader-facing documents. |
| 2026-09-07 | Added `design/proposals/async-wallet-immediate-outbox-publish.md` for the future D-001 change that removes Kafka acknowledgment waiting from Wallet's post-commit API path while retaining durable retry, guarded finalization, and at-least-once semantics; no runtime code changed. |
| 2026-09-06 | Added a rough durable inbox/outbox alternative for D-001 and linked it from the current Kafka reliability draft and UUID reply-identity proposal; selection and transaction-boundary details remain open. |
| 2026-09-07 | Moved four unimplemented infrastructure proposals to `design/proposals/`, updated document links and code-comment pointers, and separated D-002's planned current-design document from its future protocol proposal. Original confirmation answers retain historical paths. |
| 2026-09-06 | Documented UUID-per-persisted-Ledger-reply identity as a possible future D-001 optimization; removed the prior semantic `intent_hash`/first-write-wins direction and made Wallet convergence, failure/DLT policy, correlation, concurrency, and traffic explicit preconditions. |
| 2026-09-06 | Confirmed D-001's raw-payload same-intent comparison as an unsound abstraction and recorded the confirmation in interview Q&A. The initially open remedy was later replaced by the UUID-per-persisted-reply candidate above. |
| 2026-09-06 | Drafted D-001 at `design/infrastructure/kafka-outbox-reliability.md`; expanded evidence through service transaction paths and deployment provisioning, classified guarantees/failure windows, and exposed the reply-payload replay conflict plus open `occurred_at` semantics. |
| 2026-09-06 | Reconciled D-006 confirmations: recorded owner-verified dashboard validation, classified exporters/consumer lag as future work, retained outbox lifecycle metrics as a low-priority requirement, and marked remaining gRPC/decorator verification as partial non-blocking debt. All four answers were excluded from interview Q&A. |
| 2026-09-06 | Reconciled D-005 confirmations: scheduled the conflicting historical error-handling file for retirement after D-005, added detailed public `UNAVAILABLE` policy TODOs to both service boundary mappers, and deferred `detail`/`scope` alignment. Marked answers remain excluded from interview Q&A. |
| 2026-09-06 | Reconciled D-004 decisions: unknown transaction results surface as failures with upstream recovery ownership; Spring defaults are sufficient today with optional transaction naming noted as a future enhancement; `insertIgnore` same-intent checks remain requirement-specific. All three answers were excluded from interview Q&A as requested. |
| 2026-09-06 | Confirmed D-003 equal-version overwrite and its database-version precondition; reclassified focused cache tests and deployed Redisson validation as future verification gaps excluded from interview Q&A. |
| 2026-09-06 | Removed the pre-launch D-002 `v2` key/value version and legacy-compatibility rollout; the target protocol will be implemented directly because no production data requires migration. |
| 2026-09-06 | Confirmed D-002 token-owned completion and DB-authoritative request equivalence as future requirements, added precise implementation TODOs, and recorded the current delimiter constraint while leaving the target encoding choice open. |
| 2026-09-06 | Reconciled the first D-001 confirmation batch: recorded the accepted separate Ledger reply-outbox boundary and service-specific event identities; reclassified exhausted-row handling and ack-marker integration coverage as future work/test gaps. |
| 2026-09-06 | Added the persistent internal interview Q&A rule and seeded `codex/documentation/qa.md` from aligned common-util confirmations; this log is not a publication dependency for `./design`. |
| 2026-09-06 | Recorded the accepted public infrastructure error-boundary policy: selective `UNAVAILABLE` mapping for temporary, safely retryable failures; no application code changed. |
| 2026-09-05 | Applied the self-contained `./design` publication rule and moved every proposed common-util canonical path plus the final documentation entry page into that tree. |
| 2026-09-05 | Step 01 common-util-only discovery: replaced template placeholders with six design records, adoption classifications, proposed document boundaries/tree, dependency-aware queue, uncertainties, contract-adjustment candidates, and test baseline. |
