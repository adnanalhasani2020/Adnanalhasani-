-- Keep Hospital-Finance settings bound to an active Finance Activity at the database boundary.
-- Application checks provide friendly errors; these triggers protect alternate writers.
CREATE TRIGGER hospital_finance_settings_activity_active_insert
BEFORE INSERT ON hospital_finance_integration_settings
WHEN NOT EXISTS (
  SELECT 1 FROM activities a
  WHERE a.activity_id = NEW.activity_id AND a.state = 'active'
)
BEGIN
  SELECT RAISE(ABORT, 'Hospital Finance settings require an active Finance Activity');
END;

CREATE TRIGGER hospital_finance_settings_activity_active_update
BEFORE UPDATE OF activity_id, status ON hospital_finance_integration_settings
WHEN NEW.status IN ('pending_approval', 'active')
 AND NOT EXISTS (
  SELECT 1 FROM activities a
  WHERE a.activity_id = NEW.activity_id AND a.state = 'active'
)
BEGIN
  SELECT RAISE(ABORT, 'Hospital Finance settings require an active Finance Activity');
END;
