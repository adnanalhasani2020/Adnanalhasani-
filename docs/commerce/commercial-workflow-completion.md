# Commercial workflow completion — implementation and release gates

**Status:** Active engineering work. This checklist is not a claim of tax compliance or production readiness.

## Non-negotiable data invariants

- The sale/order source record remains the sole invoice identity; do not create a second financial invoice ledger.
- Money is persisted as integer minor units with an explicit currency. Do not use binary floating point for persisted totals.
- Quantity is stored in a documented base inventory unit. Sale, purchase receipt, return, and restock quantities must use the same conversion contract and precision rules.
- Every mutating operation has a stable idempotency key bound to a canonical request fingerprint. Same-key/same-payload replays the original result; same-key/different-payload is rejected.
- Stock, payment, supplier liability, and general-ledger effects are transactionally coupled to their source operation or represented as explicit pending/reconciliation states.
- Returns never edit the original invoice or delete original payment/stock events. Corrections are append-only reversals/credit notes linked to the source.
- Organization, activity, location, supplier/customer, currency, and role scope are enforced server-side and in database policies.
- Tax settings are explicit and versioned. No default tax rate or jurisdictional compliance claim is inferred from location.
- Print/share documents are immutable renderings of saved records, not alternate sources of totals. Label operational receipts separately from legally compliant tax invoices.

## Financial vouchers and general-ledger linkage

- Customer collections: the cloud debt/sales flows call the guarded `commercial_record_customer_payment` RPC. It validates the outstanding amount and tenant/role scope, uses an idempotency key, and posts the receipt journal in the same transaction. The app must retain the same pending key after an uncertain network response.
  Live schema inspection found this RPC missing even though its earlier migration was recorded. The new repair migration restores the endpoint and requires a posted journal entry; a follow-up correction relies on the payment INSERT trigger exactly once, avoiding a second posting attempt after a full collection.
- Supplier payments: the purchasing screen calls `commercial_record_supplier_payment`. The RPC checks the supplier obligation, currency and remaining balance, requires evidence, posts the balanced payable/cash journal, and updates the paid balance atomically.
- Operating expenses: `commercial_expense_vouchers` stores server-confirmed vouchers. `commercial_record_expense` creates/validates the expense account, checks the cash/bank mapping, writes a balanced journal entry and voucher atomically, enforces tenant/role scope, and rejects changed payloads reusing an idempotency key. Direct clients cannot write voucher rows.
- Payment-method routing: customer receipts debit cash, bank, card-clearing or other-receipt-clearing accounts according to the selected method; supplier disbursements debit cash, bank, check-clearing or other-disbursement-clearing accounts. Default chart initialization now seeds the required mapping keys and validates account type, normal balance and currency. Card/check/other clearing still requires later settlement reconciliation; the unified financial report remains gated until that reconciliation is verified.
- Legacy local expenses are not migrated or shown as cloud-confirmed accounting records. They must be reconciled/imported explicitly before they can be included in cloud financial reports.
- These implementations are not a substitute for an authenticated end-to-end test. Verify a successful receipt, supplier payment, and expense against their source rows, customer/supplier balances, and balanced posted journal entries; also test replay, changed-payload replay, insufficient balance, wrong tenant, missing account mapping and transaction rollback.

## Workstreams

### A. Discounts and taxes
- [ ] Define line/document discount semantics, fixed/percentage limits, rounding, tax-inclusive/exclusive rules, exemptions, and tax-rule version snapshots.
- [x] Add pure exact-arithmetic foundation with integer minor-unit totals and Decimal unit conversion.
- [ ] Wire calculations into authoritative checkout and GL/tax posting; never trust client totals.
- [ ] Persist line subtotal/discount/tax snapshots and document totals; include all fields in idempotency fingerprint.
- [ ] Test zero/100% discount, over-discount rejection, half-unit rounding, multiple taxes, inclusive/exclusive taxes, currency boundaries and replay.
- [ ] Do not enable jurisdiction-specific tax invoices until jurisdiction and required issuer/tax fields are configured.

