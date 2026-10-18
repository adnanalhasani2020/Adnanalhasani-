-- Preserve the validity of governed Hospital-Finance mappings when their referenced
-- Activity, Finance Person, or Financial Account is deactivated.
-- Managers must pause/reject pending or active settings before changing these references.
CREATE TRIGGER hospital_finance_settings_activity_state_guard
BEFORE UPDATE OF state ON activities
WHEN NEW.state <> 'active'
 AND EXISTS (
   SELECT 1 FROM hospital_finance_integration_settings s
   WHERE s.activity_id = OLD.activity_id
     AND s.status IN ('pending_approval', 'active')
 )
BEGIN
  SELECT RAISE(ABORT, 'Pause or reject Hospital Finance settings before deactivating the Finance Activity');
END;

CREATE TRIGGER hospital_finance_settings_person_state_guard
BEFORE UPDATE OF state ON persons
WHEN NEW.state <> 'active'
 AND EXISTS (
   SELECT 1 FROM hospital_finance_integration_settings s
   WHERE s.finance_person_id = OLD.person_id
     AND s.status IN ('pending_approval', 'active')
 )
BEGIN
  SELECT RAISE(ABORT, 'Pause or reject Hospital Finance settings before deactivating the mapped Finance Person');
END;

CREATE TRIGGER hospital_finance_settings_account_state_guard
BEFORE UPDATE OF state ON financial_accounts
WHEN NEW.state <> 'active'
 AND EXISTS (
   SELECT 1 FROM hospital_finance_integration_settings s
   WHERE s.financial_account_id = OLD.financial_account_id
     AND s.status IN ('pending_approval', 'active')
 )
BEGIN
  SELECT RAISE(ABORT, 'Pause or reject Hospital Finance settings before closing the mapped Financial Account');
END;
