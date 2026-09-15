# Wallet Service Ledger-Parity Change Scope

## Scope

This evaluation compares the current `wallet-service` with patterns already used by
`ledger-service` and `common-util`. Deployment files, environment endpoints, and server
provisioning are excluded.

## Assessment

| Priority | Area | Current state | Change scope |
| --- | --- | --- | --- |
| P0 | Ledger reply correctness | Wallet parses `PostTransactionReplyPb` but ignores `error` and always calls completion logic. | Branch on reply outcome. Only a successful ledger reply may call `AfterPostLedgerProcessor`. |
| P0 | Kafka listener | Wallet uses the legacy blocking `DefaultErrorHandler` path and handles only envelope parse errors. | Adopt the common listener template, explicit failure classification, `@RetryableTopic`, and DLT handling. |
| P0 | Failed-reply recovery | A failed ledger reply has no durable wallet-side record or reconciliation trigger. | Keep the transaction pending, persist enough failure/reconciliation state before ack, and never rely on logs alone. The storage shape needs a separate decision. |
| P1 | API metrics | Actuator is enabled, but no global gRPC interceptor or wallet business metrics exist. | Register the common gRPC interceptor and add an explicit wallet business metric wrapper. |
| P1 | Cache/idempotency metrics | Redis support metrics exist, but wallet cache strategies are unmetered and business code bypasses `IdempotencyClient`. | Wrap all four cache strategies and migrate wallet idempotency calls to the measured wrapper. |
| P1 | DB exception parity | Wallet imports the common DB beans and receives AOP classes transitively, but does not enable AspectJ proxies or declare AOP as a direct service dependency. | Enable repository/transaction exception translation and retain existing MyBatis/transaction timers. |
| P2 | Runtime safeguards | Wallet lacks ledger's bounded Hikari and MyBatis statement timeout settings. | Add equivalent service-appropriate timeouts after validating wallet load characteristics. This is runtime safety, not deployment work. |
| P2 | Legacy client | `PostServiceClient` and `grpc.client.ledger-client` are unused; posting is Kafka/outbox based. | Remove them after confirming no external reflective use. Keep `AccountServiceClient`. |

## Phase 1: Kafka Listener Template Migration (P0)

### Goal

Migrate the ledger-reply consumer from the blocking `DefaultErrorHandler` path to the common
listener template and `@RetryableTopic`. Wallet consumes a terminal reply and sends no reply of
its own, so it must use `AckOnlyDefaultMessageHandler`, not `OutboxReplyDefaultMessageHandler`.

### Already Sound

- Keep `EventEnvelopePb` as the Kafka value and protobuf bytes as its payload.
- Keep wallet command creation in the same DB transaction as wallet state changes.
- Keep the existing deterministic outbox row across publish retries.
- Keep repository-only mapper access; wallet business code already follows this boundary.

### Required Changes

| Change point | Scope |
| --- | --- |
| Spring adapter | Refactor wallet `DefaultListener` into a thin adapter over the common listener. Accept `ConsumerRecord<String, byte[]>`, pass value/headers/ack to the delegate, and add `@RetryableTopic` plus `@DltHandler`. |
| Wallet handler | Extract envelope event validation, `PostTransactionReplyPb` parsing, reply-outcome branching, and `AfterPostLedgerProcessor` invocation into a wallet-owned handler. Only `ERROR_OK` may invoke completion. |
| Ack-only action | Register the handler through `AckOnlyDefaultMessageHandler`. Supply an explicit failure policy; its default ack-on-failure constructor is unsafe for wallet. |
| Failure classification | Convert recoverable processing failures, especially retryable `DbException`, to `KafkaListenerRetriableException`. Route malformed payloads, unsupported event types, non-retryable failures, unexpected failures, and exhausted retries to DLT without acknowledging them as successful. |
| Retry ownership | Use `concurrentRetryTopicCommandKafkaListenerContainerFactory`; wallet must no longer depend on `KafkaErrorHandlingRegister`/`DefaultErrorHandler` for this listener. Let `@RetryableTopic.attempts` own final DLT routing; do not introduce an earlier failed-reply threshold because AckOnly creates no failure outbox. |
| Ack recovery | Reuse the common ack-failure header behavior. Once wallet DB work is durable, a redelivery caused only by ack failure must skip business processing and retry acknowledgment. |
| Configuration | Add wallet listener retry properties and `application.yml` values for attempts, backoff, and topic auto-creation. Retry/DLT topics remain infrastructure-provisioned in production. |
| DLT logging | Log topic, partition, offset, key, original topic, exception class, and exception message from Kafka headers. Do not declare an `Exception` argument on `@DltHandler`. |

