# Z-Exchange — Project Hints (LLM-Optimized)

## System Mental Model
- Wallet = user-facing state machine (reserve → consume → settle/release)
- Ledger = passive double-entry journal (no business rules)
- Outbox + Kafka = eventual consistency bridge between services

---

## Core Invariants (MUST HOLD)
- Double-entry: sum(debit) == sum(credit) per transaction
- No double-spend: consume must be backed by prior reserve
- Non-negative balances enforced at DB level (atomic SQL guards)
- Reservation invariant: total == remaining + consumed + pendingSettle + released
- Idempotency: same key + same request → same result (no duplicate effects)

---

## Service Boundaries
- Wallet owns balances, reservations, and transaction decisions
- Ledger owns accounting records and balance correctness per transaction
- Ledger does NOT enforce business constraints (wallet is gatekeeper)
- Wallet delegates all accounting to ledger via async outbox
- Common-util is infra only (no domain logic)

---

## Critical Design Decisions (WITH INTENT)
- Async wallet-ledger choreography (no blocking, eventual consistency)
- Two-bucket balance model (available vs reserved for in-flight funds)
- Outbox pattern for all cross-service messaging (atomic with DB)
- Redis + DB layered idempotency (performance + correctness)
- Versioned cache for mutable data, stable cache for immutable mapping

---

## Dangerous Areas (HIGH RISK)
- Balance updates rely on SQL atomic guards — never move to application checks
- Outbox must be in SAME DB transaction as business state change
- Redis Lua scripts enforce atomicity — do not replace with multi-commands
- Reservation field updates must preserve invariant at all times
- Cache format and CAS logic must stay consistent with Lua scripts
- Locking/order of updates must prevent deadlocks (consistent ordering)

---

## Allowed vs Forbidden Patterns

### Allowed
- DB-enforced invariants (NOT application-only validation)
- Cache failure → fallback to DB (never fail business flow)
- INSERT IGNORE / unique key for idempotent persistence
- Outbox-driven async communication (no direct Kafka in business logic)

### Forbidden
- Application-only balance checks (must rely on DB atomic guard)
- Direct Kafka publish without outbox
- Unconditional cache overwrite for frequently mutable data (must use CAS)
- Floating-point monetary values (use integer minor units only)
- Sharing transaction context across threads

---

## System Assumptions

### Failure Model
- Redis failure → degrade to DB (correct but slower)
- Kafka failure → delayed processing (eventual recovery via outbox)
- DB failure → system unavailable (single source of truth)

### Delivery Guarantees
- Kafka/outbox = at-least-once (consumers must be idempotent)
- gRPC = retryable, caller must ensure idempotency
- DB transactions = exactly-once state change

### Ordering
- Per-transaction ordering guaranteed (by txnId)
- No global ordering across transactions

---

## Anti-Misguidance Rules (IMPORTANT)
- Do NOT enforce strong consistency between wallet and ledger (eventual by design)
- Do NOT move accounting logic into wallet for “simplicity”
- Do NOT remove Redis idempotency layer (DB-only is insufficient for hot path)
- Do NOT bypass DB atomic guards with application validation
- Do NOT simplify outbox pattern at the cost of delivery guarantees

---

## Code Comments
- Add concise comments for important or complex logic, especially invariants, failure handling, concurrency, and non-obvious design intent.
- Avoid comments that merely restate what the code already makes clear.
