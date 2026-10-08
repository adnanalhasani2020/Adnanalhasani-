# Stage 11 — GAP-0003 Decision Dependency

**Decision Dependency ID:** DD-ST11-0003
**Status:** DECISION REQUIRED — IMPLEMENTATION BLOCKED
**Baseline:** `8969fd2eb806b535e7f3e8bcf8bb200e6a6b4fe7`
**Related Decision:** DEC-ST11-0001
**Requirement:** R11-003
**GAP:** GAP-0003 — AuthorizationGrant → AgentAction binding

## 1. Trigger

The first bounded GAP-0003 implementation attempt demonstrated that `AuthorizationGrant.subject_id + action` cannot identify a specific `AgentAction` when multiple actions share the same subject/action pair.

The attempted implementation was reverted. No implementation is accepted by this record.

## 2. Governance authority

DEC-ST11-0001 grants limited implementation authority for the approved Stage 11 scope, but explicitly states:

- no Architecture/Data Model change is authorized by that decision;
- any Architecture/Data Model semantic change is a separate Decision Dependency;
- R11-003 acceptance criteria are approved for implementation planning and currently require an explicitly bound/applicable grant.

Therefore the implementation agent is **not authorized to reinterpret R11-003 from explicit action binding to class/scope authorization**.

## 3. Option A — Explicit authoritative Grant → AgentAction binding

**Status: DECISION REQUIRED**

Semantic model:
- AuthorizationGrant is an authorization record.
- A governed executable action has an authoritative relationship to the Grant that authorizes it.
- The relationship must define identity/cardinality, lifecycle behavior, historical reference behavior, persistence representation, and runtime enforcement.

Advantages:
- directly satisfies the current R11-003 wording;
- removes ambiguity when multiple AgentActions share subject/action;
- gives runtime enforcement an authoritative binding identity;
- preserves the distinction between authorization and approval.

Required decision detail before implementation:
- whether one Grant may authorize many AgentActions;
- whether one AgentAction may reference one or multiple Grants;
- how grant suspension/revocation/expiration affects already-bound actions;
- how historical references behave;
- whether the relation requires persistence/schema/migration;
- exact runtime enforcement point.

No such relation is introduced by this record.

## 4. Option B — Grant authorizes a class/scope of actions

**Status: REJECTED FOR IMPLEMENTATION UNDER CURRENT GOVERNANCE**

Semantics would be:
- `subject_id + action + scope + valid lifecycle` authorizes any matching AgentAction;
- two distinct AgentActions with identical subject/action are intentionally covered by the same Grant;
- no Grant identity is bound to an individual AgentAction;
- GAP-0001 Approval remains individually bound to the AgentAction.

This is technically coherent and minimizes architectural change, but it is not merely an implementation detail: it changes the current R11-003 acceptance criterion from explicit binding to class/scope applicability.

Adopting Option B therefore requires an explicit decision that changes/clarifies the approved requirement and acceptance semantics. The current DEC-ST11-0001 does not grant that authority.

## 5. Security and Source of Truth

The current model separates:
- AuthorizationGrant = authorization source of truth;
- AgentAction = executable action source of truth;
- Approval = per-action approval gate under GAP-0001.

Option A preserves explicit authorization identity.
Option B deliberately defines authorization as a reusable scope rather than per-action binding.

Neither option is implemented by this record.

## 6. Required decision

A governance-authorized decision must explicitly select one of:

**A — Explicit Grant → AgentAction binding**, with its domain/data-model semantics defined before implementation;

or

**B — Scope/class authorization**, with R11-003 and GAP-0003 acceptance criteria explicitly revised to state that no individual Grant identity binding is required.

Until that decision is recorded, GAP-0003 implementation remains blocked.

## 7. Hard boundaries

- No src/ changes.
- No tests changed.
- No GAP-0004.
- No new Stage.
- No release.
- `v1.0.0` unchanged.
- GAP-0001/0002/0005/0006 semantics unchanged.
- No new authorization authority.
- No merge of GAP-0003.
