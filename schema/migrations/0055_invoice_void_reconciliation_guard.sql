-- InvoiceApplication refuses to void an issued Invoice while an Obligation or
-- Payment history remains linked. Enforce the same reconciliation boundary for
-- direct SQL writers; voiding is not a financial reversal.
CREATE TRIGGER invoice_void_requires_reconciliation
BEFORE UPDATE OF state ON invoices
WHEN OLD.state = 'issued'
 AND NEW.state = 'void'
 AND (
     OLD.obligation_id IS NOT NULL
     OR NEW.obligation_id IS NOT NULL
     OR EXISTS (
         SELECT 1 FROM payments p WHERE p.invoice_id = OLD.invoice_id
     )
 )
BEGIN
    SELECT RAISE(
        ABORT,
        'Invoice with linked Obligation or Payment history requires explicit reconciliation before voiding'
    );
END;
