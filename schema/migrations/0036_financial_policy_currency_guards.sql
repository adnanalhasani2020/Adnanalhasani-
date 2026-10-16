-- Guard Finance policy currency fields at the database boundary as well as in the application.
-- Canonical order prevents semantically identical currency sets from having different persisted forms.
CREATE TRIGGER financial_policy_currency_insert_guard
BEFORE INSERT ON financial_policy_settings
WHEN NEW.enabled_currencies NOT IN (
    'SAR', 'YER', 'USD', 'SAR,YER', 'SAR,USD', 'YER,USD', 'SAR,YER,USD'
)
OR NEW.default_currency NOT IN ('SAR', 'YER', 'USD')
OR instr(',' || NEW.enabled_currencies || ',', ',' || NEW.default_currency || ',') = 0
BEGIN
    SELECT RAISE(ABORT, 'invalid Finance policy enabled currencies or default currency');
END;

CREATE TRIGGER financial_policy_currency_update_guard
BEFORE UPDATE OF enabled_currencies, default_currency ON financial_policy_settings
WHEN NEW.enabled_currencies NOT IN (
    'SAR', 'YER', 'USD', 'SAR,YER', 'SAR,USD', 'YER,USD', 'SAR,YER,USD'
)
OR NEW.default_currency NOT IN ('SAR', 'YER', 'USD')
OR instr(',' || NEW.enabled_currencies || ',', ',' || NEW.default_currency || ',') = 0
BEGIN
    SELECT RAISE(ABORT, 'invalid Finance policy enabled currencies or default currency');
END;
