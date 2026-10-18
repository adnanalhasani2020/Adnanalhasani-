-- Permit append-only, positive stock restoration movements for authorized Sale returns.
-- Rebuild the table because SQLite cannot widen a CHECK constraint in place.
CREATE TABLE inventory_movements_rebuilt (
    inventory_movement_id TEXT PRIMARY KEY,
    inventory_position_id TEXT NOT NULL REFERENCES inventory_positions(inventory_position_id),
    activity_id TEXT NOT NULL REFERENCES activities(activity_id),
    product_id TEXT NOT NULL REFERENCES products(product_id),
    offering_id TEXT NOT NULL REFERENCES offerings(offering_id),
    location_ref TEXT NULL,
    scope_key TEXT NOT NULL CHECK (length(trim(scope_key)) > 0),
    quantity_delta INTEGER NOT NULL CHECK (typeof(quantity_delta) = 'integer' AND quantity_delta <> 0),
    source_type TEXT NOT NULL CHECK (source_type IN ('sale','sale_return')),
    source_ref TEXT NOT NULL CHECK (length(trim(source_ref)) > 0),
    actor_context_ref TEXT NOT NULL CHECK (length(trim(actor_context_ref)) > 0),
    occurred_at TEXT NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE (source_type, source_ref)
);
INSERT INTO inventory_movements_rebuilt (
    inventory_movement_id, inventory_position_id, activity_id, product_id, offering_id,
    location_ref, scope_key, quantity_delta, source_type, source_ref, actor_context_ref,
    occurred_at, created_at
)
SELECT inventory_movement_id, inventory_position_id, activity_id, product_id, offering_id,
       location_ref, scope_key, quantity_delta, source_type, source_ref, actor_context_ref,
       occurred_at, created_at
FROM inventory_movements;
DROP TABLE inventory_movements;
ALTER TABLE inventory_movements_rebuilt RENAME TO inventory_movements;

CREATE INDEX ix_inventory_movements_scope_time
    ON inventory_movements(activity_id, product_id, offering_id, location_ref, scope_key, occurred_at);

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
OR (NEW.source_type='sale_return' AND (
    NEW.quantity_delta <= 0
    OR NOT EXISTS (
        SELECT 1 FROM inventory_movements original
        JOIN sale_lines l ON l.sale_id=original.source_ref
        JOIN sales s ON s.sale_id=original.source_ref
        WHERE original.source_type='sale'
          AND original.source_ref=NEW.source_ref
          AND original.inventory_position_id=NEW.inventory_position_id
          AND original.activity_id=NEW.activity_id
          AND original.product_id=NEW.product_id
          AND original.offering_id=NEW.offering_id
          AND original.location_ref IS NEW.location_ref
          AND original.scope_key=NEW.scope_key
          AND original.quantity_delta=-NEW.quantity_delta
          AND s.activity_id=NEW.activity_id
          AND s.offering_id=NEW.offering_id
          AND l.product_id=NEW.product_id
          AND l.quantity=NEW.quantity_delta
    )
))
BEGIN
    SELECT RAISE(ABORT, 'inventory movement must match effective position and source transaction');
END;

