# Documentation Confirmation Q&A

This file is the persistent internal record of confirmation questions resolved during documentation work that may also be useful in a technical interview.

It is not part of the reader-facing `./design` documentation set and must not be linked or cited from documents under `./design`.

## Recording rules

- Add an entry only after the user has answered or confirmed the question.
- If the question or answer is marked `<NO NEED TO DOCUMENT THIS ANSWER>`, do not add it; remove any existing matching entry.
- Preserve **My original answer** verbatim apart from harmless Markdown formatting.
- The polished answer may improve clarity, organization, terminology, and precision, but must not change the decision or invent supporting details.
- Clearly distinguish current implementation, accepted trade-offs, future work, and unresolved points.
- A confirmation is not by itself implementation verification and does not make a design `VERIFIED`.
- Update an existing matching entry instead of creating a duplicate.

## Q&A-001 — Ledger business commit and reply-outbox transaction boundary

**Question**

Is the current Ledger business-commit-then-reply-outbox sequence an accepted gap, or should both be atomic in one database transaction?

**My original answer**

> yes, it is. I can give the following reasons for this design:
>
> 1. current design decouples outbox logic from business logic, which prevents them from affecting each other.
> 2. the reply content of outbox only depends on the business result and reply process, it does not need to know the business logic.
> 3. the business logic will success even if the reply fails, it matches the ituitive logic.

**Polished interview-ready answer**

The separate transaction boundary is intentional. It decouples Ledger's business transaction from reply-outbox handling so a failure in the reply path does not invalidate an otherwise successful business operation. Reply construction depends on the committed business result and the reply workflow, not on Ledger's internal business logic. This keeps the responsibilities isolated while accepting a post-commit failure window whose recovery must be handled outside the business transaction.

**Related design/module**

D-001 — Kafka/outbox reliability; Ledger Service.

**Follow-up questions an interviewer may ask**

- What recovers a successfully committed Ledger operation if reply-outbox persistence fails?
- How does Wallet avoid remaining indefinitely in an intermediate state?
- Why was decoupling preferred over atomic business-and-reply persistence?
- Which duplicate or retry scenarios can occur across this boundary?

## Q&A-003 — Reply event identity

**Question**

Why are Wallet request event IDs sequence-based while Ledger reply event IDs are derived from reply intent?

**My original answer**

> confirmed, reply id is intent-deterministic, consider the situation:
>
> 1. a wallet msg is retried for many times on kafka level
> 2. ledger will get multiple duplicated msg with a same event id
> 3. if the first attempt fails and sends back a failed reply,
> 4. the second attempt successes and sends back a successful reply
> 5. when wallet receives them, the second reply should not be treated as duplication, or there will be inconsistency between ledger and wallet.
>
> that is why reply event id is based on both requests id and process output.

**Polished interview-ready answer**

In the current implementation, Ledger reply IDs are deterministic for the combination of the request event and processing outcome. If a failure reply and a success reply are produced for the same request, they must not collapse into one identity at Wallet; otherwise Wallet could ignore the successful outcome and diverge from Ledger. Including the outcome in the current ID keeps those outcomes distinct. A separately documented future candidate would instead give every persisted reply a UUID and make Wallet state responsible for convergence.

**Related design/module**

D-001 — Kafka/outbox reliability; Wallet ↔ Ledger interaction.

**Follow-up questions an interviewer may ask**

- Which fields constitute the processing output used for reply identity?
- Can two semantically equivalent failures receive different IDs?
- How does Wallet order or reconcile failure and success replies arriving out of order?
- Where is same-intent equality enforced?

## Q&A-004 — Idempotency ownership and authoritative request equivalence

**Question**

Should idempotency completion compare the pending owner token, and must the database detect the same key being reused for a different request after Redis loss or expiry?

**My original answer**

> yes, these are true requirements, mark them with a more precise TODO comment in code. next step optimization is not implemented but documented in 'design/infrastructure/redis-idempotency-db-authoritative-optimization.md', you should refer to it in next step document.

