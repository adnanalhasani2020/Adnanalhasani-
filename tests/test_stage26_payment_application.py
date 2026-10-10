"""Integration tests for persisted Payment lifecycle and transactional history."""
import sqlite3
import uuid

import pytest

from agent_core.payment_application import PaymentApplication
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

STAMP = "2026-10-10T12:00:00Z"


def uid():
    return str(uuid.uuid4())


def seed(db):
    payer, payee, approver, agent, context, obligation = [uid() for _ in range(6)]
    for person in (payer, payee, approver):
        db.execute(
            "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
            (person, STAMP, STAMP),
        )
    db.execute(
        "INSERT INTO agents(agent_id,owner_person_id,configured_identity_ref,context_ref,state,created_at,updated_at) "
        "VALUES(?,?,?,?, 'active',?,?)",
        (agent, approver, "agent-config", context, STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO obligations(obligation_id,creditor_person_id,debtor_person_id,amount_minor,currency_code,"
        "state,created_at,updated_at,version_no) VALUES(?,?,?,?,?,'open',?,?,1)",
        (obligation, payee, payer, 10000, "YER", STAMP, STAMP),
    )
    db.commit()
    return payer, payee, approver, agent, context, obligation


def authorize(db, *, agent, context, payment_id, action_code, approver, state="approved"):
    grant, action, approval = uid(), uid(), uid()
    db.execute(
        "INSERT INTO authorization_grants(authorization_grant_id,agent_id,action_code,scope_ref,context_ref,"
        "state,effective_from,created_at,updated_at) VALUES(?,?,?,?,?,'active',?,?,?)",
        (grant, agent, action_code, payment_id, context, "2026-10-01T00:00:00Z", STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO agent_actions(agent_action_id,agent_id,action_code,target_ref,context_ref,"
        "authorization_grant_id,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?, ?,?)",
        (action, agent, action_code, payment_id, context, grant, state, STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO approvals(approval_id,agent_action_id,approval_gate_code,state,approver_person_id,"
        "decision_at,created_at,updated_at) VALUES(?,?, 'default','APPROVED',?,?,?,?)",
        (approval, action, approver, STAMP, STAMP, STAMP),
    )
    db.commit()
    return grant, action


def create_payment(db, app, parties, *, amount=4000, payment_id=None):
    payer, payee, approver, agent, context, obligation = parties
    payment_id = payment_id or uid()
    grant, action = authorize(
        db, agent=agent, context=context, payment_id=payment_id,
        action_code="payment.create", approver=approver,
    )
    record = app.create_payment(
        db, payment_id=payment_id, payer_person_id=payer, payee_person_id=payee,
        obligation_id=obligation, amount_minor=amount, currency_code="YER",
        context_ref=context, authorization_grant_id=grant, agent_action_id=action, now=STAMP,
    )
    return record, (payer, payee, approver, agent, context, obligation)


def test_payment_create_and_complete_are_persisted_separately_from_settlement():
    db = connect_database()
    parties = seed(db)
    app = PaymentApplication()
    payment, parties = create_payment(db, app, parties, amount=4000)
    assert payment.state == "initiated"
    assert payment.amount_minor == 4000
    assert payment.amount_minor != 10000  # partial payment is valid; no invoice/obligation equality assumption
    assert db.execute("SELECT COUNT(*) FROM settlements").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM financial_transactions").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM ledger_entries").fetchone()[0] == 0

    payer, payee, approver, agent, context, obligation = parties
    grant, action = authorize(
        db, agent=agent, context=context, payment_id=str(payment.payment_id),
        action_code="payment.transition.completed", approver=approver,
    )
    completed = app.transition_payment(
        db, payment.payment_id, "completed", expected_version=1, context_ref=context,
        authorization_grant_id=grant, agent_action_id=action, now=STAMP,
    )
    assert completed.state == "completed"
    assert completed.version_no == 2
    assert app.get_payment(db, payment.payment_id).state == "completed"
    history = db.execute(
        "SELECT prior_version_ref,current_version_ref,change_payload_ref FROM domain_history "
        "WHERE target_ref=? AND change_type='payment_state_transition' ORDER BY rowid",
        (str(payment.payment_id),),
    ).fetchall()
    assert history == [
        (None, f"payment-state:{payment.payment_id}:v1", "state:initiated"),
        (f"payment-state:{payment.payment_id}:v1", f"payment-state:{payment.payment_id}:v2", "state:completed"),
    ]
    assert db.execute("SELECT state FROM obligations WHERE obligation_id=?", (str(obligation),)).fetchone()[0] == "open"
    db.close()


@pytest.mark.parametrize("bad", ["wrong_scope", "wrong_action", "unapproved_action", "missing_approval"])
def test_payment_create_rejects_invalid_authority_without_writing(bad):
    db = connect_database()
    payer, payee, approver, agent, context, obligation = seed(db)
    app = PaymentApplication()
    payment_id = uid()
    action_code = "payment.create"
    grant, action = authorize(
        db, agent=agent, context=context,
        payment_id=uid() if bad == "wrong_scope" else payment_id,
        action_code="payment.transition.pending" if bad == "wrong_action" else action_code,
        approver=approver, state="prepared" if bad == "unapproved_action" else "approved",
    )
    if bad == "missing_approval":
        db.execute("DELETE FROM approvals WHERE agent_action_id=?", (action,))
        db.commit()
    with pytest.raises(ValidationError):
        app.create_payment(
            db, payment_id=payment_id, payer_person_id=payer, payee_person_id=payee,
            obligation_id=obligation, amount_minor=1000, currency_code="YER",
            context_ref=context, authorization_grant_id=grant, agent_action_id=action, now=STAMP,
        )
    assert db.execute("SELECT COUNT(*) FROM payments").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM domain_history WHERE owner_domain='payments'").fetchone()[0] == 0
    db.close()


def test_payment_rejects_missing_commercial_reference_and_self_payment():
    db = connect_database()
    parties = seed(db)
    payer, payee, approver, agent, context, obligation = parties
    app = PaymentApplication()
    payment_id = uid()
    grant, action = authorize(
        db, agent=agent, context=context, payment_id=payment_id,
        action_code="payment.create", approver=approver,
    )
    with pytest.raises(ValidationError, match="missing Obligation"):
        app.create_payment(
            db, payment_id=payment_id, payer_person_id=payer, payee_person_id=payee,
            obligation_id=uid(), amount_minor=1000, currency_code="YER",
            context_ref=context, authorization_grant_id=grant, agent_action_id=action, now=STAMP,
        )
    with pytest.raises(ValidationError, match="parties must differ"):
        app.create_payment(
            db, payment_id=payment_id, payer_person_id=payer, payee_person_id=payer,
            amount_minor=1000, currency_code="YER", context_ref=context,
            authorization_grant_id=grant, agent_action_id=action, now=STAMP,
        )
    assert db.execute("SELECT COUNT(*) FROM payments").fetchone()[0] == 0
    db.close()


def test_payment_history_failure_rolls_back_creation():
    db = connect_database()
    parties = seed(db)
    app = PaymentApplication()
    payer, payee, approver, agent, context, obligation = parties
    payment_id = uid()
    grant, action = authorize(
        db, agent=agent, context=context, payment_id=payment_id,
        action_code="payment.create", approver=approver,
    )
    db.execute(
        "CREATE TRIGGER fail_payment_history BEFORE INSERT ON domain_history "
        "WHEN NEW.change_type='payment_state_transition' BEGIN SELECT RAISE(ABORT, 'history unavailable'); END"
    )
    db.commit()
    with pytest.raises(sqlite3.IntegrityError, match="history unavailable"):
        app.create_payment(
            db, payment_id=payment_id, payer_person_id=payer, payee_person_id=payee,
            obligation_id=obligation, amount_minor=1000, currency_code="YER",
            context_ref=context, authorization_grant_id=grant, agent_action_id=action, now=STAMP,
        )
    assert db.execute("SELECT COUNT(*) FROM payments").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM domain_history WHERE owner_domain='payments'").fetchone()[0] == 0
    db.close()


def test_payment_transition_rejects_stale_version_offline_and_terminal_changes():
    db = connect_database()
    parties = seed(db)
    app = PaymentApplication()
    payment, parties = create_payment(db, app, parties)
    payer, payee, approver, agent, context, obligation = parties
    grant, action = authorize(
        db, agent=agent, context=context, payment_id=str(payment.payment_id),
        action_code="payment.transition.pending", approver=approver,
    )
    with pytest.raises(ValidationError, match="version conflict"):
        app.transition_payment(
            db, payment.payment_id, "pending", expected_version=7, context_ref=context,
            authorization_grant_id=grant, agent_action_id=action, now=STAMP,
        )
    with pytest.raises(ValidationError, match="connectivity"):
        app.transition_payment(
            db, payment.payment_id, "pending", expected_version=1, context_ref=context,
            authorization_grant_id=grant, agent_action_id=action, now=STAMP, connected=False,
        )
    pending = app.transition_payment(
        db, payment.payment_id, "pending", expected_version=1, context_ref=context,
        authorization_grant_id=grant, agent_action_id=action, now=STAMP,
    )
    assert pending.state == "pending"
    # A completed Payment is not itself a Settlement; unsupported/reversal states are not invented.
    completed_grant, completed_action = authorize(
        db, agent=agent, context=context, payment_id=str(payment.payment_id),
        action_code="payment.transition.completed", approver=approver,
    )
    completed = app.transition_payment(
        db, payment.payment_id, "completed", expected_version=2, context_ref=context,
        authorization_grant_id=completed_grant, agent_action_id=completed_action, now=STAMP,
    )
    assert completed.state == "completed"
    failed_grant, failed_action = authorize(
        db, agent=agent, context=context, payment_id=str(payment.payment_id),
        action_code="payment.transition.failed", approver=approver,
    )
    with pytest.raises(ValidationError, match="cannot transition"):
        app.transition_payment(
            db, payment.payment_id, "failed", expected_version=3, context_ref=context,
            authorization_grant_id=failed_grant, agent_action_id=failed_action, now=STAMP,
        )
    db.close()


def test_payment_transition_history_failure_rolls_back_state_and_version():
    db = connect_database()
    parties = seed(db)
    app = PaymentApplication()
    payment, parties = create_payment(db, app, parties)
    payer, payee, approver, agent, context, obligation = parties
    grant, action = authorize(
        db, agent=agent, context=context, payment_id=str(payment.payment_id),
        action_code="payment.transition.pending", approver=approver,
    )
    db.execute(
        "CREATE TRIGGER fail_payment_history BEFORE INSERT ON domain_history "
        "WHEN NEW.change_type='payment_state_transition' AND NEW.change_payload_ref='state:pending' "
        "BEGIN SELECT RAISE(ABORT, 'history unavailable'); END"
    )
    db.commit()
    with pytest.raises(sqlite3.IntegrityError, match="history unavailable"):
        app.transition_payment(
            db, payment.payment_id, "pending", expected_version=1, context_ref=context,
            authorization_grant_id=grant, agent_action_id=action, now=STAMP,
        )
    persisted = app.get_payment(db, payment.payment_id)
    assert persisted.state == "initiated"
    assert persisted.version_no == 1
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=? AND change_payload_ref='state:pending'",
        (str(payment.payment_id),),
    ).fetchone()[0] == 0
    db.close()


def test_payment_rejects_invoice_obligation_reference_mismatch():
    db = connect_database()
    payer, payee, approver, agent, context, obligation = seed(db)
    other_obligation = uid()
    db.execute(
        "INSERT INTO obligations(obligation_id,creditor_person_id,debtor_person_id,amount_minor,"
        "currency_code,state,created_at,updated_at,version_no) VALUES(?,?,?,?,?,'open',?,?,1)",
        (other_obligation, payee, payer, 5000, "YER", STAMP, STAMP),
    )
    activity, product, offering, sale, invoice = [uid() for _ in range(5)]
    db.execute(
        "INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) "
        "VALUES(?,?,'active',?,?)", (activity, approver, STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO products(product_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
        (product, STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO offerings(offering_id,product_id,activity_id,state,effective_from,created_at,updated_at) "
        "VALUES(?,?,?,'active',?,?,?)",
        (offering, product, activity, STAMP, STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO sales(sale_id,offering_id,activity_id,state,occurred_at,created_at,updated_at,version_no) "
        "VALUES(?,?,?,'initiated',?,?,?,1)",
        (sale, offering, activity, STAMP, STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO invoices(invoice_id,sale_id,obligation_id,state,issuer_ref,invoice_number,issue_at,"
        "created_at,updated_at,version_no) VALUES(?,?,?,'issued',?,?,?, ?,?,1)",
        (invoice, sale, obligation, str(approver), "INV-MISMATCH", STAMP, STAMP, STAMP),
    )
    db.commit()
    payment_id = uid()
    grant, action = authorize(
        db, agent=agent, context=context, payment_id=payment_id,
        action_code="payment.create", approver=approver,
    )
    with pytest.raises(ValidationError, match="references do not match"):
        PaymentApplication().create_payment(
            db, payment_id=payment_id, payer_person_id=payer, payee_person_id=payee,
            obligation_id=other_obligation, invoice_id=invoice, amount_minor=1000,
            currency_code="YER", context_ref=context, authorization_grant_id=grant,
            agent_action_id=action, now=STAMP,
        )
    assert db.execute("SELECT COUNT(*) FROM payments").fetchone()[0] == 0
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=? AND change_type='payment_state_transition'",
        (payment_id,),
    ).fetchone()[0] == 0
    db.close()


def test_payment_transition_rejects_timestamp_older_than_current_update_without_writes():
    db = connect_database()
    parties = seed(db)
    app = PaymentApplication()
    payment, parties = create_payment(db, app, parties)
    _, _, approver, agent, context, _ = parties
    grant, action = authorize(
        db, agent=agent, context=context, payment_id=str(payment.payment_id),
        action_code="payment.transition.pending", approver=approver,
    )
    before_history = db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=? AND change_type='payment_state_transition'",
        (str(payment.payment_id),),
    ).fetchone()[0]

    with pytest.raises(ValidationError, match="cannot precede the current update timestamp"):
        app.transition_payment(
            db, payment.payment_id, "pending", expected_version=1, context_ref=context,
            authorization_grant_id=grant, agent_action_id=action,
            now="2026-10-10T11:59:59Z",
        )

    persisted = app.get_payment(db, payment.payment_id)
    assert persisted.state == "initiated"
    assert persisted.version_no == 1
    assert persisted.updated_at == STAMP
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=? AND change_type='payment_state_transition'",
        (str(payment.payment_id),),
    ).fetchone()[0] == before_history
    assert db.execute("SELECT COUNT(*) FROM settlements").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM financial_transactions").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM ledger_entries").fetchone()[0] == 0
    db.close()


def test_payment_transition_accepts_equivalent_timestamp_with_different_timezone_offset():
    db = connect_database()
    parties = seed(db)
    app = PaymentApplication()
    payment, parties = create_payment(db, app, parties)
    _, _, approver, agent, context, _ = parties
    grant, action = authorize(
        db, agent=agent, context=context, payment_id=str(payment.payment_id),
        action_code="payment.transition.pending", approver=approver,
    )

    transitioned = app.transition_payment(
        db, payment.payment_id, "pending", expected_version=1, context_ref=context,
        authorization_grant_id=grant, agent_action_id=action,
        now="2026-10-10T15:00:00+03:00",
    )

    assert transitioned.state == "pending"
    assert transitioned.version_no == 2
    assert transitioned.updated_at == STAMP
    history = db.execute(
        "SELECT prior_version_ref,current_version_ref,change_payload_ref FROM domain_history "
        "WHERE target_ref=? AND change_type='payment_state_transition' ORDER BY rowid",
        (str(payment.payment_id),),
    ).fetchall()
    assert history[-1] == (
        f"payment-state:{payment.payment_id}:v1",
        f"payment-state:{payment.payment_id}:v2",
        "state:pending",
    )
    db.close()
