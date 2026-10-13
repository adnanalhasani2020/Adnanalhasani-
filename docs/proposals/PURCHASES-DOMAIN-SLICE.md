# Procurement domain slice — implementation boundary

**Status:** Domain-only first slice; not yet a persisted or production-ready purchases feature.

This change adds a canonical buyer-side Purchase aggregate and line/receipt value objects. It intentionally does not reuse Sale, Offering, Invoice, Payment, Obligation, Settlement, InventoryPosition, or Ledger records.

## Implemented in this slice

- Draft → submitted → approved → ordered → partially received → received lifecycle.
- Cancellation only before ordering; no cancellation after order placement.
- Positive integer ordered/received quantities and explicit per-line unit labels.
- Optional unit cost represented as integer minor units with an explicit three-letter uppercase currency code when cost is present.
- Partial receipts and over-receipt protection; immutable return values and receipt events.
- Supplier reference remains an opaque non-empty reference because the repository's canonical supplier identity policy is not yet established.

## Deliberately not implemented

- Persistence and SQL migration.
- Authorization/grant checks, approval thresholds, actor attribution, and domain-history writes.
- Inventory quantity updates or receipt-to-inventory integration.
- Invoice, obligation, payment, settlement, financial transaction, or ledger effects.
- Idempotency across persisted requests, concurrency/version handling, UI/API endpoints.
- Tax, discounts, freight, multiple units/conversions, and supplier foreign-key semantics.

Those omissions are explicit boundaries, not implied capabilities. The next safe step is to add persistence and authorization only after matching the existing application/history/grant contracts and then run the focused plus full CI suites.
