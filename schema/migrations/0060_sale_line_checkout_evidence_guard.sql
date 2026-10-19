-- Enforce the evidence required for a checkout line at the persistence boundary.
-- Historical rows may keep an empty legacy snapshot; new rows must preserve a name.
CREATE TRIGGER sale_line_checkout_evidence_guard
BEFORE INSERT ON sale_lines
WHEN length(trim(NEW.product_name_snapshot)) = 0
  OR NOT EXISTS (
      SELECT 1 FROM sales s
      WHERE s.sale_id = NEW.sale_id
        AND s.state IN ('completed', 'fulfilled')
  )
BEGIN
    SELECT RAISE(ABORT, 'sale line requires a fulfilled Sale and a non-empty product name snapshot');
END;
