# z-exchange Documentation Contract

You are documenting the **z-exchange** backend project for technical interviewers.

This file contains persistent rules for all documentation tasks. Individual task prompts may narrow the scope, but must not weaken these rules.

---

# 1. Purpose

Present z-exchange as a serious backend/distributed-system design project rather than a source-code tour.

The documentation should demonstrate, where supported by the implementation and evidence:

- system design ability
- business understanding
- financial/data correctness
- distributed-system knowledge
- consistency and reliability design
- concurrency control
- failure handling and recovery
- performance engineering
- observability
- production-oriented infrastructure design

You may add other important engineering topics when they materially improve the documentation.

Never add a topic merely to sound sophisticated.

## Project positioning

z-exchange is not presented as a commercial production system. It is a technical project intended to implement and demonstrate techniques used in industry-level backend and distributed-system designs.

Documentation should therefore:

- emphasize the implemented engineering techniques, the problems they address, and the reasoning/trade-offs behind them;
- avoid claiming that the project has commercial-production maturity, operational history, compliance, or exhaustive hardening;
- assess robustness in proportion to the project's demonstration scope rather than implying that every possible production safeguard must be implemented;
- still identify material correctness gaps and unimplemented guarantees honestly, especially where they affect the techniques being demonstrated;
- distinguish a deliberate scope trade-off from an accidental defect or an unsupported production claim.

## Database enforcement boundary

The current project-wide policy is to keep domain and cross-row validation in application code rather than introduce additional database `CHECK` constraints, foreign keys, or posting/business stored procedures. This is an intentional scope and performance trade-off intended to avoid extra database coordination, locking, and potential deadlock risk; it is a design rationale, not a benchmark-proven performance claim.

Database safeguards retained for domain/invariant enforcement are:

- primary-key and unique-key enforcement;
- atomic conditional/CAS-like updates such as `UPDATE ... SET status = x WHERE status = y`.

Ordinary database transactions still provide atomic commit/rollback for related writes, but documentation must describe that separately from domain validation.

Documentation must distinguish application-enforced validation from database-enforced guarantees and must not imply that the schema independently prevents invalid domain rows. This policy may be revisited in a future version. It does not prohibit atomic SQL guards used to enforce balance or state-transition safety.

## Pre-launch configuration maturity

The project has not gone online, and its configuration values are not yet confirmed operating contracts. Values such as TTLs, batch/page sizes, retry counts, schedules, timeouts, concurrency, thread pools, and connection-pool sizes may be documented as current implementation snapshots, but not as validated, optimal, final, or production-safe settings.

Documentation must:

- distinguish the design purpose of a setting from the suitability of its current numeric value;
- qualify benchmark evidence with the exact configuration used at the time;
- avoid deriving a supported capacity, duration, or service guarantee solely from checked-in configuration;
- retain tuning and validation work as future evidence until representative testing exists;
- update configuration-sensitive conclusions when later test data or deployment requirements become available.

This rule does not prevent explaining a configuration principle—for example, that a tombstone TTL should cover the relevant in-flight stale-fill window—while leaving the concrete value undecided.

## Canonical publication boundary

The canonical project landing page is the repository-root `./README.md`. All deeper reader-facing documentation and documentation assets must live under `./design`.

This includes:

- the documentation landing page at `./README.md`;
- business, system, module, and subsystem overviews;
- detailed design documents;
- consistency, operations, observability, and performance documents;
- diagrams, screenshots, and other assets intended to be opened from those documents.

Documents under `./design` must form a self-contained documentation set:

- they may link only to other files under `./design`, using relative links;
- they must not link to, cite, or instruct the reader to open documentation outside `./design`, including `./doc`, `./standard`, `./engineering-notes`, or `./codex`;
- external or historical repository documents may be inspected during research, but their validated information must be restated in the appropriate canonical `./design` owner;
- if information from an external document cannot be validated from current implementation/evidence, preserve it as `TODO: NEED CONFIRMATION` rather than creating an external dependency;
- source code, configuration, schema, tests, and runtime artifacts may be named as implementation evidence, but the explanation must remain understandable without requiring the reader to open them;
- assets needed by a `./design` document must be copied or generated under `./design` rather than linked from another documentation tree.

