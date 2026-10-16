-- A recognized settlement transaction must retain the payment's currency.
-- This links settlement-level financial recognition to the currency already
-- enforced across the Payment, Obligation, and Settlement records.

CREATE TRIGGER financial_transaction_settlement_currency_matches_insert
BEFORE INSERT ON financial_transactions
WHEN NEW.settlement_id IS NOT NULL
 AND NOT EXISTS (
    SELECT 1
    FROM settlements s
    JOIN payments p ON p.payment_id = s.payment_id
    WHERE s.settlement_id = NEW.settlement_id
      AND p.currency_code = NEW.currency_code
 )
BEGIN
    SELECT RAISE(ABORT, 'financial transaction currency_code must match settlement payment currency_code');
END;

CREATE TRIGGER financial_transaction_settlement_currency_matches_update
BEFORE UPDATE OF settlement_id, currency_code ON financial_transactions
WHEN NEW.settlement_id IS NOT NULL
 AND NOT EXISTS (
    SELECT 1
    FROM settlements s
    JOIN payments p ON p.payment_id = s.payment_id
    WHERE s.settlement_id = NEW.settlement_id
      AND p.currency_code = NEW.currency_code
 )
BEGIN
    SELECT RAISE(ABORT, 'financial transaction currency_code must match settlement payment currency_code');
END;

CREATE TRIGGER settlement_currency_matches_existing_financial_transactions_update
BEFORE UPDATE OF payment_id ON settlements
WHEN EXISTS (
    SELECT 1
    FROM financial_transactions ft
    JOIN payments p ON p.payment_id = NEW.payment_id
    WHERE ft.settlement_id = OLD.settlement_id
      AND ft.currency_code <> p.currency_code
)
BEGIN
    SELECT RAISE(ABORT, 'settlement payment currency_code must match recognized financial transaction');
END;
