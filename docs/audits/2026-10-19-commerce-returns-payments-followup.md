# Follow-up audit: Commerce returns, invoices, payments and Finance boundaries

**Reviewed:** 2026-10-19  
**Baseline:** `main` after payment transition fix `18acf2a4cda432417b86afee7fafabf36eed8584`  
**Scope:** checkout/receipt, sale return, invoice issue/void, payment transitions, persisted settlement recognition, refunds and tax boundaries.

## Verified changes

- **Invoice uniqueness:** the application and SQLite INSERT/UPDATE guards enforce at most one `issued` invoice per Sale. This follow-up adds a standalone regression test for changing a historical `void` invoice to `issued` when another invoice for the Sale is already issued.
- **Payment completion safety:** PR #465 was merged after both Python tests and Stage 7 Automated Tests passed on its exact head. Payment completion now validates persisted Payment invariants inside the atomic transition and refuses provider completion when a linked invoice is no longer issued or a linked obligation is no longer eligible.
- **Inventory return:** a governed Sale lifecycle return and append-only positive stock-restoration movement already exist. Reuse this path; do not create a duplicate inventory-return mechanism.
- **Persisted Finance recognition:** `PersistedSettlementRecognitionApplication` is the Finance-owned persistence boundary. It validates persisted Payment/Settlement/Obligation/Account state and authorization, then persists recognized FinancialTransaction and posted LedgerEntry data transactionally. This must be reused rather than bypassed by Commerce or Hospital code.

## Four highest-priority gaps

### P0 — Explicit Commerce-to-Finance linkage
There is still no verified source mapping proving that a Commerce Sale/receipt/invoice becomes a Finance Obligation, Payment and Settlement. Define the source event, ownership, authorization context, durable operation identity, retry semantics and reconciliation contract before wiring it. Never post ledger rows from checkout or infer payment from Sale state.

### P0 — Partial settlement and remaining balance
The persisted recognition path currently requires full settlement; it does not provide a verified remaining-balance workflow for multiple partial payments. Implement only with a durable balance invariant, concurrency/overpayment protection, currency checks, duplicate-delivery handling and crash/retry tests. Do not simply remove the equality guard.

### P1 — Authorized refund and credit-note lifecycle
Stock restoration is not a financial refund. A complete flow still needs explicit refund authorization, partial-return quantity/value rules, credit-note treatment, provider refund/reversal semantics, ledger reversal/correction, idempotency and reconciliation. Preserve original sale, invoice, payment and ledger history. The relevant accounting policy must be decided before implementation.

### P1 — Jurisdiction-specific invoice compliance
The generic invoice model does not establish tax compliance. Confirm the target jurisdiction and requirements for issuer registration, tax calculation and rounding, mandatory fields, numbering, credit notes and reporting. Until then, keep the customer receipt clearly distinct from a tax invoice and make no compliance claim.

## Release decisions

- **Merged and CI-verified:** Payment completion atomicity and linked invoice/obligation eligibility checks (PR #465).
- **Regression protection added here:** database UPDATE guard against a second issued invoice for a Sale.
- **Existing capabilities to reuse:** governed stock return, invoice lifecycle, Payment/Obligation applications, persisted Finance settlement recognition and external-event/outbox components.
- **Do not enable without policy/integration:** automatic invoice issuance, Commerce-to-Finance mapping, partial settlement, refunds/credit notes and jurisdiction-specific tax treatment.

## Verification limits

The PR must pass Python tests and Stage 7 on its exact head before merge. This source-level review is not a production certification and does not claim that refund or tax compliance has been implemented.
