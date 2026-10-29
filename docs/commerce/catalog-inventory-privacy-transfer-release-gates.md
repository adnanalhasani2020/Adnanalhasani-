# Catalog, inventory costing, phone authentication, and authorization release gates

## Implemented in the current feature branch
- Catalog discovery pagination, idempotent unpriced linking, organization/activity mapping, and onboarding handoff.
- Selected items can be configured to an existing activity, branch, warehouse and curated base/sale units.
- A catalog-backed inventory creation RPC creates the priced local offering, opening stock movement, activity mapping, branch/warehouse/unit setup, and explicit global-catalog offering mapping in one transaction. It uses an idempotency key and rejects conflicting retries; Android checks for an existing mapped offering before opening creation.
- Nearby popularity and price suggestions use explicit offering-to-global-item mappings and cohort aggregates. No fuzzy product-name joins, seller identities, exact seller counts, or individual prices are returned.
- Seller-invoice transfer requests require recipient approval; approval creates a draft purchase order with an active receiving location. Stock changes only through the normal receipt workflow.
- Activity profile supports Supabase phone SMS OTP request and verification for existing accounts, alongside email/password authentication. OTP requires the Supabase Auth SMS provider to be enabled and configured.

## Important warehouse limitation
- The existing inventory balance and checkout model is location-scoped, not warehouse-scoped. The new setup records the selected warehouse, but current stock balances and sale movements still use the branch/location key. Independent balances per warehouse require a coordinated migration of balance uniqueness, receipt/issue/return movements, checkout, and all reports; this is not falsely represented as completed.

## Inventory costing audit — valuation method must not be switched yet
- Current checkout reads one `commercial_inventory_balances.unit_cost_minor` value and snapshots it into sale lines; COGS is posted from that snapshot.
- Purchase receiving records purchase-line `unit_cost_minor` and calls `commercial_receive_inventory_stock`, but checkout does not select or allocate FIFO/LIFO cost layers.
- Return/restocking and opening/manual inventory paths also need cost-preserving reversal semantics.
- A real FIFO/LIFO/weighted-average implementation must atomically create cost layers at receipt, allocate each sale issue to layers, snapshot allocations per sale line, restore original layer cost on return, handle opening stock and adjustments, prevent negative layer quantities, remain idempotent, and reconcile inventory asset/COGS journals. LIFO may be restricted by accounting rules in some jurisdictions.
- Until those controls and reconciliation tests exist, the current balance-cost model remains authoritative. No valuation toggle was added.

## Authorization review
- Existing purchase receipt and checkout RPCs perform server-side membership/role checks; catalog write RPCs explicitly require owner/admin/manager and scope reads by organization.
- A complete exhaustive audit still requires enumerating every `SECURITY DEFINER` function, execute grant, RLS policy, and organization/branch-scoped read/write path, then exercising every role against a live staging database. Repository tests cannot prove deployed RLS behavior.
- Phone OTP verifies possession at the time of the SMS challenge only. The Android app does not claim daily SIM ownership verification; a normal app cannot reliably prove the same SIM remains present daily without platform/carrier support and server-side freshness gates on every protected operation.
- WhatsApp and voice OTP are not enabled by this UI; they require provider support/configuration and verified delivery testing.

## Rollout
- No live database migrations have been applied by this change. Stage these changes first; do not merge/deploy until database integration, two-organization isolation, role matrix, and current CI pass.