-- Reinstall the timestamp guards from migration 0049 on the rebuilt table.
-- Keep append-only stock effects inside the validity window of the position they reference.
-- A position may remain state='effective' after its effective_to timestamp; state alone
-- is not sufficient to establish that it was effective when a movement occurred.
CREATE TRIGGER inventory_movement_position_time_guard
BEFORE INSERT ON inventory_movements
WHEN NOT EXISTS (
    SELECT 1
    FROM inventory_positions p
    WHERE p.inventory_position_id = NEW.inventory_position_id
      -- Normalize timezone offsets to UTC whole seconds, then restore the
      -- fractional part as integer microseconds. julianday() rounds fractional
      -- seconds to milliseconds and would misclassify valid near-boundary instants.
      AND (CAST(strftime('%s', (CASE WHEN instr(p.effective_from, '.') = 0 THEN p.effective_from ELSE substr(p.effective_from, 1, instr(p.effective_from, '.') - 1) || (CASE WHEN instr(substr(p.effective_from, instr(p.effective_from, '.') + 1), 'Z') > 0 THEN 'Z' WHEN instr(substr(p.effective_from, instr(p.effective_from, '.') + 1), '+') > 0 THEN substr(substr(p.effective_from, instr(p.effective_from, '.') + 1), instr(substr(p.effective_from, instr(p.effective_from, '.') + 1), '+')) WHEN instr(substr(p.effective_from, instr(p.effective_from, '.') + 1), '-') > 0 THEN substr(substr(p.effective_from, instr(p.effective_from, '.') + 1), instr(substr(p.effective_from, instr(p.effective_from, '.') + 1), '-')) ELSE '' END) END)) AS INTEGER) * 1000000 + (CASE WHEN instr(p.effective_from, '.') = 0 THEN 0 ELSE CAST(substr((CASE WHEN instr(substr(p.effective_from, instr(p.effective_from, '.') + 1), 'Z') > 0 THEN substr(substr(p.effective_from, instr(p.effective_from, '.') + 1), 1, instr(substr(p.effective_from, instr(p.effective_from, '.') + 1), 'Z') - 1) WHEN instr(substr(p.effective_from, instr(p.effective_from, '.') + 1), '+') > 0 THEN substr(substr(p.effective_from, instr(p.effective_from, '.') + 1), 1, instr(substr(p.effective_from, instr(p.effective_from, '.') + 1), '+') - 1) WHEN instr(substr(p.effective_from, instr(p.effective_from, '.') + 1), '-') > 0 THEN substr(substr(p.effective_from, instr(p.effective_from, '.') + 1), 1, instr(substr(p.effective_from, instr(p.effective_from, '.') + 1), '-') - 1) ELSE substr(p.effective_from, instr(p.effective_from, '.') + 1) END || '000000'), 1, 6) AS INTEGER) END)) <= (CAST(strftime('%s', (CASE WHEN instr(NEW.occurred_at, '.') = 0 THEN NEW.occurred_at ELSE substr(NEW.occurred_at, 1, instr(NEW.occurred_at, '.') - 1) || (CASE WHEN instr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), 'Z') > 0 THEN 'Z' WHEN instr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), '+') > 0 THEN substr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), instr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), '+')) WHEN instr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), '-') > 0 THEN substr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), instr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), '-')) ELSE '' END) END)) AS INTEGER) * 1000000 + (CASE WHEN instr(NEW.occurred_at, '.') = 0 THEN 0 ELSE CAST(substr((CASE WHEN instr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), 'Z') > 0 THEN substr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), 1, instr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), 'Z') - 1) WHEN instr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), '+') > 0 THEN substr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), 1, instr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), '+') - 1) WHEN instr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), '-') > 0 THEN substr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), 1, instr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), '-') - 1) ELSE substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1) END || '000000'), 1, 6) AS INTEGER) END))
      AND (p.effective_to IS NULL OR (CAST(strftime('%s', (CASE WHEN instr(NEW.occurred_at, '.') = 0 THEN NEW.occurred_at ELSE substr(NEW.occurred_at, 1, instr(NEW.occurred_at, '.') - 1) || (CASE WHEN instr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), 'Z') > 0 THEN 'Z' WHEN instr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), '+') > 0 THEN substr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), instr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), '+')) WHEN instr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), '-') > 0 THEN substr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), instr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), '-')) ELSE '' END) END)) AS INTEGER) * 1000000 + (CASE WHEN instr(NEW.occurred_at, '.') = 0 THEN 0 ELSE CAST(substr((CASE WHEN instr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), 'Z') > 0 THEN substr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), 1, instr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), 'Z') - 1) WHEN instr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), '+') > 0 THEN substr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), 1, instr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), '+') - 1) WHEN instr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), '-') > 0 THEN substr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), 1, instr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), '-') - 1) ELSE substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1) END || '000000'), 1, 6) AS INTEGER) END)) < (CAST(strftime('%s', (CASE WHEN instr(p.effective_to, '.') = 0 THEN p.effective_to ELSE substr(p.effective_to, 1, instr(p.effective_to, '.') - 1) || (CASE WHEN instr(substr(p.effective_to, instr(p.effective_to, '.') + 1), 'Z') > 0 THEN 'Z' WHEN instr(substr(p.effective_to, instr(p.effective_to, '.') + 1), '+') > 0 THEN substr(substr(p.effective_to, instr(p.effective_to, '.') + 1), instr(substr(p.effective_to, instr(p.effective_to, '.') + 1), '+')) WHEN instr(substr(p.effective_to, instr(p.effective_to, '.') + 1), '-') > 0 THEN substr(substr(p.effective_to, instr(p.effective_to, '.') + 1), instr(substr(p.effective_to, instr(p.effective_to, '.') + 1), '-')) ELSE '' END) END)) AS INTEGER) * 1000000 + (CASE WHEN instr(p.effective_to, '.') = 0 THEN 0 ELSE CAST(substr((CASE WHEN instr(substr(p.effective_to, instr(p.effective_to, '.') + 1), 'Z') > 0 THEN substr(substr(p.effective_to, instr(p.effective_to, '.') + 1), 1, instr(substr(p.effective_to, instr(p.effective_to, '.') + 1), 'Z') - 1) WHEN instr(substr(p.effective_to, instr(p.effective_to, '.') + 1), '+') > 0 THEN substr(substr(p.effective_to, instr(p.effective_to, '.') + 1), 1, instr(substr(p.effective_to, instr(p.effective_to, '.') + 1), '+') - 1) WHEN instr(substr(p.effective_to, instr(p.effective_to, '.') + 1), '-') > 0 THEN substr(substr(p.effective_to, instr(p.effective_to, '.') + 1), 1, instr(substr(p.effective_to, instr(p.effective_to, '.') + 1), '-') - 1) ELSE substr(p.effective_to, instr(p.effective_to, '.') + 1) END || '000000'), 1, 6) AS INTEGER) END)))
)
BEGIN
    SELECT RAISE(ABORT, 'inventory movement must occur within its position effective window');