**Polished interview-ready answer**

Both are real correctness requirements, but the optimization is not implemented yet. Completion should eventually verify ownership rather than unconditionally overwriting a newer pending claim, and the durable database layer should be able to distinguish a replay from reuse of the same idempotency key for a different request after Redis state is lost or expires. The planned design is captured in `design/proposals/redis-idempotency-db-authoritative.md`; current documentation must label these behaviors as future work.

**Related design/module**

D-002 — Redis idempotency; common-util, Wallet Service, and Ledger Service.

**Follow-up questions an interviewer may ask**

- What race occurs when a pending claim expires while the original owner is still working?
- Where should the authoritative request hash be persisted?
- How should a token mismatch during completion be handled?
- How will existing idempotency records be migrated?

## Q&A-005 — Colon-delimited idempotency values

**Question**

Is the colon-delimited accepted-value format intentional, or should it be replaced by JSON, protobuf, or another encoding?

**My original answer**

> this is intently designed, json/protobuf is hard to decode in Lua. Since the idemp infrastructure is only used inside the project, it is rare to meet an invalid format.

**Polished interview-ready answer**

The current delimiter-based format is intentional. It keeps the representation simple for Lua-side processing, whereas JSON or protobuf would be harder to decode there. Because the idempotency infrastructure is internal to this project, values that violate the delimiter constraint are considered unlikely. This is a deliberate simplicity trade-off, not a claim that malformed or delimiter-containing content is impossible.

**Related design/module**

D-002 — Redis idempotency; common-util.

**Follow-up questions an interviewer may ask**

- Where is the delimiter constraint enforced?
- What happens when accepted content contains a colon?
- Would a length-prefixed or escaped format retain Lua simplicity?
- When would the stored format need an explicit version after launch?
- Does this decision apply only to the current protocol or also to the proposed target protocol?

## Q&A-006 — Equal-version cache overwrites

**Question**

Is allowing an equal-version value to overwrite the current versioned cache entry intentional?

**My original answer**

> In my opinion, equal-version overwrite is appropriate, since the version is related to db version field, equal-version means identical version in db, it is safe to update the cache.
> Meanwhile, equal-version allows a tombstone/data key with a 0-version to overwriting 0-version negative cache, which is natural.

**Polished interview-ready answer**

Yes. The cache version is tied to the database version, so equal versions represent the same database revision and are considered safe to rewrite. Allowing equality also lets a version-0 tombstone or data entry replace a version-0 negative entry, which matches the intended cache-state transition. The CAS rule therefore rejects only older versions rather than requiring every write to be strictly newer. Its required precondition is that every cache-visible database mutation advances the version; otherwise equal versions would not prove an identical revision.

**Related design/module**

D-003 — Redis cache coherency; common-util.

**Follow-up questions an interviewer may ask**

- Can two different payloads legitimately carry the same database version?
- How do tombstone and negative-entry semantics differ?
- Why use `>=` rather than strict `>` in the Lua CAS?
- What tests protect equal-version transitions?

## Q&A-010 — Public infrastructure error mapping

**Question**

Should common infrastructure errors remain collapsed to service `INTERNAL_ERROR`, or should retryable failures preserve an `UNAVAILABLE` classification at public boundaries?

**My original answer**

> yes, this is a meaningful optimization, leave detailed TODO comment in code to describe it. the future change document is in 'design/infrastructure/public-infrastructure-error-boundary-policy.md', you should refer to it in next step document.

**Polished interview-ready answer**

Preserving a more meaningful public classification for selected retryable infrastructure failures is a worthwhile future optimization. It must not become a blanket passthrough of common error categories: public `UNAVAILABLE` is appropriate only for temporary failures remaining after fallback and server-side retry when replay is known safe. Services should expose their own stable error codes without leaking the failed dependency. Internal defects and unknown-result operations remain `INTERNAL`. This is not current completed behavior; the intended change is described in `design/proposals/public-infrastructure-error-boundary-policy.md`.

