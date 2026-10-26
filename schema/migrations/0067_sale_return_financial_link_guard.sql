-- A stock return is not a financial return. Block direct SQL stock compensation
-- whenever the Sale still has an issued Invoice or any Invoice-linked Obligation/Payment.
DROP TRIGGER IF EXISTS inventory_sale_return_invoice_reconciliation_guard;
CREATE TRIGGER inventory_sale_return_invoice_reconciliation_guard
BEFORE INSERT ON inventory_movements
WHEN NEW.source_type='sale_return'
 AND EXISTS (
    SELECT 1
    FROM sale_return_lines rl
    JOIN sale_return_events re ON re.return_event_id=rl.return_event_id
    JOIN invoices i ON i.sale_id=re.sale_id
    LEFT JOIN payments p
      ON p.invoice_id=i.invoice_id
      OR (i.obligation_id IS NOT NULL AND p.obligation_id=i.obligation_id)
    WHERE rl.sale_return_line_id=NEW.source_ref
      AND (
        i.state='issued'
        OR i.obligation_id IS NOT NULL
        OR p.payment_id IS NOT NULL
      )
 )
BEGIN
    SELECT RAISE(ABORT, 'Sale return requires explicit Invoice reconciliation first');
END;
