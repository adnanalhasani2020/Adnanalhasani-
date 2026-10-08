# GAP-0004 — Financial Orchestration Contract

**Status:** APPROVED DESIGN CONTRACT — NO IMPLEMENTATION  
**Baseline:** `806292c7d59e6a949a38db3ec6031bd6e6fdeb37`

## 1. Canonical workflow
```
Payment
  → Settlement
  → Financial Transaction
  → Ledger Entry
  → Balance (derived)
```

This is the canonical orchestrated path when financial recognition is reached. Payment and Settlement remain independent domain truths; not every Payment must settle.

## 2. Orchestration owner
**Authoritative owner:** Finance-scoped **Financial Orchestrator**.

It:
- coordinates across existing source-of-truth domains;
- does not replace Domain Owners;
- does not create authorization;
- does not become the Ledger owner;
- may invoke domain operations only through their existing ownership boundaries;
- delegates final financial recognition to Finance.

## 3. Workflow identity
**WorkflowId** is the business workflow identity.

It is distinct from GAP-0005 durable operation identity. GAP-0005 remains the layer for durable operation identity, idempotency, replay integrity, and related existing semantics. Stage 12A must not redefine GAP-0005.

## 4. Payment ↔ Settlement matching
Settlement must reference the exact Payment it settles, and the Payment must be settlement-eligible.

No matching by amount/time/user alone.

## 5. Settlement ↔ Obligation matching
Settlement must explicitly reference the exact Obligation it satisfies or reduces. The Obligation remains owned by Financial Relations.

For Stage 12A:
**ONE Settlement → ONE Obligation.**

Multi-obligation allocation is out of scope and requires a separate decision.

## 6. Financial recognition boundary
The authoritative recognition boundary is Finance.

Recognition is permitted only after:
- exact Payment/Settlement/Obligation matching;
- settlement eligibility and financial integrity checks;
- required connectivity/finality conditions;
- AuthorizationGrant exact AgentAction binding;
- GAP-0001 Approval;
- all other existing authorization and domain invariants.

Payment.completed alone is not recognition. Settlement.settled outside the canonical recognition path is not recognition.

## 7. Finance Recognition Command
**Finance Recognition Command** is the only approved command path capable of creating a financially final Ledger Entry.

There is no:
- Settlement → Ledger direct path;
- generic automatic event → Ledger finality path.

The Orchestrator coordinates and invokes Finance Recognition Command; Finance remains authoritative for Financial Transaction/Ledger truth.

## 8. Balance
Balance remains derived exclusively from authoritative Ledger Entries. No mutable Balance source of truth is introduced.

## 9. Workflow lifecycle
The minimal orchestration lifecycle is:
- CREATED
- IN_PROGRESS
- RECOGNIZED
- FAILED
- CANCELLED
- REVERSED

These states do not replace Payment, Settlement, Obligation, or Ledger lifecycles.

## 10. Failure / cancel / reversal / correction
- **Failure:** no new financial finality; preserve history.
- **Cancel:** stop before recognition; preserve attempted history.
- **Retry:** same WorkflowId for the same semantic operation.
- **Reversal:** create an approved reversing effect; never erase the original Ledger history.
- **Correction:** preserve original identity and provenance; never silently overwrite history.

## 11. Workflow idempotency integration
Workflow operation identity uses:
**WorkflowId + operation fingerprint**.

At minimum the fingerprint covers PaymentId, SettlementId, ObligationId, operation type, and authoritative amount/currency/scope where applicable.

Same identity + same fingerprint → reuse existing result with no duplicate effect.
Same identity + different fingerprint → CONFLICT with no effect.
GAP-0005 semantics remain unchanged and provide the durable replay/idempotency integrity underneath.

## 12. Offline finality
Offline/disconnected execution may retain permitted operational pending state, but MUST NOT create:
- Financially Recognized state;
- final Ledger Entry;
- final Balance effect.

Any attempt to cross that boundary offline is rejected with zero new financial effect.

## 13. Authorization chain
```
AuthorizationGrant
  → exact AgentAction binding
  → GAP-0001 Approval
  → Financial Orchestrator
  → Finance Recognition Command
  → financial recognition
```

Finance objects never create authority, and authorization never transfers Financial Ownership.

## 14. Historical / provenance preservation
Preserve, at minimum:
- WorkflowId;
- Payment/Settlement/Obligation identities;
- authorization and approval references;
- recognition decision;
- Financial Transaction and Ledger Entry identities;
- failure/cancel/retry/reversal/correction lineage;
- required actor/agent and timestamp context.

History remains append-preserving.

## 15. Explicit non-goals
No provider, bank, wallet, FX, API/UI, legal/regulatory engine, production-readiness claim, autonomous financial authority, Stage 12B durable workflow, multi-obligation allocation, or new financial/authorization concept is part of this contract.