The repository-root `README.md` is the sole reader-facing documentation file outside `./design`. It links into the self-contained `./design` set and prioritizes comprehension over a fixed reading-time limit.

---

# 2. Evidence rules

Before making important implementation claims, inspect the relevant:

- source code
- configuration
- tests
- database/schema/migration material
- stress-test code/results
- monitoring/dashboard artifacts
- `./standard`
- `./doc`
- `./codex`
- `./engineering-notes`

Existing documents may be outdated. Treat them as leads, not automatically as ground truth.

For each important claim, prefer evidence in approximately this order:

```text
current executable implementation/config/schema
    > current automated tests
    > current operational/stress-test evidence
    > current repository documentation
    > old/historical documentation
```

If sources disagree, describe the disagreement and follow the current implementation unless the task explicitly asks to document intended/future design.

Never invent:

- behavior
- guarantees
- benchmark numbers
- throughput/latency
- failure semantics
- reconciliation mechanisms
- transaction boundaries
- infrastructure usage
- adoption of common-util capabilities

If an important design is uncertain:

1. resolve it from source/tests/config when practical;
2. otherwise add a conspicuous `TODO: NEED CONFIRMATION` section;
3. ask the user only when the uncertainty materially blocks a correct document and cannot be responsibly represented as a TODO.

Clearly distinguish:

```text
CURRENTLY GUARANTEED
CURRENTLY MITIGATED / BEST-EFFORT
NOT IMPLEMENTED / FUTURE WORK
UNKNOWN / NEED CONFIRMATION
```

Do not turn intended design into implemented behavior.

---

# 3. Reader hierarchy vs generation hierarchy

The final documentation should be **read top-down**:

```text
README.md
    ↓
Business / subsystem overview
    ↓
Detailed design documents
    ↓
Monitoring / performance evidence
```

But it should be **generated bottom-up**:

```text
Repository investigation
    ↓
Documentation manifest
    ↓
Detailed design docs
    ↓
Verification
    ↓
Module/subsystem overviews
    ↓
Cross-system/business overview
    ↓
README.md
    ↓
Final audit
```

Do not regenerate a higher-level document directly from the entire repository when verified lower-level documentation already exists. Prefer synthesis from verified docs, then return to source code only to resolve uncertainty or validate critical claims.

---

# 4. Scope priority

The core business and technical design concentrates on **Wallet transactions and Ledger transactions**.

The following APIs primarily exist for business completeness/integrity:

```text
ledger-service: createAccount
wallet-service: createWallet
```

They are supporting initialization capabilities rather than the main engineering focus.

Explain their purpose briefly where useful, but do not spend significant documentation space on their implementation or present them as major project highlights.

Prioritize:

- Wallet transaction lifecycle
- Ledger transaction/posting lifecycle
- reserve / consume / release / transfer
- balance correctness
- ledger correctness
- Wallet ↔ Ledger interaction
- transaction state transitions
- transaction/database boundaries
- concurrency control
- locking and conditional updates
- idempotency and deduplication
- Kafka/outbox reliability
- failure/retry/recovery
- prevention of duplication and data loss
- prevention/handling of Wallet/Ledger inconsistency
- timeout/unknown-result handling
- caching correctness where relevant
- observability
- transaction performance and stress-test evidence

Allocate substantially more depth to transaction flows than account/wallet creation APIs.

---

# 5. Required design coverage

The exact document boundaries should be decided from the implementation. Do **not** mechanically create one file per bullet.

## Ledger areas

Where applicable, cover:

- business/accounting model
- architecture and boundaries
- data model
- main write/read flows
- posting lifecycle
- financial/ledger invariants
- database transactions
- idempotency
- Kafka integration
- transactional outbox
- retry and failure handling
- concurrency
- recovery
- performance considerations

## Wallet areas

Where applicable, cover:

- business model
- architecture and boundaries
- wallet/balance model
- transaction model
- reserve / consume / release / transfer
- reservation lifecycle
- data model
- locking/concurrency
- conditional updates
- idempotency
- caching
- Ledger interaction
- failure/recovery
- performance considerations

