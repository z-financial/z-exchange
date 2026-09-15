# z-exchange Codex Documentation Kit

This kit is designed for generating a self-contained, interviewer-oriented documentation set **bottom-up** rather than asking Codex to summarize the whole codebase into one README in a single pass.

## Output boundary

The final entry page is the repository-root `./README.md`. All deeper reader-facing documents and their assets belong under `./design`.

Documents in `./design` may link only to other files in `./design`. Existing material in `./doc`, `./standard`, `./engineering-notes`, or `./codex` may help with research, but generated documents must validate and restate useful information instead of linking readers to those locations. Source/config/schema/test artifacts may be named as implementation evidence, but the `./design` set must explain the project without depending on external documentation. The root README links into this self-contained set.

## Core idea

The documentation is **read top-down** but should be **generated bottom-up**:

```text
source/config/tests/evidence
        ↓
verified detailed design docs
        ↓
module/subsystem overviews
        ↓
cross-system/business overview
        ↓
README.md
        ↓
final consistency audit
```

The detailed design documents become a compact, verified knowledge base. Higher-level documents should primarily synthesize those documents and only return to source code when a claim needs verification.

## Files

```text
DOCUMENTATION_CONTRACT.md
    Persistent project-specific rules. Every Codex documentation task should read this first.

templates/documentation-manifest.md
    Persistent state for the documentation effort: discovered designs, evidence, dependencies,
    status, uncertainty, and verification state.

documentation/qa.md
    Internal log of aligned, interview-useful confirmation questions, preserving the user's
    original answers alongside careful interview-ready versions.

prompts/01-discover-and-plan.md
    Repository investigation only. Produces/populates the manifest and proposes document boundaries.

prompts/02-write-design-doc.md
    Reusable prompt for one bounded detailed design document.

prompts/03-verify-design-doc.md
    Independent verification pass for a detailed design document.

prompts/04-write-module-overview.md
    Synthesizes verified design docs into a Ledger/Wallet/common-util subsystem overview.

prompts/05-write-system-overview.md
    Builds business and cross-service architecture/consistency documentation after module docs exist.

prompts/06-write-root-readme.md
    Generates the final interviewer-oriented documentation entry page at README.md last.

prompts/07-final-audit.md
    Audits claims, links, terminology, duplication, TODOs, and navigation across the documentation set.
```

## Recommended execution

### 0. Install the persistent files

Copy `DOCUMENTATION_CONTRACT.md` and `templates/documentation-manifest.md` into a stable repository location, for example:

```text
./codex/documentation/DOCUMENTATION_CONTRACT.md
./codex/documentation/documentation-manifest.md
```

If your repository already has an `AGENTS.md`, do not overwrite it blindly. You may instead add a short instruction there telling Codex to read the documentation contract before documentation work.

### 1. Discovery

Run `prompts/01-discover-and-plan.md`.

Do **not** ask Codex to write `README.md` at this stage. The output should be an evidence-backed documentation manifest and proposed detailed-document boundaries targeting `./design`.

For a very large repository, discovery may be performed module-by-module. Merge the findings into the same manifest.

### 2. Detailed designs

For each high-priority design in the manifest, start a bounded Codex task using `prompts/02-write-design-doc.md`.

Prefer one coherent design domain per task. Examples:

- Wallet transaction and reservation lifecycle
- Wallet balance/concurrency correctness
- Ledger posting and accounting correctness
- Idempotency and deduplication
- Kafka/outbox reliability and recovery
- Wallet ↔ Ledger consistency
- Caching architecture
- common-util infrastructure
- Observability
- Stress testing/performance

A design domain may contain several related topics. Do not turn every topic into a separate tiny Markdown file.

### 3. Verify each design

Run `prompts/03-verify-design-doc.md` on each important detailed document. A design should not be used as a primary source for higher-level summaries until its manifest status is `VERIFIED`, unless the uncertainty is explicitly preserved.

### 4. Module overviews

Once enough detailed designs for a module are verified, run `prompts/04-write-module-overview.md` for:

- Wallet Service
- Ledger Service
- common-util

These should summarize and navigate; they should not duplicate every detail.

### 5. Cross-system/business documentation

After Wallet + Ledger are both sufficiently understood, run `prompts/05-write-system-overview.md` to create the business model, end-to-end transaction flow, and cross-service correctness/consistency story.

### 6. Documentation entry README

Only after the previous layers exist, run `prompts/06-write-root-readme.md`.

`README.md` should give an interviewer a clear project entry point and should navigate only to documents and assets under `./design`.

### 7. Final audit

Run `prompts/07-final-audit.md` after all major documentation is present.

## Working rule for separate Codex sessions

Every documentation session should begin by reading:

```text
1. DOCUMENTATION_CONTRACT.md
2. documentation-manifest.md
3. qa.md when asking, resolving, or recording confirmation questions
4. only the existing `./design` design/module docs relevant to the current task
5. relevant source/config/tests/evidence needed to verify the current task
```

This avoids loading the entire documentation set and entire repository into one context unnecessarily.

## Manifest status flow

Use roughly:

```text
DISCOVERED
    ↓
IN_PROGRESS
    ↓
DRAFTED
    ↓
VERIFIED
```

Alternative states:

```text
BLOCKED                 important uncertainty prevents completion
NEEDS_REVERIFY          implementation changed after verification
DEFERRED                legitimate topic, intentionally postponed
MERGED                   folded into another design document
```

Higher-level documents should preferentially rely on `VERIFIED` designs.

When a user resolves a confirmation question that would also be useful in a technical interview, update `./codex/documentation/qa.md` during the same task. Preserve the original answer and keep the polished version faithful to it. This internal Q&A log must not be linked from documents under `./design`.
