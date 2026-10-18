-- Treat each Hospital-Finance settings proposal as an immutable versioned snapshot.
-- Corrections require a new proposal; decision metadata is written only on approval.
CREATE TRIGGER hospital_finance_settings_snapshot_immutable
BEFORE UPDATE OF
  settings_id, activity_id, hospital_scope_ref, finance_person_id,
  financial_account_id, invoice_policy, payment_trigger,
  partial_payment_policy, currency_policy, version_no,
  proposed_by_person_id, proposed_at, created_at
ON hospital_finance_integration_settings
BEGIN
  SELECT RAISE(ABORT, 'Hospital Finance settings snapshots are immutable; create a new version');
END;

CREATE TRIGGER hospital_finance_settings_decision_metadata_guard
BEFORE UPDATE OF approved_by_person_id, approved_at, approval_note
ON hospital_finance_integration_settings
WHEN OLD.status <> 'pending_approval'
  OR NEW.status <> 'active'
BEGIN
  SELECT RAISE(ABORT, 'Hospital Finance decision metadata may only be written during approval');
END;

CREATE TRIGGER hospital_finance_settings_decision_metadata_immutable
BEFORE UPDATE OF approved_by_person_id, approved_at, approval_note
ON hospital_finance_integration_settings
WHEN OLD.status = 'active'
BEGIN
  SELECT RAISE(ABORT, 'Hospital Finance approval history is immutable');
END;

CREATE TRIGGER hospital_finance_settings_status_transition_guard
BEFORE UPDATE OF status ON hospital_finance_integration_settings
WHEN NEW.status <> OLD.status
 AND NOT (
   (OLD.status = 'pending_approval' AND NEW.status IN ('active', 'rejected', 'paused'))
   OR (OLD.status = 'active' AND NEW.status IN ('superseded', 'paused'))
   OR (OLD.status = 'paused' AND NEW.status = 'superseded')
 )
BEGIN
  SELECT RAISE(ABORT, 'Invalid Hospital Finance settings status transition');
END;

CREATE TRIGGER hospital_finance_settings_approval_requires_distinct_active_actor
BEFORE UPDATE OF status ON hospital_finance_integration_settings
WHEN NEW.status = 'active'
 AND (
   OLD.status <> 'pending_approval'
   OR NEW.approved_by_person_id IS NULL
   OR NEW.approved_at IS NULL
   OR NEW.approved_by_person_id = NEW.proposed_by_person_id
   OR NOT EXISTS (
     SELECT 1 FROM persons p
     WHERE p.person_id = NEW.approved_by_person_id AND p.state = 'active'
   )
 )
BEGIN
  SELECT RAISE(ABORT, 'Hospital Finance approval requires a distinct active Finance Person and timestamp');
END;
