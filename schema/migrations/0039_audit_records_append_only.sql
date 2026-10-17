-- Audit history is immutable once persisted; corrections must be new events.
CREATE TRIGGER audit_records_reject_update
BEFORE UPDATE ON audit_records
BEGIN
    SELECT RAISE(ABORT, 'audit_records are append-only');
END;

CREATE TRIGGER audit_records_reject_delete
BEFORE DELETE ON audit_records
BEGIN
    SELECT RAISE(ABORT, 'audit_records are append-only');
END;
