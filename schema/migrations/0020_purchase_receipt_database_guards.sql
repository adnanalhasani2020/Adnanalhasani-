-- Purchase receipts are immutable evidence. Keep direct SQL writes aligned with
-- the received quantity already persisted by the application transaction.
CREATE TRIGGER trg_purchase_receipt_quantity_consistency_insert
BEFORE INSERT ON purchase_receipts
BEGIN
    SELECT CASE
        WHEN NOT EXISTS (
            SELECT 1
            FROM purchase_lines line
            JOIN purchases purchase ON purchase.purchase_id = line.purchase_id
            WHERE line.purchase_line_id = NEW.purchase_line_id
              AND line.purchase_id = NEW.purchase_id
              AND purchase.state IN ('partially_received', 'received')
              AND NEW.quantity_units + COALESCE((
                  SELECT SUM(existing.quantity_units)
                  FROM purchase_receipts existing
                  WHERE existing.purchase_line_id = NEW.purchase_line_id
                    AND existing.purchase_id = NEW.purchase_id
              ), 0) = line.received_quantity_units
              AND NEW.quantity_units + COALESCE((
                  SELECT SUM(existing.quantity_units)
                  FROM purchase_receipts existing
                  WHERE existing.purchase_line_id = NEW.purchase_line_id
                    AND existing.purchase_id = NEW.purchase_id
              ), 0) <= line.quantity_units
        )
        THEN RAISE(ABORT, 'purchase receipt event must agree with persisted received quantity')
    END;
END;

CREATE TRIGGER trg_purchase_receipt_immutable_update
BEFORE UPDATE ON purchase_receipts
BEGIN
    SELECT RAISE(ABORT, 'purchase receipt evidence is immutable');
END;

CREATE TRIGGER trg_purchase_receipt_immutable_delete
BEFORE DELETE ON purchase_receipts
BEGIN
    SELECT RAISE(ABORT, 'purchase receipt evidence is immutable');
END;

INSERT INTO schema_migrations(version, applied_at)
VALUES ('0020_purchase_receipt_database_guards', strftime('%Y-%m-%dT%H:%M:%fZ','now'));
