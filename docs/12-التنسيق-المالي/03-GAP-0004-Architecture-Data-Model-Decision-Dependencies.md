# GAP-0004 — Architecture / Data Model Decision Dependencies

**Status:** RESOLVED — ALL STAGE 12 DESIGN DEPENDENCIES APPROVED  
**Baseline:** `806292c7d59e6a949a38db3ec6031bd6e6fdeb37`

## DD-ST12-0001 — Orchestration ownership boundary
**Status: APPROVED / RESOLVED**

**Decision:** Finance-scoped Financial Orchestrator is the authoritative orchestration owner.

It coordinates across source-of-truth domains but does not replace Domain Owners, create authorization, or become Ledger owner. Financial recognition remains within Finance authority.

## DD-ST12-0002 — Workflow identity and durable boundary
**Status: APPROVED / RESOLVED**

**Decision:** Use an independent **WorkflowId** for business workflow identity.

GAP-0005 remains the durable operation identity / idempotency / replay-integrity layer. Stage 12A must not redefine GAP-0005 semantics.

Business workflow identity and durable operation/replay identity are explicitly separate concerns.

A durable workflow record/schema is NOT authorized by this decision and remains outside Stage 12A.

## DD-ST12-0003 — Canonical Settlement allocation cardinality
**Status: APPROVED / RESOLVED**

**Decision:** **ONE Settlement → ONE Obligation** for Stage 12A.

Multi-obligation allocation is outside scope and requires a separate future decision.

## DD-ST12-0004 — Recognition / Ledger creation authority
**Status: APPROVED / RESOLVED**

**Decision:** **Finance Recognition Command** is the only approved path capable of creating a financially final Ledger Entry.

The orchestrator invokes the command; Settlement cannot create Ledger directly, and a generic automatic event cannot create final Ledger truth.

## Rejected alternatives
- generic application-layer orchestration as authoritative owner;
- Payment/Settlement-owned cross-domain orchestration;
- reusing GAP-0005 as the business WorkflowId;
- PaymentId as WorkflowId;
- multi-obligation allocation in 12A;
- Settlement → Ledger direct creation;
- generic event → Ledger finality.

## Remaining architecture/data-model boundary
No unresolved Stage 12 dependency remains.

Any new persistence/schema/cardinality dependency discovered during implementation must be stopped, recorded as a new decision dependency, and approved before scope expands.

## Explicit non-dependencies
Providers, banks/wallets, FX, API/UI, cloud selection, sync/CRDT, new Refund/Chargeback concepts, legal/regulatory engine, and new authorization/Agent authority models are outside this gate.
