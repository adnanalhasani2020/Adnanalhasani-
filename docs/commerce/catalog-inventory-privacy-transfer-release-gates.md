# Catalog, inventory costing, phone authentication, and authorization release gates

## Implemented in main
- Catalog discovery pagination, idempotent unpriced linking, organization/activity mapping, and onboarding handoff.
- Selected items can be configured to an existing activity, branch, warehouse and curated base/sale units.
- A catalog-backed inventory creation RPC creates the priced local offering, opening stock movement, activity mapping, branch/warehouse/unit setup, and explicit global-catalog offering mapping in one transaction. It uses an idempotency key and rejects conflicting retries; Android checks for an existing mapped offering before opening creation.
- Nearby popularity uses completed invoice transaction counts (not incomparable raw quantities across different units). Price suggestions first reduce each merchant's branch prices to one median, then calculate market quartiles across merchants; results use explicit offering-to-global-item mappings and cohort aggregates. No fuzzy product-name joins, seller identities, exact seller counts, or individual prices are returned.
- Seller-invoice transfer requests require recipient approval; approval creates a draft purchase order with an active receiving location. Stock changes only through the normal receipt workflow.
- Activity profile supports Supabase phone SMS OTP request and verification for existing accounts, alongside email/password authentication. OTP requires the Supabase Auth SMS provider to be enabled and configured.

## Important warehouse limitation
- The existing inventory balance and checkout model is location-scoped, not warehouse-scoped. The new setup records the selected warehouse, but current stock balances and sale movements still use the branch/location key. Independent balances per warehouse require a coordinated migration of balance uniqueness, receipt/issue/return movements, checkout, and all reports; this is not falsely represented as completed.

## Inventory costing audit — valuation method must not be switched yet
- Current checkout reads one `commercial_inventory_balances.unit_cost_minor` value and snapshots it into sale lines; COGS is posted from that snapshot.
- Purchase receiving records purchase-line `unit_cost_minor` and calls `commercial_receive_inventory_stock`, but checkout does not select or allocate FIFO/LIFO cost layers.
- Return/restocking and opening/manual inventory paths also need cost-preserving reversal semantics.
- **Rolling weighted average is already implemented** in the current balance-cost model: purchase receipts recompute `commercial_inventory_balances.unit_cost_minor` from the prior balance and incoming receipt, checkout snapshots that cost on sale lines, and COGS uses the sale-line snapshot. This is not a FIFO/LIFO layer engine.
- FIFO and LIFO are not implemented. They require cost layers per organization/location/offering, atomic issue allocations, per-line cost snapshots, original-layer restoration on returns, opening/adjustment handling, negative-layer prevention, idempotency, and inventory-asset/COGS journal reconciliation. LIFO may be restricted by accounting rules in some jurisdictions.
- Return restocking currently restores stock at the original sale-line `unit_cost_minor` and updates the rolling average. The GL return routine reverses COGS using the sum of restock-posting quantity multiplied by its stored unit cost. The accounting integrity view currently verifies posting/movement quantities, but it does not yet verify the cost basis, offering/location identity, or the matching inventory/COGS journal amounts. A live read-only comparison on 2026-10-31 found zero rows violating the compared posting-vs-sale-line cost/quantity/offering/organization/movement checks; this is a snapshot, not a guarantee against future corruption.
- The current rolling-average balance model remains authoritative until the additional cost/journal integrity checks and FIFO/LIFO requirements are implemented. No valuation toggle was added.

## Authorization review
- Existing purchase receipt and checkout RPCs perform server-side membership/role checks; catalog write RPCs explicitly require owner/admin/manager and scope reads by organization.
- A complete exhaustive audit still requires enumerating every `SECURITY DEFINER` function, execute grant, RLS policy, and organization/branch-scoped read/write path, then exercising every role against a live staging database. Repository tests cannot prove deployed RLS behavior.
- Phone OTP verifies possession at the time of the SMS challenge only. The Android app does not claim daily SIM ownership verification; a normal app cannot reliably prove the same SIM remains present daily without platform/carrier support and server-side freshness gates on every protected operation.
- WhatsApp and voice OTP are not enabled by this UI; they require provider support/configuration and verified delivery testing.

## Rollout
- No live database migrations have been applied by this change. Stage these changes first; do not merge/deploy until database integration, two-organization isolation, role matrix, and current CI pass.
