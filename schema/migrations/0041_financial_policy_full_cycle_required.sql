-- Full-cycle financial validation is a mandatory safety baseline, not a manager-disableable preference.
-- Existing invalid rows are not silently rewritten; reads fail closed and an authorized settings update
-- must restore the mandatory value before the configuration can be read as valid.
CREATE TRIGGER financial_policy_full_cycle_required_insert
BEFORE INSERT ON financial_policy_settings
WHEN NEW.require_full_cycle_validation <> 1
BEGIN
    SELECT RAISE(ABORT, 'full-cycle financial validation is mandatory');
END;

CREATE TRIGGER financial_policy_full_cycle_required_update
BEFORE UPDATE ON financial_policy_settings
WHEN NEW.require_full_cycle_validation <> 1
BEGIN
    SELECT RAISE(ABORT, 'full-cycle financial validation is mandatory');
END;
