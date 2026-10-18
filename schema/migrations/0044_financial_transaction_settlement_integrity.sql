-- Enforce settlement-to-ledger invariants at the database boundary.
-- Application validation remains necessary; these triggers protect against
-- alternate writers and accidental direct SQL that bypasses the application.
CREATE TRIGGER financial_transaction_settlement_consistency_insert
BEFORE INSERT ON financial_transactions
WHEN NEW.settlement_id IS NOT NULL
 AND NOT EXISTS (
    SELECT 1
    FROM settlements s
    JOIN payments p ON p.payment_id = s.payment_id
    JOIN obligations o ON o.obligation_id = s.obligation_id
    JOIN financial_accounts a ON a.financial_account_id = NEW.financial_account_id
    WHERE s.settlement_id = NEW.settlement_id
      AND s.state = 'settled'
      AND p.state = 'completed'
      AND p.amount_minor = NEW.amount_minor
      AND o.amount_minor = NEW.amount_minor
      AND p.currency_code = NEW.currency_code
      AND o.currency_code = NEW.currency_code
      AND a.person_id = o.creditor_person_id
      AND p.payee_person_id = o.creditor_person_id
      AND p.payer_person_id = o.debtor_person_id
      AND NEW.recognition_source_type = 'settlement'
      AND NEW.recognition_source_ref = s.settlement_id
 )
BEGIN
    SELECT RAISE(ABORT, 'financial transaction currency_code/amount/source must match a fully settled payment, obligation, account owner, and source');
END;

CREATE TRIGGER financial_transaction_settlement_consistency_update
BEFORE UPDATE OF settlement_id, financial_account_id, amount_minor, currency_code,
                 state, recognition_source_type, recognition_source_ref
ON financial_transactions
WHEN NEW.settlement_id IS NOT NULL
 AND NOT EXISTS (
    SELECT 1
    FROM settlements s
    JOIN payments p ON p.payment_id = s.payment_id
    JOIN obligations o ON o.obligation_id = s.obligation_id
    JOIN financial_accounts a ON a.financial_account_id = NEW.financial_account_id
    WHERE s.settlement_id = NEW.settlement_id
      AND s.state = 'settled'
      AND p.state = 'completed'
      AND p.amount_minor = NEW.amount_minor
      AND o.amount_minor = NEW.amount_minor
      AND p.currency_code = NEW.currency_code
      AND o.currency_code = NEW.currency_code
      AND a.person_id = o.creditor_person_id
      AND p.payee_person_id = o.creditor_person_id
      AND p.payer_person_id = o.debtor_person_id
      AND NEW.recognition_source_type = 'settlement'
      AND NEW.recognition_source_ref = s.settlement_id
 )
BEGIN
    SELECT RAISE(ABORT, 'financial transaction currency_code/amount/source must match a fully settled payment, obligation, account owner, and source');
END;
