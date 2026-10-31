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

- A successful return restock restores quantities to the exact original cost layers consumed by that sale line. It does not create a fictitious purchase receipt.
- Return-cost allocations preserve the source sale allocation, cost layer, quantity, and historical unit cost as a separate audit trail from the cash-refund posting.
- Repeated or excessive return quantities are bounded by the original unreturned sale allocations; any mismatch aborts the entire restock transaction.
- Weighted-average returns keep active layers aligned with the moving-average balance maintained by the existing restock RPC. FIFO/LIFO preserve the original layer costs.
- Sale cancellation restores only the still-unreturned portions of original cost allocations and records explicit reversal rows.
- The existing cancellation journal reversal remains in place; the new trigger also reverses any additional sale-cost-adjustment journal exactly once.
- Cancellation is blocked while a sale has an unresolved return request, preventing a return from being orphaned after stock is restored by cancellation.
- The layer ledger is checked against the existing balance quantity without replacing the balance table as the quantity source of truth.

### Remaining work before calling the entire costing lifecycle production-complete

- Add an audited opening-stock valuation workflow for balances whose cost is unknown; these balances must not receive fabricated costs.
- Add operator-facing organization/branch costing-method settings and cost-layer valuation/reconciliation reports.
- Validate the migrations against a disposable/staging database with real authenticated checkout, receipt, partial-return, full-return, and cancellation transactions before production deployment.

FIFO/LIFO/weighted-average now have repository-level foundations for receipts, checkout/COGS, returns, and cancellation reversals. Do not deploy the migrations to the live Supabase project until the staging transaction tests and release checks are complete.

> LIFO is implemented as a technical valuation option; confirm the applicable accounting standards and local tax rules before using it for statutory reporting.
