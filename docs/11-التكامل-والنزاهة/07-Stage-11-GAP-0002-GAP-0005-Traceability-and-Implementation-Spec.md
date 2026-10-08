# Stage 11 — GAP-0002 + GAP-0005 Traceability & Implementation Specification

**Status:** APPROVED FOR IMPLEMENTATION
**Decision baseline:** e354ddf71c2408e374a144bb4d926e43684b44f3

## 1. GAP-0002 Traceability

| Decision | Requirement | Specification | Implementation Target | Test Case | Evidence |
|---|---|---|---|---|---|
| DEC-ST11-0002 | R11-002 | Semantic Authority resolves authoritative entity and owner; provenance is conditional by operation spec | One runtime semantic enforcement gateway before semantic effect | existing entity succeeds; missing entity rejects; owner mismatch rejects; required provenance missing/invalid rejects; UUID/type-only reference rejects | runtime test results + boundary evidence + diff/commit |
| DEC-ST11-0002 | R11-002 | Domain constructors remain structural validation only | Reference resolution must occur at governed application runtime boundary | positive valid reference; negative nonexistent/wrong-owner/provenance cases | targeted test output |

Implementation invariants:
1. authoritative source is domain owner, not payload/cache/audit;
2. UUID/type correctness is necessary at most, never sufficient;
3. not-found, owner-mismatch, and required-provenance failures are deterministic rejections;
4. no state mutation occurs before the semantic gate.

## 2. GAP-0005 Traceability

| Decision | Requirement | Specification | Implementation Target | Test Case | Evidence |
|---|---|---|---|---|---|
| DEC-ST11-0003 | R11-005 | Durable operation record keyed by operation identity and compared by canonical request fingerprint | One runtime durable-operation enforcement gateway before semantic effect | first submission; same identity/same fingerprint duplicate; restart replay; same identity/different fingerprint conflict | persistence/restart test output + authoritative-state evidence + diff/commit |
| DEC-ST11-0003 | R11-005 | Durable authority returns stored deterministic outcome for idempotent replay | No second semantic effect on duplicate | effect count remains one; replay returns same outcome | targeted test output |

Implementation invariants:
1. operation key is namespace + operation_id;
2. fingerprint detects conflicting reuse; it does not replace identity;
3. duplicate never produces a second semantic effect;
4. conflict never overwrites the original record;
5. replay remains detectable after process restart;
6. only durable operation authority decides duplicate/conflict.

## 3. Required implementation boundary

The implementation may introduce only the minimum runtime abstractions required to realize these contracts. It must not:
- implement GAP-0006, GAP-0001, GAP-0003, or GAP-0004;
- alter v1.0.0 claims;
- modify release/tag/main;
- introduce provider/cloud/API/UI work;
- choose a storage technology unless separately approved.

## 4. Required Architecture/Data Model follow-up before or with implementation

The following are explicit design boundaries, not implementation work:
- a semantic authority/repository contract must map each governed entity type to its existing domain owner;
- a provenance applicability relation may be needed where provenance is required;
- a durable operation record becomes a first-class persistence concept;
- uniqueness/atomicity constraints must prevent two first submissions of the same operation key from both becoming effective.

If any of these require changing an approved domain owner or creating a new cross-domain truth relation, STOP and create a new Decision Dependency.

## 5. Acceptance mapping

### GAP-0002
READY when implementation demonstrates all:
- valid authoritative reference accepted;
- nonexistent reference rejected;
- wrong-owner reference rejected;
- required provenance missing/invalid rejected;
- UUID/type-valid but semantically absent reference rejected;
- enforcement occurs before semantic effect.

### GAP-0005
READY when implementation demonstrates all:
- first submission accepted once;
- exact repeat returns deterministic prior outcome without second effect;
- replay after restart detected;
- same operation identity with changed fingerprint is CONFLICT;
- original durable record remains authoritative;
- enforcement occurs before semantic effect.

No implementation is included in this document.
