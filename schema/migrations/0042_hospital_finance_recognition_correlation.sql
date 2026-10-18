-- Correlate Hospital payment intake with the durable Finance recognition outcome.
-- This is Finance-side metadata only; it never creates or posts accounting records.
ALTER TABLE external_event_intake
    ADD COLUMN recognition_status TEXT NOT NULL DEFAULT 'not_started'
    CHECK (recognition_status IN ('not_started', 'recognized', 'reconciliation_required'));

ALTER TABLE external_event_intake
    ADD COLUMN recognized_settlement_id TEXT;

ALTER TABLE external_event_intake
    ADD COLUMN financial_transaction_id TEXT;

ALTER TABLE external_event_intake
    ADD COLUMN ledger_entry_id TEXT;

CREATE TRIGGER external_event_intake_recognition_consistency_insert
BEFORE INSERT ON external_event_intake
WHEN
    (NEW.recognition_status = 'recognized'
        AND (NEW.event_type <> 'hospital.payment.received'
            OR NEW.recognized_settlement_id IS NULL
            OR NEW.financial_transaction_id IS NULL
            OR NEW.ledger_entry_id IS NULL))
    OR
    (NEW.recognition_status <> 'recognized'
        AND (NEW.recognized_settlement_id IS NOT NULL
            OR NEW.financial_transaction_id IS NOT NULL
            OR NEW.ledger_entry_id IS NOT NULL))
BEGIN
    SELECT RAISE(ABORT, 'Hospital Finance recognition correlation is inconsistent');
END;

CREATE TRIGGER external_event_intake_recognition_consistency_update
BEFORE UPDATE OF recognition_status, recognized_settlement_id,
    financial_transaction_id, ledger_entry_id, event_type
ON external_event_intake
WHEN
    (NEW.recognition_status = 'recognized'
        AND (NEW.event_type <> 'hospital.payment.received'
            OR NEW.recognized_settlement_id IS NULL
            OR NEW.financial_transaction_id IS NULL
            OR NEW.ledger_entry_id IS NULL))
    OR
    (NEW.recognition_status <> 'recognized'
        AND (NEW.recognized_settlement_id IS NOT NULL
            OR NEW.financial_transaction_id IS NOT NULL
            OR NEW.ledger_entry_id IS NOT NULL))
BEGIN
    SELECT RAISE(ABORT, 'Hospital Finance recognition correlation is inconsistent');
END;

CREATE INDEX external_event_intake_recognition_status
    ON external_event_intake(recognition_status, last_seen_at);
