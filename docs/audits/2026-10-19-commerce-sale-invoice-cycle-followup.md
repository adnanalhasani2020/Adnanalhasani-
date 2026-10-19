# Follow-up audit: Commerce sale, invoice, payment and finance boundaries

**Reviewed:** 2026-10-19  
**Baseline:** `main` at `071eb07d98f2d46987caf08118a6d3edf02bf511`  
**Scope:** checkout, receipt, sale/return, invoice issuance and voiding, Payment/Settlement, durable Finance recognition, external-event intake, and refund/tax boundaries.

## Verified changes

### Duplicate issued invoices blocked
PR #462 was merged after Python and Stage 7 workflows passed on its exact head. The application service and SQLite INSERT/UPDATE triggers now reject a second `issued` invoice for the same Sale. Historical void invoices remain available; the checkout does not invent an issuer or silently issue an invoice.

### UPDATE-trigger regression test
The follow-up adds a regression test for changing a historical `void` invoice to `issued` when the Sale already has an issued invoice. The invoice schema's canonical states are `issued`, `void`, and `settled`; the test must use `void` rather than a non-existent `voided` state. CI is required on the final head before merge.

## End-to-end lifecycle findings

1. **Checkout and receipt:** checkout validates offering/product activity, effective price and stock, snapshots sale-line data, records stock movement and a replayable receipt within its transaction. Idempotency rejects reuse of the same key with a different request payload. A receipt is not a tax invoice.
2. **Invoice:** invoice issuance is separately governed by issuer identity, scoped authorization, obligation checks and invoice-number uniqueness. Void operations preserve history and are guarded when linked financial activity requires reconciliation. A database-level active-issued-invoice invariant is now in place.
3. **Inventory return:** a return path and compensating positive stock movement already exist. Do not add a duplicate inventory-return feature.
4. **Payment and Finance recognition:** a Finance-owned `PersistedSettlementRecognitionApplication` exists. It reloads persisted Payment/Settlement/Obligation/Account records, validates authorization and human approval, and transactionally persists a recognized FinancialTransaction, posted LedgerEntry, Obligation state/history and (when supplied) the Hospital intake correlation. Existing-recognition retries verify the durable records. Therefore, it would be incorrect to report that the repository has no persisted recognition service.
5. **Important Finance limitation:** the recognition application explicitly supports only full settlement: Payment amount must equal the whole Obligation amount. It does not track remaining balances for partial settlement. The existence of a Hospital outbox/intake path does not itself prove that Commerce Sales/receipts/invoices are mapped into that flow.
6. **Tax/legal configuration:** the generic invoice model does not establish jurisdiction-specific tax compliance. Jurisdiction, issuer registration, tax calculation/rounding, mandatory invoice fields, and credit-note policy need an approved policy.
7. **Customer refunds:** inventory restoration is not a financial refund. A complete authorized refund/credit-note flow across the original invoice, payment provider, settlement/ledger reversal, and audit/reconciliation has not been verified.

## Four highest-priority gaps

### P0 — Explicit Commerce-to-Finance linkage
There is no basis to infer that a Commerce Sale/receipt equals a persisted Finance Payment or that a receipt has been financially recognized. Implement an explicit source mapping and retry/reconciliation contract only after deciding which Sale/invoice events create obligations and payments. Reuse existing Finance-owned recognition and outbox/intake components; do not write ledger rows from checkout or create parallel abstractions.

### P0 — Partial settlement and remaining balance
The current recognition service rejects partial or excess settlement. A safe implementation requires a persisted remaining-balance model, constraints against overpayment/concurrent recognition, and tests for multiple partial payments, duplicate delivery, currency mismatches, final settlement and crash/retry behavior. Do not merely remove the equality check.

### P1 — Governed refund and credit-note workflow
Define return windows, partial quantities, refund authorization, payment-provider semantics, credit-note requirements, reversal/correction records and retry policy. Preserve original sale, invoice and accounting evidence. Reuse existing stock-return behavior instead of duplicating it.

### P1 — Jurisdiction-specific invoice compliance
Confirm target jurisdiction and legal/accounting requirements before implementing tax rates, tax IDs, mandatory fields, numbering or credit notes. Until then, keep the receipt clearly distinguished from a tax invoice and avoid compliance claims.

## Release decision

- **Merged and CI-verified:** one active `issued` invoice per Sale.
- **Follow-up regression:** direct SQL UPDATE guard test; do not merge until CI passes on the final head.
- **Already present and should be reused:** persisted Finance settlement recognition, Finance ledger persistence, Hospital external-event correlation/outbox components, and inventory-return stock movement.
- **Not safe to enable without explicit policy/integration:** automatic invoice issuance, Commerce-to-Finance event mapping, partial settlement, customer refunds/credit notes, and jurisdiction-specific tax treatment.
