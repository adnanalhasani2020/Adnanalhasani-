-- Prevent linked financial records from silently disagreeing about currency.
-- No conversion is performed: without an approved FX model, linked records must
-- use the same currency representation.

CREATE TRIGGER obligation_loan_currency_matches_insert
BEFORE INSERT ON obligations
WHEN NEW.loan_id IS NOT NULL
 AND NOT EXISTS (
    SELECT 1 FROM loans l
    WHERE l.loan_id = NEW.loan_id AND l.currency_code = NEW.currency_code
 )
BEGIN
    SELECT RAISE(ABORT, 'obligation currency_code must match linked loan currency_code');
END;

CREATE TRIGGER obligation_loan_currency_matches_update
BEFORE UPDATE OF loan_id, currency_code ON obligations
WHEN NEW.loan_id IS NOT NULL
 AND NOT EXISTS (
    SELECT 1 FROM loans l
    WHERE l.loan_id = NEW.loan_id AND l.currency_code = NEW.currency_code
 )
BEGIN
    SELECT RAISE(ABORT, 'obligation currency_code must match linked loan currency_code');
END;

CREATE TRIGGER loan_currency_matches_existing_obligations_update
BEFORE UPDATE OF currency_code ON loans
WHEN EXISTS (
    SELECT 1 FROM obligations o
    WHERE o.loan_id = OLD.loan_id AND o.currency_code <> NEW.currency_code
)
BEGIN
    SELECT RAISE(ABORT, 'loan currency_code must match linked obligations');
END;

CREATE TRIGGER payment_obligation_currency_matches_insert
BEFORE INSERT ON payments
WHEN NEW.obligation_id IS NOT NULL
 AND NOT EXISTS (
    SELECT 1 FROM obligations o
    WHERE o.obligation_id = NEW.obligation_id AND o.currency_code = NEW.currency_code
 )
BEGIN
    SELECT RAISE(ABORT, 'payment currency_code must match linked obligation currency_code');
END;

CREATE TRIGGER payment_obligation_currency_matches_update
BEFORE UPDATE OF obligation_id, currency_code ON payments
WHEN NEW.obligation_id IS NOT NULL
 AND NOT EXISTS (
    SELECT 1 FROM obligations o
    WHERE o.obligation_id = NEW.obligation_id AND o.currency_code = NEW.currency_code
 )
BEGIN
    SELECT RAISE(ABORT, 'payment currency_code must match linked obligation currency_code');
END;

CREATE TRIGGER obligation_currency_matches_existing_payments_update
BEFORE UPDATE OF currency_code ON obligations
WHEN EXISTS (
    SELECT 1 FROM payments p
    WHERE p.obligation_id = OLD.obligation_id AND p.currency_code <> NEW.currency_code
)
OR EXISTS (
    SELECT 1
    FROM settlements s
    JOIN payments p ON p.payment_id = s.payment_id
    WHERE s.obligation_id = OLD.obligation_id AND p.currency_code <> NEW.currency_code
)
BEGIN
    SELECT RAISE(ABORT, 'obligation currency_code must match linked payments and settlements');
END;

CREATE TRIGGER payment_currency_matches_existing_settlements_update
BEFORE UPDATE OF currency_code ON payments
WHEN EXISTS (
    SELECT 1
    FROM settlements s
    JOIN obligations o ON o.obligation_id = s.obligation_id
    WHERE s.payment_id = OLD.payment_id AND o.currency_code <> NEW.currency_code
)
BEGIN
    SELECT RAISE(ABORT, 'payment currency_code must match settled obligation currency_code');
END;

CREATE TRIGGER settlement_currency_links_match_insert
BEFORE INSERT ON settlements
WHEN NOT EXISTS (
    SELECT 1
    FROM payments p
    JOIN obligations o ON o.obligation_id = NEW.obligation_id
    WHERE p.payment_id = NEW.payment_id
      AND p.currency_code = o.currency_code
      AND (p.obligation_id IS NULL OR p.obligation_id = NEW.obligation_id)
)
BEGIN
    SELECT RAISE(ABORT, 'settlement payment and obligation currency_code must match');
END;

CREATE TRIGGER settlement_currency_links_match_update
BEFORE UPDATE OF payment_id, obligation_id ON settlements
WHEN NOT EXISTS (
    SELECT 1
    FROM payments p
    JOIN obligations o ON o.obligation_id = NEW.obligation_id
    WHERE p.payment_id = NEW.payment_id
      AND p.currency_code = o.currency_code
      AND (p.obligation_id IS NULL OR p.obligation_id = NEW.obligation_id)
)
BEGIN
    SELECT RAISE(ABORT, 'settlement payment and obligation currency_code must match');
END;
