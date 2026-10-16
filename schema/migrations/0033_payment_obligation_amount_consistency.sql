-- Preserve the existing PaymentApplication contract at the database boundary:
-- an explicitly obligation-linked Payment may not exceed that Obligation's amount.
-- This is per-Payment validation only; it does not define cumulative partial-payment
-- accounting or settlement cardinality.
CREATE TRIGGER payment_amount_within_obligation_insert
BEFORE INSERT ON payments
WHEN NEW.obligation_id IS NOT NULL
 AND NOT EXISTS (
    SELECT 1 FROM obligations o
    WHERE o.obligation_id = NEW.obligation_id
      AND NEW.amount_minor <= o.amount_minor
 )
BEGIN
    SELECT RAISE(ABORT, 'payment amount_minor must not exceed linked obligation amount_minor');
END;

CREATE TRIGGER payment_amount_within_obligation_update
BEFORE UPDATE OF obligation_id, amount_minor ON payments
WHEN NEW.obligation_id IS NOT NULL
 AND NOT EXISTS (
    SELECT 1 FROM obligations o
    WHERE o.obligation_id = NEW.obligation_id
      AND NEW.amount_minor <= o.amount_minor
 )
BEGIN
    SELECT RAISE(ABORT, 'payment amount_minor must not exceed linked obligation amount_minor');
END;

CREATE TRIGGER obligation_amount_covers_linked_payments_update
BEFORE UPDATE OF amount_minor ON obligations
WHEN EXISTS (
    SELECT 1 FROM payments p
    WHERE p.obligation_id = OLD.obligation_id
      AND p.amount_minor > NEW.amount_minor
)
BEGIN
    SELECT RAISE(ABORT, 'obligation amount_minor must cover linked payment amount_minor');
END;
