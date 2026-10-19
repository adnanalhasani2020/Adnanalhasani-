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


def seed_invoice_for_payment(db, *, issuer, obligation, state="issued"):
    activity, product, offering, sale, invoice = [uid() for _ in range(5)]
    db.execute(
        "INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) "
        "VALUES(?,?,'active',?,?)", (activity, issuer, STAMP, STAMP),
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
        "created_at,updated_at,version_no) VALUES(?,?,?, ?,?,?,?, ?,?,1)",
        (invoice, sale, obligation, state, issuer, "INV-" + invoice, STAMP, STAMP, STAMP),
    )
    db.commit()
    return invoice


def test_payment_creation_rejects_operation_before_human_approval_without_writes():
    db = connect_database()
    parties = seed(db)
    payer, payee, approver, agent, context, obligation = parties
    payment_id = uid()
    grant, action = authorize(
        db, agent=agent, context=context, payment_id=payment_id,
        action_code="payment.create", approver=approver,
    )
    app = PaymentApplication()

    with pytest.raises(ValidationError, match="cannot precede human approval decision"):
        app.create_payment(
            db, payment_id=payment_id, payer_person_id=payer, payee_person_id=payee,
            obligation_id=obligation, amount_minor=4000, currency_code="YER",
            context_ref=context, authorization_grant_id=grant, agent_action_id=action,
            now="2026-10-10T11:59:59Z",
        )

    assert db.execute("SELECT COUNT(*) FROM payments").fetchone()[0] == 0
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE owner_domain='payments'"
    ).fetchone()[0] == 0
    db.close()


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


def test_payment_rejects_void_invoice_without_writing():
    db = connect_database()
    payer, payee, approver, agent, context, obligation = seed(db)
    invoice = seed_invoice_for_payment(db, issuer=approver, obligation=obligation, state="void")
    payment_id = uid()
    grant, action = authorize(
        db, agent=agent, context=context, payment_id=payment_id,
        action_code="payment.create", approver=approver,
    )

    with pytest.raises(ValidationError, match="issued Invoice"):
        PaymentApplication().create_payment(
            db, payment_id=payment_id, payer_person_id=payer, payee_person_id=payee,
            obligation_id=obligation, invoice_id=invoice, amount_minor=1000,
            currency_code="YER", context_ref=context, authorization_grant_id=grant,
            agent_action_id=action, now=STAMP,
        )

    assert db.execute("SELECT COUNT(*) FROM payments").fetchone()[0] == 0
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=? AND change_type='payment_state_transition'",
        (payment_id,),
    ).fetchone()[0] == 0
    db.close()


def test_payment_rejects_parties_that_conflict_with_obligation_without_writing():
    db = connect_database()
    payer, payee, approver, agent, context, obligation = seed(db)
    payment_id = uid()
    grant, action = authorize(
        db, agent=agent, context=context, payment_id=payment_id,
        action_code="payment.create", approver=approver,
    )

    with pytest.raises(ValidationError, match="do not match the linked Obligation parties"):
        PaymentApplication().create_payment(
            db, payment_id=payment_id, payer_person_id=payee, payee_person_id=payer,
            obligation_id=obligation, amount_minor=1000, currency_code="YER",
            context_ref=context, authorization_grant_id=grant, agent_action_id=action, now=STAMP,
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
    # Make the approval older than the attempted transition so this test isolates
    # the persisted Payment timestamp guard rather than the approval chronology guard.
    db.execute(
        "UPDATE approvals SET decision_at=? WHERE agent_action_id=?",
        ("2026-10-10T11:59:00Z", action),
    )
    db.commit()
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


def test_payment_create_preserves_caller_transaction_on_success_and_failure():
    db = connect_database()
    parties = seed(db)
    payer, payee, approver, agent, context, obligation = parties
    app = PaymentApplication()
    payment_id = uid()
    grant, action = authorize(
        db, agent=agent, context=context, payment_id=payment_id,
        action_code="payment.create", approver=approver,
    )
    caller_person = uid()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
        (caller_person, STAMP, STAMP),
    )
    app.create_payment(
        db, payment_id=payment_id, payer_person_id=payer, payee_person_id=payee,
        obligation_id=obligation, amount_minor=4000, currency_code="YER",
        context_ref=context, authorization_grant_id=grant, agent_action_id=action, now=STAMP,
    )
    assert db.in_transaction
    db.rollback()
    assert db.execute("SELECT COUNT(*) FROM payments WHERE payment_id=?", (payment_id,)).fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM persons WHERE person_id=?", (caller_person,)).fetchone()[0] == 0
    db.close()


