# Procurement domain slice — implementation boundary

**Status:** Initial persisted backend slice is merged into `main` via PR #205. SQLite supplier-invoice guards are merged via PR #207. Immutable receipt-event database guards are proposed in PR #208. This is not the complete procurement workflow.

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
- Supplier invoice voiding/credit notes, tax jurisdiction rules, freight allocation, multi-currency conversion, automated price variance approval, UI/API endpoints, and external supplier integrations remain out of scope. Invoice tax and freight are stored as explicit amounts, but no jurisdiction calculation or allocation is performed.
- Purchase creation and receipt writes require idempotency keys. A repeated key with the same canonical request returns the existing purchase state without repeating the write; reusing a key with a different payload is rejected. Keys are unique within their operation table.
- Purchase-order discounts, purchase-order tax/freight calculation, approval thresholds, UI/API endpoints, and external supplier integrations are not included. Supplier-invoice tax/freight amounts can be recorded explicitly, without jurisdiction rules or freight allocation.
- CI must pass on the latest PR head before merge; each subsequent code change invalidates results from an earlier head.

The implementation is a meaningful backend slice, not a claim that the complete procurement workflow is finished.

## Historical unit snapshot groundwork

- Purchase lines now have nullable, immutable snapshot fields for the configured product-unit setting, measurement unit, code/name/dimension labels, exact positive conversion numerator/denominator, and both setting/catalog versions.
- Legacy purchase lines remain valid with no snapshot; the migration does not infer or backfill units.
- The snapshot represents the exact conversion contract used when the line was created. Its base quantity can be derived with rational arithmetic; no float rounding is introduced.
- Snapshot fields must be complete or entirely absent and cannot be rewritten after line creation. Migration 0012 adds a database trigger that rejects snapshots inconsistent with the active product-unit setting, ratio, labels, or versions. The combined integration branch exposes `PurchaseApplication.capture_unit_snapshot(connection, line, measurement_unit_id)` so callers select a unit explicitly rather than relying on a display-label guess.
- When the product-unit catalog is present, creating a snapshotted line validates the product, active setting/unit, ratio, labels, and versions against the current catalog. Restoring a historical line does not require its current setting to remain active or unchanged.
- Receipt quantities continue to use the purchase line's entered unit. This migration does not post inventory or convert quantities implicitly.
- The unit catalog and purchase snapshot migrations were integrated and merged together through PR #205. Migrations `0015`–`0018` are now part of `main`; migration `0019` adds database-level supplier-invoice guards; migration `0020` adds immutable receipt evidence and received-quantity consistency guards. Legacy lines without snapshots remain supported.