**Related design/module**

D-005 — Result and error contract; Wallet Service and Ledger Service API boundaries.

**Follow-up questions an interviewer may ask**

- Which common errors should map to `UNAVAILABLE` rather than `INTERNAL_ERROR`?
- How would the mapping affect client retry behavior?
- Could exposing infrastructure categories leak internal details?
- How will mapping parity be maintained across Wallet and Ledger?

## Q&A-011 — Pre-launch idempotency protocol versioning

**Question**

Should the proposed Redis idempotency key retain a `v2` protocol-version segment for compatibility?

**My original answer**

> I guess we could remove this, since the system is not gone online and no need to consider data compatible

**Polished interview-ready answer**

Remove the `v2` marker from the target idempotency protocol. The system has not gone online, so no persisted production data or deployed older protocol requires backward-compatible keys, dual reads, or a compatibility migration. The target schema and Redis protocol can be introduced directly. This is a pre-launch decision, not a general claim that protocol versioning will never be useful after deployment.

**Related design/module**

D-002 — Redis idempotency; common-util, Wallet Service, and Ledger Service.

**Follow-up questions an interviewer may ask**

- When should a version segment be introduced after a system goes live?
- How would a rolling deployment work if old and new Redis protocols later need to coexist?
- Can non-production databases and Redis keys be safely recreated during this change?
- Is key-namespace versioning independent from value serialization?

## Q&A-012 — Reply-outbox semantic intent versus raw payload equality

**Question**

Is comparing the complete serialized reply payload a sound way to verify that a duplicate outbox event has the same intent?

**My original answer**

> I think the design here is not sound:
> it depends on business code to return the identical detail to prevent idemp check failure.

**Polished interview-ready answer**

No. Comparing complete serialized payloads is unsound when independently reconstructed replies share a deterministic logical ID, because diagnostic differences can create a false conflict. The documented future candidate avoids semantic comparison between those replies: each newly persisted reply receives its own UUID, while exact payload comparison remains only for accidental reuse of that same UUID. Logical duplicate handling then belongs to Wallet's guarded transaction state.

**Related design/module**

D-001 — Kafka/outbox reliability; common-util and Ledger Service.

**Follow-up questions an interviewer may ask**

- Why is full-payload equality valid for one concrete UUID but not for independently reconstructed logical replies?
- How does Wallet prevent concurrent successful replies from applying effects twice?
- What happens when failure is followed by success, or when success never arrives?
- Should valid negative replies use the same DLT path as malformed or unprocessable records?
- How is request causation preserved when the reply UUID no longer embeds the request event ID?

## Q&A-013 — Unique Ledger reply events as a future optimization

**Question**

Should Ledger replace deterministic reply IDs with a unique UUID for each newly persisted reply and rely on Wallet transaction state to tolerate repeated responses?

**My original answer**

> I think maybe there is another way:
>
> in replyEventId of ledger-service, we map outbox insertions with same intent into one eventId, so that the db could deduplicate the outbox.
>
> Now with the previous issue, I think this might be an over design. pls evaluate if we can replace like this:
> replyEventId should be unique for each retry, it should include a uuid to be unique. it means one request could receive many different responses. Only if we only proceed wallet txn status after receiving successful resp, the duplicated response will not be a problem.
>
> we also keep current sameIntent check, but this time it can check with the whole payload (it is a conflict if not match perfectly)

**Polished interview-ready answer**

A possible future simplification is to treat every newly persisted Ledger reply as a distinct event instance with its own UUID. Reprocessing one request may therefore create multiple failure or success replies, while publishing retries of one durable outbox row must retain its stored UUID. Exact whole-payload comparison remains appropriate only when that same UUID is reused; any mismatch then represents a true event-identity conflict. Wallet becomes the logical convergence boundary: only a successful reply advances the transaction, and its database transition must tolerate repeated or concurrent successes without duplicate effects. Before adoption, the design must also define failure-after-success, no-success recovery, causation, DLT classification, and the additional message volume.

