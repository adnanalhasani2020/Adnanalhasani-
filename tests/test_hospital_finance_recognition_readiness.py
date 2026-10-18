from uuid import uuid4

from agent_core.hospital_finance_recognition_readiness import (
    build_hospital_finance_recognition_readiness_report,
)
from agent_core.persistence import connect_database


def _insert_invoice_event(db, *, key, obligation_id, status="mapped", amount=1200, currency="YER"):
    db.execute(
        "INSERT INTO external_event_intake("
        "external_event_intake_id,source_system,source_event_key,event_type,"
        "source_aggregate_type,source_aggregate_id,payload_sha256,amount_minor,"
        "currency_code,occurred_at,status,finance_entity_type,finance_entity_id,"
        "first_seen_at,last_seen_at,mapped_at"
        ") VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        (
            str(uuid4()), "hospital", key, "hospital.invoice.created", "invoice",
            key, "a" * 64, amount, currency, "2026-10-18T10:00:00Z", status,
            "obligation" if status == "mapped" else None,
            obligation_id if status == "mapped" else None,
            "2026-10-18T10:00:00Z", "2026-10-18T10:00:00Z",
            "2026-10-18T10:01:00Z" if status == "mapped" else None,
        ),
    )


def _insert_obligation(db, obligation_id, *, amount=1200, currency="YER", state="open"):
    creditor, debtor = str(uuid4()), str(uuid4())
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (creditor, "active", "2026-10-18T10:00:00Z", "2026-10-18T10:00:00Z"),
    )
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (debtor, "active", "2026-10-18T10:00:00Z", "2026-10-18T10:00:00Z"),
    )
    db.execute(
        "INSERT INTO obligations(obligation_id,creditor_person_id,debtor_person_id,"
        "amount_minor,currency_code,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
        (obligation_id, creditor, debtor, amount, currency, state,
         "2026-10-18T10:00:00Z", "2026-10-18T10:00:00Z"),
    )
    return creditor


def test_readiness_report_distinguishes_unmapped_missing_and_waiting_settlement():
    db = connect_database()
    missing_id = str(uuid4())
    obligation_id = str(uuid4())
    _insert_invoice_event(db, key="invoice-unmapped", obligation_id=None, status="received")
    _insert_invoice_event(db, key="invoice-missing", obligation_id=missing_id)
    _insert_obligation(db, obligation_id)
    _insert_invoice_event(db, key="invoice-awaiting", obligation_id=obligation_id)

    before = db.execute("SELECT COUNT(*) FROM financial_transactions").fetchone()[0]
    report = build_hospital_finance_recognition_readiness_report(db)
    after = db.execute("SELECT COUNT(*) FROM financial_transactions").fetchone()[0]
    by_key = {event["source_event_key"]: event for event in report["events"]}

    assert by_key["invoice-unmapped"]["disposition"] == "source_not_mapped"
    assert by_key["invoice-missing"]["disposition"] == "mapped_finance_entity_missing"
    assert by_key["invoice-awaiting"]["disposition"] == "awaiting_settlement"
    assert report["read_only"] is True
    assert "not a full accounting reconciliation" in report["scope"]
    assert before == after == 0
    db.close()


def test_readiness_report_follows_settlement_transaction_and_ledger_stages():
    db = connect_database()
    obligation_id = str(uuid4())
    creditor = _insert_obligation(db, obligation_id)
    _insert_invoice_event(db, key="invoice-settled", obligation_id=obligation_id)
    debtor = db.execute(
        "SELECT debtor_person_id FROM obligations WHERE obligation_id=?", (obligation_id,)
    ).fetchone()[0]
    payment_id, settlement_id = str(uuid4()), str(uuid4())
    db.execute(
        "INSERT INTO payments(payment_id,obligation_id,payer_person_id,payee_person_id,"
        "amount_minor,currency_code,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
        (payment_id, obligation_id, debtor, creditor, 1200, "YER", "completed",
         "2026-10-18T10:00:00Z", "2026-10-18T10:00:00Z"),
    )
    db.execute(
        "INSERT INTO settlements(settlement_id,payment_id,obligation_id,state,settled_at,"
        "created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
        (settlement_id, payment_id, obligation_id, "settled", "2026-10-18T10:02:00Z",
         "2026-10-18T10:00:00Z", "2026-10-18T10:02:00Z"),
    )
    report = build_hospital_finance_recognition_readiness_report(db)
    assert report["events"][0]["disposition"] == "settled_without_financial_transaction"

    account_id, tx_id = str(uuid4()), str(uuid4())
    db.execute(
        "INSERT INTO financial_accounts(financial_account_id,person_id,account_type,state,"
        "created_at,updated_at) VALUES(?,?,?,?,?,?)",
        (account_id, creditor, "receivable", "active",
         "2026-10-18T10:00:00Z", "2026-10-18T10:00:00Z"),
    )
    db.execute(
        "INSERT INTO financial_transactions(financial_transaction_id,settlement_id,"
        "financial_account_id,amount_minor,currency_code,state,recognition_source_type,"
        "recognition_source_ref,recognized_at,created_at,updated_at) "
        "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        (tx_id, settlement_id, account_id, 1200, "YER", "recognized", "settlement",
         settlement_id, "2026-10-18T10:03:00Z", "2026-10-18T10:03:00Z",
         "2026-10-18T10:03:00Z"),
    )
    report = build_hospital_finance_recognition_readiness_report(db)
    assert report["events"][0]["disposition"] == "recognized_transaction_without_ledger_entry"

    db.execute(
        "INSERT INTO ledger_entries(ledger_entry_id,financial_account_id,financial_transaction_id,"
        "amount_minor,currency_code,entry_sequence,state,posted_at,created_at,updated_at) "
        "VALUES(?,?,?,?,?,?,?,?,?,?)",
        (str(uuid4()), account_id, tx_id, 1200, "YER", 1, "posted",
         "2026-10-18T10:04:00Z", "2026-10-18T10:04:00Z", "2026-10-18T10:04:00Z"),
    )
    report = build_hospital_finance_recognition_readiness_report(db)
    assert report["events"][0]["disposition"] == "ledger_entry_present"
    assert report["events"][0]["posted_ledger_entry_count"] == 1
    db.close()


def test_readiness_report_prioritizes_source_conflicts():
    db = connect_database()
    intake_id, obligation_id = str(uuid4()), str(uuid4())
    _insert_obligation(db, obligation_id)
    db.execute(
        "INSERT INTO external_event_intake(external_event_intake_id,source_system,"
        "source_event_key,event_type,source_aggregate_type,source_aggregate_id,payload_sha256,"
        "amount_minor,currency_code,occurred_at,status,finance_entity_type,finance_entity_id,"
        "first_seen_at,last_seen_at,mapped_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        (intake_id, "hospital", "invoice-conflict", "hospital.invoice.created", "invoice",
         "invoice-conflict", "b" * 64, 1200, "YER", "2026-10-18T10:00:00Z", "mapped",
         "obligation", obligation_id, "2026-10-18T10:00:00Z", "2026-10-18T10:00:00Z",
         "2026-10-18T10:01:00Z"),
    )
    db.execute(
        "INSERT INTO external_event_intake_conflicts(external_event_intake_conflict_id,"
        "external_event_intake_id,observed_payload_sha256,observed_at,reason) "
        "VALUES(?,?,?,?,?)",
        (str(uuid4()), intake_id, "c" * 64, "2026-10-18T10:02:00Z", "test conflict"),
    )
    report = build_hospital_finance_recognition_readiness_report(db)
    assert report["events"][0]["disposition"] == "source_conflict_requires_review"
    db.close()
