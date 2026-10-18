import json

from agent_core.external_event_intake import ExternalEventIntake
from agent_core.hospital_finance_transport_report import (
    build_hospital_finance_transport_report,
)
from agent_core.persistence import connect_database
from hospital.core import HospitalDB
from hospital.finance_adapter import validate_hospital_finance_event


def test_transport_report_compares_keys_without_claiming_ledger_reconciliation(tmp_path):
    hospital = HospitalDB(tmp_path / "hospital.sqlite3")
    patient_id = hospital.register_patient(
        patient_no="P-TRANSPORT-1",
        full_name="Synthetic Patient",
        created_at="2026-10-18T10:00:00Z",
        actor="test",
    )
    invoice_id = hospital.create_invoice(
        invoice_no="INV-TRANSPORT-1",
        patient_id=patient_id,
        total_minor=1000,
        created_at="2026-10-18T10:01:00Z",
        actor="test",
        currency="YER",
    )
    hospital.record_payment(
        invoice_id=invoice_id,
        amount_minor=400,
        method="cash",
        received_at="2026-10-18T10:02:00Z",
        actor="test",
    )

    with hospital.connect() as hospital_db:
        invoice_row = hospital_db.execute(
            "SELECT event_key,event_type,aggregate_type,aggregate_id,payload_json "
            "FROM finance_outbox WHERE event_type='hospital.invoice.created'"
        ).fetchone()
        payment_key = hospital_db.execute(
            "SELECT event_key FROM finance_outbox "
            "WHERE event_type='hospital.payment.received'"
        ).fetchone()[0]

        finance_db = connect_database()
        validated = validate_hospital_finance_event(dict(invoice_row))
        intake = ExternalEventIntake()
        received = intake.ingest(finance_db, validated, now="2026-10-18T10:03:00Z")
        finance_db.execute(
            "UPDATE external_event_intake SET status='mapped',finance_entity_type='obligation',"
            "finance_entity_id=?,mapped_at=? WHERE external_event_intake_id=?",
            ("00000000-0000-4000-8000-000000000001", "2026-10-18T10:04:00Z", received.intake_id),
        )

        report = build_hospital_finance_transport_report(hospital_db, finance_db)

    assert report["outbox_event_count"] == 2
    assert report["by_disposition"]["mapped_source_only"] == 1
    assert report["by_disposition"]["awaiting_intake"] == 1
    assert report["events"][0]["event_key"] != payment_key
    assert "not financial reconciliation" in report["scope"]
    assert all("payload" not in event for event in report["events"])
    assert finance_db.execute("SELECT COUNT(*) FROM financial_transactions").fetchone()[0] == 0
    assert finance_db.execute("SELECT COUNT(*) FROM ledger_entries").fetchone()[0] == 0
    finance_db.close()


def test_transport_report_flags_delivered_event_missing_from_intake(tmp_path):
    hospital = HospitalDB(tmp_path / "hospital.sqlite3")
    patient_id = hospital.register_patient(
        patient_no="P-TRANSPORT-2",
        full_name="Synthetic Patient",
        created_at="2026-10-18T10:00:00Z",
        actor="test",
    )
    invoice_id = hospital.create_invoice(
        invoice_no="INV-TRANSPORT-2",
        patient_id=patient_id,
        total_minor=700,
        created_at="2026-10-18T10:01:00Z",
        actor="test",
    )
    with hospital.connect() as hospital_db:
        key = hospital_db.execute(
            "SELECT event_key FROM finance_outbox WHERE aggregate_id=?",
            (str(invoice_id),),
        ).fetchone()[0]
        hospital_db.execute(
            "UPDATE finance_outbox SET status='delivered',delivered_at=? WHERE event_key=?",
            ("2026-10-18T10:02:00Z", key),
        )
        finance_db = connect_database()
        report = build_hospital_finance_transport_report(hospital_db, finance_db)

    assert report["by_disposition"]["delivered_without_intake"] == 1
    assert report["events"][0]["disposition"] == "delivered_without_intake"
    finance_db.close()
