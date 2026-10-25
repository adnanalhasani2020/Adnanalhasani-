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

## Workstreams

### A. Discounts and taxes
- [ ] Define line/document discount semantics, fixed/percentage limits, rounding, tax-inclusive/exclusive rules, exemptions, and tax-rule version snapshots.
- [x] Add pure exact-arithmetic foundation with integer minor-unit totals and Decimal unit conversion.
- [ ] Wire calculations into authoritative checkout and GL/tax posting; never trust client totals.
- [ ] Persist line subtotal/discount/tax snapshots and document totals; include all fields in idempotency fingerprint.
- [ ] Test zero/100% discount, over-discount rejection, half-unit rounding, multiple taxes, inclusive/exclusive taxes, currency boundaries and replay.
- [ ] Do not enable jurisdiction-specific tax invoices until jurisdiction and required issuer/tax fields are configured.

### B. Units and packaging
- [x] Add Decimal conversion primitive for piece/carton/kg-style ratios.
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

## Current boundary

The cloud checkout migration explicitly excludes discounts and taxes, and the cloud purchase screen documents read-only receiving. UI-only controls or passing contract tests do not complete these workflows; end-to-end wiring and database verification remain mandatory.
