# Proposal: Unique Ledger Reply Events and Wallet Outcome Convergence

> Status: possible future optimization; not implemented.
>
> Scope: Ledger reply-outbox identity and Wallet handling of repeated Ledger replies. This is a candidate direction, not a current guarantee.

## Decision under consideration

Treat every newly persisted Ledger reply as a distinct event instance:

- Generate a UUID when creating a new reply outbox row, including when the same Wallet request is processed again.
- Store that UUID as the row's `event_id` and reuse it for every Kafka publishing retry of that row.
- Keep `commandId` as the Wallet transaction correlation key and preserve the originating request identity as explicit causation data when the event contract is revised.
- Allow one request to produce multiple reply events, including failure followed by success and repeated successes.
- Advance Wallet business state only from a successful reply. Repeated or late replies must not reverse a completed transaction or apply its effects twice.

The UUID belongs to the durable reply event, not to a Kafka send attempt. Generating a new ID every time the outbox publisher retries would break the identity of the stored event and create unnecessary duplicates.

## Motivation

The current Ledger reply ID is deterministic from the request event and outcome. The outbox also verifies a duplicate ID by comparing the complete payload. This creates a false-conflict path: an initial successful Ledger result can contain diagnostic detail such as `success`, while an idempotent replay can contain `already exists`. Both represent success for the same transaction and receive the same deterministic ID, but their serialized payloads differ.

Making each persisted reply a separate event removes the need to decide whether two independently constructed payloads represent the same semantic reply. They are simply different observations correlated to the same Wallet transaction. Wallet then owns convergence through its transaction state rather than through reply-ID deduplication.

## Identity and idempotency boundaries

| Concern | Proposed owner and rule |
|---|---|
| Reply event identity | Ledger assigns one UUID to each newly persisted reply outbox row. |
| Outbox publishing retry | Reuses the UUID and payload stored in that row. |
| Accidental reuse of one UUID | Existing whole-payload `sameIntent` verification remains; any field or payload mismatch is a conflict. |
| Repeated processing of one request | May create multiple outbox rows with different UUIDs. This is not deduplicated in Ledger's outbox. |
| Transaction correlation | `commandId` continues to identify the Wallet transaction; request causation should not depend on parsing the reply UUID. |
| Business-effect idempotency | Wallet's database transaction and guarded state transition ensure that successful replies apply effects at most once. |

Under this model, complete-payload comparison is appropriate only when the exact same UUID is encountered again. A UUID identifies one concrete event instance, so the same ID with different bytes indicates accidental ID reuse, corruption, or an ambiguous insert reconstructed incorrectly. The comparison no longer claims that different reply events have different business intent.

## Reply lifecycle

| Observed sequence for one `commandId` | Required Wallet behavior |
|---|---|
| Failure only | Do not advance the transaction to success; retain enough operational state to detect that no success arrived. |
| Failure, then success | The failure must not block the later success; apply the successful transition once. |
| Success, then duplicate success | The first committed transition wins; later successes are acknowledged as harmless replays. |
| Success, then late failure | Do not reverse or poison the completed transaction. Record the contradictory observation if operationally useful. |
| Concurrent successes | Serialize or guard the database transition so balance/reservation effects cannot be applied twice. |

The rule “only success advances Wallet state” is necessary but not sufficient. If Ledger will never produce a success, Wallet can otherwise remain intermediate indefinitely. Terminal failure, compensation, timeout, alerting, and reconciliation therefore remain part of the future consistency design.

## Interaction with retry and DLT policy

The current Wallet reply handler treats a failed Ledger reply as listener failure and eventually sends it to DLT. With multiple uniquely identified replies, failure-then-success may be an expected sequence. Treating every failed business reply as a poison message would produce noisy DLT traffic and could obscure real deserialization or processing failures.

Before implementing this optimization, define separate handling for:

- a valid Ledger business-failure reply;
- a malformed or unsupported reply;
- a transient Wallet processing failure;
- a permanently unprocessable event;
- a Wallet transaction that never receives a successful reply.

DLT should retain a clear operational meaning rather than becoming the normal destination for valid negative outcomes.

## Trade-offs

| Benefit | Cost |
|---|---|
| Eliminates false conflicts caused by semantically equivalent payloads with different diagnostic detail. | Reprocessing one request can create additional database rows and Kafka messages. |
| Keeps event identity simple: one ID represents one durable event instance. | Ledger no longer deduplicates equivalent reply outcomes at the outbox boundary. |
| Allows failure and success observations to coexist without identity collision. | Wallet becomes the authoritative outcome-convergence and effect-idempotency boundary. |
| Keeps full-payload conflict detection meaningful for actual ID reuse. | Metrics and logs must distinguish expected repeated replies from abnormal duplication. |

## Superseded alternative

Do not introduce a persisted `intent_hash` or event-specific semantic-intent key solely to deduplicate Ledger reply rows. That approach requires canonicalizing business meaning, deciding which payload fields are diagnostic, and choosing which payload survives when equivalent events differ. It also preserves source-side logical deduplication that this proposal deliberately removes.

The candidate design instead uses:

- UUID identity for each durable reply event;
- exact payload verification only when that UUID is reused;
- Wallet transaction state for logical convergence and duplicate-effect prevention.

## Alternative plan: durable inbox/outbox

A broader alternative is to acknowledge Kafka after durably inserting an inbox row, then let a database worker own business processing, retries, reply-outbox creation, and inbox-state advancement. This prevents redelivery of the same source event from repeatedly entering the logical workflow, instead of allowing multiple replies and converging them in Wallet.

The inbox approach is potentially the stronger long-term reliability model, but it is a substantial refactor with new state, claim, retry, retention, monitoring, and recovery responsibilities. It also requires choosing whether business changes and reply-outbox creation become atomic or remain decoupled through an intermediate durable `BUSINESS_COMPLETED` state.

If the inbox design is selected, the UUID-per-reprocessing behavior proposed here is mostly unnecessary: one inbox item should materialize one logical reply outbox row, although that row can still use a normal unique event ID and must keep it across publishing retries. See [Durable Inbox/Outbox Processing Refactor](inbox-outbox-processing.md).

## Preconditions before implementation

1. Verify with concurrent tests that multiple successful replies cannot apply Wallet reservation, balance, snapshot, or status changes twice.
2. Define monotonic handling for failure-before-success and failure-after-success.
3. Define the terminal policy for a Wallet transaction that never receives success.
4. Separate valid negative replies from poison-message and infrastructure-failure DLT handling.
5. Preserve explicit request causation and `commandId` correlation after removing request identity from the reply ID.
6. Add load and operational tests for the extra outbox rows, Kafka traffic, and repeated-reply metrics.

Until these conditions are satisfied, the deterministic Ledger reply identity and its known payload-conflict flaw remain current behavior. The shared Kafka/outbox reliability model is documented in [Kafka Transport, Listener Durability, and Transactional-Outbox Recovery](../infrastructure/kafka-outbox-reliability.md).
