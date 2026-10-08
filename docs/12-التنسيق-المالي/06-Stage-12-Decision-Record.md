# Stage 12 — Decision Record

**Decision ID:** DEC-ST12-0001  
**Status:** DECISION PACKAGE — HUMAN APPROVAL REQUIRED  
**Baseline:** `806292c7d59e6a949a38db3ec6031bd6e6fdeb37`

## Decision
Open Stage 12 as a Scope + Architecture Decision Gate for GAP-0004 only.

## Approved by existing human authorization
- Stage 12 governance/design work is authorized.
- No src/persistence/schema/provider implementation is authorized.
- No release/v1.0.0 change is authorized.
- No Production Readiness claim is authorized.

## Proposed contract
Canonical recognition path:
**Payment → Settlement → Financial Transaction → Ledger Entry → Balance(derived)**

Recommended:
- Finance-scoped Financial Orchestrator.
- WorkflowId distinct from GAP-0005 operation identity, with GAP-0005 reused for durable replay/idempotency.
- Settlement explicitly references one Obligation for 12A.
- Finance recognition boundary is the sole final Ledger creation authority.
- Offline cannot produce financial finality.
- AuthorizationGrant → exact AgentAction binding → GAP-0001 Approval → orchestration.

## Human decisions required
1. **DD-ST12-0001:** dedicated Finance-scoped orchestrator vs application coordinator.
   **Recommendation: Finance-scoped orchestrator.**
2. **DD-ST12-0002:** workflow identity / durable boundary.
   **Recommendation: distinct WorkflowId mapped to GAP-0005 durable operation identity.**
3. **DD-ST12-0003:** Settlement→Obligation cardinality.
   **Recommendation: one Settlement → one Obligation for 12A.**
4. **DD-ST12-0004:** Ledger creation authority.
   **Recommendation: Finance recognition command invoked by orchestrator.**

## Stage 12 determination
The design can be made internally coherent without adding a new financial concept, but it is **not yet DESIGN-READY** because these four authority/cardinality/persistence decisions have executable architectural consequences.

## Explicit non-decisions
No provider, schema, migration, persistence implementation, API, UI, sync algorithm, legal/regulatory model, or new authorization concept is decided here.

## Outcome
**Stage 12 remains OPEN pending human resolution of DD-ST12-0001..0004.**
