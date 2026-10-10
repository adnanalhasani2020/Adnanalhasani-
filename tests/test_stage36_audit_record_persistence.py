"""Integration tests for append-only audit record persistence."""
import uuid

import pytest

from agent_core.domain_audit import AuditRecordApplication
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

STAMP = "2026-10-10T12:00:00Z"


def test_audit_record_is_persisted_with_explicit_context_and_correlations(tmp_path):
    path = tmp_path / "audit.sqlite"
    db = connect_database(path)
    record_id = uuid.uuid4()
    app = AuditRecordApplication()
    record = app.append(
        db, event_type="operation.submitted", actor_context_ref="actor-context:42",
        target_ref="pending-operation:request-1", result_status="recorded",
        occurred_at=STAMP, operation_ref="request-1", correlation_ref="trace-1",
        audit_record_id=record_id, now=STAMP,
    )
    assert record.audit_record_id == record_id
    db.close()

    db = connect_database(path)
    row = db.execute(
        "SELECT audit_record_id,event_type,actor_context_ref,target_ref,occurred_at,"
        "result_status,operation_ref,correlation_ref FROM audit_records WHERE audit_record_id=?",
        (str(record_id),),
    ).fetchone()
    assert row == (
        str(record_id), "operation.submitted", "actor-context:42",
        "pending-operation:request-1", STAMP, "recorded", "request-1", "trace-1",
    )
    db.close()


@pytest.mark.parametrize(
    "field,value",
    [
        ("event_type", ""),
        ("actor_context_ref", " "),
        ("target_ref", ""),
        ("result_status", ""),
    ],
)
def test_audit_append_requires_explicit_core_fields(field, value):
    db = connect_database()
    values = {
        "event_type": "domain.changed", "actor_context_ref": "actor-context:1",
        "target_ref": "subject:1", "result_status": "success", "now": STAMP,
    }
    values[field] = value
    with pytest.raises(ValidationError, match="is required"):
        AuditRecordApplication().append(db, **values)
    assert db.execute("SELECT COUNT(*) FROM audit_records").fetchone()[0] == 0
    db.close()


def test_audit_append_rejects_naive_timestamps():
    db = connect_database()
    with pytest.raises(ValidationError, match="timezone-aware"):
        AuditRecordApplication().append(
            db, event_type="domain.changed", actor_context_ref="actor-context:1",
            target_ref="subject:1", result_status="success",
            occurred_at="2026-10-10T12:00:00", now=STAMP,
        )
    assert db.execute("SELECT COUNT(*) FROM audit_records").fetchone()[0] == 0
    db.close()


@pytest.mark.parametrize("record_id", ["not-a-uuid", "", None])
def test_audit_append_rejects_invalid_explicit_record_identifier(record_id):
    if record_id is None:
        pytest.skip("None requests generated identifier rather than an explicit identifier")
    db = connect_database()
    with pytest.raises(ValidationError, match="audit_record_id must be a valid UUID"):
        AuditRecordApplication().append(
            db, event_type="domain.changed", actor_context_ref="actor-context:1",
            target_ref="subject:1", result_status="success",
            audit_record_id=record_id, now=STAMP,
        )
    assert db.execute("SELECT COUNT(*) FROM audit_records").fetchone()[0] == 0
    db.close()