## Wallet ↔ Ledger consistency

This is a primary interview topic.

Explain how the actual system prevents, detects, mitigates, or fails to address:

- duplicated requests
- duplicated messages
- lost messages
- DB/Kafka dual-write inconsistency
- Wallet succeeded but Ledger failed
- Ledger succeeded but Wallet state was not finalized
- retries after partial failures
- consumer crashes
- timeout with unknown result
- concurrent wallet operations
- stale cache
- inconsistent balances / ledger records

Where supported, explain:

- transaction boundaries
- idempotency boundaries
- database unique constraints
- Redis idempotency acceleration
- transactional outbox
- Kafka at-least-once semantics
- application-level deduplication
- state transitions
- retry/recovery mechanisms
- reconciliation mechanisms
- actual guarantees
- remaining consistency risks
- future improvements

---

# 6. common-util

Document the reusable infrastructure layer where present:

```text
messaging        Kafka
caching          Redis
idempotency      Redis
database         MySQL
monitoring       Prometheus + Grafana
outbox           Outbox Table + Kafka
error handling   Exception model + customized Result
```

For each important capability explain:

- problem it solves
- abstraction/design
- important guarantees
- failure behavior
- trade-offs
- how services currently use it

Some common-util capabilities may exist but not be fully leveraged by Wallet/Ledger.

Still document valuable reusable capabilities, but classify them as:

```text
CURRENTLY USED
PARTIALLY ADOPTED
AVAILABLE BUT CURRENTLY UNUSED
POSSIBLE FUTURE USAGE
```

Do not imply adoption that does not exist.

---

# 7. Monitoring and performance evidence

## Monitoring

Where evidence exists, cover:

- service metrics
- JVM/container metrics
- latency
- throughput
- error rate
- Kafka
- Redis
- MySQL
- HikariCP
- CPU
- memory
- important Grafana panels
- how metrics diagnose bottlenecks, saturation, or failures

Use repository dashboard images/screenshots when useful and available, but copy or regenerate any published asset under `./design` before embedding it.

Explain why important panels matter rather than simply listing metric names.

## Stress testing

For Wallet/Ledger stress tests, where evidence exists, cover:

- environment
- dataset
- workload model
- hot/cold data distribution
- concurrency / arrival-rate pattern
- warm-up
- ramp-up
- saturation
- load reduction
- recovery
- APIs tested
- server/client configuration
- throughput
- p50/p95/p99
- timeout/error behavior
- CPU/memory
- DB pool behavior
- Redis/Kafka/MySQL behavior
- bottleneck analysis
- saturation point
- recovery behavior
- conclusions
- limitations

Never fabricate missing results.

Use tables/charts/screenshots/test artifacts when they make evidence easier to understand.

---

# 8. Writing model

For important designs, prefer this reasoning structure where applicable:

```text
Problem
→ Business requirements
→ Constraints / invariants
→ Design
→ Main flow / state transitions
→ Transaction & concurrency boundaries
→ Correctness guarantees
→ Failure scenarios / recovery
→ Trade-offs / alternatives
→ Observability
→ Performance
→ Limitations / future improvements
```

Do not force every section into every document. Include only what contributes meaningful information.

Prefer:

- concise Markdown
- directly rendered architecture/sequence/state diagrams when they materially improve understanding
- tables
- relative links between documents under `./design`
- actual class/table/module/API names where they clarify implementation
- explicit invariants
- concrete failure scenarios
- implementation evidence

Avoid:

- documenting every class
- package-by-package source-code narration
- generic textbook explanations
- repeating the same explanation across documents
- exaggerated claims
- marketing language
- excessive file fragmentation
- giant unstructured documents

For important decisions, explain **why the design was chosen, relevant alternatives, and trade-offs** when those can be responsibly inferred or are documented.

If the rationale is not knowable from the repository, do not invent it. Mark it as an inference or TODO.

## Reader-first presentation rules