def test_payment_create_failure_preserves_caller_transaction():
    db = connect_database()
    parties = seed(db)
    payer, payee, approver, agent, context, obligation = parties
    app = PaymentApplication()
    payment_id = uid()
    grant, action = authorize(
        db, agent=agent, context=context, payment_id=payment_id,
        action_code="payment.create", approver=approver,
    )
    db.execute(
        "CREATE TRIGGER fail_payment_history BEFORE INSERT ON domain_history "
        "WHEN NEW.change_type='payment_state_transition' BEGIN SELECT RAISE(ABORT, 'history unavailable'); END"
    )
    caller_person = uid()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
        (caller_person, STAMP, STAMP),
    )
    with pytest.raises(sqlite3.IntegrityError, match="history unavailable"):
        app.create_payment(
            db, payment_id=payment_id, payer_person_id=payer, payee_person_id=payee,
            obligation_id=obligation, amount_minor=4000, currency_code="YER",
            context_ref=context, authorization_grant_id=grant, agent_action_id=action, now=STAMP,
        )
    assert db.in_transaction
    assert db.execute("SELECT COUNT(*) FROM payments WHERE payment_id=?", (payment_id,)).fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM persons WHERE person_id=?", (caller_person,)).fetchone()[0] == 1
    db.rollback()
    assert db.execute("SELECT COUNT(*) FROM persons WHERE person_id=?", (caller_person,)).fetchone()[0] == 0
    db.close()


@pytest.mark.parametrize("target_state", [[], {}])
def test_payment_transition_rejects_non_string_target_states_without_mutation(target_state):
    db = connect_database()
    parties = seed(db)
    app = PaymentApplication()
    payment, parties = create_payment(db, app, parties)
    _, _, _, _, context, _ = parties
    with pytest.raises(ValidationError, match="Unsupported persisted Payment target state"):
        app.transition_payment(
            db, payment.payment_id, target_state, expected_version=1,
            context_ref=context, authorization_grant_id="unused",
            agent_action_id="unused", now=STAMP,
        )
    current = app.get_payment(db, payment.payment_id)
    assert current.state == "initiated"
    assert current.version_no == 1
    db.close()


@pytest.mark.parametrize("payment_obligation_mode", ["different", "missing"])
def test_payment_restore_rejects_persisted_invoice_obligation_mismatch(payment_obligation_mode):
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
        (invoice, sale, obligation, str(approver), "INV-RESTORE-MISMATCH", STAMP, STAMP, STAMP),
    )
    payment_id = uid()
    db.execute(
        "INSERT INTO payments(payment_id,obligation_id,invoice_id,payer_person_id,payee_person_id,"
        "amount_minor,currency_code,state,created_at,updated_at,version_no) "
        "VALUES(?,?,?,?,?,?,?,'initiated',?,?,1)",
        (payment_id, other_obligation if payment_obligation_mode == "different" else None,
         invoice, payer, payee, 1000, "YER", STAMP, STAMP),
    )
    db.commit()

    with pytest.raises(ValidationError, match="Persisted Payment Invoice and Obligation references do not match"):
        PaymentApplication().get_payment(db, payment_id)
    expected_obligation = other_obligation if payment_obligation_mode == "different" else None
    assert db.execute(
        "SELECT obligation_id,invoice_id FROM payments WHERE payment_id=?", (payment_id,)
    ).fetchone() == (expected_obligation, invoice)
    db.close()


@pytest.mark.parametrize(
    ("amount", "currency", "message"),
    [
        (10001, "YER", "exceeds the linked Obligation amount"),
        (4000, "USD", "currency does not match the linked Obligation currency"),
    ],
)
def test_payment_rejects_amount_or_currency_mismatch_with_linked_obligation(amount, currency, message):
    db = connect_database()
    parties = seed(db)
    payer, payee, approver, agent, context, obligation = parties
    app = PaymentApplication()
    payment_id = uid()
    grant, action = authorize(
        db, agent=agent, context=context, payment_id=payment_id,
        action_code="payment.create", approver=approver,
    )
    with pytest.raises(ValidationError, match=message):
        app.create_payment(
            db, payment_id=payment_id, payer_person_id=payer, payee_person_id=payee,
            obligation_id=obligation, amount_minor=amount, currency_code=currency,
            context_ref=context, authorization_grant_id=grant, agent_action_id=action, now=STAMP,
        )
    assert db.execute(
        "SELECT COUNT(*) FROM payments WHERE payment_id=?", (payment_id,)
    ).fetchone()[0] == 0
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=? AND change_type='payment_state_transition'",
        (payment_id,),
    ).fetchone()[0] == 0
    db.close()


