# GAP-0004 — Source-of-Truth Matrix

| Concern | Source of Truth | Who may mutate | Required invariant |
|---|---|---|---|
| Authorization | Authorization / Grant | Authorization authority | Authorization never implies ownership |
| Approval | Approval | Approval authority / governed workflow | Approved status is required before governed execution |
| AgentAction | AgentAction | Agent runtime under authority | Must reference exact authoritative Grant |
| Payment | Payments | Payment owner | Payment ≠ Settlement ≠ Financial Truth |
| Settlement | Payments/Settlement | Settlement owner / orchestrated boundary | Must reference a valid Payment and Obligation |
| Obligation | Financial Relations | Financial Relations authority | Settlement cannot reassign ownership |
| FinancialTransaction | Finance | Finance recognition boundary | Created only at canonical recognition |
| LedgerEntry | Finance | Finance ledger boundary | Final ledger truth cannot be created by Payment/Settlement/Agent |
| Balance | Derived from Finance Ledger | No independent mutation | Never becomes mutable source of truth |
| Workflow identity | Financial Orchestrator | Orchestrator | Identity is stable; it is not financial truth |
| Idempotency/replay state | Durable operation integrity boundary | Authorized orchestration/persistence boundary | Same identity+fingerprint cannot create duplicate effect |
| Provenance/history | Domain history / Audit according to existing ownership | Owning domain / audit authority | Correction/reversal never erases history |

## Ownership rule
Orchestration coordinates the owners. It does not replace them.

## Recognition rule
Only the Finance recognition boundary may convert an accepted Settlement into Financial Transaction / Ledger Entry truth.

## Derived-state rule
Balance is always derived; it is never a command target or independent write authority.
