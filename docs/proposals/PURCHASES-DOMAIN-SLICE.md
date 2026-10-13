# Procurement domain slice — implementation boundary

**Status:** Initial persisted slice implemented on an isolated branch; not yet merged or production-ready.

This change adds a canonical buyer-side Purchase aggregate and line/receipt value objects, a dedicated persistence application, and additive SQL tables. It intentionally does not reuse Sale, Offering, Invoice, Payment, Obligation, Settlement, InventoryPosition, or Ledger records.

## Implemented

- Lifecycle: draft → submitted → approved → ordered → partially received → received.
- Cancellation is allowed only before ordering.
- Purchase lines require an existing active Product when persisted; purchases require an existing active Activity.
- Ordered and received quantities are positive integers with explicit per-line unit labels.
- Optional unit cost is stored as integer minor units and requires a three-letter uppercase currency code.
- Partial receipts and over-receipt protection; receipt facts are persisted separately from order lines.
- Exact persisted AuthorizationGrant checks for create, submit, approve, order, cancel, and receive operations, including actor, action, scope, Activity context, effective period, and active delegation checks.
- Optimistic version checks, transaction/savepoint boundaries, and domain-history entries are written atomically with changes.
- Loading validates history version chains and receipt totals instead of silently repairing inconsistent persisted data.
- Supplier invoices have a separate persistence model and application. Each invoice is tied to its purchase and supplier, uses integer minor-unit amounts, unique supplier invoice numbers and idempotency keys, and cannot invoice more units than have been received and remain uninvoiced.
- Supplier invoice capture validates exact persisted authorization, currency compatibility where the purchase line has a known currency, arithmetic bounds, duplicate lines, and atomic history writes. Restoration revalidates each persisted line's quantity × unit price arithmetic as well as invoice totals and receipt bounds. A read-only price-variance report exposes signed unit and extended differences against a known purchase-line baseline; it never approves the variance or posts financial effects.
- Focused domain and application tests were added for lifecycle, authorization failure, persistence restoration, partial/full receipts, over-receipt rollback, and ensuring receipt does not mutate inventory.

## Explicit boundaries and remaining work

- Supplier identity is an opaque non-empty reference until a canonical supplier identity policy is approved. It is not silently treated as a Person or Organization foreign key.
- Receipt records evidence only. Inventory Position, quantity, reservation, and availability are not changed.
- Supplier invoice capture does not create an Obligation, Payment, Settlement, financial transaction, or ledger entry. It also does not change inventory.
- Supplier invoice voiding/credit notes, tax jurisdiction rules, freight allocation, multi-currency conversion, automated price variance approval, UI/API endpoints, and external supplier integrations remain out of scope.
- Purchase creation and receipt writes require idempotency keys. A repeated key with the same canonical request returns the existing purchase state without repeating the write; reusing a key with a different payload is rejected. Keys are unique within their operation table.
- No tax, discounts, freight, multiple units/conversions, approval thresholds, UI/API endpoints, or external supplier integrations are included.
- CI must pass on the latest PR head before merge; each subsequent code change invalidates results from an earlier head.

The implementation is a meaningful backend slice, not a claim that the complete procurement workflow is finished.
