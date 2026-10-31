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

## Phase 2 — checkout and COGS integration

The additive migration `20261031090000_inventory_cost_layers_checkout_integration.sql` connects the existing checkout without replacing its pricing, payment, stock, or mixed-selling-unit workflows:

- A deferred constraint trigger runs at transaction end, after the mixed-selling-unit wrapper has materialized the final sale-item rows.
- It allocates each final sale line against the configured FIFO, LIFO, or weighted-average layers and verifies the exact sale stock movement, offering, location, and total issued quantity.
- Insufficient or unknown cost layers abort the checkout transaction; stock, sale, allocations, and journal work do not partially commit.
- The trigger reconciles the allocated sale-item COGS against the original posted sale journal. If the valuation differs, it posts one idempotent balanced inventory/COGS adjustment using the existing GL posting routine.
- Replayed trigger events are idempotent and verify the existing adjustment rather than posting it twice.

### Remaining integration before declaring the full costing lifecycle complete

Return restocking must restore the original sale cost-layer quantities (not create a new purchase-cost layer), and sale cancellation must reverse the same allocations and any COGS adjustment. These paths are not yet changed by Phase 2 because their existing posting contracts need to be connected without duplicating stock or journal entries. Opening stock with unknown cost must be valued through an audited opening-stock workflow before it can be issued through the layer allocator.

Until return and cancellation reversals are integrated and tested, FIFO/LIFO checkout costing is integrated, but the full sale-return-cancellation lifecycle is not complete.

> LIFO is implemented as a technical valuation option; confirm the applicable accounting standards and local tax rules before using it for statutory reporting.
