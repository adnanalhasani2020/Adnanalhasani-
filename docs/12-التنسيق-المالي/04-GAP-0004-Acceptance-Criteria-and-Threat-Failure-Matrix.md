# GAP-0004 — Acceptance Criteria & Threat / Failure Matrix

## Acceptance Criteria

| ID | Criterion |
|---|---|
| AC-01 | Valid Payment → Settlement → Financial Transaction → Ledger Entry workflow succeeds. |
| AC-02 | Invalid Payment/Settlement relation is rejected with zero new financial effect. |
| AC-03 | Settlement referencing an incompatible/wrong Obligation is rejected with zero new financial effect. |
| AC-04 | Financial recognition occurs only at the canonical Finance recognition boundary. |
| AC-05 | Arbitrary Payment/Settlement construction cannot create financial finality. |
| AC-06 | Final Ledger Entry creation is controlled exclusively by Finance recognition. |
| AC-07 | Balance is derived only from authoritative Ledger Entries. |
| AC-08 | Offline/disconnected execution cannot create prohibited financial finality. |
| AC-09 | AuthorizationGrant is required for governed Agent financial execution. |
| AC-10 | GAP-0001 Approval is required after exact Grant binding and before financial effect. |
| AC-11 | Missing/wrong/revoked authorization or mismatched approval causes zero financial effect. |
| AC-12 | Same WorkflowId + same fingerprint is idempotent and creates no duplicate effect. |
| AC-13 | Same WorkflowId + different fingerprint is a conflict and creates no effect. |
| AC-14 | Retry after restart does not duplicate financial recognition. |
| AC-15 | Failure/cancel preserves workflow and domain history. |
| AC-16 | Reversal creates a traceable reversing effect; original history remains. |
| AC-17 | Correction preserves original identity and provenance. |
| AC-18 | Cross-domain ownership remains Finance/Payments/Financial Relations as defined. |
| AC-19 | Persistence invariants are tested if/when durable workflow is implemented. |
| AC-20 | Adversarial/concurrency tests cover duplicate and conflicting orchestration attempts. |

## Threat / Failure Matrix

| Scenario | Expected result |
|---|---|
| Duplicate Payment | Preserve distinct domain identity; no duplicate financial recognition merely from retry. |
| Duplicate Settlement | Reject/reuse according to workflow identity and fingerprint; no second financial effect. |
| Retry after process restart | Resume/reuse same workflow; no duplicate Ledger Entry. |
| Partial workflow | Remains non-final; no Ledger finality until recognition gate succeeds. |
| Payment without Settlement | Valid non-final state; no Ledger finality. |
| Settlement without valid Payment | Reject. |
| Settlement for wrong Obligation | Reject, zero financial effect. |
| Ledger Entry created twice | Second creation rejected/idempotently reused only when same recognized workflow identity. |
| Reversal replay | No second reversal effect; preserve original and first reversal history. |
| Correction replay | No silent overwrite; duplicate correction rejected/reused by identity. |
| Offline finality attempt | Reject finality; preserve permitted pending operational state only. |
| Authorization revoked between stages | Reject before financial recognition; zero new financial effect. |
| Approval mismatch | Reject before financial recognition; zero new financial effect. |
| Stale workflow | Reject or require explicit revalidation; never recognize against stale authority/state. |
| Conflicting WorkflowId | CONFLICT; no financial effect. |
| Concurrent orchestration attempts | Exactly one authoritative recognition; others deterministically reuse or conflict. |

## Security invariant
No failure path may bypass the sequence:
Grant binding → Approval → financial recognition.
