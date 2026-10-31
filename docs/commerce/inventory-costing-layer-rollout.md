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

## Phase 3 — return and cancellation reversals

The additive migration `20261031100000_inventory_cost_return_cancel_reversals.sql` connects the existing return and sale-cancellation workflows:

- A successful return restock restores quantities to the exact original cost layers consumed by that sale line; it does not create a fictitious purchase receipt.
- Return-cost allocations preserve the source sale allocation, cost layer, quantity, and historical unit cost as a separate audit trail from the cash-refund posting. The return GL posting now uses these exact allocation totals for COGS reversal, with the original posting-cost calculation retained for legacy returns.
- Returned quantities are bounded by original unreturned sale allocations; any mismatch aborts the entire restock transaction.
- Weighted-average returns keep active layers aligned with the moving-average balance maintained by the existing restock RPC. FIFO/LIFO preserve the original layer costs.
- Sale cancellation restores only still-unreturned portions of original cost allocations and records explicit reversal rows.
- The existing cancellation journal reversal remains intact; the new trigger also reverses any additional sale-cost-adjustment journal.
- Cancellation is blocked while a return request remains unresolved, preventing a return from being orphaned after cancellation restores stock.
- The existing inventory balance remains the quantity source of truth; the cost-layer ledger is checked so it cannot exceed that balance.

### Remaining release work

- Add an audited opening-stock valuation workflow for balances whose cost is unknown; never fabricate historical costs.
- Organization-default and current-location costing-method controls are exposed in the cloud stock-alert screen, with server-side role enforcement. The same screen summarizes active cost-layer coverage and flags quantities not covered by known cost layers. Detailed exportable layer-allocation and reconciliation reports remain to be completed.
- Validate the migrations with authenticated receipt, checkout, partial-return, full-return, and cancellation transactions in a disposable or staging database before production deployment.

FIFO/LIFO/weighted-average now have repository-level foundations for receipts, checkout/COGS, returns, and cancellation reversals. Do not deploy these migrations to the live Supabase project until staging transaction tests and release checks are complete.

> LIFO is implemented as a technical valuation option; confirm the applicable accounting standards and local tax rules before using it for statutory reporting.
