import json

from hospital.core import HospitalDB


def _hospital(tmp_path):
    db = HospitalDB(tmp_path / "hospital.sqlite3")
    patient_id = db.register_patient(
        patient_no="P-OUTBOX-1", full_name="Test Patient",
        created_at="2026-10-18T10:00:00Z", actor="test",
    )
    return db, patient_id


def test_invoice_and_payment_create_transactional_finance_outbox_intents(tmp_path):
    hospital, patient_id = _hospital(tmp_path)
    invoice_id = hospital.create_invoice(
        invoice_no="INV-OUTBOX-1", patient_id=patient_id, total_minor=1500,
        created_at="2026-10-18T10:01:00Z", actor="test", currency="YER",
    )
    payment_id = hospital.record_payment(
        invoice_id=invoice_id, amount_minor=500, method="cash",
        received_at="2026-10-18T10:02:00Z", actor="test",
    )

    with hospital.connect() as db:
        rows = db.execute(
            "SELECT event_key,event_type,aggregate_type,aggregate_id,payload_json,status "
            "FROM finance_outbox ORDER BY id"
        ).fetchall()

    assert len(rows) == 2
    invoice_event, payment_event = rows
    assert invoice_event["event_key"] == f"hospital:invoice:{invoice_id}:created:v1"
    assert invoice_event["event_type"] == "hospital.invoice.created"
    assert invoice_event["aggregate_type"] == "invoice"
    assert invoice_event["aggregate_id"] == str(invoice_id)
    assert invoice_event["status"] == "pending"
    assert json.loads(invoice_event["payload_json"])["total_minor"] == 1500

    assert payment_event["event_key"] == f"hospital:payment:{payment_id}:received:v1"
    assert payment_event["event_type"] == "hospital.payment.received"
    payload = json.loads(payment_event["payload_json"])
    assert payload["invoice_id"] == invoice_id
    assert payload["payment_id"] == payment_id
    assert payload["amount_minor"] == 500
    assert payload["currency"] == "YER"
    assert payment_event["status"] == "pending"


def test_rejected_payment_does_not_create_finance_outbox_event(tmp_path):
    hospital, patient_id = _hospital(tmp_path)
    invoice_id = hospital.create_invoice(
        invoice_no="INV-OUTBOX-2", patient_id=patient_id, total_minor=100,
        created_at="2026-10-18T10:01:00Z", actor="test",
    )

    try:
        hospital.record_payment(
            invoice_id=invoice_id, amount_minor=101, method="cash",
            received_at="2026-10-18T10:02:00Z", actor="test",
        )
    except ValueError as exc:
        assert "exceeds invoice balance" in str(exc)
    else:
        raise AssertionError("overpayment should be rejected")

    with hospital.connect() as db:
        assert db.execute(
            "SELECT COUNT(*) FROM finance_outbox WHERE event_type='hospital.payment.received'"
        ).fetchone()[0] == 0
        assert db.execute("SELECT status FROM invoices WHERE id=?", (invoice_id,)).fetchone()[0] == "unpaid"
