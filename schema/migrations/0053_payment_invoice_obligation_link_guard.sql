-- Enforce exact Invoice/Obligation linkage for new or changed Payments.
-- Existing rows are intentionally preserved for explicit historical reconciliation.
CREATE TRIGGER payment_invoice_obligation_link_insert
BEFORE INSERT ON payments
WHEN NEW.invoice_id IS NOT NULL
 AND EXISTS (
     SELECT 1 FROM invoices i
     WHERE i.invoice_id = NEW.invoice_id
       AND i.obligation_id IS NOT NEW.obligation_id
 )
BEGIN
    SELECT RAISE(ABORT, 'payment invoice and obligation references must match');
END;

CREATE TRIGGER payment_invoice_obligation_link_update
BEFORE UPDATE OF invoice_id, obligation_id ON payments
WHEN NEW.invoice_id IS NOT NULL
 AND EXISTS (
     SELECT 1 FROM invoices i
     WHERE i.invoice_id = NEW.invoice_id
       AND i.obligation_id IS NOT NEW.obligation_id
 )
BEGIN
    SELECT RAISE(ABORT, 'payment invoice and obligation references must match');
END;
