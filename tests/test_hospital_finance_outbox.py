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


def test_outbox_claim_retry_and_delivery_are_state_guarded(tmp_path):
    hospital, patient_id = _hospital(tmp_path)
    invoice_id = hospital.create_invoice(
        invoice_no="INV-OUTBOX-3", patient_id=patient_id, total_minor=200,
        created_at="2026-10-18T10:01:00Z", actor="test",
    )
    with hospital.connect() as db:
        event_key = db.execute(
            "SELECT event_key FROM finance_outbox WHERE aggregate_id=?",
            (str(invoice_id),),
        ).fetchone()[0]

    claimed = hospital.claim_finance_outbox(
        now="2026-10-18T10:03:00Z",
        lease_expired_before="2026-10-18T10:02:00Z",
    )
    assert len(claimed) == 1
    assert claimed[0]["event_key"] == event_key
    assert claimed[0]["attempts"] == 1

    # A second consumer cannot claim the active lease.
    assert hospital.claim_finance_outbox(
        now="2026-10-18T10:04:00Z",
        lease_expired_before="2026-10-18T10:02:00Z",
    ) == []

    assert hospital.mark_finance_outbox_failed(
        event_key=event_key, failed_at="2026-10-18T10:05:00Z", error="temporary outage",
    )
    assert not hospital.mark_finance_outbox_delivered(
        event_key=event_key, delivered_at="2026-10-18T10:05:30Z",
    )

    retried = hospital.claim_finance_outbox(
        now="2026-10-18T10:06:00Z",
        lease_expired_before="2026-10-18T10:05:00Z",
    )
    assert len(retried) == 1
    assert retried[0]["attempts"] == 2
    assert hospital.mark_finance_outbox_delivered(
        event_key=event_key, delivered_at="2026-10-18T10:07:00Z",
    )
    assert not hospital.mark_finance_outbox_failed(
        event_key=event_key, failed_at="2026-10-18T10:08:00Z", error="late failure",
    )

    with hospital.connect() as db:
        row = db.execute(
            "SELECT status,attempts,delivered_at,last_error FROM finance_outbox WHERE event_key=?",
            (event_key,),
        ).fetchone()
    assert tuple(row) == ("delivered", 2, "2026-10-18T10:07:00Z", None)


def test_outbox_recovers_expired_processing_lease(tmp_path):
    hospital, patient_id = _hospital(tmp_path)
    invoice_id = hospital.create_invoice(
        invoice_no="INV-OUTBOX-4", patient_id=patient_id, total_minor=200,
        created_at="2026-10-18T10:01:00Z", actor="test",
    )
    first = hospital.claim_finance_outbox(
        now="2026-10-18T10:02:00Z",
        lease_expired_before="2026-10-18T10:00:00Z",
    )
    assert len(first) == 1

    reclaimed = hospital.claim_finance_outbox(
        now="2026-10-18T10:10:00Z",
        lease_expired_before="2026-10-18T10:03:00Z",
    )
    assert len(reclaimed) == 1
    assert reclaimed[0]["aggregate_id"] == str(invoice_id)
    assert reclaimed[0]["attempts"] == 2


def test_outbox_insert_failure_rolls_back_invoice_and_audit_together(tmp_path):
    hospital, patient_id = _hospital(tmp_path)
    with hospital.connect() as db:
        db.execute(
            """CREATE TRIGGER reject_finance_outbox_insert
               BEFORE INSERT ON finance_outbox
               BEGIN SELECT RAISE(ABORT, 'simulated outbox failure'); END"""
        )

    try:
        hospital.create_invoice(
            invoice_no="INV-OUTBOX-ATOMIC", patient_id=patient_id, total_minor=300,
            created_at="2026-10-18T10:11:00Z", actor="test",
        )
    except Exception as exc:
        assert "simulated outbox failure" in str(exc)
    else:
        raise AssertionError("invoice creation must fail when its outbox write fails")

    with hospital.connect() as db:
        assert db.execute(
            "SELECT COUNT(*) FROM invoices WHERE invoice_no='INV-OUTBOX-ATOMIC'"
        ).fetchone()[0] == 0
        assert db.execute(
            "SELECT COUNT(*) FROM audit_log WHERE action='invoice.created'"
        ).fetchone()[0] == 0
        assert db.execute(
            "SELECT COUNT(*) FROM finance_outbox WHERE event_key LIKE 'hospital:invoice:%'"
        ).fetchone()[0] == 0