### B. Units and packaging
- [x] Add Decimal conversion primitive for piece/carton/kg-style ratios, sizing precision to avoid ambient Decimal-context rounding.
- [x] Add explicit base-unit precision validation that rejects unsupported fractions rather than silently rounding stock quantities.
- [ ] Add base-unit and sell/receive-unit definitions with positive factors, dimensional compatibility and precision rules.
- [ ] Send unit code and decimal quantity through APIs; convert server-side before stock locks and movement posting.
- [ ] Snapshot entered quantity, selected unit, conversion factor and normalized base quantity on source lines.
- [ ] Unit-factor changes must never rewrite historic invoices or movements.
- [ ] Test packages, weight, fractional quantities, incompatible dimensions, overflow and concurrent stock boundaries.

### C. Returns and refunds
- [ ] Validate cumulative returned base quantity and refundable paid amount against original sale and prior returns under row locks.
- [ ] Enforce request → decision → inspection → restock/refund lifecycle and authorized roles.
- [ ] Restock and refund are independently idempotent, linked to one return, and atomically update stock/finance state.
- [ ] Require payout evidence for manual refunds; do not claim an external provider was called unless it was.
- [ ] Test partial/multiple returns, zero-paid credit sale, allocations, duplicate retries, key mismatch, concurrency, rollback and ledger reversals.

### D. Purchasing and suppliers
- [ ] Complete supplier create/edit and purchase order create/approve/cancel with scoped authorization and audit.
- [ ] Receive full/partial quantities into inventory exactly once, converting to base units.
- [ ] Link receipt, stock movement, supplier invoice/obligation, payments and journal entries by source IDs.
- [ ] Distinguish ordered, received, invoiced and paid. A purchase order alone must not create stock or a liability.
- [ ] Test over-receipt, wrong supplier/product/location/currency, partial receipts, duplicate retries, cancellation after receipt and supplier balance reconciliation.

### E. Invoices, print and share
- [ ] Render saved invoice number/date/issuer/buyer, line and unit snapshots, subtotal/discount/tax/total, paid/outstanding, terms and source references.
- [ ] Add printable/shareable PDF or platform print output for saved invoices.
- [ ] Escape user-controlled text, format money/date by locale, preserve Arabic RTL, and keep a stable document identifier.
- [ ] Test rendered totals against database totals, missing issuer/tax-data warnings and reopen/replay.
- [ ] Keep operational receipt labels until jurisdiction-specific tax invoice requirements are approved and verified.

## Required gates before merge/release

- Unit tests for arithmetic, conversion, precision and overflow.
- Database integration tests against a disposable schema with authenticated users and real RLS/RPC boundaries.
- Atomicity: forced failures leave no partial sale/payment/stock/receipt/GL rows.
- Idempotency: same payload creates one effect; changed payload under same key is rejected.
- Multi-tenant isolation for all reads/writes.
- Retry/reopen proves invoice totals, payment balance, inventory and supplier balance come from persisted records.
- CI passes on the exact PR head. Source-contract tests or an APK build alone do not clear integration gates.

## Current verification snapshot (2026-10-26)

- The live Supabase migration registry was rechecked on 2026-10-26. It includes `20261025230817` (`commercial_fractional_purchase_receiving`) and `20261026080000` (`commercial_purchase_fractional_accrual_rounding`). Read-only inspection of the effective receipt and supplier-invoice posting functions confirms cumulative per-line rounding and sequence ordering.
- Applied `restrict_accounting_integrity_view_access` to the configured Supabase project. The diagnostics view now uses `security_invoker`; `anon` and `authenticated` cannot SELECT it, while `service_role` retains access. The Supabase security advisor no longer reports this view as a security-definer-view finding.
- Applied `commercial_purchase_order_fractional_quantities` to the configured Supabase project. The effective purchase-order RPC now accepts positive quantities up to six decimal places, matching the `numeric(18,6)` storage and receipt validation. The Android repository previously had an integer-only guard; a PR now changes it to the same six-decimal limit.
- Repository contract tests and pgTAP assertions cover the purchase-order precision and diagnostics-view permissions. CI runs for the open PR are the authoritative result for the updated source; do not treat them as passed until the exact head's workflows complete successfully.
- Authenticated end-to-end integration across sales, collections, returns, inventory and general-ledger postings remains a release gate. No production sales/return/payment fixtures were executed. Use a disposable staging database with authenticated sessions and before/after stock, payment and journal reconciliation before declaring full workflow acceptance.

## Current boundary

The cloud checkout migration explicitly excludes discounts and taxes, and the cloud purchase screen documents read-only receiving. UI-only controls or passing contract tests do not complete these workflows; end-to-end wiring and database verification remain mandatory.
