-- Defense in depth: direct SQL writes must not complete a Payment against
-- a void/missing Invoice or a terminal/missing Obligation. Application-level
-- authorization remains required; these triggers enforce only persisted facts.

CREATE TRIGGER payment_completed_links_eligible_insert
BEFORE INSERT ON payments
WHEN NEW.state = 'completed'
 AND (
    (NEW.invoice_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM invoices i
        WHERE i.invoice_id = NEW.invoice_id AND i.state = 'issued'
    ))
    OR
    (COALESCE(
        NEW.obligation_id,
        (SELECT i.obligation_id FROM invoices i WHERE i.invoice_id = NEW.invoice_id),
        (SELECT s.obligation_id FROM settlements s WHERE s.payment_id = NEW.payment_id LIMIT 1)
    ) IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM obligations o
        WHERE o.obligation_id = COALESCE(
            NEW.obligation_id,
            (SELECT i.obligation_id FROM invoices i WHERE i.invoice_id = NEW.invoice_id),
            (SELECT s.obligation_id FROM settlements s WHERE s.payment_id = NEW.payment_id LIMIT 1)
        )
        AND o.state IN ('open','due','overdue')
    ))
 )
BEGIN
    SELECT RAISE(ABORT, 'completed Payment requires an issued Invoice and eligible Obligation');
END;

CREATE TRIGGER payment_completed_links_eligible_update
BEFORE UPDATE OF state, invoice_id, obligation_id ON payments
WHEN NEW.state = 'completed'
 AND (
    (NEW.invoice_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM invoices i
        WHERE i.invoice_id = NEW.invoice_id AND i.state = 'issued'
    ))
    OR
    (COALESCE(
        NEW.obligation_id,
        (SELECT i.obligation_id FROM invoices i WHERE i.invoice_id = NEW.invoice_id),
        (SELECT s.obligation_id FROM settlements s WHERE s.payment_id = NEW.payment_id LIMIT 1)
    ) IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM obligations o
        WHERE o.obligation_id = COALESCE(
            NEW.obligation_id,
            (SELECT i.obligation_id FROM invoices i WHERE i.invoice_id = NEW.invoice_id),
            (SELECT s.obligation_id FROM settlements s WHERE s.payment_id = NEW.payment_id LIMIT 1)
        )
        AND o.state IN ('open','due','overdue')
    ))
 )
BEGIN
    SELECT RAISE(ABORT, 'completed Payment requires an issued Invoice and eligible Obligation');
END;