**Related design/module**

D-001 — Kafka/outbox reliability; Ledger reply identity and Wallet outcome convergence.

**Follow-up questions an interviewer may ask**

- Why must a UUID remain stable across retries of the same outbox row?
- What prevents two concurrent success replies from applying Wallet effects twice?
- How should Wallet handle failure-before-success and failure-after-success?
- What mechanism detects a transaction for which no success will ever arrive?
- Why keep `sameIntent` if UUID collisions are extremely unlikely?
- How should valid business failures differ from poison messages in DLT handling?

## Q&A-014 — Durable inbox/outbox as the broader alternative

**Question**

Would inserting a durable inbox record before Kafka acknowledgment and processing it asynchronously be a more promising alternative to generating multiple unique Ledger replies?

**My original answer**

> And I think there is another approach, maybe even better, which is we introduce an inbox, so the flow becomes:
>
> 1. receive msg from kafka
> 2. insert inbox
> 3. ack
> 4. business logic
> 5. insert outbox and change inbox status
> 6. publish outbox
>
> we do not need to go too far into this approach, since it is a refactor instead of a simple optimization. we just need to evaluate whether it is more promising

**Polished interview-ready answer**

A durable inbox is the more promising long-term reliability direction, although it is a larger architectural refactor. Kafka can be acknowledged once the service has durably accepted the request, after which an inbox worker owns processing and recovery. Duplicate Kafka delivery is absorbed by inbox uniqueness rather than repeatedly invoking business logic and reconstructing replies. The critical transaction boundary must still be selected: business changes, reply-outbox insertion, and inbox completion can commit atomically, or the inbox can record a durable `BUSINESS_COMPLETED` result before a separate reply-materialization step to preserve Ledger's current business/reply decoupling. Outbox publishing remains at least once, so Wallet still requires effect-level idempotency.

**Related design/module**

D-001 — Kafka/outbox reliability; Ledger intake processing and Wallet/Ledger consistency.

**Follow-up questions an interviewer may ask**

- Why is it safe to acknowledge Kafka immediately after inbox insertion?
- What recovers a row after acknowledgment but before business processing?
- Should business changes and reply-outbox creation share one transaction?
- How are inbox rows claimed, retried, exhausted, re-driven, and cleaned up?
- What uniqueness scope distinguishes duplicate delivery from a distinct command?
- Why does Wallet remain idempotent even after introducing an inbox?

## Q&A-015 — Project scope and production positioning

**Question**

Should z-exchange be presented as a commercially production-ready system, or as a technical project demonstrating techniques used in industry-level systems?

**My original answer**

> this is NOT a commercial project but a project which wants to leverage techniques that would used in industry-level project; which means it does not have to be too robust but should focus on the technique skill implementation

**Polished interview-ready answer**

z-exchange is not a commercial production system. Its purpose is to implement and demonstrate techniques used in industry-level backend and distributed-system projects. The emphasis is therefore on sound technical mechanisms, design reasoning, and explicit trade-offs rather than exhaustive production hardening. Important correctness limitations should still be acknowledged, but missing operational or defensive layers should be evaluated in proportion to the project's demonstration scope rather than presented as failed claims of commercial readiness.

**Related design/module**

Global documentation positioning; all modules and design documents.

**Follow-up questions an interviewer may ask**

- Which implemented techniques best demonstrate industry-level engineering skill?
- Which safeguards were deliberately left outside the project's scope?
- Which limitations would have to be addressed before commercial deployment?
- How do you distinguish a deliberate scope trade-off from a correctness defect?

## Q&A-016 — Tombstone purpose and TTL boundary

**Question**

Is a cache tombstone intended to guarantee that queries can never repopulate stale data, and why is Ledger's tombstone TTL configured as 120 seconds?

