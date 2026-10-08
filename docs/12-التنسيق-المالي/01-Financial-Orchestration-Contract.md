# GAP-0004 — Financial Orchestration Contract

**Status:** DESIGN PROPOSAL — NO IMPLEMENTATION  
**Baseline:** `806292c7d59e6a949a38db3ec6031bd6e6fdeb37`

## 1. Canonical workflow
```
Payment
  → Settlement
  → Financial Transaction
  → Ledger Entry
  → Balance (derived)
```

The sequence is the canonical orchestrated path for a workflow that reaches financial recognition. It does not imply every Payment must settle or every financial relation originates as a Payment.

## 2. Orchestration owner
**Recommended:** a dedicated **Financial Orchestrator** inside the Finance application/domain boundary.

Reason:
- Finance owns Financial Transaction and Ledger Entry.
- Payment/Settlement remain their own source-of-truth domains.
- orchestration coordinates without becoming a fourth financial truth.
- final recognition must be decided at the Finance boundary.

The orchestrator may call Payment/Settlement domain operations but may not mutate their truth outside their owners.

**Human decision required:** whether the repository's architecture permits a dedicated orchestration component under Finance, or requires orchestration to remain an application-layer coordinator. No implementation is authorized until selected.

## 3. Workflow identity
Recommended identity:
**WorkflowId** — unique identity for one orchestration attempt/business workflow.

A workflow may reference:
- Payment identity
- Settlement identity
- Obligation identity
- resulting Financial Transaction identity
- resulting Ledger Entry identity

WorkflowId is orchestration identity, not Financial Truth.

## 4. Payment ↔ Settlement matching
Canonical rule:
**Settlement must reference exactly the Payment it settles, and the Payment must be in a settlement-eligible state.**

For partial settlement, one Payment may be allocated to one or more explicitly supported settlement portions only if the obligation allocation is unambiguous.

No matching by amount/time/user alone.

## 5. Settlement ↔ Obligation
Canonical rule:
**Settlement must explicitly reference the Obligation it satisfies or reduces.**

The obligation remains owned by Financial Relations. Settlement does not transfer ownership.

A Settlement for an unrelated, closed, or incompatible Obligation is rejected.

## 6. Financial recognition boundary
The single recognition boundary is:

> **Finance accepts a valid Settlement for a valid Obligation, after all authorization, approval, connectivity/finality, matching, and integrity checks succeed.**

At this boundary Finance may create the authoritative Financial Transaction and corresponding Ledger Entry according to the approved financial rules.

Payment.completed alone is not recognition.
Settlement.settled alone, outside the orchestrator, is not recognition.

## 7. Ledger Entry creation
Only the **Finance-owned recognition boundary/orchestrator** may request creation of a final Ledger Entry.

Payment, Settlement, AgentAction, Device, Channel, Invoice, and WorkflowId cannot create final Ledger Truth directly.

## 8. Balance
Balance remains a derived read from authoritative Ledger Entries. No mutable Balance-as-truth is introduced.

## 9. Minimal lifecycle
Workflow:
- CREATED
- IN_PROGRESS
- RECOGNIZED
- FAILED
- CANCELLED
- REVERSED

No additional workflow states are required at this gate.

These states describe orchestration state, not replacement states for Payment, Settlement, Obligation, or Ledger Entry.

## 10. Failure semantics
- **fail:** no new financial finality; preserve existing history.
- **cancel:** stop before recognition; preserve attempted workflow history.
- **retry:** same WorkflowId where semantically the same operation is retried.
- **reversal:** never delete the recognized Ledger Entry; create the approved reversing financial effect.
- **correction:** never rewrite history silently; preserve original identity and correction provenance.

## 11. Idempotency
Recommended identity:
**WorkflowId + operation fingerprint**.

Fingerprint covers the canonical immutable inputs relevant to the operation, at minimum:
PaymentId, SettlementId, ObligationId, operation type, and authoritative amount/currency/scope where applicable.

Semantics:
- same identity + same fingerprint → return/reuse existing result; no duplicate financial effect.
- same identity + different fingerprint → CONFLICT; no financial effect.
- new identity → independently evaluated, with domain duplicate checks.
- GAP-0005 durable replay/idempotency guarantees are reused rather than redefined.

## 12. Offline
Disconnected execution may create/retain operational Pending state where permitted.

It MUST NOT create:
- Financially Recognized state
- final Ledger Entry
- final Balance effect

Offline attempt that reaches a prohibited finality boundary is rejected with zero new financial effect.

## 13. Authorization chain
```
AuthorizationGrant
  → AgentAction.authorization_grant_id
  → GAP-0001 Approval
  → Financial Orchestrator
  → Financial recognition
```

Finance objects never create authority. Authorization never transfers Financial Ownership.

## 14. Provenance / history
Must preserve:
- original WorkflowId
- Payment/Settlement/Obligation identities
- authorization and approval references
- recognition decision
- resulting Financial Transaction identity
- Ledger Entry identity
- failure/cancel/retry/reversal/correction lineage
- timestamps and actor/agent context required by existing audit rules

History is append-preserving; correction/reversal does not erase the original event.

## 15. Explicit non-goals
No provider, bank, wallet, FX engine, accounting-standard implementation, legal compliance engine, autonomous financial authority, sync engine, or new financial concept is part of GAP-0004 at this gate.
