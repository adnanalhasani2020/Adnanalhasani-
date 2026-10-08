# DEC-ST11-0003 — GAP-0003 Option A Decision Record

**Decision ID:** DEC-ST11-0003  
**Decision Dependency:** DD-ST11-0003  
**Status:** DECIDED / APPROVED — HUMAN APPROVAL RECEIVED  
**Decision:** OPTION A — Explicit authoritative Grant → AgentAction binding  
**Baseline:** `8969fd2eb806b535e7f3e8bcf8bb200e6a6b4fe7`  
**Decision-dependency evidence:** `f31135e66a987e54b5f64327c744531ff3a6f9bc`  
**Related scope decision:** DEC-ST11-0001  
**Requirement:** R11-003  
**GAP:** GAP-0003

## 1. Decision

Recommend **OPTION A**: introduce an explicit authoritative relationship from an `AuthorizationGrant` to a specific `AgentAction`.

This is the minimum semantic change that preserves the currently approved R11-003 requirement that authorization must be explicitly bound and applicable to the governed action.

**Human approval:** Explicitly granted in the Stage 11 execution instruction. This approval authorizes only the minimum Data Model change and implementation scope defined in this record.

## 2. Why Option A

The bounded GAP-0003 implementation attempt established that `subject_id + action` cannot uniquely identify a governed action when multiple `AgentAction` instances share the same subject/action pair.

Option B would solve that ambiguity by changing authorization semantics from an explicit individual binding to reusable class/scope authorization. That would alter the approved R11-003 acceptance semantics.

Option A resolves the ambiguity without weakening R11-003: the authorization record itself becomes explicitly bound to the governed action.

## 3. Exact relationship semantics

### 3.1 Cardinality

- One `AgentAction` has **exactly one authoritative AuthorizationGrant binding** when the action is executable.
- One `AuthorizationGrant` may be bound to **many AgentActions**.
- The Grant remains the authorization source of truth; the binding identifies which governed actions the Grant authorizes.
- A Grant cannot be used for an AgentAction unless that AgentAction is explicitly bound to that Grant.

### 3.2 Binding identity

The binding identity is the pair:

`(authorization_grant_id, agent_action_id)`

The binding must reference the actual persistent identity of both domain objects. Subject/action equality is not a substitute for the binding.

### 3.3 Uniqueness

- At most one authoritative Grant is bound to a given AgentAction at a time.
- The same Grant may be bound to multiple AgentActions.
- A duplicate binding pair is rejected/idempotently prevented by the authoritative relation.
- No alternate inferred binding is permitted from `subject_id`, `action`, scope, or object similarity.

### 3.4 Lifecycle

A bound AgentAction is executable only when:

1. its authoritative Grant binding exists;
2. the bound Grant is `ACTIVE`;
3. the Grant is applicable to the action's governed scope/action;
4. the GAP-0001 Approval is valid and explicitly applicable to that AgentAction.

For lifecycle transitions:

- `ACTIVE` → executable, subject to Approval.
- `SUSPENDED` → execution rejected.
- `REVOKED` → execution rejected.
- `EXPIRED` → execution rejected.
- A transition back to `ACTIVE`, if permitted by the existing Grant lifecycle semantics, restores only authorization applicability; it does not bypass or create the required Approval.

No lifecycle transition may silently create or retarget a Grant→AgentAction binding.

### 3.5 Historical references

Historical AgentActions must retain the identity of the Grant that was bound to them.

A later Grant lifecycle transition must not rewrite historical binding identity. Historical records remain attributable to the Grant originally bound to the action.

If the domain later permits deletion/anonymization of authorization records, that policy must preserve the historical binding reference semantics; deletion behavior is not introduced by this decision.

### 3.6 Runtime enforcement

The governed execution boundary must require, in order:

1. the AgentAction exists and is the action being executed;
2. an authoritative Grant→AgentAction binding exists for that exact action;
3. the bound Grant is `ACTIVE`;
4. Grant applicability/scope/action constraints match the governed action;
5. the GAP-0001 Approval exists, is `APPROVED`, and references that exact AgentAction;
6. only then may the effect execute.

