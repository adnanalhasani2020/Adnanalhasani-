-- Persist administrator-controlled Finance policy per Activity context.
-- Ledger minor units remain fixed at two decimal places; display precision is presentation only.
CREATE TABLE financial_policy_settings (
 activity_id TEXT PRIMARY KEY REFERENCES activities(activity_id),
 enabled_currencies TEXT NOT NULL DEFAULT 'SAR,YER,USD',
 default_currency TEXT NOT NULL DEFAULT 'YER',
 display_decimal_places INTEGER NOT NULL DEFAULT 2 CHECK (display_decimal_places BETWEEN 0 AND 4),
 settlement_cardinality TEXT NOT NULL DEFAULT 'single' CHECK (settlement_cardinality IN ('single','multiple')),
 posted_voucher_policy TEXT NOT NULL DEFAULT 'reversal_only' CHECK (posted_voucher_policy IN ('reversal_only','disallow')),
 require_full_cycle_validation INTEGER NOT NULL DEFAULT 1 CHECK (require_full_cycle_validation IN (0,1)),
 version_no INTEGER NOT NULL DEFAULT 1 CHECK (version_no > 0),
 updated_at TEXT NOT NULL,
 updated_by_person_id TEXT NOT NULL REFERENCES persons(person_id)
);
CREATE TRIGGER financial_policy_default_currency_enabled_insert
BEFORE INSERT ON financial_policy_settings
WHEN instr(',' || NEW.enabled_currencies || ',', ',' || NEW.default_currency || ',') = 0
BEGIN SELECT RAISE(ABORT, 'default currency must be enabled'); END;
CREATE TRIGGER financial_policy_default_currency_enabled_update
BEFORE UPDATE OF enabled_currencies, default_currency ON financial_policy_settings
WHEN instr(',' || NEW.enabled_currencies || ',', ',' || NEW.default_currency || ',') = 0
BEGIN SELECT RAISE(ABORT, 'default currency must be enabled'); END;
