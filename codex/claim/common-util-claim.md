D-001 — Kafka transport, listener durability, and transactional-outbox recovery
TODO: NEED CONFIRMATION — Is the current Ledger business-commit-then-reply-outbox sequence an accepted gap, or should both be atomic in one DB transaction?
yes, it is. I can give the following reasons for this design:
    1. current design decouples outbox logic from business logic, which prevents them from affecting each other.
    2. the reply content of outbox only depends on the business result and reply process, it does not need to know the business logic.
    3. the business logic will success even if the reply fails, it matches the ituitive logic.

TODO: NEED CONFIRMATION — Define and implement the intended terminal handling/alerting for retry-exhausted outbox rows.
this part still requires future supplement. <NO NEED TO DOCUMENT THIS ANSWER>

TODO: NEED CONFIRMATION — Verify ack-failure marker propagation and idempotent recovery with a real Kafka retry-topic integration test.
need future test <NO NEED TO DOCUMENT THIS ANSWER>

TODO: NEED CONFIRMATION — Confirm service-specific event identity rules for every outbox type; Wallet request event IDs are sequence-based while Ledger reply IDs are intent-deterministic.
confirmed, reply id is intent-deterministic, consider the situation:   
    1. a wallet msg is retried for many times on kafka level
    2. ledger will get multiple duplicated msg with a same event id
    3. if the first attempt fails and sends back a failed reply, 
    4. the second attempt successes and sends back a successful reply
    5. when wallet receives them, the second reply should not be treated as duplication, or there will be inconsistency between ledger and wallet.
that is why reply event id is based on both requests id and process output.

D-002 — Layered Redis idempotency coordination
TODO: NEED CONFIRMATION — Should mark-done become compare-and-set by pending token, and what should happen if ownership has expired or moved?
TODO: NEED CONFIRMATION — Is same-key/different-request conflict required after Redis loss? Current DB fallbacks cannot consistently prove request equivalence.
yes, these are true requirements, mark them with a more precise TODO comment in code. next step optimization is not implemented but documented in 'design/infrastructure/redis-idempotency-db-authoritative-optimization.md', you should refer to it in next step document.

TODO: NEED CONFIRMATION — Is the colon-delimited accepted content intentionally constrained, or should the format become escaped, length-delimited, JSON, or protobuf?
this is intently designed, json/protobuf is hard to decode in Lua. Since the idemp infrastructure is only used inside the project, it is rare to meet an invalid format.


D-003 — Redis cache-coherency and hot-data strategies
TODO: NEED CONFIRMATION — Confirm equal-version overwrite is intentional for every versioned cache user.
In my opinion, equal-version overwrite is appropriate, since the version is related to db version field, equal-version means identical version in db, it is safe to update the cache.
Meanwhile, equal-version allows a tombstone/data key with a 0-version to overwriting 0-version negative cache, which is natural.

TODO: NEED CONFIRMATION — Add focused tests for codecs, strategy transitions, Lua CAS (including malformed/equal/older/newer values), negative cache, and tombstones.
will add later <NO NEED TO DOCUMENT THIS ANSWER>

TODO: NEED CONFIRMATION — Validate Redisson client-side caching against the deployed Redis/Redisson versions and document observed invalidation behavior.
will verify later <NO NEED TO DOCUMENT THIS ANSWER>


D-004 — Database transaction, persistence, exception, and SQL-metrics foundation
TODO: NEED CONFIRMATION — Define the intended treatment of commit/rollback exceptions not recognized by DbExceptionTranslator, especially unknown-result cases.
<NO NEED TO DOCUMENT THIS ANSWER>
not now, treat unknown-result cases as failure is cleaner, it is upstream responsibility to retry or confirm the status.

TODO: NEED CONFIRMATION — Confirm Spring default isolation/propagation/timeout are intentionally sufficient for every current DbTxnExecutor call site.
<NO NEED TO DOCUMENT THIS ANSWER>
default isolation/propagation/timeout is enough now, but a customized name param could be an improvement. add it into doc, maybe we will implement it later.

TODO: NEED CONFIRMATION — Decide whether insertIgnore needs a common same-intent verification convention beyond outbox usage.
<NO NEED TO DOCUMENT THIS ANSWER>
no, same-intent verification should depend on requirement.


D-005 — Shared Result, error taxonomy, exception, and boundary-mapping contract
TODO: NEED CONFIRMATION — Establish one current Result-versus-exception policy and update or retire standard/design/error-handling.txt accordingly.
<NO NEED TO DOCUMENT THIS ANSWER>
yes, this should be retired after the current phase document is done.

TODO: NEED CONFIRMATION — Confirm whether common infrastructure errors should remain collapsed to service INTERNAL_ERROR or preserve UNAVAILABLE at public boundaries.
yes, this is a meaningful optimization, leave detailed TODO comment in code to describe it. the future change document is in 'design/infrastructure/public-infrastructure-error-boundary-policy.md', you should refer to it in next step document.

TODO: NEED CONFIRMATION — Define whether detail/scope may cross RPC/event boundaries and what redaction/cardinality rules apply.
<NO NEED TO DOCUMENT THIS ANSWER>
this one is small, we could align later


D-006 — Shared application observability instrumentation
TODO: NEED CONFIRMATION — Validate the provisioned dashboards against an actual /actuator/prometheus capture for both services.
<NO NEED TO DOCUMENT THIS ANSWER>
already verified

TODO: NEED CONFIRMATION — Decide whether infrastructure exporters and Kafka consumer-lag collection are intended current scope or explicit future work.
<NO NEED TO DOCUMENT THIS ANSWER>
future work

TODO: NEED CONFIRMATION — Define outbox backlog/exhaustion/finalization metrics after the DEAD-state policy is resolved.
<NO NEED TO DOCUMENT THIS ANSWER>
this is a true requirement, will implement it later. However, it is low-prioritized, since it is only for observation rather than performance/business.

TODO: NEED CONFIRMATION — Add direct gRPC interceptor lifecycle/concurrency tests and review metric decorators for complete failure isolation.
<NO NEED TO DOCUMENT THIS ANSWER>
half done, but not a blocker for any change or design.