- Place the document's primary architecture or lifecycle diagram in its opening overview section when that diagram provides the fastest useful mental model.
- Keep the opening **Design in one minute** section and its primary diagram focused on the intended architecture, responsibilities, main lifecycle, and core guarantees. Put design flaws, failure scenarios, recovery boundaries, and limitations in their dedicated later sections rather than in this first-look summary.
- Publish important diagrams as SVG assets under `./design/assets` and embed them directly in the owning document. Readers should not need Mermaid support or have to read diagram source. Mermaid may still be used as an authoring format before rendering.
- Keep responsibility descriptions positive and concise: state what each component owns. Add exclusions such as “does not own” only when the boundary is important enough to prevent a likely misunderstanding.
- Separate current design trade-offs from possible improvements; they communicate different aspects of the design.
- When the current design is coherent and complete for its stated scope, title the improvement section **Future enhancements** rather than **Remaining work**. Do not use this framing to hide a known correctness gap, unmet requirement, or unimplemented guarantee; classify those explicitly.

---

# 9. Documentation boundaries and links

Create a small number of substantial documents.

Do not mechanically create files such as:

```text
write-flow.md
read-flow.md
locking.md
retry.md
...
```

when those topics naturally belong in the same coherent design document.

Each detailed document should have a clear ownership boundary. When a concept is owned by another document:

- summarize it in one or two sentences if needed for context;
- link to the canonical document under `./design`;
- do not duplicate the full explanation.

Higher-level documents should behave as maps and summaries, not copies of lower-level documents.

No `./design` document may depend on documentation outside `./design`. When useful information exists only in `./doc`, `./standard`, `./engineering-notes`, `./codex`, or another external documentation location, validate it and incorporate the supported explanation into the owning `./design` document. External documentation paths may remain in the manifest as research provenance, but must not appear as reader dependencies in generated documentation.

---

# 10. Interviewer reading depths

The final result should support three depths:

```text
Entry page        README.md
5–10 minutes     Business/subsystem overviews
Deep dive        Detailed architecture, consistency, infrastructure,
                 observability, and performance docs
```

`README.md` should surface the project's strongest verified engineering signals clearly and provide navigation to deeper documents and assets within `./design`.

---

# 11. Manifest discipline

All documentation work must use `documentation-manifest.md` as persistent state.

For the current task:

1. read the manifest first;
2. update affected entries before/after work;
3. record important implementation evidence;
4. record dependencies on other docs/designs;
5. record TODOs/uncertainty;
6. set the correct status;
7. do not mark `VERIFIED` until an explicit verification pass checks important claims against implementation/evidence.

Preferred statuses:

```text
DISCOVERED
IN_PROGRESS
DRAFTED
VERIFIED
BLOCKED
NEEDS_REVERIFY
DEFERRED
MERGED
```

If implementation changes invalidate a verified document, mark it `NEEDS_REVERIFY` rather than silently leaving stale documentation.

---

# 12. Interview confirmation Q&A discipline

Use `./codex/documentation/qa.md` as the persistent log for confirmation questions that are resolved with the user and could be useful in a technical interview.

When such a question is aligned, update `qa.md` in the same documentation task. Every entry must contain:

- **Question**
- **My original answer**
- **Polished interview-ready answer**
- **Related design/module**
- **Follow-up questions an interviewer may ask**

Preserve the user's actual design decision and intent:

- keep **My original answer** verbatim apart from harmless Markdown formatting;
- improve only clarity, structure, terminology, and technical precision in the polished answer;
- do not add guarantees, recovery mechanisms, implementation details, rationale, or future commitments that the user did not state or that current evidence does not support;
- distinguish current behavior from intended optimization or future work;
- retain uncertainty when the answer is incomplete;
- do not create an entry for an unanswered question;
- do not create or retain an entry when the question or answer is marked `<NO NEED TO DOCUMENT THIS ANSWER>`;
- update an existing matching entry instead of creating duplicates.

`qa.md` is internal working material under `./codex`, not part of the published `./design` set. Documents under `./design` must not link to or cite it. A confirmed decision may be incorporated into the appropriate `./design` document, but it does not become an implementation guarantee or make a design `VERIFIED` without supporting evidence.
