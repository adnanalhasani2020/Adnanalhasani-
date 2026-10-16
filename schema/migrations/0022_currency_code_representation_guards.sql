-- Defense-in-depth validation for canonical currency-code representation.
-- This validates representation only; it does not claim that a currency is assigned
-- or enabled for a market. Application inputs may be normalized before persistence.

CREATE TRIGGER currency_code_guard_loans_insert
BEFORE INSERT ON loans
WHEN NOT (
    length(NEW.currency_code) = 3
    AND NEW.currency_code = upper(NEW.currency_code)
    AND NEW.currency_code GLOB '[A-Z][A-Z][A-Z]'
)
BEGIN
    SELECT RAISE(ABORT, 'currency_code must be a three-letter uppercase ASCII code');
END;

CREATE TRIGGER currency_code_guard_loans_update
BEFORE UPDATE OF currency_code ON loans
WHEN NOT (
    length(NEW.currency_code) = 3
    AND NEW.currency_code = upper(NEW.currency_code)
    AND NEW.currency_code GLOB '[A-Z][A-Z][A-Z]'
)
BEGIN
    SELECT RAISE(ABORT, 'currency_code must be a three-letter uppercase ASCII code');
END;

CREATE TRIGGER currency_code_guard_obligations_insert
BEFORE INSERT ON obligations
WHEN NOT (
    length(NEW.currency_code) = 3
    AND NEW.currency_code = upper(NEW.currency_code)
    AND NEW.currency_code GLOB '[A-Z][A-Z][A-Z]'
)
BEGIN
    SELECT RAISE(ABORT, 'currency_code must be a three-letter uppercase ASCII code');
END;

CREATE TRIGGER currency_code_guard_obligations_update
BEFORE UPDATE OF currency_code ON obligations
WHEN NOT (
    length(NEW.currency_code) = 3
    AND NEW.currency_code = upper(NEW.currency_code)
    AND NEW.currency_code GLOB '[A-Z][A-Z][A-Z]'
)
BEGIN
    SELECT RAISE(ABORT, 'currency_code must be a three-letter uppercase ASCII code');
END;

CREATE TRIGGER currency_code_guard_payments_insert
BEFORE INSERT ON payments
WHEN NOT (
    length(NEW.currency_code) = 3
    AND NEW.currency_code = upper(NEW.currency_code)
    AND NEW.currency_code GLOB '[A-Z][A-Z][A-Z]'
)
BEGIN
    SELECT RAISE(ABORT, 'currency_code must be a three-letter uppercase ASCII code');
END;

CREATE TRIGGER currency_code_guard_payments_update
BEFORE UPDATE OF currency_code ON payments
WHEN NOT (
    length(NEW.currency_code) = 3
    AND NEW.currency_code = upper(NEW.currency_code)
    AND NEW.currency_code GLOB '[A-Z][A-Z][A-Z]'
)
BEGIN
    SELECT RAISE(ABORT, 'currency_code must be a three-letter uppercase ASCII code');
END;

CREATE TRIGGER currency_code_guard_financial_transactions_insert
BEFORE INSERT ON financial_transactions
WHEN NOT (
    length(NEW.currency_code) = 3
    AND NEW.currency_code = upper(NEW.currency_code)
    AND NEW.currency_code GLOB '[A-Z][A-Z][A-Z]'
)
BEGIN
    SELECT RAISE(ABORT, 'currency_code must be a three-letter uppercase ASCII code');
END;

CREATE TRIGGER currency_code_guard_financial_transactions_update
BEFORE UPDATE OF currency_code ON financial_transactions
WHEN NOT (
    length(NEW.currency_code) = 3
    AND NEW.currency_code = upper(NEW.currency_code)
    AND NEW.currency_code GLOB '[A-Z][A-Z][A-Z]'
)
BEGIN
    SELECT RAISE(ABORT, 'currency_code must be a three-letter uppercase ASCII code');
END;

CREATE TRIGGER currency_code_guard_ledger_entries_insert
BEFORE INSERT ON ledger_entries
WHEN NOT (
    length(NEW.currency_code) = 3
    AND NEW.currency_code = upper(NEW.currency_code)
    AND NEW.currency_code GLOB '[A-Z][A-Z][A-Z]'
)
BEGIN
    SELECT RAISE(ABORT, 'currency_code must be a three-letter uppercase ASCII code');
END;

CREATE TRIGGER currency_code_guard_ledger_entries_update
BEFORE UPDATE OF currency_code ON ledger_entries
WHEN NOT (
    length(NEW.currency_code) = 3
    AND NEW.currency_code = upper(NEW.currency_code)
    AND NEW.currency_code GLOB '[A-Z][A-Z][A-Z]'
)
BEGIN
    SELECT RAISE(ABORT, 'currency_code must be a three-letter uppercase ASCII code');
END;
