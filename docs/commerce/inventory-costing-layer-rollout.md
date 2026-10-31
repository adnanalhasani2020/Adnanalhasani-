# Inventory costing rollout

## Phase 1 — persisted cost layers and allocation engine

The costing foundation adds three methods at organization or branch scope:

- **FIFO**: consumes the oldest remaining cost layer first.
- **LIFO**: consumes the newest remaining cost layer first.
- **Weighted average**: revalues remaining layers to the moving weighted-average cost on each posted receipt.

A missing policy keeps the existing weighted-average balance-cost behavior. The current balance table remains the quantity source of truth.

### What is active in this phase

- Existing positive balances with a known `unit_cost_minor` are seeded as an opening layer at their current carrying cost. This is a valuation cut-over baseline; historical purchase layers are not fabricated.
- New inventory receipts create cost layers in the same database transaction as receipt posting.
- Under weighted average, remaining layers are revalued using the pre-receipt balance and the new receipt's quantity and cost.
- The private sale-line allocator consumes layers in FIFO, LIFO, or weighted-average mode, writes immutable sale-to-layer allocation rows, and snapshots the calculated cost into the sale item.
- Repeated allocation calls are idempotent; insufficient layer quantity fails rather than falling back to a guessed cost.
- Policy, layer, and allocation records are organization-scoped and protected by RLS.

### Required integration before enabling FIFO/LIFO for live sales

This phase deliberately does not silently switch existing checkout, return, cancellation, or accounting behavior. The checkout transaction must call `commercial_private.commercial_allocate_sale_item_cost(sale_item_id, location_id)` after creating each sale line and before decrementing its inventory balance. The existing mixed-selling-unit wrapper must allocate all resulting sale lines exactly once.

Return restocking must restore the original cost-layer allocations (not create a new purchase-cost layer), and sale cancellation must reverse the same allocations. Opening stock with unknown cost must be valued through an audited opening-stock workflow before it can be issued through the layer allocator.

The next integration phase must reconcile the resulting sale-item COGS snapshots with the existing inventory-asset / COGS journal lines and extend the inventory integrity checks. Until that is complete, the current checkout remains authoritative and this migration does not claim end-to-end FIFO/LIFO activation.

> LIFO is implemented as a technical valuation option; confirm the applicable accounting standards and local tax rules before using it for statutory reporting.
