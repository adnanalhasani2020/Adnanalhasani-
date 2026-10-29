# Catalog and transfer implementation gates

## Backend foundation added in this batch
- Catalog items can be assigned to an existing activity, branch and warehouse; unit conversion is accepted only from curated product-unit records.
- New links remain unpriced and unavailable. Setup does not silently create opening stock.
- Historical offering identity must be explicitly bound to a global catalog item; no fuzzy product-name matching.
- Price suggestions use median/interquartile aggregates from active mapped offerings, matching currency and radius, with at least five distinct other organizations. Seller identities, addresses, exact counts and individual prices are not returned.
- Nearby popularity uses completed sales from the last 30 days, explicit item/offering mappings, progressive radius values capped at 100 km, pagination and a five-organization cohort threshold.
- Seller invoice transfer requests are idempotent. The destination organization must approve or reject. Approval creates a draft purchase order and supplier mapping; inventory remains unchanged until the existing receiving workflow is used.

## Remaining end-to-end release blockers
- Android screens are not yet wired to all new setup, price-suggestion, nearby-discovery and buyer-approval RPCs; backend contracts alone do not prove end-to-end completion.
- Historical offerings are not auto-mapped. An authorized user must bind exact product identities before they contribute to market aggregates.
- FIFO/LIFO/weighted-average must not be enabled by a setting alone. Current checkout consumes existing balance cost and sale-item cost snapshots. A safe implementation requires receipt cost layers, issue allocations, return reversals, transfers, idempotency, and GL reconciliation before changing checkout COGS.
- Phone OTP must use configured Supabase Auth SMS delivery and be integrated with Android registration/recovery. Daily SIM ownership cannot be guaranteed by an ordinary Android app without carrier/platform support.
- Complete role-by-role screen/operation audit and two-organization authenticated integration tests remain required.
- Migrations have not been applied to the live Supabase project; do not merge or deploy until staging validation and the release gates pass.
