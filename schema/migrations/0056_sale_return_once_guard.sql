-- A Sale may restore stock at most once. The application transition already
-- checks this invariant; this database guard prevents direct SQL from adding a
-- second positive compensating movement for the same Sale.
CREATE TRIGGER inventory_sale_return_once_insert
BEFORE INSERT ON inventory_movements
WHEN NEW.source_type = 'sale_return'
 AND EXISTS (
     SELECT 1 FROM inventory_movements existing
     WHERE existing.source_type = 'sale_return'
       AND existing.source_ref = NEW.source_ref
 )
BEGIN
    SELECT RAISE(ABORT, 'Sale stock has already been returned');
END;

CREATE TRIGGER inventory_sale_return_once_update
BEFORE UPDATE OF source_type, source_ref ON inventory_movements
WHEN NEW.source_type = 'sale_return'
 AND EXISTS (
     SELECT 1 FROM inventory_movements existing
     WHERE existing.source_type = 'sale_return'
       AND existing.source_ref = NEW.source_ref
       AND existing.inventory_movement_id <> OLD.inventory_movement_id
 )
BEGIN
    SELECT RAISE(ABORT, 'Sale stock has already been returned');
END;
