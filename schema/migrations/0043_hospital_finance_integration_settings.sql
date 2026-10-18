-- Versioned, two-person-governed Hospital-to-Finance mapping configuration.
-- This stores choices only; it does not authorize financial writes or enable a worker.
CREATE TABLE hospital_finance_integration_settings (
  settings_id TEXT PRIMARY KEY,
  activity_id TEXT NOT NULL REFERENCES activities(activity_id),
  hospital_scope_ref TEXT NOT NULL CHECK(length(trim(hospital_scope_ref)) BETWEEN 1 AND 160),
  finance_person_id TEXT NOT NULL REFERENCES persons(person_id),
  financial_account_id TEXT NOT NULL REFERENCES financial_accounts(financial_account_id),
  invoice_policy TEXT NOT NULL CHECK(invoice_policy IN ('operational_only','finance_obligation_review_required')),
  payment_trigger TEXT NOT NULL CHECK(payment_trigger IN ('fully_settled_existing_finance_payment', 'manual_review_only')),
  partial_payment_policy TEXT NOT NULL CHECK(partial_payment_policy IN ('reject_until_supported')),
  currency_policy TEXT NOT NULL CHECK(currency_policy IN ('require_exact_match')),
  status TEXT NOT NULL CHECK(status IN ('pending_approval','active','rejected','superseded','paused')),
  version_no INTEGER NOT NULL CHECK(version_no > 0),
  proposed_by_person_id TEXT NOT NULL REFERENCES persons(person_id),
  proposed_at TEXT NOT NULL,
  approved_by_person_id TEXT REFERENCES persons(person_id),
  approved_at TEXT,
  approval_note TEXT,
  created_at TEXT NOT NULL,
  CHECK((status='active' AND approved_by_person_id IS NOT NULL AND approved_at IS NOT NULL) OR status<>'active'),
  CHECK(approved_by_person_id IS NULL OR approved_by_person_id<>proposed_by_person_id),
  UNIQUE(activity_id, version_no, hospital_scope_ref)
);
CREATE INDEX hospital_finance_settings_active_scope
  ON hospital_finance_integration_settings(activity_id, hospital_scope_ref, status);
CREATE UNIQUE INDEX hospital_finance_settings_one_active_scope
  ON hospital_finance_integration_settings(activity_id, hospital_scope_ref)
  WHERE status='active';

CREATE TRIGGER hospital_finance_settings_account_owner_insert
BEFORE INSERT ON hospital_finance_integration_settings
WHEN NOT EXISTS (
  SELECT 1 FROM financial_accounts a
  JOIN persons p ON p.person_id=a.person_id
  WHERE a.financial_account_id=NEW.financial_account_id
    AND a.person_id=NEW.finance_person_id AND a.state='active' AND p.state='active'
)
BEGIN SELECT RAISE(ABORT, 'Hospital Finance mapping requires active matching Person and Account'); END;
CREATE TRIGGER hospital_finance_settings_account_owner_update
BEFORE UPDATE OF finance_person_id,financial_account_id ON hospital_finance_integration_settings
WHEN NOT EXISTS (
  SELECT 1 FROM financial_accounts a
  JOIN persons p ON p.person_id=a.person_id
  WHERE a.financial_account_id=NEW.financial_account_id
    AND a.person_id=NEW.finance_person_id AND a.state='active' AND p.state='active'
)
BEGIN SELECT RAISE(ABORT, 'Hospital Finance mapping requires active matching Person and Account'); END;
