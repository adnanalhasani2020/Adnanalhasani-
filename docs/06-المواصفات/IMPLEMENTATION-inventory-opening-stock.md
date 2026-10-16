# Inventory Opening Stock — Implementation Contract

## Purpose

Record the initial counted quantity for an existing Product in one Activity and stock scope. The opening record links to the existing Inventory Position lifecycle and becomes an effective position, so the existing read path can return the opening quantity.

## Rules

- Product and Activity must already exist and be active. If an Offering is supplied, it must be active and match the Product and Activity.
- Quantity is a non-negative integer in the repository's current `quantity_minor` representation. No fractional quantities or unit conversions are inferred.
- An opening timestamp is required, timezone-aware, and cannot be in the future relative to the recording timestamp.
- A non-empty source/evidence reference and idempotency key are required.
- Replaying the same idempotency key with identical business data returns the existing position without adding records or history. Reusing the key with different data is rejected.
- Only one opening record may exist for the same Activity, Product, Offering (if any), Location (if any), and scope key. A unique database index protects this invariant under concurrent writers.
- The Inventory Position, its lifecycle history, and the opening-stock record are written atomically. Database triggers verify that the opening record exactly matches its effective position, and prevent editing or deleting the opening audit record.
- The opening record is a starting snapshot, not a stock movement. Purchase receipt evidence, sales, reservations, and stock deduction remain separate; this feature does not post those effects.
- Product-level reads filter by Product, Activity, Offering context, and exact Location (including NULL-location scope). When no Offering or Location is specified, only positions with no Offering and no Location are considered, preventing unrelated scopes from being mixed.
- Once an opening stock is recorded, the linked position's quantity and scope/timing facts cannot be silently rewritten; lifecycle state/version transitions remain allowed. Corrections require a separately designed auditable workflow.
