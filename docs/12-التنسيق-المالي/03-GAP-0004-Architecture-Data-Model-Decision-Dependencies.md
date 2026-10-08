# GAP-0004 — Architecture / Data Model Decision Dependencies

**Status:** OPEN — HUMAN DECISION REQUIRED FOR IMPLEMENTATION DESIGN  
**Baseline:** `806292c7d59e6a949a38db3ec6031bd6e6fdeb37`

## DD-ST12-0001 — Orchestration ownership boundary
**Problem:** Current model defines Finance ownership of recognition/Ledger but has no authoritative orchestration owner.

**Why insufficient:** A workflow spanning Payment, Settlement, Obligation and Finance needs one coordination boundary without transferring source-of-truth ownership.

**Minimum change:** Define one orchestration boundary; do not add a new financial truth.

**Alternatives:**
A. Dedicated Financial Orchestrator inside Finance.
B. Generic application-layer coordinator outside Finance.
C. Payment/Settlement owns cross-domain orchestration.

**Recommendation:** **A.** Finance owns recognition and Ledger; a Finance-scoped orchestrator minimizes cross-domain authority ambiguity.

**Persistence impact:** none at decision stage; later implementation may require workflow state.

**Security impact:** must enforce existing Grant → AgentAction → Approval chain.

**Test impact:** cross-domain positive/negative and authorization tests.

**Human approval required:** YES — ownership is an architectural authority decision.

---

## DD-ST12-0002 — Workflow identity and durable boundary
**Problem:** Current GAP-0005 operation identity exists, but GAP-0004 needs a canonical financial workflow identity spanning Payment/Settlement/Obligation and recognition.

**Why insufficient:** Payment IDs and Settlement IDs alone do not identify the orchestration attempt as one semantic unit.

**Minimum change:** Introduce a WorkflowId at the orchestration boundary and define its relationship to existing durable operation identity.

**Alternatives:**
A. Reuse GAP-0005 operation identity directly.
B. Introduce WorkflowId and map/reuse GAP-0005 idempotency underneath.
C. Use PaymentId as workflow identity.

**Recommendation:** **B.** Workflow identity expresses business orchestration; GAP-0005 operation identity remains the durable replay/idempotency mechanism.

**Persistence impact:** likely persistent workflow record if restart/recovery is in scope; schema shape must be designed separately.

**Security impact:** workflow identity cannot grant authority.

**Test impact:** restart, duplicate, conflict, concurrency and stale workflow tests.

**Human approval required:** YES if persistence/cardinality is introduced; the conceptual identity itself is a design recommendation.

---

## DD-ST12-0003 — Canonical Settlement allocation cardinality
**Problem:** Existing semantics permit partial payment/settlement but do not define the implementation cardinality for allocating Settlement to Obligation.

**Why insufficient:** Matching by amount/time/user is unsafe and does not prove which obligation is settled.

**Minimum change:** Settlement explicitly references Obligation; exact allocation rules must be fixed before implementation.

**Alternatives:**
A. One Settlement → exactly one Obligation.
B. One Settlement → many Obligations through explicit allocations.
C. Infer obligation from Payment metadata.

**Recommendation:** **A for 12A.** It is the smallest implementable integrity boundary. Multi-obligation allocation can be a later bounded extension if required.

**Persistence impact:** one explicit Settlement→Obligation reference; no allocation entity required for 12A.

**Security impact:** prevents cross-obligation financial effect.

**Test impact:** wrong obligation, missing obligation, closed/incompatible obligation, partial amount.

**Human approval required:** YES — cardinality changes executable financial semantics.

---

## DD-ST12-0004 — Recognition / Ledger creation authority
**Problem:** Current specs define Finance as owner but do not define one executable recognition gate.

**Minimum change:** establish Finance recognition boundary as the only path capable of creating final Ledger Entry.

**Alternatives:**
A. Dedicated orchestrator invokes Finance recognition command.
B. Settlement directly creates Ledger Entry.
C. Generic domain event automatically creates Ledger Entry.

**Recommendation:** **A.** It preserves ownership and gives one explicit control point.

**Persistence impact:** recognition must be atomic with the relevant durable financial effect if durability is implemented.

**Security impact:** central enforcement of authorization/approval and financial invariants.

**Test impact:** direct Settlement-to-Ledger attempt must fail; only valid recognition path succeeds.

**Human approval required:** YES — it is a financial authority boundary.

---

## Non-dependencies explicitly rejected
The following are NOT treated as dependencies at this stage:
- payment provider integration
- bank/wallet integration
- FX
- API/UI
- cloud/provider selection
- sync/CRDT
- new Refund/Chargeback financial concepts
- legal/regulatory engine
- new authorization model
- new Agent authority model
