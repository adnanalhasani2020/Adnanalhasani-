-- Persist an auditable, idempotent opening-stock snapshot linked to the effective Inventory Position.
-- This is a starting quantity snapshot, not an inventory movement or purchase receipt.

CREATE TABLE inventory_opening_stock (
    opening_stock_id TEXT PRIMARY KEY,
    inventory_position_id TEXT NOT NULL UNIQUE
        REFERENCES inventory_positions(inventory_position_id),
    activity_id TEXT NOT NULL REFERENCES activities(activity_id),
    product_id TEXT NOT NULL REFERENCES products(product_id),
    offering_id TEXT NULL REFERENCES offerings(offering_id),
    location_ref TEXT NULL,
    scope_key TEXT NOT NULL CHECK (length(trim(scope_key)) > 0),
    quantity_minor INTEGER NOT NULL
        CHECK (typeof(quantity_minor) = 'integer' AND quantity_minor >= 0),
    source_ref TEXT NOT NULL
        CHECK (length(trim(source_ref)) BETWEEN 1 AND 200),
    idempotency_key TEXT NOT NULL UNIQUE
        CHECK (length(trim(idempotency_key)) BETWEEN 1 AND 200),
    actor_context_ref TEXT NOT NULL
        CHECK (length(trim(actor_context_ref)) > 0),
    opening_at TEXT NOT NULL,
    recorded_at TEXT NOT NULL
);

-- NULL Offering/Location values must participate in uniqueness, not bypass it.
CREATE UNIQUE INDEX uq_inventory_opening_stock_scope
    ON inventory_opening_stock (
        activity_id,
        product_id,
        COALESCE(offering_id, ''),
        COALESCE(location_ref, ''),
        scope_key
    );
