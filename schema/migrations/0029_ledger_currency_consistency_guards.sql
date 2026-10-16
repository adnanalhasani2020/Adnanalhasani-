-- Keep every posted ledger entry aligned with the account and currency of its
-- recognized financial transaction. Representation guards live in migration 0022.

CREATE TRIGGER ledger_entry_currency_matches_transaction_insert
BEFORE INSERT ON ledger_entries
WHEN NOT EXISTS (
    SELECT 1
    FROM financial_transactions ft
    WHERE ft.financial_transaction_id = NEW.financial_transaction_id
      AND ft.financial_account_id = NEW.financial_account_id
      AND ft.currency_code = NEW.currency_code
)
BEGIN
    SELECT RAISE(ABORT, 'ledger entry account/currency_code must match financial transaction');
END;

CREATE TRIGGER ledger_entry_currency_matches_transaction_update
BEFORE UPDATE OF financial_transaction_id, financial_account_id, currency_code ON ledger_entries
WHEN NOT EXISTS (
    SELECT 1
    FROM financial_transactions ft
    WHERE ft.financial_transaction_id = NEW.financial_transaction_id
      AND ft.financial_account_id = NEW.financial_account_id
      AND ft.currency_code = NEW.currency_code
)
BEGIN
    SELECT RAISE(ABORT, 'ledger entry account/currency_code must match financial transaction');
END;

CREATE TRIGGER financial_transaction_currency_matches_ledger_update
BEFORE UPDATE OF financial_account_id, currency_code ON financial_transactions
WHEN EXISTS (
    SELECT 1
    FROM ledger_entries le
    WHERE le.financial_transaction_id = OLD.financial_transaction_id
      AND (
          le.financial_account_id <> NEW.financial_account_id
          OR le.currency_code <> NEW.currency_code
      )
)
BEGIN
    SELECT RAISE(ABORT, 'financial transaction account/currency must match existing ledger entries');
END;
