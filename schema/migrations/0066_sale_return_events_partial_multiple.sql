-- Canonical, append-only return events and line quantities.
-- A return movement references one return line, never the Sale itself, so a Sale can
-- have multiple return events while each line remains cumulatively quantity-bounded.
CREATE TABLE sale_return_events (
    return_event_id TEXT PRIMARY KEY,
    sale_id TEXT NOT NULL REFERENCES sales(sale_id),
    idempotency_key TEXT NOT NULL CHECK (length(trim(idempotency_key)) BETWEEN 8 AND 200),
    request_fingerprint TEXT NOT NULL CHECK (length(trim(request_fingerprint)) > 0),
    reason TEXT NOT NULL CHECK (length(trim(reason)) BETWEEN 3 AND 2000),
    actor_context_ref TEXT NOT NULL CHECK (length(trim(actor_context_ref)) > 0),
    created_at TEXT NOT NULL,
    UNIQUE (sale_id, idempotency_key)
);

CREATE TABLE sale_return_lines (
    sale_return_line_id TEXT PRIMARY KEY,
    return_event_id TEXT NOT NULL REFERENCES sale_return_events(return_event_id),
    sale_line_id TEXT NOT NULL REFERENCES sale_lines(sale_line_id),
    quantity INTEGER NOT NULL CHECK (typeof(quantity) = 'integer' AND quantity > 0),
    created_at TEXT NOT NULL,
    UNIQUE (return_event_id, sale_line_id)
);

CREATE INDEX ix_sale_return_lines_sale_line
    ON sale_return_lines(sale_line_id, return_event_id);

-- Preserve pre-existing full-return movements as completed legacy events before changing
-- their source references. The prior schema allowed at most one full return per Sale.
INSERT INTO sale_return_events(
    return_event_id,sale_id,idempotency_key,request_fingerprint,reason,actor_context_ref,created_at
)
SELECT m.source_ref,m.source_ref,'legacy-full-return:'||m.source_ref,'legacy-backfill',
       'legacy_full_return',m.actor_context_ref,m.created_at
FROM inventory_movements m
WHERE m.source_type='sale_return'
GROUP BY m.source_ref,m.actor_context_ref,m.created_at;

INSERT INTO sale_return_lines(
    sale_return_line_id,return_event_id,sale_line_id,quantity,created_at
)
SELECT m.inventory_movement_id,m.source_ref,l.sale_line_id,m.quantity_delta,m.created_at
FROM inventory_movements m
JOIN sale_lines l ON l.sale_id=m.source_ref
                 AND l.offering_id=m.offering_id
                 AND l.product_id=m.product_id
                 AND l.quantity=m.quantity_delta
WHERE m.source_type='sale_return';

-- Legacy return movement source_ref was the Sale ID. Re-key it to the new return-line ID.
DROP TRIGGER IF EXISTS inventory_movement_immutable_update;
UPDATE inventory_movements
SET source_ref=inventory_movement_id
WHERE source_type='sale_return';
CREATE TRIGGER inventory_movement_immutable_update
BEFORE UPDATE ON inventory_movements
BEGIN
    SELECT RAISE(ABORT, 'inventory movements are immutable');
END;

-- Database-level cumulative quantity guard; application validation is not the only boundary.
CREATE TRIGGER sale_return_line_quantity_guard
BEFORE INSERT ON sale_return_lines
WHEN NOT EXISTS (
    SELECT 1
    FROM sale_return_events e
    JOIN sales s ON s.sale_id=e.sale_id
    JOIN sale_lines l ON l.sale_line_id=NEW.sale_line_id AND l.sale_id=e.sale_id
    WHERE e.return_event_id=NEW.return_event_id
      AND s.state IN ('completed','fulfilled')
      AND COALESCE((
          SELECT SUM(prior.quantity)
          FROM sale_return_lines prior
          JOIN sale_return_events prior_event ON prior_event.return_event_id=prior.return_event_id
          WHERE prior.sale_line_id=NEW.sale_line_id
      ),0) + NEW.quantity <= l.quantity
)
BEGIN
    SELECT RAISE(ABORT, 'cumulative Sale return quantity exceeds sold quantity or source scope');
END;

-- Rebuild the source/context guard to bind each return movement to its exact return line.
DROP TRIGGER IF EXISTS inventory_movement_context_guard;
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
        SELECT 1
        FROM sale_return_lines rl
        JOIN sale_return_events re ON re.return_event_id=rl.return_event_id
        JOIN sale_lines l ON l.sale_line_id=rl.sale_line_id AND l.sale_id=re.sale_id
        JOIN sales s ON s.sale_id=re.sale_id
        JOIN inventory_movements original
          ON original.source_type='sale' AND original.source_ref=s.sale_id
        WHERE rl.sale_return_line_id=NEW.source_ref
          AND rl.quantity=NEW.quantity_delta
          AND original.inventory_position_id=NEW.inventory_position_id
          AND original.activity_id=NEW.activity_id
          AND original.product_id=NEW.product_id
          AND original.offering_id=NEW.offering_id
          AND original.location_ref IS NEW.location_ref
          AND original.scope_key=NEW.scope_key
          AND original.quantity_delta=-l.quantity
          AND s.activity_id=NEW.activity_id
          AND s.offering_id=NEW.offering_id
          AND l.product_id=NEW.product_id
          AND NEW.quantity_delta<=l.quantity
    )
))
BEGIN
    SELECT RAISE(ABORT, 'inventory movement must match effective position and source transaction');
END;

DROP TRIGGER IF EXISTS inventory_sale_return_invoice_reconciliation_guard;
CREATE TRIGGER inventory_sale_return_invoice_reconciliation_guard
BEFORE INSERT ON inventory_movements
WHEN NEW.source_type='sale_return'
 AND EXISTS (
    SELECT 1
    FROM sale_return_lines rl
    JOIN sale_return_events re ON re.return_event_id=rl.return_event_id
    JOIN invoices i ON i.sale_id=re.sale_id
    WHERE rl.sale_return_line_id=NEW.source_ref AND i.state='issued'
 )
BEGIN
    SELECT RAISE(ABORT, 'Sale return requires explicit Invoice reconciliation first');
END;

CREATE TRIGGER sale_return_event_immutable_update
BEFORE UPDATE ON sale_return_events
BEGIN
    SELECT RAISE(ABORT, 'Sale return events are immutable');
END;
CREATE TRIGGER sale_return_event_immutable_delete
BEFORE DELETE ON sale_return_events
BEGIN
    SELECT RAISE(ABORT, 'Sale return events are immutable');
END;
CREATE TRIGGER sale_return_line_immutable_update
BEFORE UPDATE ON sale_return_lines
BEGIN
    SELECT RAISE(ABORT, 'Sale return lines are immutable');
END;
CREATE TRIGGER sale_return_line_immutable_delete
BEFORE DELETE ON sale_return_lines
BEGIN
    SELECT RAISE(ABORT, 'Sale return lines are immutable');
END;