**My original answer**

> I think we have another design point, in config:
> tombstone-ttl: 120s *# should be short to only cover request ttl*\
> tombstone itself cannot prevent all query from caching stale data, while a well-tuned tombstone-ttl could significantly improve this
>
> I guess you could add this, not only for the config itself, but also discuss why we need tombstone

**Polished interview-ready answer**

A tombstone is a temporary race barrier, not a complete cache-freshness guarantee. After a cache-aside insert or update, it occupies the key so a query that began earlier with stale database state cannot normally finish later and repopulate a negative or older cached value. The protection lasts only while the tombstone exists: an abnormally delayed cache fill could still succeed after expiry, and Redis failure can prevent the barrier from being installed. The TTL should therefore be tuned to comfortably cover the expected request and in-flight cache-fill lifetime while ensuring obsolete tombstone keys do not remain indefinitely in Redis. Ledger currently uses 120 seconds for that purpose. Protecting MySQL is not the expiry's primary purpose: a tombstone hit normally causes one database reload whose successful cache write replaces the tombstone with a normal value.

**Related design/module**

D-003 — Redis cache coherency; D-010 — Ledger transaction query and cache read model.

**Follow-up questions an interviewer may ask**

- Which insert/update race does a raw or versioned tombstone prevent?
- Why can a tombstone not prove that stale repopulation is impossible?
- How should the TTL be selected and validated under slow requests or service pauses?
- What is the cost of making the tombstone TTL too short or too long?

## Q&A-017 — Project-wide database enforcement boundary

**Question**

Should the project add database constraints, foreign keys, or stored procedures to enforce domain invariants independently of application processors?

**My original answer**

> We will not introduce DB constrains/FK or stored procedure into our project for now. it is for performance consideration, we would complete validation inside the application, which could increase throughput and minimize the chance of deadlock.
> The only db safeguard is PK/UK check and CAS-like update (such as `update t set status=x where status=y`)

**Polished interview-ready answer**

For the current project, domain and cross-row validation remains in application code rather than being duplicated through additional database `CHECK` constraints, foreign keys, or business/posting stored procedures. This is a project-wide scope and performance decision intended to reduce extra database coordination, locking, and potential deadlock risk. Database domain safeguards are limited to primary-key and unique-key enforcement plus conditional CAS-like state updates such as `UPDATE ... SET status = x WHERE status = y`. Transactions still provide atomic commit/rollback for related writes, but that is a separate guarantee rather than domain validation. The performance benefit is the design rationale rather than a benchmark-proven claim, and documentation must not present application validation as an independently schema-enforced guarantee.

**Related design/module**

Global documentation contract; D-004 — Database foundation; D-007 — Ledger posting correctness; future Wallet transaction documents.

**Follow-up questions an interviewer may ask**

- Which invariants remain safe under concurrent requests because they use conditional SQL updates?
- What integrity risks are accepted when foreign keys and `CHECK` constraints are omitted?
- How are application validation and related inserts kept atomic?
- What evidence would justify revisiting this trade-off?

## Q&A-018 — Future distributed ID generator integration

**Question**

Should the current time-plus-process-local-sequence generators be qualified now and later replaced by a distributed generator in both Ledger and Wallet?

**My original answer**

> YES, also make a note for wallet-service. we will integrate with my self-made id-generator later, which could support distributed id generation

**Polished interview-ready answer**

Yes. The current process-local sequence generators must not be presented as safely unique across horizontally scaled instances. They are temporary implementations in both Ledger and Wallet. A future change will integrate the project owner's distributed ID generator, after which its multi-instance uniqueness properties must be verified before documentation makes that guarantee.

**Related design/module**

D-007 — Ledger posting correctness; Ledger and Wallet identifier generation; future Wallet design documents.

**Follow-up questions an interviewer may ask**

- How will the distributed generator prevent collisions across instances and restarts?
- What ordering, clock, and availability guarantees will it provide?
- Which existing Ledger and Wallet identifiers will migrate to it?
- How will generator behavior be tested before horizontal-uniqueness claims are made?

