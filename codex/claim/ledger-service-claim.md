D-007 — Ledger posting and accounting correctness

TODO: CORRECTNESS — Decide whether amounts must be strictly positive and add overflow-safe per-asset summation if that is the intended contract.
<NO NEED TO DOCUMENT THIS ANSWER>
this is not a commercial project, we will determine this later

TODO: CORRECTNESS — Define the account identity scope used by posting and validate account service/status/asset (and any required owner dimensions) before entry creation.
<NO NEED TO DOCUMENT THIS ANSWER>
this is a true requirement, but it is out of the range of this version, we may design it later

TODO: DB INVARIANT — Decide whether DB constraints/FKs or a posting stored procedure are required to prevent invalid journal rows outside this processor.
<This should be a global contract across all modules>
We will not introduce DB constrains/FK or stored procedure into our project for now. it is for performance consideration, we would complete validation inside the application, which could increase throughput and minimize the chance of deadlock.
The only db safeguard is PK/UK check and CAS-like update (such as `update t set status=x where status=y`)

TODO: IMMUTABILITY — Confirm whether a posted entry set is permanently immutable and, if so, add schema-level protection such as a transaction-local sequence/uniqueness contract.
<NO NEED TO DOCUMENT THIS ANSWER>
I think this is a true requirement, although it is not that mandatory for now, I still suggest we force a transactional-local seq contract in the future change.
Therefore, take short note of it.

TODO: TEST GAP — Add focused validation, rollback, negative/overflow amount, multi-asset, mismatched-account-asset, and concurrent-post tests; the current service suite has only one posting happy path.
<NO NEED TO DOCUMENT THIS ANSWER>
will add it later

TODO: SCALE — Replace or qualify the time-plus-process-local-sequence ID generator before claiming safe horizontal multi-instance uniqueness.
<YES, also make a note for wallet-service. we will integrate with my self-made id-generator later, which could support distributed id generation>



D-008 — Ledger posting idempotency, concurrency, and replay
TODO: NEED CONFIRMATION — Confirm that entry ordering is intentionally outside request identity and that description is semantically part of identity despite not being persisted.
<NO NEED TO DOCUMENT THIS ANSWER>
I remember the idemp value format will be refactored as we talked in previous discussion. we will decide this once we refactor the format.

TODO: TEST GAP — There are no focused processor tests for Redis loss, hash conflict, pending ownership, malformed values, DB replay, duplicate-insert race, or post-commit completion failure.
<NO NEED TO DOCUMENT THIS ANSWER>
will provide this later


D-009 — Ledger asynchronous command processing and durable replies
TODO: RECOVERY DESIGN — Define detection/reconciliation when Ledger commits but cannot persist/deliver a usable reply before DLT or retry exhaustion.
this is a true requirement, wallet-service needs a cron job to retry stuck txn. ledger-service now guarantees business idemp, so it is safe to retry. 
DLT/retry exhaustion detection/reconciliation should only for alert/metric, business correctness should not rely on it.
<this should be updated in appropriated doc of common-util: the user of the listener should provide idemp, since there will be retry>

TODO: NEED CONFIRMATION — Clarify whether direct gRPC posting is a supported production integration or only an administrative/test ingress.
<NO NEED TO DOCUMENT THIS ANSWER>
it is an expired API, now only for test usage. mention this precisely in doc.

TODO: TEST GAP — Add real Kafka/MySQL tests for crash-after-business-commit, reply-insert ambiguity/conflict, retry-to-failure-reply, source-ack failure, and Wallet convergence.
<NO NEED TO DOCUMENT THIS ANSWER>
will add later


D-010 — Ledger transaction query and cache read model
TODO: CONTRACT DEFECT — Decide whether to persist/return original account references or rename the protobuf field to internal accountId.
<NO NEED TO DOCUMENT THIS ANSWER>
will decide later

TODO: PERFORMANCE — Validate a realistic entry page size; current 3 appears test-oriented and amplifies multi-entry queries.
<NO NEED TO DOCUMENT THIS ANSWER>
this project has not gone online, will decide later

TODO: EVIDENCE GAP — Query workload/tooling exists, but no retained query benchmark results were found.
<NO NEED TO DOCUMENT THIS ANSWER>
yes, will test later

TODO: TEST GAP — Existing Spring tests bind to external Redis/MySQL and several mock repository stubs do not reflect the current store indirection; modernize hermetic coverage before verification.
<NO NEED TO DOCUMENT THIS ANSWER>
will add later

TODO: TTL VALIDATION — Confirm that 120 seconds reasonably covers the maximum supported Ledger request/cache-fill duration and test a delayed pre-insert miss against the tombstone boundary.
<NO NEED TO DOCUMENT THIS ANSWER>
<this should be remembered for all modules: all configs are not yet decided, this project is not online yet, so do not confirm with config like ttl/batch-size/etc.>


D-011 — Ledger observability and performance evidence
you could generate based on what we have now. in the future, I will provide more test data, and we will improve the doc with them.

D-012 — Account and asset reference-data bootstrap
no need doc for this part for now