END;


-- The comparison above is defined at microsecond precision. Reject inputs that
-- would otherwise be silently truncated by the integer-microsecond conversion.
CREATE TRIGGER inventory_movement_position_time_precision_guard
BEFORE INSERT ON inventory_movements
WHEN (instr(NEW.occurred_at, '.') > 0 AND length(CASE WHEN instr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), 'Z') > 0 THEN substr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), 1, instr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), 'Z') - 1) WHEN instr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), '+') > 0 THEN substr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), 1, instr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), '+') - 1) WHEN instr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), '-') > 0 THEN substr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), 1, instr(substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1), '-') - 1) ELSE substr(NEW.occurred_at, instr(NEW.occurred_at, '.') + 1) END) > 6)
  OR EXISTS (
      SELECT 1 FROM inventory_positions p
      WHERE p.inventory_position_id = NEW.inventory_position_id
        AND ((instr(p.effective_from, '.') > 0 AND length(CASE WHEN instr(substr(p.effective_from, instr(p.effective_from, '.') + 1), 'Z') > 0 THEN substr(substr(p.effective_from, instr(p.effective_from, '.') + 1), 1, instr(substr(p.effective_from, instr(p.effective_from, '.') + 1), 'Z') - 1) WHEN instr(substr(p.effective_from, instr(p.effective_from, '.') + 1), '+') > 0 THEN substr(substr(p.effective_from, instr(p.effective_from, '.') + 1), 1, instr(substr(p.effective_from, instr(p.effective_from, '.') + 1), '+') - 1) WHEN instr(substr(p.effective_from, instr(p.effective_from, '.') + 1), '-') > 0 THEN substr(substr(p.effective_from, instr(p.effective_from, '.') + 1), 1, instr(substr(p.effective_from, instr(p.effective_from, '.') + 1), '-') - 1) ELSE substr(p.effective_from, instr(p.effective_from, '.') + 1) END) > 6) OR (p.effective_to IS NOT NULL AND (instr(p.effective_to, '.') > 0 AND length(CASE WHEN instr(substr(p.effective_to, instr(p.effective_to, '.') + 1), 'Z') > 0 THEN substr(substr(p.effective_to, instr(p.effective_to, '.') + 1), 1, instr(substr(p.effective_to, instr(p.effective_to, '.') + 1), 'Z') - 1) WHEN instr(substr(p.effective_to, instr(p.effective_to, '.') + 1), '+') > 0 THEN substr(substr(p.effective_to, instr(p.effective_to, '.') + 1), 1, instr(substr(p.effective_to, instr(p.effective_to, '.') + 1), '+') - 1) WHEN instr(substr(p.effective_to, instr(p.effective_to, '.') + 1), '-') > 0 THEN substr(substr(p.effective_to, instr(p.effective_to, '.') + 1), 1, instr(substr(p.effective_to, instr(p.effective_to, '.') + 1), '-') - 1) ELSE substr(p.effective_to, instr(p.effective_to, '.') + 1) END) > 6)))
  )
BEGIN
    SELECT RAISE(ABORT, 'inventory movement timestamps support at most 6 fractional digits');
END;