## Q&A-019 — Wallet-owned recovery and listener idempotency

**Question**

How should the system recover when Ledger commits a posting but cannot persist or deliver a usable reply, and what idempotency responsibility belongs to users of the common listener?

**My original answer**

> this is a true requirement, wallet-service needs a cron job to retry stuck txn. ledger-service now guarantees business idemp, so it is safe to retry.
>
> DLT/retry exhaustion detection/reconciliation should only for alert/metric, business correctness should not rely on it.
>
> <this should be updated in appropriated doc of common-util: the user of the listener should provide idemp, since there will be retry>

**Polished interview-ready answer**

Business recovery belongs to Wallet rather than to the DLT path. A future Wallet scheduled job should detect transactions that remain stuck and repost the same logical Ledger command with the same business identity and payload. Ledger's business idempotency then prevents a second accounting effect. DLT and retry-exhaustion detection should exist for metrics and alerting, but correctness must not depend on those signals being delivered or acted upon. More generally, common-util provides retry, acknowledgment, and transport coordination; every service adopting its listener must provide effect-level idempotency because duplicate delivery and reprocessing are expected. This recovery job is not implemented yet, and Ledger's current reconstructed-reply payload conflict must still be resolved for reliable reply convergence.

**Related design/module**

D-001 — Kafka/outbox reliability; D-008 — Ledger posting idempotency; D-009 — Ledger asynchronous posting and reply; D-013 — Wallet transaction lifecycle; D-016 — Wallet transaction idempotency; future Wallet–Ledger consistency design.

**Follow-up questions an interviewer may ask**

- Which Wallet states and age threshold define a stuck transaction?
- How does the job preserve the original Ledger business identity and payload?
- What prevents concurrent scheduler runs from creating uncontrolled duplicate commands?
- How does Wallet stop retrying after a successful reply or authoritative reconciliation?
- Which metrics distinguish stuck Wallet state, retry exhaustion, DLT arrival, and reply-outbox failure?

## Q&A-020 — Compensation after a valid Ledger rejection

**Question**

What business outcome should Wallet produce when Ledger returns a valid failure reply after Wallet has already committed held or `pendingSettle` state?

**My original answer**

> yes, this is a true requirement, and it will a new tech design to compensate when ledger rejects the post request.
>
> this is a requirement, will do it later

**Polished interview-ready answer**

A valid Ledger rejection must not leave the Wallet transaction and its reserved or `pendingSettle` funds indefinitely pending. This is an accepted correctness requirement, but the compensation mechanism is future design work. The new design must define how Wallet reaches a terminal failure outcome and reverses or releases the affected transaction, reservation, and balance state. The exact state transitions, idempotency, and interaction with retries remain intentionally unspecified until that design is completed. The current DLT behavior is therefore a temporary implementation gap rather than the intended business outcome.

**Related design/module**

D-009 — Ledger asynchronous posting and reply; D-013 — Wallet transaction lifecycle; D-015 — Wallet reservation lifecycle; future Wallet–Ledger consistency design.

**Follow-up questions an interviewer may ask**

- Which Wallet transaction and reservation states represent a rejected posting?
- Which balance movements reverse the pre-Ledger hold without releasing funds twice?
- How is compensation made idempotent under duplicate or out-of-order failure replies?
- How does Wallet distinguish an explicit rejection from an unknown or missing Ledger outcome?
- Can a later success reply supersede an earlier failure reply, and how is that ordered?

## Q&A-021 — Idempotent convergence of successful Ledger replies

**Question**

How should Wallet handle repeated or concurrent successful Ledger replies for a transaction that another consumer has already completed?

**My original answer**

> yes, will optimize it in the future: Make repeated and concurrent successful replies explicitly idempotent. Once another consumer has completed the transaction, subsequent consumers must return success without modifying state. The current behavior of routing losing concurrent attempts to DLQ is temporary and should not be treated as the final correctness or recovery design.

