-- Preserve the contextual integrity of Inventory Positions linked to an Offering.
-- This migration adds guards only; it does not rewrite historical rows or quantities.
CREATE TRIGGER inventory_position_offering_context_on_insert
BEFORE INSERT ON inventory_positions
WHEN NEW.offering_id IS NOT NULL
 AND NOT EXISTS (
    SELECT 1
      FROM offerings AS o
     WHERE o.offering_id = NEW.offering_id
       AND o.activity_id = NEW.activity_id
       AND (NEW.product_id IS NULL OR o.product_id = NEW.product_id)
 )
BEGIN
    SELECT RAISE(ABORT, 'inventory position context must match its offering');
END;

CREATE TRIGGER inventory_position_offering_context_on_update
BEFORE UPDATE OF offering_id, activity_id, product_id ON inventory_positions
WHEN NEW.offering_id IS NOT NULL
 AND NOT EXISTS (
    SELECT 1
      FROM offerings AS o
     WHERE o.offering_id = NEW.offering_id
       AND o.activity_id = NEW.activity_id
       AND (NEW.product_id IS NULL OR o.product_id = NEW.product_id)
 )
BEGIN
    SELECT RAISE(ABORT, 'inventory position context must match its offering');
END;

CREATE TRIGGER offering_update_preserves_inventory_position_context
BEFORE UPDATE OF activity_id, product_id ON offerings
WHEN EXISTS (
    SELECT 1
      FROM inventory_positions AS ip
     WHERE ip.offering_id = OLD.offering_id
       AND (
            ip.activity_id <> NEW.activity_id
            OR (
                ip.product_id IS NOT NULL
                AND (NEW.product_id IS NULL OR ip.product_id <> NEW.product_id)
            )
       )
)
BEGIN
    SELECT RAISE(ABORT, 'offering update would break inventory position context');
END;
