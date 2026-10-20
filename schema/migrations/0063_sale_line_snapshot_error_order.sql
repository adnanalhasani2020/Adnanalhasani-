-- Preserve the more actionable empty-snapshot error from migration 0060.
-- Validate name matching only when a non-empty snapshot is present.
DROP TRIGGER IF EXISTS sale_line_product_name_snapshot_match_guard;

CREATE TRIGGER sale_line_product_name_snapshot_match_guard
BEFORE INSERT ON sale_lines
WHEN length(trim(NEW.product_name_snapshot)) > 0
  AND NEW.product_name_snapshot IS NOT (
      SELECT p.name FROM products p WHERE p.product_id = NEW.product_id
  )
BEGIN
    SELECT RAISE(ABORT, 'sale line product name snapshot must match Product at checkout');
END;