def test_payment_transition_preserves_caller_transaction_on_success():
    db = connect_database()
    parties = seed(db)
    app = PaymentApplication()
    payment, parties = create_payment(db, app, parties)
    _, _, approver, agent, context, _ = parties
    grant, action = authorize(
        db, agent=agent, context=context, payment_id=str(payment.payment_id),
        action_code="payment.transition.pending", approver=approver,
    )
    db.commit()
    caller_person = uid()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
        (caller_person, STAMP, STAMP),
    )

    pending = app.transition_payment(
        db, payment.payment_id, "pending", expected_version=1, context_ref=context,
        authorization_grant_id=grant, agent_action_id=action, now=STAMP,
    )

    assert pending.state == "pending"
    assert pending.version_no == 2
    assert db.in_transaction
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=? AND change_payload_ref='state:pending'",
        (str(payment.payment_id),),
    ).fetchone()[0] == 1

    db.rollback()
    restored = app.get_payment(db, payment.payment_id)
    assert (restored.state, restored.version_no) == ("initiated", 1)
    assert db.execute(
        "SELECT COUNT(*) FROM persons WHERE person_id=?", (caller_person,)
    ).fetchone()[0] == 0
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=? AND change_payload_ref='state:pending'",
        (str(payment.payment_id),),
    ).fetchone()[0] == 0
    db.close()


def test_payment_transition_history_failure_preserves_caller_transaction():
    db = connect_database()
    parties = seed(db)
    app = PaymentApplication()
    payment, parties = create_payment(db, app, parties)
    _, _, approver, agent, context, _ = parties
    grant, action = authorize(
        db, agent=agent, context=context, payment_id=str(payment.payment_id),
        action_code="payment.transition.pending", approver=approver,
    )
    db.execute(
        "CREATE TRIGGER fail_pending_payment_history BEFORE INSERT ON domain_history "
        "WHEN NEW.owner_domain='payments' AND NEW.change_payload_ref='state:pending' "
        "BEGIN SELECT RAISE(ABORT, 'history unavailable'); END"
    )
    db.commit()
    caller_person = uid()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
        (caller_person, STAMP, STAMP),
    )

    with pytest.raises(sqlite3.IntegrityError, match="history unavailable"):
        app.transition_payment(
            db, payment.payment_id, "pending", expected_version=1, context_ref=context,
            authorization_grant_id=grant, agent_action_id=action, now=STAMP,
        )

    assert db.in_transaction
    assert db.execute(
        "SELECT COUNT(*) FROM persons WHERE person_id=?", (caller_person,)
    ).fetchone()[0] == 1
    restored = app.get_payment(db, payment.payment_id)
    assert (restored.state, restored.version_no) == ("initiated", 1)
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=? AND change_payload_ref='state:pending'",
        (str(payment.payment_id),),
    ).fetchone()[0] == 0

    db.rollback()
    assert db.execute(
        "SELECT COUNT(*) FROM persons WHERE person_id=?", (caller_person,)
    ).fetchone()[0] == 0
    db.close()



def test_payment_rejects_invoice_issuer_mismatch_without_writes():
    db = connect_database()
    payer, payee, approver, agent, context, _obligation = seed(db)
    invoice = seed_invoice_for_payment(db, issuer=approver, obligation=None, state="issued")
    payment_id = uid()
    grant, action = authorize(
        db, agent=agent, context=context, payment_id=payment_id,
        action_code="payment.create", approver=approver,
    )

    with pytest.raises(ValidationError, match="payee must match the linked Invoice issuer"):
        PaymentApplication().create_payment(
            db, payment_id=payment_id, payer_person_id=payer, payee_person_id=payee,
            invoice_id=invoice, amount_minor=1000, currency_code="YER",
            context_ref=context, authorization_grant_id=grant, agent_action_id=action, now=STAMP,
        )

    assert db.execute("SELECT COUNT(*) FROM payments").fetchone()[0] == 0
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=? AND change_type='payment_state_transition'",
        (payment_id,),
    ).fetchone()[0] == 0
    db.close()


def test_partial_payments_cannot_exceed_remaining_obligation_balance():
    db = connect_database()
    parties = seed(db)
    app = PaymentApplication()
    first, _ = create_payment(db, app, parties, amount=7000)
    assert first.amount_minor == 7000

    with pytest.raises(ValidationError, match="remaining linked Obligation balance"):
        create_payment(db, app, parties, amount=4000)

    assert db.execute(
        "SELECT COALESCE(SUM(amount_minor),0) FROM payments WHERE obligation_id=? "
        "AND state IN ('initiated','pending','completed')",
        (str(parties[-1]),),
    ).fetchone()[0] == 7000
    assert db.execute("SELECT COUNT(*) FROM payments").fetchone()[0] == 1
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE owner_domain='payments' "
        "AND change_type='payment_state_transition'"
    ).fetchone()[0] == 1
    db.close()


