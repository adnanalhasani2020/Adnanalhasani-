# Physical inventory batches and FIFO/FEFO

## Data model

The existing `commercial_inventory_cost_layers` ledger remains the single source of stock provenance and valuation. Each received layer now also carries:
- `batch_number`: supplier/manufacturer lot identifier when supplied.
- `expires_on`: expiry date when the item is date-controlled.
- `stock_condition`: `sellable`, `quarantine`, `damaged`, or `expired`.

Purchase receiving accepts batch metadata on each received order line. The purchase receipt item trigger copies that metadata to the exact inventory cost layer created by the same transaction. It does not merge distinct receipts into one lot.

## Issue order

Physical stock rotation is independent from valuation policy:
- **FEFO** is the default: earliest expiry first, then earliest receipt, then stable layer ID.
- **FIFO**: earliest receipt first, then stable layer ID.
- Missing expiry dates sort after dated stock under FEFO and therefore fall back to receipt age.
- Expired, quarantined, damaged, and otherwise non-sellable layers are never issued.
- If eligible layers cannot cover the sale quantity, checkout fails atomically rather than silently drawing from an unknown or blocked layer.

Each sale allocation stores the actual layer ID, quantity, cost snapshot, valuation method, and physical rotation method. The existing sale return engine links returned quantities to the original sale cost allocations and restores the same layer. Return inspection already prevents restocking damaged or written-off items.

## Android workflow

The purchase receipt dialog accepts a lot number and optional expiry date for each received line. The stock screen exposes the effective FIFO/FEFO policy, allows an authorized manager to change it for the current location or organization, and displays remaining batch quantities and expiry status through a member-scoped RPC that does not expose acquisition costs.

## Migration and release safety

The two forward-only migrations are:
- `20261106130000_inventory_batch_expiry_and_rotation.sql`
- `20261106131000_inventory_batch_fefo_allocation.sql`

Historical stock layers retain their current quantities and costs. Existing layers without lot metadata remain eligible under FIFO; FEFO treats missing expiry as unknown and sorts those layers after known expiry dates. No production migration should be applied until the PostgreSQL 17 batch smoke test, Python suite, and Android build pass, and the live migration history has been reconciled. Authenticated end-to-end acceptance should then cover a supplier receipt, multi-layer sale, partial return to the original layer, expired/damaged exclusion, and two-tenant isolation.
