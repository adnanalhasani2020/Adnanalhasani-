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

-- The opening snapshot must describe the exact effective position it references.
CREATE TRIGGER trg_inventory_opening_stock_position_consistency
BEFORE INSERT ON inventory_opening_stock
WHEN NOT EXISTS (
    SELECT 1
    FROM inventory_positions AS p
    WHERE p.inventory_position_id = NEW.inventory_position_id
      AND p.activity_id = NEW.activity_id
      AND p.product_id = NEW.product_id
      AND p.offering_id IS NEW.offering_id
      AND p.location_ref IS NEW.location_ref
      AND p.scope_key = NEW.scope_key
      AND p.quantity_minor = NEW.quantity_minor
      AND p.state = 'effective'
)
BEGIN
    SELECT RAISE(ABORT, 'opening stock must match its effective Inventory Position');
END;

-- Opening stock is an immutable audit fact. Corrections must be explicit,
-- separately recorded actions rather than silent edits or deletion.
CREATE TRIGGER trg_inventory_opening_stock_immutable_update
BEFORE UPDATE ON inventory_opening_stock
BEGIN
    SELECT RAISE(ABORT, 'opening stock records are immutable');
END;

CREATE TRIGGER trg_inventory_opening_stock_immutable_delete
BEFORE DELETE ON inventory_opening_stock
BEGIN
    SELECT RAISE(ABORT, 'opening stock records are immutable');
END;

-- Preserve the immutable opening quantity and scope on its linked position.
-- Lifecycle state/version updates remain allowed; changing the observed stock
-- fact itself requires a separately designed correction workflow.
CREATE TRIGGER trg_inventory_opening_position_snapshot_immutable
BEFORE UPDATE OF activity_id, product_id, offering_id, location_ref, scope_key,
                 quantity_minor, observed_at, effective_from, effective_to
ON inventory_positions
WHEN EXISTS (
    SELECT 1 FROM inventory_opening_stock AS opening
    WHERE opening.inventory_position_id = OLD.inventory_position_id
)
AND (
       OLD.activity_id IS NOT NEW.activity_id
    OR OLD.product_id IS NOT NEW.product_id
    OR OLD.offering_id IS NOT NEW.offering_id
    OR OLD.location_ref IS NOT NEW.location_ref
    OR OLD.scope_key IS NOT NEW.scope_key
    OR OLD.quantity_minor IS NOT NEW.quantity_minor
    OR OLD.observed_at IS NOT NEW.observed_at
    OR OLD.effective_from IS NOT NEW.effective_from
    OR OLD.effective_to IS NOT NEW.effective_to
)
BEGIN
    SELECT RAISE(ABORT, 'opening Inventory Position snapshot is immutable');
END;
