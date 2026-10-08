> **Historical design-time record.** This document records the approved Stage 12 design gate that preceded Stage 12A. Current repository state is Stage 12A **CLOSED / SATISFIED**; Durable Financial Workflow is **DEFERRED**; Stage 12B is **NOT AUTHORIZED / NOT STARTED**. This reconciliation preserves the original design meaning and decisions; it does not authorize new implementation.

# Stage 12 — Decision Record

**Decision ID:** DEC-ST12-0001  
**Status:** APPROVED / DESIGN-READY  
**Baseline:** `806292c7d59e6a949a38db3ec6031bd6e6fdeb37`

## Decision
Stage 12 is approved and closed as the **Scope + Architecture Decision Gate for GAP-0004**.

The human approval explicitly resolves DD-ST12-0001 through DD-ST12-0004.

## Human-approved decisions

### DD-ST12-0001 — Orchestrator Ownership
**APPROVED**

Finance-scoped **Financial Orchestrator** is the authoritative owner of financial orchestration.

Boundaries:
- coordinates workflows across existing sources of truth;
- does not replace Domain Owners;
- does not create authorization;
- does not own the Ledger;
- financial recognition remains under Finance authority.

### DD-ST12-0002 — Workflow Identity / Durable Boundary
**APPROVED**

Use an independent **WorkflowId** for business workflow identity.

GAP-0005 remains the durable operation identity / idempotency / replay-integrity layer.

Business workflow identity and durable operation/replay identity are separate concerns. Stage 12A must not redefine GAP-0005 semantics.

No durable workflow schema or persistence implementation is authorized by this decision.

### DD-ST12-0003 — Settlement Cardinality
**APPROVED**

For Stage 12A:
**ONE Settlement → ONE Obligation.**

Multi-obligation allocation is outside scope and requires a separate future decision.

### DD-ST12-0004 — Ledger Creation Authority
**APPROVED**

**Finance Recognition Command** is the only approved path capable of creating a financially final Ledger Entry.

There is no Settlement → Ledger direct path and no generic automatic event → Ledger finality path.

## Approved GAP-0004 contract

Canonical path:

**Payment → Settlement → Financial Transaction → Ledger Entry → Balance (derived)**

Required controls:
- exact Payment/Settlement/Obligation matching;
- Finance recognition boundary;
- WorkflowId;
- GAP-0005 idempotency/replay integration without semantic redefinition;
- AuthorizationGrant → exact AgentAction binding → GAP-0001 Approval;
- failure/cancel/reversal/correction semantics;
- offline finality rejection;
- historical/provenance preservation;
- Balance remains derived.

## Stage 12A implementation authority
The four design decisions authorize **design closure and preparation of the implementation entry package only**.

They do **not** authorize:
- `src/` implementation;
- persistence/schema implementation;
- migration;
- provider integration;
- API/UI;
- Stage 12B;
- release/tag;
- v1.0.0 changes;
- Production Readiness;
- autonomous financial authority;
- new financial or authorization concepts.

A separate explicit implementation authorization is required before code or persistence changes begin.

## Stage 12 status
**DESIGN-READY.**

All Stage 12 decision dependencies are resolved. The implementation boundary is frozen at Stage 12A as documented in the entry package.

## Closure evidence
- Decision dependencies: `03-GAP-0004-Architecture-Data-Model-Decision-Dependencies.md`
- Exit conditions: `05-Dependency-Graph-and-Stage-12-Exit-Conditions.md`
- Contract: `01-Financial-Orchestration-Contract.md`
- Source of Truth: `02-Source-of-Truth-Matrix.md`
- Acceptance / threat matrix: `04-GAP-0004-Acceptance-Criteria-and-Threat-Failure-Matrix.md`
- Implementation entry package: `07-Stage-12A-Implementation-Entry-Package.md`

## Final outcome
**Stage 12 = DESIGN-READY.**

No implementation was started in this cycle. Main is unchanged. PR #21 remains a separate governance PR and is not merged by this decision record.
