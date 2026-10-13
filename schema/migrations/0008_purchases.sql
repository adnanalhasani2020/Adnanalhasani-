-- Additive procurement schema. Purchase receipts are evidence only; they do not mutate inventory.
CREATE TABLE purchases (
    purchase_id TEXT PRIMARY KEY,
    supplier_ref TEXT NOT NULL CHECK (length(trim(supplier_ref)) > 0),
    activity_id TEXT NOT NULL REFERENCES activities(activity_id),
    state TEXT NOT NULL CHECK (state IN (
        'draft','submitted','approved','ordered','partially_received','received','cancelled'
    )),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    created_by_ref TEXT NOT NULL,
    create_idempotency_key TEXT NOT NULL UNIQUE,
    create_request_fingerprint TEXT NOT NULL,
    version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no > 0)
);

CREATE TABLE purchase_lines (
    purchase_line_id TEXT PRIMARY KEY,
    purchase_id TEXT NOT NULL REFERENCES purchases(purchase_id),
    product_id TEXT NOT NULL REFERENCES products(product_id),
    quantity_units INTEGER NOT NULL CHECK (quantity_units > 0),
    unit_label TEXT NOT NULL CHECK (length(trim(unit_label)) > 0),
    unit_cost_minor INTEGER NULL CHECK (unit_cost_minor IS NULL OR unit_cost_minor >= 0),
    currency_code TEXT NULL CHECK (
        currency_code IS NULL OR (
            length(currency_code) = 3
            AND currency_code = upper(currency_code)
            AND currency_code GLOB '[A-Z][A-Z][A-Z]'
        )
    ),
    received_quantity_units INTEGER NOT NULL DEFAULT 0
        CHECK (received_quantity_units >= 0 AND received_quantity_units <= quantity_units),
    created_at TEXT NOT NULL,
    CHECK ((unit_cost_minor IS NULL AND currency_code IS NULL)
        OR (unit_cost_minor IS NOT NULL AND currency_code IS NOT NULL))
);

CREATE TABLE purchase_receipts (
    receipt_id TEXT PRIMARY KEY,
    purchase_id TEXT NOT NULL REFERENCES purchases(purchase_id),
    purchase_line_id TEXT NOT NULL REFERENCES purchase_lines(purchase_line_id),
    quantity_units INTEGER NOT NULL CHECK (quantity_units > 0),
    received_at TEXT NOT NULL,
    actor_context_ref TEXT NOT NULL,
    idempotency_key TEXT NOT NULL UNIQUE,
    request_fingerprint TEXT NOT NULL
);

CREATE INDEX idx_purchases_activity_state_time ON purchases(activity_id, state, created_at);
CREATE INDEX idx_purchases_supplier ON purchases(supplier_ref);
CREATE INDEX idx_purchase_lines_purchase ON purchase_lines(purchase_id);
CREATE INDEX idx_purchase_lines_product ON purchase_lines(product_id);
CREATE INDEX idx_purchase_receipts_purchase_time ON purchase_receipts(purchase_id, received_at);
CREATE INDEX idx_purchase_receipts_line ON purchase_receipts(purchase_line_id);

INSERT INTO schema_migrations(version, applied_at)
VALUES ('0008_purchases', strftime('%Y-%m-%dT%H:%M:%fZ','now'));