def test_payment_history_auditor_accepts_complete_chain_and_detects_actor_tampering():
    from agent_core.payment_history_audit import PaymentHistoryIntegrityAuditor

    db = connect_database()
    parties = seed(db)
    app = PaymentApplication()
    payment, parties = create_payment(db, app, parties, amount=4000)
    grant, action = authorize(
        db, agent=parties[3], context=parties[4], payment_id=str(payment.payment_id),
        action_code="payment.transition.completed", approver=parties[2],
    )
    app.transition_payment(
        db, payment.payment_id, "completed", expected_version=1, context_ref=parties[4],
        authorization_grant_id=grant, agent_action_id=action,
        now="2026-10-10T12:01:00Z",
    )

    auditor = PaymentHistoryIntegrityAuditor()
    result = auditor.inspect(db, str(payment.payment_id))
    assert result.status == "consistent"
    assert result.payment_version == result.history_record_count == 2

    db.execute(
        "UPDATE domain_history SET actor_context_ref=' ' WHERE target_ref=? "
        "AND change_type='payment_state_transition' AND current_version_ref=?",
        (str(payment.payment_id), f"payment-state:{payment.payment_id}:v2"),
    )
    db.commit()
    result = auditor.inspect(db, str(payment.payment_id))
    assert result.status == "inconsistent"
    assert "history_actor_context_missing:2" in result.findings
    db.close()


def test_payment_history_auditor_detects_non_chronological_events():
    from agent_core.payment_history_audit import PaymentHistoryIntegrityAuditor

    db = connect_database()
    parties = seed(db)
    app = PaymentApplication()
    payment, parties = create_payment(db, app, parties, amount=4000)
    grant, action = authorize(
        db, agent=parties[3], context=parties[4], payment_id=str(payment.payment_id),
        action_code="payment.transition.completed", approver=parties[2],
    )
    app.transition_payment(
        db, payment.payment_id, "completed", expected_version=1, context_ref=parties[4],
        authorization_grant_id=grant, agent_action_id=action,
        now="2026-10-10T12:01:00Z",
    )
    db.execute(
        "UPDATE domain_history SET historical_at='2026-10-10T12:02:00Z' "
        "WHERE target_ref=? AND change_type='payment_state_transition' "
        "AND current_version_ref=?",
        (str(payment.payment_id), f"payment-state:{payment.payment_id}:v1"),
    )
    db.commit()

    result = PaymentHistoryIntegrityAuditor().inspect(db, str(payment.payment_id))
    assert result.status == "inconsistent"
    assert "history_timestamps_not_chronological:2" in result.findings
    db.close()


@pytest.mark.parametrize(
    ("column", "value", "message"),
    [
        ("state", "corrupt-state", "Persisted Payment state is invalid"),
        ("version_no", 0, "Persisted Payment version must be a positive integer"),
        ("amount_minor", 0, "Persisted Payment amount_minor must be a positive integer"),
        ("updated_at", "2026-10-10T11:59:59Z", "Persisted Payment update timestamp precedes creation timestamp"),
    ],
)
def test_payment_reader_rejects_corrupted_persisted_scalars(column, value, message):
    db = connect_database()
    parties = seed(db)
    payment, _ = create_payment(db, PaymentApplication(), parties)
    db.execute("PRAGMA ignore_check_constraints=ON")
    db.execute(f"UPDATE payments SET {column}=? WHERE payment_id=?", (value, str(payment.payment_id)))
    db.commit()

    with pytest.raises(ValidationError, match=message):
        PaymentApplication().get_payment(db, str(payment.payment_id))
    db.close()


@pytest.mark.parametrize(
    ("column", "value", "finding"),
    [
        ("state", "corrupt-state", "payment_state_invalid"),
        ("version_no", 0, "payment_version_invalid"),
        ("updated_at", "2026-10-10T11:59:59Z", "payment_lifecycle_timestamp_order_invalid"),
    ],
)
def test_payment_history_auditor_fails_closed_on_corrupted_persisted_fields(column, value, finding):
    from agent_core.payment_history_audit import PaymentHistoryIntegrityAuditor

    db = connect_database()
    parties = seed(db)
    payment, _ = create_payment(db, PaymentApplication(), parties)
    db.execute("PRAGMA ignore_check_constraints=ON")
    db.execute(f"UPDATE payments SET {column}=? WHERE payment_id=?", (value, str(payment.payment_id)))
    db.commit()

    result = PaymentHistoryIntegrityAuditor().inspect(db, str(payment.payment_id))

    assert result.status == "inconsistent"
    assert finding in result.findings
    db.close()