A Grant found by subject/action matching without an explicit binding is insufficient.

### 3.7 Rejection behavior

Any of the following must reject execution before the side effect/effect occurs:

- no Grant binding;
- unrelated Grant;
- Grant bound to another AgentAction;
- wrong action/scope/resource applicability;
- `SUSPENDED` Grant;
- `REVOKED` Grant;
- `EXPIRED` Grant;
- missing/invalid/mismatched GAP-0001 Approval.

The rejection must be side-effect free with respect to the governed effect.

### 3.8 Source-of-truth boundaries

- **AuthorizationGrant:** source of truth for authorization state and authorization scope.
- **Grant→AgentAction binding:** authoritative relation establishing applicability of that Grant to the specific executable action.
- **AgentAction:** source of truth for the executable action identity/state.
- **Approval:** source of truth for the GAP-0001 per-action approval gate.

The binding does not turn Approval into authorization, and Approval does not substitute for the Grant.

## 4. Persistence / Data Model implications

Option A necessarily introduces an authoritative Grant→AgentAction relation that is not currently represented by the existing `AuthorizationGrant` and `AgentAction` fields.

The minimum Data Model change is therefore:

- add a persistent relation carrying `authorization_grant_id` and `agent_action_id`;
- enforce the one-authoritative-binding-per-AgentAction invariant;
- preserve historical binding identity;
- make runtime execution resolve authorization through this relation.

The selected persistence representation for the current repository is the minimum domain-level relation: `AgentAction.authorization_grant_id`, referencing the authoritative `AuthorizationGrant.id`. The repository has no separate persistence/repository layer for these domain objects, so no join table, database schema, or migration is introduced. The binding field is immutable once set, preserving historical identity.

## 5. Compatibility with GAP-0001

Option A preserves GAP-0001 unchanged:

`Grant binding + ACTIVE authorization + valid Approval`

are jointly required for execution.

The Grant binding does not replace the Approval gate, and the Approval remains explicitly bound to the AgentAction.

## 6. Required test contract after approval

The implementation must include focused tests for:

- correct Grant → Action binding permits execution when Grant is ACTIVE and Approval is valid;
- wrong/unrelated Grant → Action rejects;
- no Grant binding rejects;
- scope/action mismatch rejects;
- ACTIVE → executable;
- SUSPENDED → reject;
- REVOKED → reject;
- EXPIRED → reject;
- rejected paths produce no governed side effect;
- GAP-0001 valid Approval remains required;
- mismatched/missing Approval remains rejected;
- multiple AgentActions may share a Grant without losing per-action binding identity;
- duplicate binding cannot create a second authoritative relation for the same AgentAction;
- historical AgentAction binding remains attributable to its original Grant.

## 7. Governance status

DEC-ST11-0001 explicitly states:

- implementation authority is limited to the approved Stage 11 scope/order;
- Architecture/Data Model authority is **NO**;
- any required Architecture/Data Model change is a separate Decision Dependency.

DD-ST11-0003 is resolved for implementation within the explicitly approved Option A scope.

## 8. Exact human decision required

**Approve the minimum Data Model semantic change defined here: an authoritative Grant→AgentAction relation with one authoritative Grant per AgentAction, Grant-to-many-AgentActions cardinality, identity by (authorization_grant_id, agent_action_id), preserved historical binding, and runtime enforcement of that exact binding.**

## 9. Execution record

Following explicit human approval, implementation proceeds only within this scope:

1. use the minimum domain-level persistence representation already present in the repository model;
2. update R11-003/GAP-0003 acceptance text only as necessary to encode the approved semantics;
3. implement the authoritative relation and runtime enforcement;
4. add focused lifecycle/security/compatibility tests;
5. run the Full Suite;
6. open an OPEN / NOT MERGED PR from the approved baseline.

No merge, release, tag, GAP-0004 work, or v1.0.0 modification is authorized by this record. The GAP-0003 implementation remains OPEN/NOT MERGED until reviewed.
