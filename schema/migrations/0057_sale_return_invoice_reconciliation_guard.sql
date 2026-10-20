-- A direct SQL inventory write must not bypass the application rule that an
-- issued Invoice is reconciled before a Sale return restores stock.
-- This does not perform a refund or financial reversal.
CREATE TRIGGER inventory_sale_return_invoice_reconciliation_guard
BEFORE INSERT ON inventory_movements
WHEN NEW.source_type = 'sale_return'
 AND EXISTS (
     SELECT 1 FROM invoices i
     WHERE i.sale_id = NEW.source_ref
       AND i.state = 'issued'
 )
BEGIN
    SELECT RAISE(ABORT, 'Sale return requires explicit Invoice reconciliation first');
END;

INSERT INTO schema_migrations(version, applied_at)
VALUES ('0057_sale_return_invoice_reconciliation_guard', strftime('%Y-%m-%dT%H:%M:%fZ','now'));