def test_payment_creation_counts_settlement_linked_payments_when_reserving_obligation_balance():
    db = connect_database()
    payer, payee, approver, agent, context, obligation = seed(db)
    app = PaymentApplication()

    # Existing data may link a Payment to an Obligation through a Settlement while
    # the Payment's direct obligation_id remains NULL.
    first_payment_id = uid()
    grant, action = authorize(
        db, agent=agent, context=context, payment_id=first_payment_id,
        action_code="payment.create", approver=approver,
    )
    first = app.create_payment(
        db, payment_id=first_payment_id, payer_person_id=payer, payee_person_id=payee,
        amount_minor=4000, currency_code="YER", context_ref=context,
        authorization_grant_id=grant, agent_action_id=action, now=STAMP,
    )
    db.execute(
        "INSERT INTO settlements(settlement_id,payment_id,obligation_id,state,created_at,updated_at,version_no) "
        "VALUES(?,?,?,'pending',?,?,1)",
        (uid(), str(first.payment_id), str(obligation), STAMP, STAMP),
    )
    db.commit()

    second_payment_id = uid()
    grant, action = authorize(
        db, agent=agent, context=context, payment_id=second_payment_id,
        action_code="payment.create", approver=approver,
    )
    with pytest.raises(ValidationError, match="remaining linked Obligation balance"):
        app.create_payment(
            db, payment_id=second_payment_id, payer_person_id=payer, payee_person_id=payee,
            obligation_id=obligation, amount_minor=7000, currency_code="YER",
            context_ref=context, authorization_grant_id=grant, agent_action_id=action, now=STAMP,
        )

    assert db.execute("SELECT COUNT(*) FROM payments").fetchone()[0] == 1
    assert db.execute(
        "SELECT COUNT(*) FROM payments WHERE payment_id=?", (second_payment_id,)
    ).fetchone()[0] == 0
    db.close()


def test_payment_rejects_invoice_and_obligation_reference_mismatch_without_writes():
    db = connect_database()
    payer, payee, approver, agent, context, obligation = seed(db)
    invoice = seed_invoice_for_payment(db, issuer=payee, obligation=None, state="issued")
    payment_id = uid()
    grant, action = authorize(
        db, agent=agent, context=context, payment_id=payment_id,
        action_code="payment.create", approver=approver,
    )

    with pytest.raises(ValidationError, match="Payment Invoice and Obligation references do not match"):
        PaymentApplication().create_payment(
            db, payment_id=payment_id, payer_person_id=payer, payee_person_id=payee,
            obligation_id=obligation, invoice_id=invoice, amount_minor=1000,
            currency_code="YER", context_ref=context, authorization_grant_id=grant,
            agent_action_id=action, now=STAMP,
        )

    assert db.execute("SELECT COUNT(*) FROM payments WHERE payment_id=?", (payment_id,)).fetchone()[0] == 0
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=? AND change_type='payment_state_transition'",
        (payment_id,),
    ).fetchone()[0] == 0

    # Application validation is not the only boundary: direct SQL writes must not
    # create the same inconsistent link by bypassing PaymentApplication.
    with pytest.raises(sqlite3.IntegrityError, match="invoice and obligation"):
        db.execute(
            "INSERT INTO payments(payment_id,obligation_id,invoice_id,payer_person_id,payee_person_id,"
            "amount_minor,currency_code,state,created_at,updated_at,version_no) "
            "VALUES(?,?,?,?,?,?,?,'initiated',?,?,1)",
            (payment_id, str(obligation), invoice, payer, payee, 1000, "YER", STAMP, STAMP),
        )

    # The UPDATE trigger closes the same integrity gap for existing Payment rows.
    db.execute(
        "INSERT INTO payments(payment_id,obligation_id,invoice_id,payer_person_id,payee_person_id,"
        "amount_minor,currency_code,state,created_at,updated_at,version_no) "
        "VALUES(?,NULL,?,?,?,?,?,'initiated',?,?,1)",
        (payment_id, invoice, payer, payee, 1000, "YER", STAMP, STAMP),
    )
    with pytest.raises(sqlite3.IntegrityError, match="invoice and obligation"):
        db.execute(
            "UPDATE payments SET obligation_id=? WHERE payment_id=?",
            (str(obligation), payment_id),
        )
    db.close()
