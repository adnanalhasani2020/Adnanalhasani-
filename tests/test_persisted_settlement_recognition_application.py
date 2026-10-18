"""Focused tests for Finance-owned persisted settlement recognition."""
import uuid

import pytest

from agent_core.persisted_settlement_recognition_application import (
    PersistedSettlementRecognitionApplication,
)
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

STAMP = "2026-10-18T10:00:00Z"


def uid():
    return str(uuid.uuid4())


def seed_recognition(db, *, payment_amount=12500, obligation_amount=12500, currency="YER"):
    creditor, debtor, approver, agent, context = [uid() for _ in range(5)]
    account, obligation, payment, settlement = [uid() for _ in range(4)]
    for person in (creditor, debtor, approver):
        db.execute(
            "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
            (person, STAMP, STAMP),
        )
    db.execute(
        "INSERT INTO agents(agent_id,owner_person_id,configured_identity_ref,context_ref,state,created_at,updated_at) "
        "VALUES(?,?,?,?, 'active',?,?)",
        (agent, approver, "finance-recognition-agent", context, STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO financial_accounts(financial_account_id,person_id,account_type,state,created_at,updated_at) "
        "VALUES(?,?, 'cash','active',?,?)",
        (account, creditor, STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO obligations(obligation_id,creditor_person_id,debtor_person_id,amount_minor,currency_code,"
        "state,created_at,updated_at) VALUES(?,?,?,?,?,'open',?,?)",
        (obligation, creditor, debtor, obligation_amount, currency, STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO payments(payment_id,obligation_id,payer_person_id,payee_person_id,amount_minor,currency_code,"
        "state,created_at,updated_at) VALUES(?,?,?,?,?,?,'completed',?,?)",
        (payment, obligation, debtor, creditor, payment_amount, currency, STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO settlements(settlement_id,payment_id,obligation_id,state,settled_at,created_at,updated_at) "
        "VALUES(?,?,?,'settled',?,?,?)",
        (settlement, payment, obligation, STAMP, STAMP, STAMP),
    )
    grant, action = uid(), uid()
    db.execute(
        "INSERT INTO authorization_grants(authorization_grant_id,agent_id,action_code,scope_ref,context_ref,"
        "state,effective_from,created_at,updated_at) VALUES(?,?,?,?,?,'active',?,?,?)",
        (grant, agent, PersistedSettlementRecognitionApplication.ACTION_CODE,
         settlement, context, "2026-10-01T00:00:00Z", STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO agent_actions(agent_action_id,agent_id,action_code,target_ref,context_ref,"
        "authorization_grant_id,state,created_at,updated_at) VALUES(?,?,?,?,?,?, 'approved',?,?)",
        (action, agent, PersistedSettlementRecognitionApplication.ACTION_CODE,
         settlement, context, grant, STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO approvals(approval_id,agent_action_id,approval_gate_code,state,approver_person_id,"
        "decision_at,created_at,updated_at) VALUES(?,?, 'default','APPROVED',?,?,?,?)",
        (uid(), action, approver, STAMP, STAMP, STAMP),
    )
    db.commit()
    return {
        "account": account, "obligation": obligation, "payment": payment,
        "settlement": settlement, "context": context, "grant": grant, "action": action,
    }


def recognize(db, ids):
    return PersistedSettlementRecognitionApplication().recognize(
        db, settlement_id=ids["settlement"], financial_account_id=ids["account"],
        context_ref=ids["context"], authorization_grant_id=ids["grant"],
        agent_action_id=ids["action"], now=STAMP,
    )


def test_full_settlement_creates_durable_transaction_ledger_and_obligation_history():
    db = connect_database()
    ids = seed_recognition(db)
    result = recognize(db, ids)

    assert result.replayed is False
    assert result.amount_minor == 12500
    assert db.execute(
        "SELECT settlement_id,financial_account_id,amount_minor,currency_code,state,"
        "recognition_source_type,recognition_source_ref FROM financial_transactions"
    ).fetchone() == (
        ids["settlement"], ids["account"], 12500, "YER", "recognized",
        "settlement", ids["settlement"],
    )
    assert db.execute(
        "SELECT financial_transaction_id,financial_account_id,amount_minor,currency_code,state "
        "FROM ledger_entries"
    ).fetchone() == (
        str(result.financial_transaction_id), ids["account"], 12500, "YER", "posted",
    )
    assert db.execute(
        "SELECT state,version_no FROM obligations WHERE obligation_id=?", (ids["obligation"],)
    ).fetchone() == ("satisfied", 2)
    assert db.execute(
        "SELECT change_type,prior_version_ref,current_version_ref FROM domain_history "
        "WHERE target_ref=? AND change_type='settlement_financial_recognition'",
        (ids["obligation"],),
    ).fetchone() == (
        "settlement_financial_recognition",
        f"obligation-state:{ids['obligation']}:v1",
        f"obligation-state:{ids['obligation']}:v2",
    )
    db.close()


def test_exact_retry_returns_the_existing_recognition_without_duplicate_ledger_rows():
    db = connect_database()
    ids = seed_recognition(db)
    first = recognize(db, ids)
    second = recognize(db, ids)

    assert second.replayed is True
    assert second.financial_transaction_id == first.financial_transaction_id
    assert second.ledger_entry_id == first.ledger_entry_id
    assert db.execute("SELECT COUNT(*) FROM financial_transactions").fetchone()[0] == 1
    assert db.execute("SELECT COUNT(*) FROM ledger_entries").fetchone()[0] == 1
    assert db.execute(
        "SELECT state,version_no FROM obligations WHERE obligation_id=?", (ids["obligation"],)
    ).fetchone() == ("satisfied", 2)
    db.close()


def test_partial_settlement_is_rejected_without_financial_side_effects():
    db = connect_database()
    ids = seed_recognition(db, payment_amount=5000, obligation_amount=12500)

    with pytest.raises(ValidationError, match="Partial or excess settlement"):
        recognize(db, ids)

    assert db.execute("SELECT COUNT(*) FROM financial_transactions").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM ledger_entries").fetchone()[0] == 0
    assert db.execute(
        "SELECT state,version_no FROM obligations WHERE obligation_id=?", (ids["obligation"],)
    ).fetchone() == ("open", 1)
    db.close()


def test_account_must_belong_to_the_obligation_creditor():
    db = connect_database()
    ids = seed_recognition(db)
    other_person, other_account = uid(), uid()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
        (other_person, STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO financial_accounts(financial_account_id,person_id,account_type,state,created_at,updated_at) "
        "VALUES(?,?, 'cash','active',?,?)",
        (other_account, other_person, STAMP, STAMP),
    )
    db.commit()
    ids["account"] = other_account

    with pytest.raises(ValidationError, match="creditor/payee"):
        recognize(db, ids)

    assert db.execute("SELECT COUNT(*) FROM financial_transactions").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM ledger_entries").fetchone()[0] == 0
    db.close()


def test_missing_human_approval_is_rejected():
    db = connect_database()
    ids = seed_recognition(db)
    db.execute("DELETE FROM approvals WHERE agent_action_id=?", (ids["action"],))
    db.commit()

    with pytest.raises(ValidationError, match="human approval"):
        recognize(db, ids)

    assert db.execute("SELECT COUNT(*) FROM financial_transactions").fetchone()[0] == 0
    db.close()
