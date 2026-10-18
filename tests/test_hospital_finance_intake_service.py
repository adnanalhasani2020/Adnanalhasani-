import json
import sqlite3
import uuid

from agent_core.external_event_intake import (
    ExternalEventIntake,
    build_intake_reconciliation_report,
)
from agent_core.persistence import connect_database
from hospital.finance_adapter import validate_hospital_finance_event


def _invoice_event(*, amount=1500, invoice_id=12):
    row = {
        "event_key": f"hospital:invoice:{invoice_id}:created:v1",
        "event_type": "hospital.invoice.created",
        "aggregate_type": "invoice",
        "aggregate_id": str(invoice_id),
        "payload_json": json.dumps({
            "invoice_id": invoice_id,
            "invoice_no": f"INV-{invoice_id}",
            "total_minor": amount,
            "currency": "YER",
            "created_at": "2026-10-18T10:00:00Z",
        }),
    }
    return validate_hospital_finance_event(row)


def _payment_event(*, amount=900, payment_id=31):
    row = {
        "event_key": f"hospital:payment:{payment_id}:received:v1",
        "event_type": "hospital.payment.received",
        "aggregate_type": "payment",
        "aggregate_id": str(payment_id),
        "payload_json": json.dumps({
            "payment_id": payment_id,
            "invoice_id": 12,
            "amount_minor": amount,
            "currency": "YER",
            "method": "cash",
            "reference": "sensitive-reference-not-forwarded",
            "received_at": "2026-10-18T10:05:00Z",
        }),
    }
    return validate_hospital_finance_event(row)


def test_new_event_is_persisted_and_retry_is_idempotent():
    db = connect_database()
    intake = ExternalEventIntake()
    event = _invoice_event()
    first = intake.ingest(db, event, now="2026-10-18T10:10:00Z")
    retry = intake.ingest(db, event, now="2026-10-18T10:11:00Z")

    assert first.outcome == "received"
    assert retry.outcome == "duplicate"
    assert retry.intake_id == first.intake_id
    assert retry.status == "received"
    assert db.execute("SELECT COUNT(*) FROM external_event_intake").fetchone()[0] == 1
    assert db.execute(
        "SELECT last_seen_at FROM external_event_intake WHERE external_event_intake_id=?",
        (first.intake_id,),
    ).fetchone()[0] == "2026-10-18T10:11:00Z"
    assert db.execute("SELECT COUNT(*) FROM financial_transactions").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM ledger_entries").fetchone()[0] == 0


def test_same_event_key_with_changed_content_is_quarantined_without_overwrite():
    db = connect_database()
    intake = ExternalEventIntake()
    first = intake.ingest(db, _invoice_event(amount=1500), now="2026-10-18T10:10:00Z")
    conflict = intake.ingest(db, _invoice_event(amount=1600), now="2026-10-18T10:12:00Z")

    row = db.execute(
        "SELECT payload_sha256,amount_minor,status,last_error "
        "FROM external_event_intake WHERE external_event_intake_id=?",
        (first.intake_id,),
    ).fetchone()
    assert conflict.outcome == "conflict"
    assert conflict.status == "quarantined"
    assert row[0] == first.payload_sha256
    assert row[1] == 1500
    assert row[2] == "quarantined"
    assert "different validated content" in row[3]
    assert db.execute("SELECT COUNT(*) FROM external_event_intake_conflicts").fetchone()[0] == 1


def test_quarantined_source_stays_quarantined_on_matching_retry():
    db = connect_database()
    intake = ExternalEventIntake()
    intake.ingest(db, _invoice_event(amount=1500), now="2026-10-18T10:10:00Z")
    intake.ingest(db, _invoice_event(amount=1600), now="2026-10-18T10:12:00Z")
    retry = intake.ingest(db, _invoice_event(amount=1500), now="2026-10-18T10:13:00Z")

    assert retry.outcome == "duplicate"
    assert retry.status == "quarantined"
    assert db.execute(
        "SELECT status FROM external_event_intake"
    ).fetchone()[0] == "quarantined"


def test_payment_fingerprint_does_not_retain_raw_payment_reference():
    db = connect_database()
    intake = ExternalEventIntake()
    result = intake.ingest(db, _payment_event(), now="2026-10-18T10:10:00Z")
    row = db.execute(
        "SELECT source_event_key,payload_sha256,amount_minor,status "
        "FROM external_event_intake WHERE external_event_intake_id=?",
        (result.intake_id,),
    ).fetchone()
    assert row[0] == "hospital:payment:31:received:v1"
    assert row[2:] == (900, "received")
    serialized = json.dumps(dict(zip(("key", "digest", "amount", "status"), row)))
    assert "sensitive-reference-not-forwarded" not in serialized
    assert db.execute("SELECT COUNT(*) FROM financial_transactions").fetchone()[0] == 0


def test_reconciliation_report_is_read_only_and_surfaces_stale_and_conflicting_events():
    db = connect_database()
    intake = ExternalEventIntake()
    intake.ingest(db, _invoice_event(), now="2026-10-16T00:00:00Z")
    intake.ingest(db, _invoice_event(amount=1600), now="2026-10-16T00:01:00Z")
    before = db.execute("SELECT COUNT(*) FROM external_event_intake").fetchone()[0]

    report = build_intake_reconciliation_report(
        db, now="2026-10-18T00:00:00Z", stale_after_hours=24
    )

    assert report["by_status"]["quarantined"] == 1
    assert report["stale_received"] == 0
    assert report["conflict_observations"] == 1
    assert report["sources_with_conflicts"] == 1
    assert "not accounting" in report["scope"]
    assert db.execute("SELECT COUNT(*) FROM external_event_intake").fetchone()[0] == before


def test_database_errors_roll_back_partial_intake_changes():
    db = connect_database()
    intake = ExternalEventIntake()
    event = _invoice_event()
    # Break the conflict table after a valid initial intake, then force a collision.
    intake.ingest(db, event, now="2026-10-18T10:10:00Z")
    db.execute("DROP TABLE external_event_intake_conflicts")
    with __import__("pytest").raises(sqlite3.OperationalError):
        intake.ingest(db, _invoice_event(amount=1600), now="2026-10-18T10:12:00Z")
    row = db.execute("SELECT amount_minor,status FROM external_event_intake").fetchone()
    assert row == (1500, "received")
