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

Each sale allocation stores the actual layer ID, quantity, cost snapshot, valuation method, and physical rotation method. The existing sale return engine links returned quantities to the original sale cost allocations and restores the same layer IDs. For a partial return from a sale line that consumed several layers, the engine restores the most recently consumed allocation first (reverse consumption order); a full return restores all source layers according to their original allocations. It does not invent a new batch. Return inspection prevents damaged, written-off, or uninspected items from being restocked.

## Android workflow

The purchase receipt dialog accepts a lot number and optional expiry date for each received line. When existing opening stock has no cost-layer coverage, an owner/admin/manager can record one or more actual opening lots with exact quantities and verified unit costs; the operation must cover the complete unvalued balance, is idempotent, and does not change on-hand quantity or create a duplicate stock movement. The stock screen exposes the effective FIFO/FEFO policy, allows an authorized manager to change it for the current location or organization, and displays remaining batch quantities and expiry status through a member-scoped RPC that does not expose acquisition costs.

## Migration and release safety

The two forward-only migrations are:
- `20261106124452_inventory_batch_expiry_and_rotation.sql`
- `20261106124500_inventory_batch_fefo_allocation.sql`
- `20261106131420_opening_inventory_batch_valuation.sql`

Historical stock layers retain their current quantities and costs. Existing layers without lot metadata remain eligible under FIFO; FEFO treats missing expiry as unknown and sorts those layers after known expiry dates. The three forward-only migrations have been applied to the Supabase production project, and the PostgreSQL 17 batch smoke test, Python suite, and Android build passed on the merged candidate. Authenticated end-to-end acceptance remains necessary for a real manager receipt, opening valuation, multi-layer sale, partial/full return, expired/damaged exclusion, and two-tenant isolation.

## Exact partial-return layer restoration

Each new sale cost allocation receives a database-owned sequence within its sale item. A partial resellable return restores the most recently consumed allocation first, so the returned quantity is linked back to the precise original cost layer rather than relying on transaction-stable `created_at` timestamps or random UUID ordering.

Historical allocation rows retain a NULL sequence because their true intra-transaction order cannot be reconstructed from the existing evidence. The return routine keeps the prior deterministic timestamp/UUID fallback for those legacy rows; it does not invent historical sequence data.
