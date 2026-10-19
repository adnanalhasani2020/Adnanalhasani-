# Follow-up: commerce sale and invoice lifecycle

**Reviewed:** 2026-10-19  
**Baseline:** merge commit `071eb07d98f2d46987caf08118a6d3edf02bf511`  
**Scope:** checkout, receipts, sale lifecycle, invoice issuance/voiding, inventory returns, and Finance boundary.

## Verified change merged

PR #462, *Prevent duplicate active invoices for a sale*, was merged after both required workflows passed on the exact tested head `5d2ac552cdc8bd4d47a5b6ad06fe49b2f1471f9f`:

- Python tests: passed.
- Stage 7 automated tests: passed.
- Merge commit: `071eb07d98f2d46987caf08118a6d3edf02bf511`.

The change rejects a second invoice in `issued` state for the same Sale in the application service and at SQLite INSERT/UPDATE boundaries. Voided invoices remain historical evidence. It does not create an invoice automatically at checkout.

## Current lifecycle findings

1. **Checkout and receipt:** checkout serializes writes, validates current offering/price/stock, snapshots the line and product name, records an append-only sale stock movement, and stores a replayable receipt in the same transaction. A repeated idempotency key with a different payload is rejected. The printable receipt is correctly not described as a tax invoice.
2. **Invoice:** invoice issuance is a separate governed operation. It validates persisted issuer identity and a scoped active authorization grant, keeps history, and blocks voiding when linked financial activity exists. The active-invoice uniqueness invariant is now enforced in both service and database layers.
3. **Sale return and inventory:** the current code/tests already exercise a one-time return transition and a compensating positive stock movement. Do not duplicate this existing inventory-return behavior.
4. **Financial completion:** a sale or receipt does not prove payment, settlement, or revenue recognition. A verified, durable Commerce → Payment → Settlement → Ledger path has not been established by this review.
5. **Legal invoice configuration:** the current generic invoice is not proof of compliance with any jurisdiction's tax-invoice rules. Jurisdiction, issuer registration, mandatory fields, tax/rounding policy, and credit-note rules are not configured here.

## Four highest-priority remaining gaps

### P0 — Governed invoice orchestration
A checkout currently produces a receipt, not an issued Commerce invoice. Before linking them, establish a persisted issuer configuration, exact issuance grant, and collision-safe invoice-number allocation. If the authorized issuer is a separate actor, use a durable retry/reconciliation mechanism. Do not invent an issuer or bypass authorization.

### P0 — Verified payment, settlement, and ledger recognition
Trace and test the canonical Finance-owned persisted workflow, including authorization/approval, durable operation identity, retries, and reconciliation. Do not infer payment from Sale state or write ledger entries directly from checkout.

### P1 — Jurisdiction-specific tax invoice and credit-note rules
Confirm the target jurisdiction and approved accounting/legal policy before adding tax fields, calculations, numbering semantics, credit notes, or mandated reporting. Keep receipts clearly labeled until then.

### P1 — Governed customer return/refund workflow
Inventory restoration on the Sale return path exists, but a complete customer-facing partial/full refund workflow across invoice/obligation/payment/recognition has not been verified. Define return windows, partial quantities, refund authorization, payment provider behavior, credit-note treatment, and retry semantics before exposing a new endpoint. Preserve original Sale and financial evidence.

## Regression hardening added in follow-up branch

A further regression test covers direct SQL attempts to change a second, historical voided invoice back to `issued` while another issued invoice exists for the Sale. This checks the UPDATE trigger, complementing the existing INSERT and application-level tests. CI must pass on the follow-up branch before it is merged.

## Release decision

- **Merged and CI-verified:** one active issued invoice per Sale.
- **Added, awaiting CI:** database UPDATE-trigger regression test.
- **Not enabled by design:** automatic invoice creation, tax-compliance claims, automatic settlement/ledger posting, or an unconfigured refund endpoint.
- **Next implementation order:** Finance path verification; issuer/numbering configuration; then policy-backed return/refund orchestration.
