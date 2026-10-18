-- Preserve the source facts behind recognized settlement ledger entries.
-- Financial corrections must be recorded as reversals/corrections, not by
-- rewriting the source records that explain an already-posted ledger effect.
CREATE TRIGGER recognized_settlement_source_immutable_settlement
BEFORE UPDATE OF payment_id, obligation_id, state, settled_at ON settlements
WHEN EXISTS (
    SELECT 1 FROM financial_transactions ft
    WHERE ft.settlement_id = OLD.settlement_id
)
AND (
    NEW.payment_id IS NOT OLD.payment_id
    OR NEW.obligation_id IS NOT OLD.obligation_id
    OR NEW.state IS NOT OLD.state
    OR NEW.settled_at IS NOT OLD.settled_at
)
BEGIN
    SELECT RAISE(ABORT, 'recognized settlement source is immutable; record a financial correction instead');
END;

CREATE TRIGGER recognized_settlement_source_immutable_payment
BEFORE UPDATE OF obligation_id, payer_person_id, payee_person_id, amount_minor, currency_code, state
ON payments
WHEN EXISTS (
    SELECT 1
    FROM settlements s
    JOIN financial_transactions ft ON ft.settlement_id = s.settlement_id
    WHERE s.payment_id = OLD.payment_id
)
AND (
    NEW.obligation_id IS NOT OLD.obligation_id
    OR NEW.payer_person_id IS NOT OLD.payer_person_id
    OR NEW.payee_person_id IS NOT OLD.payee_person_id
    OR NEW.amount_minor IS NOT OLD.amount_minor
    OR NEW.currency_code IS NOT OLD.currency_code
    OR NEW.state IS NOT OLD.state
)
BEGIN
    SELECT RAISE(ABORT, 'recognized payment source is immutable; record a financial correction instead');
END;

CREATE TRIGGER recognized_settlement_source_immutable_obligation
BEFORE UPDATE OF creditor_person_id, debtor_person_id, amount_minor, currency_code
ON obligations
WHEN EXISTS (
    SELECT 1
    FROM settlements s
    JOIN financial_transactions ft ON ft.settlement_id = s.settlement_id
    WHERE s.obligation_id = OLD.obligation_id
)
AND (
    NEW.creditor_person_id IS NOT OLD.creditor_person_id
    OR NEW.debtor_person_id IS NOT OLD.debtor_person_id
    OR NEW.amount_minor IS NOT OLD.amount_minor
    OR NEW.currency_code IS NOT OLD.currency_code
)
BEGIN
    SELECT RAISE(ABORT, 'recognized obligation source is immutable; record a financial correction instead');
END;

CREATE TRIGGER recognized_financial_account_owner_immutable
BEFORE UPDATE OF person_id ON financial_accounts
WHEN NEW.person_id IS NOT OLD.person_id
 AND EXISTS (
    SELECT 1 FROM financial_transactions ft
    WHERE ft.financial_account_id = OLD.financial_account_id
 )
BEGIN
    SELECT RAISE(ABORT, 'financial account owner cannot change after a financial transaction exists');
END;
