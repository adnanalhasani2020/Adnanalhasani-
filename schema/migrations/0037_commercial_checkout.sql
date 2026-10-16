-- Commercial MVP: explicit price snapshots, append-only stock movements, and replayable checkout receipts.
-- InventoryPosition remains an observation; checkout effects are represented separately.

CREATE TABLE offering_prices (
    offering_price_id TEXT PRIMARY KEY,
    offering_id TEXT NOT NULL REFERENCES offerings(offering_id),
    unit_price_minor INTEGER NOT NULL CHECK (typeof(unit_price_minor) = 'integer' AND unit_price_minor >= 0),
    currency_code TEXT NOT NULL CHECK (currency_code IN ('SAR','YER','USD')),
    effective_from TEXT NOT NULL,
    effective_to TEXT NULL,
    created_at TEXT NOT NULL,
    CHECK (effective_to IS NULL OR effective_to > effective_from)
);

CREATE INDEX ix_offering_prices_lookup
    ON offering_prices(offering_id, effective_from, effective_to);

CREATE TABLE sale_lines (
    sale_line_id TEXT PRIMARY KEY,
    sale_id TEXT NOT NULL REFERENCES sales(sale_id),
    offering_id TEXT NOT NULL REFERENCES offerings(offering_id),
    product_id TEXT NOT NULL REFERENCES products(product_id),
    quantity INTEGER NOT NULL CHECK (typeof(quantity) = 'integer' AND quantity > 0),
    unit_price_minor INTEGER NOT NULL CHECK (typeof(unit_price_minor) = 'integer' AND unit_price_minor >= 0),
    currency_code TEXT NOT NULL CHECK (currency_code IN ('SAR','YER','USD')),
    line_total_minor INTEGER NOT NULL CHECK (typeof(line_total_minor) = 'integer' AND line_total_minor >= 0 AND line_total_minor = quantity * unit_price_minor),
    created_at TEXT NOT NULL,
    UNIQUE (sale_id, offering_id)
);

CREATE TABLE inventory_movements (
    inventory_movement_id TEXT PRIMARY KEY,
    inventory_position_id TEXT NOT NULL REFERENCES inventory_positions(inventory_position_id),
    activity_id TEXT NOT NULL REFERENCES activities(activity_id),
    product_id TEXT NOT NULL REFERENCES products(product_id),
    offering_id TEXT NOT NULL REFERENCES offerings(offering_id),
    location_ref TEXT NULL,
    scope_key TEXT NOT NULL CHECK (length(trim(scope_key)) > 0),
    quantity_delta INTEGER NOT NULL CHECK (typeof(quantity_delta) = 'integer' AND quantity_delta <> 0),
    source_type TEXT NOT NULL CHECK (source_type IN ('sale','return','receipt','adjustment')),
    source_ref TEXT NOT NULL CHECK (length(trim(source_ref)) > 0),
    actor_context_ref TEXT NOT NULL CHECK (length(trim(actor_context_ref)) > 0),
    occurred_at TEXT NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE (source_type, source_ref)
);

CREATE INDEX ix_inventory_movements_scope_time
    ON inventory_movements(activity_id, product_id, offering_id, location_ref, scope_key, occurred_at);

CREATE TABLE commercial_checkout_receipts (
    actor_person_id TEXT NOT NULL REFERENCES persons(person_id),
    idempotency_key TEXT NOT NULL CHECK (length(trim(idempotency_key)) BETWEEN 1 AND 200),
    request_hash TEXT NOT NULL,
    response_json TEXT NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY (actor_person_id, idempotency_key)
);

CREATE TRIGGER inventory_movement_immutable_update
BEFORE UPDATE ON inventory_movements
BEGIN
    SELECT RAISE(ABORT, 'inventory movements are immutable');
END;

CREATE TRIGGER inventory_movement_immutable_delete
BEFORE DELETE ON inventory_movements
BEGIN
    SELECT RAISE(ABORT, 'inventory movements are immutable');
END;

CREATE TRIGGER sale_line_immutable_update
BEFORE UPDATE ON sale_lines
BEGIN
    SELECT RAISE(ABORT, 'sale lines are immutable');
END;

CREATE TRIGGER sale_line_immutable_delete
BEFORE DELETE ON sale_lines
BEGIN
    SELECT RAISE(ABORT, 'sale lines are immutable');
END;

CREATE TRIGGER sale_line_context_guard
BEFORE INSERT ON sale_lines
WHEN NOT EXISTS (
    SELECT 1 FROM sales s
    JOIN offerings o ON o.offering_id=s.offering_id
    WHERE s.sale_id=NEW.sale_id
      AND s.offering_id=NEW.offering_id
      AND o.product_id=NEW.product_id
      AND s.activity_id=o.activity_id
)
BEGIN
    SELECT RAISE(ABORT, 'sale line context must match its Sale and Offering');
END;

CREATE TRIGGER inventory_movement_context_guard
BEFORE INSERT ON inventory_movements
WHEN NOT EXISTS (
    SELECT 1 FROM inventory_positions p
    WHERE p.inventory_position_id=NEW.inventory_position_id
      AND p.activity_id=NEW.activity_id
      AND p.product_id=NEW.product_id
      AND p.offering_id=NEW.offering_id
      AND p.location_ref IS NEW.location_ref
      AND p.scope_key=NEW.scope_key
      AND p.state='effective'
)
OR (NEW.source_type='sale' AND (
    NEW.quantity_delta >= 0
    OR NOT EXISTS (
        SELECT 1 FROM sales s
        JOIN sale_lines l ON l.sale_id=s.sale_id
        WHERE s.sale_id=NEW.source_ref
          AND s.offering_id=NEW.offering_id
          AND s.activity_id=NEW.activity_id
          AND l.product_id=NEW.product_id
          AND l.quantity=-NEW.quantity_delta
    )
))
BEGIN
    SELECT RAISE(ABORT, 'inventory movement must match effective position and source transaction');
END;