### Wallet Files

- Refactor `kafka/consumer/DefaultListener.java` as the Spring adapter and DLT endpoint.
- Add a wallet ledger-reply handler under `kafka/consumer`.
- Add `kafka/config/WalletListenerConfig.java` and `WalletListenerRetryProperties.java`.
- Add retry settings to `src/main/resources/application.yml`.
- Add focused listener, handler, configuration, and DLT-header tests.

### Dependency On Reply Semantics

This phase must not preserve the current behavior that ignores `PostTransactionReplyPb.error`.
A failed ledger reply leaves the wallet transaction `PENDING` and is retained in DLT for interim
reconciliation. A wallet-side failure record remains a separate storage decision; once implemented,
the listener may persist that record and acknowledge the source reply instead.

### Required Scenario Behavior

| Scenario | Expected behavior |
| --- | --- |
| Outer envelope cannot be parsed | Direct DLT; request identity and payload contract are unknown. |
| Event type or reply payload is invalid | DLT; do not acknowledge and silently lose the ledger result. |
| Ledger success reply | Apply the guarded wallet completion transaction, then ack. |
| Duplicate success reply after completion | Idempotent no-op/success, then ack. |
| Ledger failure reply | Do not run completion. Leave the transaction pending and route the original reply to DLT until wallet-side failure persistence exists. |
| Retryable DB failure while applying success | Throw `KafkaListenerRetriableException`; do not ack. |
| Non-retryable invariant/state conflict | DLT or durable reconciliation; never blindly complete. |
| Unexpected exception | DLT with bounded logging; do not use the default ack-only downgrade. |
| Ack failure after durable work | Retry with the ack-failed header and skip business processing next time. |

## Phase 2: DB Exception Translation Parity (P1)

### Goal

Activate the common DB exception translator for wallet repository and transaction boundaries without
changing wallet business flow, transaction boundaries, or metric semantics.

### Already Sound

- `CommonDbComponentRegister` already supplies `DbExceptionTranslator`, `DbExceptionTranslateAop`,
  the metered `DbTxnExecutor`, and the MyBatis statement metrics interceptor.
- All wallet production mapper access is contained in repositories, and every wallet repository
  extends `DbBaseRepository`. The shared `OutboxRepository` follows the same boundary.
- `CreateWalletProcessor`, `BeforePostLedgerProcessor`, and `AfterPostLedgerProcessor` already use
  the injected `DbTxnExecutor`; no transaction call-site migration is needed.

### Required Changes

| Change point | Scope |
| --- | --- |
| AOP dependency | Add `spring-boot-starter-aop` directly to `wallet-service/pom.xml`. It is currently available only through `common-util`; wallet should explicitly own the runtime capability it enables. |
| Proxy enablement | Add `@EnableAspectJAutoProxy` to `WalletServer`, matching ledger. This activates the existing repository and `DbTxnExecutor` pointcuts. |
| Exception behavior | Reuse `DbExceptionTranslator` unchanged. Preserve an existing `DbException`, translate only recognized DB/transaction failures, and rethrow unclassified exceptions unchanged. Repository translation occurs first; transaction translation must not wrap the resulting `DbException` again. |
| Metrics | Keep the existing `MybatisStatementMetricsInterceptor` and `MeteredDbTxnExecutor`. Repository AOP adds no timer, so statement and full-transaction measurements remain distinct and are not duplicated. |
| Tests | Add a wallet Spring-context test proving repository and `DbTxnExecutor` beans are proxied and representative failures become the expected `DbException`. Verify existing `DbException` identity is preserved and DB timer beans remain active. |

### Explicitly Unchanged

