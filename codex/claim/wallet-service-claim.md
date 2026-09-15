TODO: P0 CURRENT GAP — Define and implement the business outcome for a valid failed Ledger reply. Current code sends it to DLT but leaves the transaction and held/pending-settle funds unchanged; the future stuck-transaction retry job does not by itself define permanent business-failure handling or compensation.
yes, this is a true requirement, and it will a new tech design to compensate when ledger rejects the post request.

DECIDED / FUTURE IMPLEMENTATION — Add Wallet-owned recovery that finds stuck transactions and reposts the same logical Ledger command. Exact state/age selection, claiming, retry budget, stop conditions, and interaction with failed replies remain unspecified.
yes, this is a requirement, will do it later

TODO: CONVERGENCE — Make concurrent/repeated successful replies converge as an explicit benign no-op rather than relying on downstream invariant failures and DLT for a losing concurrent consumer.
yes, will optimize it in the future: Make repeated and concurrent successful replies explicitly idempotent. Once another consumer has completed the transaction, subsequent consumers must return success without modifying state. The current behavior of routing losing concurrent attempts to DLQ is temporary and should not be treated as the final correctness or recovery design.

TODO: CONTRACT VALIDATION — Validate that envelope commandId, reply referenceId, and the expected Wallet transaction identity agree; current handler trusts the payload reference and only checks that reply IDs are nonblank.
<NO NEED TO DOCUMENT THIS ANSWER>
this would be optimized

TODO: API CONTRACT — Clarify the supported operation combinations for applyReserveTransaction. Current code accepts more than earmark/release, including debit/credit combinations, while the API name and comments imply a narrower reservation-application role.
<NO NEED TO DOCUMENT THIS ANSWER>
will do it in the future

TODO: API REPLAY SHAPE — Decide whether idempotent replays must return the original transaction status. Current replySuccess(String txnId, ...) overloads omit status, leaving protobuf Unknown even when the original first response reported PENDING or COMPLETED.
<NO NEED TO DOCUMENT THIS ANSWER>
will do it in the future

TODO: WIRE CLEANUP — TwoStepTransactionRequestPb/ReplyPb, TransactionTypePb, and ActionTypePb are not active service operations; classify or remove them before documenting the supported API surface as intentional.
<NO NEED TO DOCUMENT THIS ANSWER>
yes will do it in the future

TODO: CONTRACT CONFLICT — Reconcile the repository-level project hint claiming non-negative balances are enforced by DB arithmetic guards with the active implementation, which uses row locks, application checks, and guarded updates. Until then, document executable behavior and classify the arithmetic helper methods as unused.
<NO NEED TO DOCUMENT THIS ANSWER>
pls point out the code evidence and will check later

TODO: SNAPSHOT FENCE — Decide whether last_txn_id is obsolete schema residue or should become an applied-transaction/cache-recovery fence
<NO NEED TO DOCUMENT THIS ANSWER>
this is for future design

TODO: CACHE RECOVERY — Decide whether best-effort post-commit cache maintenance is sufficient for the demonstration scope or needs durable invalidation/refresh work; do not claim strict read-after-write freshness today.
we can tolerate occasional stale cache, real-time read will bypass the cache and read db directly

TODO: P0 FAILURE SEMANTICS — Define release/compensation or continued retry for reservations left in pendingSettle after a valid Ledger failure or unrecoverable command; current code has no terminal negative transition.
this is a requirement, will do it later

TODO: LOCK ORDER — Sort reservation primary keys and add ORDER BY id to the locking query, or document/prove an alternative deterministic order. Current implementation contradicts its own PK-order comment and standard/design/db.txt.
pls give the code evidence, will verify later

TODO: STATUS CONTRACT — Decide whether CLOSED/CANCELLED are future states or dead vocabulary, and whether applies must explicitly reject non-ACTIVE reservations.
<NO NEED TO DOCUMENT THIS ANSWER>
these states are for future use or OPS

TODO: REFERENCE SEMANTICS — Clarify client-visible reservation-reference stability and collision rules. New references are derived from transaction reference plus wallet ID rather than the generated reservationId field.
<NO NEED TO DOCUMENT THIS ANSWER>
this is a temporary design

TODO: VALIDATION — Define blank/length constraints for reference IDs, idempotency keys, wallet/asset refs, and reservation refs. Current precheck validates enums and positive amounts but not required strings
<NO NEED TO DOCUMENT THIS ANSWER>
no plan to do this for now, to small and detail, this is not a commercial project

TODO: REPLAY CONTRACT — Decide and test whether replays must reproduce status and reservation information exactly, especially when an asynchronous transaction progressed from PENDING to COMPLETED between calls.
<NO NEED TO DOCUMENT THIS ANSWER>
should be lastest data, will implement this in the future

TODO: TOKEN LABEL — Wallet processors generate owner tokens with GlobalServiceId.LEDGER.name() even though the Redis namespace is Wallet. Confirm whether this is harmless diagnostic text or a copy/paste defect before documenting token semantics.
<NO NEED TO DOCUMENT THIS ANSWER>
seems like a copy-paste defect, will fix in the future

TODO: TEST GAP — Add focused concurrent duplicate, duplicate-insert winner replay, Redis expiry/ownership, post-commit mark failure, same-key/different-request DB fallback, and response-equivalence tests.
<NO NEED TO DOCUMENT THIS ANSWER>
will add later 

TODO: FRESHNESS CONTRACT — State whether public snapshots are allowed to be cache-stale and bound expectations using evidence. Current design is eventual/best-effort cache coherence, not strict read-after-write.
this is expected, normal read can tolerate a short latency. real-time read will bypass cache