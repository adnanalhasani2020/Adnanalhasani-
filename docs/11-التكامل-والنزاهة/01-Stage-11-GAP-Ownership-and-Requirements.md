# Stage 11 — GAP Ownership and Requirements

**Baseline:** `b1ea7e30b9a0c7409bb2543b212308e6df4bc946`

## Ownership Matrix

| GAP | Capability | Stage 11 Owner Track | Priority | Dependency |
|---|---|---|---|---|
| GAP-0002 | Runtime semantic existence / ownership / provenance | Track A — Semantic Integrity | P0 | Foundation |
| GAP-0005 | Persistent replay / idempotency | Track B — Durable Operation Integrity | P0 | Foundation |
| GAP-0006 | Identity uniqueness / dedup / merge | Track C — Identity Resolution | P1 | GAP-0002 + GAP-0005 foundations |
| GAP-0001 | Approval enforcement | Track D — Authority Enforcement | P1 | GAP-0006 |
| GAP-0003 | AuthorizationGrant → AgentAction binding | Track D — Authority Enforcement | P1 | GAP-0001 |

**GAP-0004:** Deferred owner track for a future stage; no Stage 11 implementation ownership.

## Requirements and Testable Acceptance Criteria

### GAP-0002 — Semantic Integrity
**Requirement R11-002:** Runtime operations must validate semantic existence and applicable ownership/provenance constraints rather than treating UUID/type validity as sufficient truth.

**Acceptance:**
- A reference to a nonexistent entity is rejected at the governed boundary.
- A reference to an entity outside the allowed ownership/domain boundary is rejected.
- Where provenance is a required invariant, a provenance-invalid operation is rejected.
- Negative tests demonstrate rejection; positive tests demonstrate valid references continue to work.
- Evidence identifies the exact runtime boundary and invariant enforced.

### GAP-0005 — Durable Operation Integrity
**Requirement R11-005:** Governed operations must have persistent operation identity with deterministic replay/idempotency semantics.

**Acceptance:**
- Repeating the same operation identity does not create a second semantic effect.
- A replay after process/runtime restart remains detectable where persistence is in scope.
- Conflicting reuse of an operation identity is rejected or deterministically handled.
- Tests cover first submission, duplicate submission, replay, and conflicting reuse.
- Persistence evidence identifies the authoritative store/state used for replay detection.

### GAP-0006 — Identity Resolution
**Requirement R11-006:** The system must define and enforce runtime identity uniqueness, duplicate detection, and controlled merge semantics for the governed identity scope.

**Acceptance:**
- Duplicate identity candidates are detected according to an explicit uniqueness rule.
- Valid distinct identities remain distinguishable.
- A merge is allowed only under explicit policy and preserves historical references.
- References to the merged identity resolve according to the approved policy.
- Negative tests cover ambiguous/unauthorized merges.

### GAP-0001 — Approval Enforcement
**Requirement R11-001:** AgentAction execution must be rejected unless the required Approval condition is actually satisfied and applicable to that action.

**Acceptance:**
- An action requiring approval cannot execute without valid approval.
- An invalid, revoked, expired, or mismatched approval cannot authorize execution where those states are in scope.
- A valid applicable approval permits only the governed action/scope.
- Tests cover missing, invalid/mismatched, and valid approval paths.
- The runtime enforcement point is directly evidenced.

### GAP-0003 — AuthorizationGrant → AgentAction Binding
**Requirement R11-003:** An AgentAction must not derive executable authority merely from the existence of an AuthorizationGrant; the grant must be explicitly bound and applicable to the action.

**Acceptance:**
- An unrelated grant cannot authorize an AgentAction.
- Scope/resource/action mismatch is rejected.
- A valid grant bound to the governed action permits execution only within its scope.
- Tests cover absent, unrelated, mismatched, and valid bindings.
- The binding is enforced at runtime, not only represented structurally.

## Cross-Cutting Requirement

All five in-scope GAPs require:
- explicit runtime boundary identification;
- negative and positive tests;
- evidence traceability;
- no broader claim than the tested scope;
- preservation of v1.0.0 bounded claims.
