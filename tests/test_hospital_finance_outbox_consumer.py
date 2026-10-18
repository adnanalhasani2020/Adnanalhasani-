import json
import uuid

from agent_core.hospital_finance_outbox_consumer import (
    FinancePaymentContext,
    HospitalFinanceOutboxConsumer,
)
from agent_core.persisted_settlement_recognition_application import (
    PersistedSettlementRecognitionApplication,
)
from agent_core.persistence import connect_database

STAMP = "2026-10-18T15:00:00Z"


def uid():
    return str(uuid.uuid4())


class FakeHospitalOutbox:
    def __init__(self, row):
        self.row = dict(row)
        self.status = "processing"
        self.error = None

    def claim_finance_outbox(self, *, now, lease_expired_before, limit):
        if self.status != "processing":
            return []
        self.row["attempts"] = 1
        return [dict(self.row)][:limit]

    def mark_finance_outbox_delivered(self, *, event_key, attempts, delivered_at):
        if self.status != "processing" or attempts != 1 or event_key != self.row["event_key"]:
            return False
        self.status = "delivered"
        return True

    def mark_finance_outbox_failed(self, *, event_key, attempts, failed_at, error):
        if self.status != "processing" or attempts != 1 or event_key != self.row["event_key"]:
            return False
        self.status = "failed"
        self.error = error
        return True


def payment_event_row():
    payload = {
        "payment_id": 42,
        "invoice_id": 8,
        "amount_minor": 12500,
        "currency": "YER",
        "method": "cash",
        "reference": None,
        "received_at": STAMP,
    }
    return {
        "id": 1,
        "event_key": "hospital:payment:42:received:v1",
        "event_type": "hospital.payment.received",
        "aggregate_type": "payment",
        "aggregate_id": "42",
        "payload_json": json.dumps(payload, sort_keys=True),
        "attempts": 1,
        "created_at": STAMP,
    }


def seed_finance(path):
    db = connect_database(path)
    creditor, debtor, approver = uid(), uid(), uid()
    agent, context = uid(), uid()
    account, obligation, payment, settlement = uid(), uid(), uid(), uid()
    for person in (creditor, debtor, approver):
        db.execute(
            "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
            (person, STAMP, STAMP),
        )
    db.execute(
        "INSERT INTO agents(agent_id,owner_person_id,configured_identity_ref,context_ref,state,created_at,updated_at) "
        "VALUES(?,?,? ,?,'active',?,?)",
        (agent, approver, "outbox-consumer-test-agent", context, STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO financial_accounts(financial_account_id,person_id,account_type,state,created_at,updated_at) "
        "VALUES(?,?, 'cash','active',?,?)",
        (account, creditor, STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO obligations(obligation_id,creditor_person_id,debtor_person_id,amount_minor,currency_code,state,created_at,updated_at) "
        "VALUES(?,?,?,?,?,'open',?,?)",
        (obligation, creditor, debtor, 12500, "YER", STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO payments(payment_id,obligation_id,payer_person_id,payee_person_id,amount_minor,currency_code,state,created_at,updated_at) "
        "VALUES(?,?,?,?,?,?,'completed',?,?)",
        (payment, obligation, debtor, creditor, 12500, "YER", STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO settlements(settlement_id,payment_id,obligation_id,state,settled_at,created_at,updated_at) "
        "VALUES(?,?,?,'settled',?,?,?)",
        (settlement, payment, obligation, STAMP, STAMP, STAMP),
    )
    grant, action = uid(), uid()
    code = PersistedSettlementRecognitionApplication.ACTION_CODE
    db.execute(
        "INSERT INTO authorization_grants(authorization_grant_id,agent_id,action_code,scope_ref,context_ref,state,effective_from,created_at,updated_at) "
        "VALUES(?,?,?,?,?,'active',?,?,?)",
        (grant, agent, code, settlement, context, "2026-10-01T00:00:00Z", STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO agent_actions(agent_action_id,agent_id,action_code,target_ref,context_ref,authorization_grant_id,state,created_at,updated_at) "
        "VALUES(?,?,?,?,?,?,'approved',?,?)",
        (action, agent, code, settlement, context, grant, STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO approvals(approval_id,agent_action_id,approval_gate_code,state,approver_person_id,decision_at,created_at,updated_at) "
        "VALUES(?,?, 'default','APPROVED',?,?,?,?)",
        (uid(), action, approver, STAMP, STAMP, STAMP),
    )
    db.commit()
    db.close()
    return FinancePaymentContext(payment, settlement, account, context, grant, action)


def test_payment_outbox_is_delivered_only_after_durable_finance_recognition(tmp_path):
    finance_path = tmp_path / "finance.sqlite"
    context = seed_finance(finance_path)
    hospital = FakeHospitalOutbox(payment_event_row())

    consumer = HospitalFinanceOutboxConsumer(
        hospital_db=hospital,
        finance_connection_factory=lambda: connect_database(finance_path),
        resolve_payment_context=lambda event, db: context,
    )
    result = consumer.run_once(now=STAMP)

    assert (result.claimed, result.delivered, result.failed, result.lease_lost) == (1, 1, 0, 0)
    assert hospital.status == "delivered"
    db = connect_database(finance_path)
    assert db.execute(
        "SELECT recognition_status,recognized_settlement_id,financial_transaction_id,ledger_entry_id "
        "FROM external_event_intake WHERE source_event_key=?",
        ("hospital:payment:42:received:v1",),
    ).fetchone()[0] == "recognized"
    assert db.execute("SELECT COUNT(*) FROM financial_transactions").fetchone()[0] == 1
    assert db.execute("SELECT COUNT(*) FROM ledger_entries").fetchone()[0] == 1
    db.close()


def test_consumer_never_acknowledges_invoice_events_without_approved_lifecycle(tmp_path):
    row = payment_event_row()
    row["event_key"] = "hospital:invoice:8:created:v1"
    row["event_type"] = "hospital.invoice.created"
    row["aggregate_type"] = "invoice"
    row["aggregate_id"] = "8"
    row["payload_json"] = json.dumps({
        "invoice_id": 8,
        "invoice_no": "INV-8",
        "total_minor": 12500,
        "currency": "YER",
        "created_at": STAMP,
    }, sort_keys=True)
    hospital = FakeHospitalOutbox(row)
    finance_path = tmp_path / "finance.sqlite"
    connect_database(finance_path).close()

    consumer = HospitalFinanceOutboxConsumer(
        hospital_db=hospital,
        finance_connection_factory=lambda: connect_database(finance_path),
        resolve_payment_context=lambda event, db: (_ for _ in ()).throw(AssertionError("must not resolve invoice")),
    )
    result = consumer.run_once(now=STAMP)

    assert (result.claimed, result.delivered, result.failed, result.lease_lost) == (1, 0, 1, 0)
    assert hospital.status == "failed"
    assert "invoice lifecycle is not enabled" in hospital.error
