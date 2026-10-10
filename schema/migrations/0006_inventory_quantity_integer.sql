-- quantity_minor is an integer minor-unit count. SQLite INTEGER affinity alone
-- still accepts REAL values, so guard writes at the persistence boundary.
CREATE TRIGGER inventory_position_quantity_integer_on_insert
BEFORE INSERT ON inventory_positions
WHEN NEW.quantity_minor IS NOT NULL
 AND typeof(NEW.quantity_minor) <> 'integer'
BEGIN
    SELECT RAISE(ABORT, 'inventory position quantity_minor must be an integer');
END;

CREATE TRIGGER inventory_position_quantity_integer_on_update
BEFORE UPDATE OF quantity_minor ON inventory_positions
WHEN NEW.quantity_minor IS NOT NULL
 AND typeof(NEW.quantity_minor) <> 'integer'
BEGIN
    SELECT RAISE(ABORT, 'inventory position quantity_minor must be an integer');
END;