- No repository, mapper, processor, SQL, transaction boundary, retry policy, or error-code change.
- No common-util implementation change; its pointcuts and conservative exception classification are
  already shared and covered by common-util unit tests.
- Do not add wallet-specific DB timers or exception types.

## Phase 3: API, Business, Cache, and Idempotency Metrics (P1)

### Goal

Adopt ledger's explicit boundary instrumentation and shared Redis metric decorators without changing
wallet business behavior, cache selection, or idempotency guarantees.

### Already Sound

- `GrpcServerMetricsInterceptor`, cache strategy decorators, and `IdempotencyClient` already exist in
  `common-util`; ledger proves their intended registration and usage patterns.
- Wallet already exposes Prometheus and receives common Redis, DB statement, and DB transaction
  metrics. Do not add wallet-specific duplicates of those lower-level timers.
- Keep metrics outside processor internals. The gRPC/Kafka boundary supplies operation and ingress;
  common Redis components supply their own bounded context.

### Required Changes

| Change point | Wallet scope |
| --- | --- |
| gRPC interceptor | Add `WalletMetricsConfig` and register `GrpcServerMetricsInterceptor` with `@GrpcGlobalServerInterceptor`, matching ledger. |
| Metric definitions | Add wallet-owned metric definitions and bounded tag constants for operations, ingress, outcomes, and error codes. Never tag IDs, idempotency keys, references, or error details. |
| Business wrapper | Add `WalletBusinessMetrics`. Wrap the six processor calls in `WalletServiceImpl` and the successful ledger-reply completion call in `WalletLedgerReplyMessageHandler`; do not use general processor AOP. Preserve return values and exceptions while recording failures in `finally`. |
| Cache strategies | Inject `MeterRegistry` into `BalanceSnapshotCacheRegister`. Decorate all four existing beans with `MetricRawCacheStrategy` or `MetricVersionCacheStrategy` while preserving bean names, qualifiers, strategy type, TTLs, negative-cache behavior, and hot/normal routing. |
| Idempotency client | Replace direct `IdempRedisClient` injection in `IdempPrecheckProcessor` and `BeforePostLedgerProcessor` with `IdempotencyClient`. Use its `Result` APIs and wrapper-owned decode/hash verification. |
| Distribution config | Enable histograms and bounded SLOs for `zexchange.grpc.server.duration`, `zexchange.wallet.processing.duration`, `zexchange.redis.operation.duration`, `zexchange.db.operation.duration`, and `zexchange.db.transaction.duration`. |

### Metrics

| Metric | Type | Tags |
| --- | --- | --- |
| `zexchange.grpc.server.active` | Gauge | `service`, `method` |
| `zexchange.grpc.server.duration` | Timer | `service`, `method`, `grpc_status` |
| `zexchange.wallet.requests` | Counter | `operation`, `ingress`, `outcome`, `error_code` |
| `zexchange.wallet.processing.duration` | Timer | `operation`, `ingress`, `outcome` |
| `zexchange.cache.ops` | Counter | `cache_type`, `result` |
| `zexchange.cache.errors` | Counter | `cache_type`, `operation`, `error_type` |
| `zexchange.idempotency.errors` | Counter | `service`, `scope`, `operation`, `error_type` |

Business operations are bounded to `create_wallet`, `atomic_transaction`, `reserve_transaction`,
`apply_reservation_transaction`, `get_snapshot_by_wallet_id`, `get_snapshot_by_ref_id`, and
`complete_ledger_transaction`; ingress is `grpc` or `kafka`. Cache types must separately identify
normal/hot reference and normal/hot snapshot strategies.

Do not add `zexchange.wallet.transactions.completed` yet. `AfterPostLedgerProcessor` currently
returns the same successful shape for a new completion and an idempotent replay, so the counter would
overcount. Add it only after the processor exposes an explicit transition outcome.

### Idempotency Behavior To Preserve

- Claim/read infrastructure failures degrade to the DB idempotency check.
- Hash conflict maps to `WalletServiceErrorCode.REQUEST_HASH_CONFLICT`.
- A malformed value triggers best-effort force-delete, then DB fallback.
- `mark_done`, release, or cleanup failures are logged and measured but must not replace an already
  committed DB outcome.

### Tests

