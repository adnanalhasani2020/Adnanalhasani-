-- Enforce one currently issued Commerce invoice per Sale at the database boundary.
-- Historical duplicates, if any, are preserved for explicit reconciliation; this
-- migration does not rewrite or delete existing financial/commercial evidence.

CREATE TRIGGER trg_invoice_one_issued_per_sale_insert
BEFORE INSERT ON invoices
WHEN NEW.state = 'issued'
 AND EXISTS (
     SELECT 1 FROM invoices existing
     WHERE existing.sale_id = NEW.sale_id
       AND existing.state = 'issued'
 )
BEGIN
    SELECT RAISE(ABORT, 'Sale already has an issued Invoice');
END;

CREATE TRIGGER trg_invoice_one_issued_per_sale_update
BEFORE UPDATE OF sale_id, state ON invoices
WHEN NEW.state = 'issued'
 AND EXISTS (
     SELECT 1 FROM invoices existing
     WHERE existing.sale_id = NEW.sale_id
       AND existing.state = 'issued'
       AND existing.invoice_id <> OLD.invoice_id
 )
BEGIN
    SELECT RAISE(ABORT, 'Sale already has an issued Invoice');
END;

INSERT INTO schema_migrations(version, applied_at)
VALUES ('0050_commerce_one_issued_invoice_per_sale', strftime('%Y-%m-%dT%H:%M:%fZ','now'));