**Polished interview-ready answer**

Successful Ledger replies must eventually be effect-idempotent at Wallet. Exactly one consumer should perform the reservation, balance, and transaction finalization. A repeated consumer, or a concurrent consumer that loses the race, should observe the already-completed transaction, make no further state changes, return success, and acknowledge the message. Sending that losing attempt to DLT because it encounters already-updated state is only temporary behavior; DLT must not be the target mechanism for normal duplicate convergence. This optimization is accepted future work and is not implemented yet.

**Related design/module**

D-001 — Kafka/outbox reliability; D-013 — Wallet transaction lifecycle; D-014 — Wallet concurrency correctness; D-015 — Wallet reservation lifecycle.

**Follow-up questions an interviewer may ask**

- Which row or state transition serializes competing finalizers?
- At what point must the consumer recheck `COMPLETED` state?
- How does the handler distinguish a benign duplicate from a genuinely inconsistent update?
- What happens when success and failure replies for the same transaction arrive out of order?
- Which tests prove that balances and reservations are finalized exactly once?

## Q&A-022 — Wallet snapshot freshness modes

**Question**

May normal Wallet snapshot reads return stale cached data, and how should callers obtain a real-time balance?

**My original answer**

> we can tolerate occasional stale cache, real-time read will bypass the cache and read db directly
>
> this is expected, normal read can tolerate a short latency. real-time read will bypass cache

**Polished interview-ready answer**

Normal Wallet snapshot reads may tolerate occasional short-lived stale cache data. This is an intentional availability and performance trade-off: cache maintenance remains best effort after the authoritative database transaction commits. A caller requiring real-time state should use a read path that bypasses Redis and queries MySQL directly. The inspected public Wallet API does not yet expose a distinct real-time bypass, so that part is intended behavior rather than a current verified capability. The acceptable meaning and measured bound of “short-lived” also still require evidence before documentation states a numerical freshness guarantee.

**Related design/module**

D-003 — Redis cache coherency; D-014 — Wallet balance correctness; D-017 — Wallet balance query and cache read model.

**Follow-up questions an interviewer may ask**

- How will callers explicitly select normal versus real-time reads?
- Is the real-time path rate-limited or restricted to particular workflows?
- What prevents the database-bypass path from becoming an unbounded hot path?
- How will cache-staleness duration be measured and bounded?
- Can snapshot versions help clients detect that a cached response is older than required?

## Q&A-023 — Stale Redis pending state as the recovery blocker

**Question**

What is the underlying failure condition when Ledger retries cannot recover an idempotent request: whether the posting committed, or whether Redis remains `PENDING`?

**My original answer**

> it does not matter whether whether ledger is successfully persisted into DB, the point is redis idemp state stays in PENDING without being cleared or moved to DONE
>
> am I correct?

**Polished interview-ready answer**

Yes. The underlying condition is that the Redis idempotency record remains `PENDING` instead of being cleared after failure or transitioned to `ACCEPTED` after completion. While that live value remains, matching retries are classified as already processing and may not reach the authoritative database lookup. Whether the Ledger posting committed determines the consequence: without a commit, retry is delayed; with a commit, the stale claim can hide the durable result and allow a later failure reply to contradict it. Database commit status is therefore important to impact analysis, but it is not the root cause of the blocked recovery path.

**Related design/module**

D-001 — Kafka/outbox reliability; D-002 — layered Redis idempotency coordination; D-009 — Ledger asynchronous posting and reply.

**Follow-up questions an interviewer may ask**

- Which failures can leave the Redis record in `PENDING`?
- Why do retries check the live Redis state before querying the authoritative database?
- How should the pending TTL relate to the transport retry window?
- Should a retry bypass `PENDING` and inspect the database after a known recovery threshold?
- How would token-owned completion and a DB-authoritative request hash change this failure mode?