- Verify the global interceptor bean and wallet request/duration tags with `SimpleMeterRegistry`.
- Verify success, returned business error, and thrown exception recording for every boundary shape.
- Verify all four cache beans retain their qualifiers/behavior and emit bounded result/error series.
- Verify idempotency claim, replay, hash conflict, malformed value, Redis downgrade, mark-done, and
  release paths preserve current DB fallback and transaction behavior.

### Explicitly Unchanged

- No processor algorithm, DB transaction, cache promotion policy, Redis Lua script, or protobuf change.
- No common-util implementation change and no Kafka publisher/outbox metric work in this phase.

## Phase 4: Runtime Database Safeguards (P2)

### Goal

Bound wallet DB resource usage and slow operations without changing business flow. Wallet and ledger
share the same MySQL instance (`max_connections=50`) and monitoring stack, so their connection pools
must be budgeted together rather than configured independently.

### Required Changes

| Change point | Wallet scope |
| --- | --- |
| Hikari pool | Add environment-overridable initial defaults in common `application.yml`: maximum pool `12`, minimum idle `8`, connection timeout `2000ms`, validation timeout `1000ms`, max lifetime `1500000ms`, and keepalive `300000ms`. |
| JDBC timeouts | Set MySQL `connectTimeout=2000ms` and `socketTimeout=5000ms`. These bound connection establishment and network reads independently of pool acquisition. |
| Statement timeout | Add `mybatis-plus.configuration.default-statement-timeout=3` seconds to bound ordinary mapper execution. Explicit per-statement settings may override it where justified. |
| Shared capacity | Keep ledger `32` plus wallet `12` at `44` maximum pooled connections, reserving six MySQL connections for operations and auxiliary clients. Do not raise either pool independently. |
| Validation | Run wallet load and then combined wallet/ledger load. Inspect Hikari active/max, pending, acquisition latency, and timeouts together with DB transaction/operation latency and DB errors through the existing shared monitor server. |

### Acceptance And Adjustment

- Under the target sustained load, acquisition timeouts and sustained pending connections should
  remain zero; acquisition latency should not dominate wallet transaction latency.
- If wallet requires more than 12 connections, first measure concurrent ledger demand, MySQL CPU/I/O,
  active sessions, and transaction latency. Rebalance the shared budget or increase verified MySQL
  capacity before increasing the wallet pool.
- Keep all values environment-overridable, but use the same bounded defaults in cloud and VMware
  profiles unless a measured environment-specific override is required.

### Explicitly Unchanged

- No SQL, mapper, repository, transaction boundary, retry, or error-mapping change.
- No Docker/Compose resource, MySQL server, Prometheus, Grafana, IP, or dashboard change. The existing
  shared monitor is used only to validate the wallet runtime settings.

## 5. Supporting Changes and Tests

- Add unit tests for reply success, reply error, malformed payload, retryable DB failure,
  unexpected failure, duplicate completion, retry exhaustion, and ack-failure recovery.
- Add configuration tests for retry thresholds and the retry-topic container factory.
- Add metric tests using `SimpleMeterRegistry`; assert exact counts and that active gauges return to
  zero.
- Add an Embedded Kafka test later for retry-topic/DLT routing. Unit tests alone cannot prove Spring
  topic routing.

## 6. Explicitly Excluded or No Change

- No Docker, Compose, cloud profile, IP, DNS, security-group, or log deployment changes.
- No wallet balance/reservation algorithm refactor in this scope.
- No direct mapper migration is needed; production code already accesses mappers through repositories.
- No publisher/outbox dispatcher rewrite solely for parity; their current implementations match
  ledger's. Shared publisher/outbox metrics should be implemented once in common infrastructure.
- No high-cardinality labels or scrape-time DB queries.

## Recommended Order

1. Correct ledger reply semantics and add focused tests.
2. Migrate wallet to the common retry-topic listener template and prove DLT behavior.
3. Enable DB exception translation and migrate idempotency/cache wrappers.
4. Add gRPC/business metrics and histogram configuration.
5. Add bounded wallet DB runtime settings and validate the shared MySQL connection budget.
6. Implement shared Kafka/outbox metrics for both services in a separate phase.
