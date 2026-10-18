-- Keep append-only stock effects inside the validity window of the position they reference.
-- A position may remain state='effective' after its effective_to timestamp; state alone
-- is not sufficient to establish that it was effective when a movement occurred.
CREATE TRIGGER inventory_movement_position_time_guard
BEFORE INSERT ON inventory_movements
WHEN NOT EXISTS (
    SELECT 1
    FROM inventory_positions p
    WHERE p.inventory_position_id = NEW.inventory_position_id
      -- SQLite TEXT ordering is not chronological across equivalent ISO-8601
      -- instants with different UTC offsets. julianday() compares normalized instants.
      AND julianday(p.effective_from) <= julianday(NEW.occurred_at)
      AND (p.effective_to IS NULL OR julianday(NEW.occurred_at) < julianday(p.effective_to))
)
BEGIN
    SELECT RAISE(ABORT, 'inventory movement must occur within its position effective window');
END;
