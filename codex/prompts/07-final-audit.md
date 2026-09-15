# Codex Task — Final Documentation Consistency Audit

Read first:

1. `./codex/documentation/DOCUMENTATION_CONTRACT.md`
2. `./codex/documentation/documentation-manifest.md`
3. `./README.md`
4. all module/system overview docs
5. all P0/P1 canonical detailed docs
6. evidence/performance docs relevant to claims made above
7. `./codex/documentation/qa.md` for confirmed decisions relevant to the audited docs

## Goal

Audit the finished documentation as a **documentation system**, not as isolated Markdown files.

Treat the repository-root `./README.md` plus `./design` as the complete reader-facing documentation set, while using source/config/schema/tests as verification evidence. The README may link only into `./design`; no `./design` document may link to, cite, or direct readers to documentation or assets outside `./design`.

Do not rewrite everything stylistically. Prioritize correctness, consistency, navigation, and information architecture.

## Audit dimensions

### 1. Claim consistency

Find contradictions involving:

- transaction lifecycle
- state names/transitions
- transaction boundaries
- concurrency/locking
- idempotency
- outbox/Kafka semantics
- failure/retry behavior
- caching behavior
- Wallet ↔ Ledger guarantees
- common-util adoption
- performance numbers/environment

Canonical detailed docs should win only if they are still verified. If evidence suggests they are stale, mark `NEEDS_REVERIFY` and fix the source of truth first.

### 2. Guarantee language

Search documentation for strong terms such as:

```text
exactly-once
no data loss
guarantee
guaranteed
atomic
consistent
never
always
production-grade
highly scalable
```

Verify that the precise wording is justified.

Downgrade or qualify unsupported language.

### 3. Terminology

Ensure canonical domain terms are consistent across `README.md`, module docs, diagrams, tables, and design docs.

Update the manifest terminology table when needed.

### 4. Duplication / ownership

Find explanations repeated across multiple documents.

Choose the best canonical owner, keep a concise summary elsewhere, and replace duplicated detail with links.

### 5. Navigation

Check:

- root `README.md` links
- module overview links
- cross-links from detailed docs
- relative paths
- anchors where used
- missing documents
- links to merged/deferred paths
- any documentation or asset link that escapes `./design`

The reader should be able to move:

```text
README.md → overview → detailed design → internal evidence/asset
```

and back naturally.

### 6. Interview readability

Check the three reading depths:

**Entry page:** Can `README.md` communicate what the system is and why it is technically interesting?

**5–10 minutes:** Can subsystem/system overviews explain responsibilities, flows, and major design choices without requiring source-code reading?

**Deep dive:** Do detailed docs answer correctness/failure/trade-off questions with concrete implementation grounding?

### 7. TODO / uncertainty hygiene

Collect all:

- `TODO: NEED CONFIRMATION`
- `BLOCKED`
- `NEEDS_REVERIFY`
- unresolved P0/P1 questions

Do not delete them merely to make documentation look finished.

Ensure each unresolved issue is either:

- visible in the appropriate doc;
- recorded in the manifest;
- intentionally omitted from higher-level claims.

### 8. Performance evidence

For every number in `README.md` or overview docs, verify:

- metric meaning
- environment
- workload
- source artifact
- whether it is measured vs estimated

Remove numbers that cannot be traced to evidence.

## End-of-task output

1. Fix clear documentation inconsistencies and broken links.
2. Update affected manifest statuses.
3. Produce a compact audit report containing:
   - critical corrections made
   - remaining P0/P1 uncertainty
   - docs marked `NEEDS_REVERIFY`
   - duplicated sections consolidated
   - broken links fixed
   - unsupported claims removed/downgraded
4. Do not mark the whole documentation effort complete if material P0 correctness questions remain unresolved.
5. Update `./codex/documentation/qa.md` for any interview-useful confirmations resolved during the audit, without treating those answers alone as implementation verification.
