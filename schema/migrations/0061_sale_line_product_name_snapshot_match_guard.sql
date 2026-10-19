-- A checkout line's name snapshot must match the Product name at insertion time.
-- The immutable snapshot may differ from the Product's current name after the sale.
CREATE TRIGGER sale_line_product_name_snapshot_match_guard
BEFORE INSERT ON sale_lines
WHEN NEW.product_name_snapshot IS NOT (
    SELECT p.name FROM products p WHERE p.product_id = NEW.product_id
)
BEGIN
    SELECT RAISE(ABORT, 'sale line product name snapshot must match Product at checkout');
END;
