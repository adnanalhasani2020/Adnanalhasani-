"""Integration tests for persisted Obligation lifecycle and atomic history."""
import sqlite3
import uuid

import pytest

from agent_core.obligation_application import ObligationApplication
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

STAMP = "2026-10-10T12:00:00Z"


def uid():
    return str(uuid.uuid4())


def seed(db):
    creditor, debtor, approver, agent, context = [uid() for _ in range(5)]
    for person in (creditor, debtor, approver):
        db.execute(
            "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
            (person, STAMP, STAMP),
        )
    db.execute(
        "INSERT INTO agents(agent_id,owner_person_id,configured_identity_ref,context_ref,state,created_at,updated_at) "
        "VALUES(?,?,?,?, 'active',?,?)",
        (agent, approver, "agent-config", context, STAMP, STAMP),
    )
    db.commit()
    return creditor, debtor, approver, agent, context


def authorize(db, *, agent, context, obligation_id, action_code, approver, state="approved"):
    grant, action, approval = uid(), uid(), uid()
    db.execute(
        "INSERT INTO authorization_grants(authorization_grant_id,agent_id,action_code,scope_ref,context_ref,"
        "state,effective_from,created_at,updated_at) VALUES(?,?,?,?,?,'active',?,?,?)",
        (grant, agent, action_code, obligation_id, context, "2026-10-01T00:00:00Z", STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO agent_actions(agent_action_id,agent_id,action_code,target_ref,context_ref,"
        "authorization_grant_id,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?, ?,?)",
        (action, agent, action_code, obligation_id, context, grant, state, STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO approvals(approval_id,agent_action_id,approval_gate_code,state,approver_person_id,"
        "decision_at,created_at,updated_at) VALUES(?,?, 'default','APPROVED',?,?,?,?)",
        (approval, action, approver, STAMP, STAMP, STAMP),
    )
    db.commit()
    return grant, action


def create_obligation(db, app, parties, *, amount=10000, obligation_id=None):
    creditor, debtor, approver, agent, context = parties
    obligation_id = obligation_id or uid()
    grant, action = authorize(
        db, agent=agent, context=context, obligation_id=obligation_id,
        action_code="obligation.create", approver=approver,
    )
    record = app.create_obligation(
        db, obligation_id=obligation_id, creditor_person_id=creditor, debtor_person_id=debtor,
        amount_minor=amount, currency_code="YER", context_ref=context,
        authorization_grant_id=grant, agent_action_id=action, now=STAMP,
    )
    return record, parties


def test_obligation_create_restore_and_lifecycle_are_persisted_without_financial_side_effects():
    db = connect_database()
    parties = seed(db)
    app = ObligationApplication()
    record, parties = create_obligation(db, app, parties)
    assert record.state == "proposed"
    assert record.version_no == 1
    assert app.get_obligation(db, record.obligation_id) == record
    assert db.execute("SELECT COUNT(*) FROM payments").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM settlements").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM financial_transactions").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM ledger_entries").fetchone()[0] == 0

    creditor, debtor, approver, agent, context = parties
    grant, action = authorize(
        db, agent=agent, context=context, obligation_id=str(record.obligation_id),
        action_code="obligation.transition.open", approver=approver,
    )
    opened = app.transition_obligation(
        db, record.obligation_id, "open", expected_version=1, context_ref=context,
        authorization_grant_id=grant, agent_action_id=action, now=STAMP,
    )
    assert opened.state == "open" and opened.version_no == 2
    assert app.get_obligation(db, record.obligation_id) == opened
    history = db.execute(
        "SELECT prior_version_ref,current_version_ref,change_payload_ref FROM domain_history "
        "WHERE target_ref=? AND change_type='obligation_state_transition' ORDER BY rowid",
        (str(record.obligation_id),),
    ).fetchall()
    assert history == [
        (None, f"obligation-state:{record.obligation_id}:v1", "state:proposed"),
        (f"obligation-state:{record.obligation_id}:v1", f"obligation-state:{record.obligation_id}:v2", "state:open"),
    ]
    assert db.execute("SELECT COUNT(*) FROM payments").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM settlements").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM financial_transactions").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM ledger_entries").fetchone()[0] == 0
    db.close()


@pytest.mark.parametrize("bad", ["wrong_scope", "wrong_action", "unapproved_action", "missing_approval"])
def test_obligation_creation_rejects_invalid_authority_atomically(bad):
    db = connect_database()
    creditor, debtor, approver, agent, context = seed(db)
    app = ObligationApplication()
    obligation_id = uid()
    grant, action = authorize(
        db, agent=agent, context=context,
        obligation_id=uid() if bad == "wrong_scope" else obligation_id,
        action_code="obligation.transition.open" if bad == "wrong_action" else "obligation.create",
        approver=approver, state="prepared" if bad == "unapproved_action" else "approved",
    )
    if bad == "missing_approval":
        db.execute("DELETE FROM approvals WHERE agent_action_id=?", (action,))
        db.commit()
    with pytest.raises(ValidationError):
        app.create_obligation(
            db, obligation_id=obligation_id, creditor_person_id=creditor, debtor_person_id=debtor,
            amount_minor=10000, currency_code="YER", context_ref=context,
            authorization_grant_id=grant, agent_action_id=action, now=STAMP,
        )
    assert db.execute("SELECT COUNT(*) FROM obligations").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM domain_history WHERE owner_domain='financial_relations'").fetchone()[0] == 0
    db.close()


def test_obligation_rejects_bad_references_and_invalid_values():
    db = connect_database()
    creditor, debtor, approver, agent, context = seed(db)
    app = ObligationApplication()
    obligation_id = uid()
    grant, action = authorize(
        db, agent=agent, context=context, obligation_id=obligation_id,
        action_code="obligation.create", approver=approver,
    )
    kwargs = dict(
        obligation_id=obligation_id, creditor_person_id=creditor, debtor_person_id=debtor,
        amount_minor=10000, currency_code="YER", context_ref=context,
        authorization_grant_id=grant, agent_action_id=action, now=STAMP,
    )
    with pytest.raises(ValidationError, match="missing Invoice"):
        app.create_obligation(db, **kwargs, source_invoice_id=uid())
    with pytest.raises(ValidationError, match="positive integer"):
        app.create_obligation(db, **{**kwargs, "amount_minor": True})
    with pytest.raises(ValidationError, match="parties must differ"):
        app.create_obligation(db, **{**kwargs, "debtor_person_id": creditor})
    assert db.execute("SELECT COUNT(*) FROM obligations").fetchone()[0] == 0
    db.close()


def test_obligation_transition_rejects_stale_version_and_illegal_state_without_writes():
    db = connect_database()
    parties = seed(db)
    app = ObligationApplication()
    record, parties = create_obligation(db, app, parties)
    creditor, debtor, approver, agent, context = parties
    grant, action = authorize(
        db, agent=agent, context=context, obligation_id=str(record.obligation_id),
        action_code="obligation.transition.satisfied", approver=approver,
    )
    with pytest.raises(ValidationError, match="cannot transition"):
        app.transition_obligation(
            db, record.obligation_id, "satisfied", expected_version=1, context_ref=context,
            authorization_grant_id=grant, agent_action_id=action, now=STAMP,
        )
    grant, action = authorize(
        db, agent=agent, context=context, obligation_id=str(record.obligation_id),
        action_code="obligation.transition.open", approver=approver,
    )
    opened = app.transition_obligation(
        db, record.obligation_id, "open", expected_version=1, context_ref=context,
        authorization_grant_id=grant, agent_action_id=action, now=STAMP,
    )
    grant, action = authorize(
        db, agent=agent, context=context, obligation_id=str(record.obligation_id),
        action_code="obligation.transition.due", approver=approver,
    )
    with pytest.raises(ValidationError, match="version conflict"):
        app.transition_obligation(
            db, record.obligation_id, "due", expected_version=1, context_ref=context,
            authorization_grant_id=grant, agent_action_id=action, now=STAMP,
        )
    assert app.get_obligation(db, record.obligation_id) == opened
    db.close()


def test_obligation_history_failure_rolls_back_creation():
    db = connect_database()
    parties = seed(db)
    app = ObligationApplication()
    creditor, debtor, approver, agent, context = parties
    obligation_id = uid()
    grant, action = authorize(
        db, agent=agent, context=context, obligation_id=obligation_id,
        action_code="obligation.create", approver=approver,
    )
    db.execute(
        "CREATE TRIGGER fail_obligation_history BEFORE INSERT ON domain_history "
        "WHEN NEW.change_type='obligation_state_transition' BEGIN SELECT RAISE(ABORT, 'history unavailable'); END"
    )
    db.commit()
    with pytest.raises(sqlite3.IntegrityError, match="history unavailable"):
        app.create_obligation(
            db, obligation_id=obligation_id, creditor_person_id=creditor, debtor_person_id=debtor,
            amount_minor=10000, currency_code="YER", context_ref=context,
            authorization_grant_id=grant, agent_action_id=action, now=STAMP,
        )
    assert db.execute("SELECT COUNT(*) FROM obligations").fetchone()[0] == 0
    db.close()

@pytest.mark.parametrize("target_state", [[], {}])
def test_obligation_transition_rejects_non_string_target_states_without_mutation(target_state):
    db = connect_database()
    parties = seed(db)
    app = ObligationApplication()
    obligation, parties = create_obligation(db, app, parties)
    _, _, _, _, context = parties
    with pytest.raises(ValidationError, match="Unsupported persisted Obligation target state"):
        app.transition_obligation(
            db, obligation.obligation_id, target_state, expected_version=1,
            context_ref=context, authorization_grant_id="unused",
            agent_action_id="unused", now=STAMP,
        )
    current = app.get_obligation(db, obligation.obligation_id)
    assert current.state == "proposed"
    assert current.version_no == 1
    db.close()


def test_obligation_rejects_invoice_already_linked_to_another_obligation_atomically():
    db = connect_database()
    parties = seed(db)
    app = ObligationApplication()
    original, parties = create_obligation(db, app, parties)
    creditor, debtor, approver, _agent, _context = parties

    actor, activity, product, offering, sale = [uid() for _ in range(5)]
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
        (actor, STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) VALUES(?,?, 'active',?,?)",
        (activity, actor, STAMP, STAMP),
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
        "INSERT INTO sales(sale_id,offering_id,activity_id,state,occurred_at,created_at,updated_at) "
        "VALUES(?,?,?,'initiated',?,?,?)",
        (sale, offering, activity, STAMP, STAMP, STAMP),
    )
    invoice = uid()
    db.execute(
        "INSERT INTO invoices(invoice_id,sale_id,obligation_id,state,issuer_ref,invoice_number,issue_at,created_at,updated_at) "
        "VALUES(?,?,?,'issued',?,?, ?,?,?)",
        (invoice, sale, str(original.obligation_id), actor, f"INV-{invoice}", STAMP, STAMP, STAMP),
    )
    db.commit()

    second_id = uid()
    grant, action = authorize(
        db, agent=parties[3], context=parties[4], obligation_id=second_id,
        action_code="obligation.create", approver=approver,
    )
    with pytest.raises(
        ValidationError, match="conflicts with the Invoice's linked Obligation"
    ):
        app.create_obligation(
            db, obligation_id=second_id, creditor_person_id=creditor, debtor_person_id=debtor,
            amount_minor=5000, currency_code="YER", context_ref=parties[4],
            authorization_grant_id=grant, agent_action_id=action,
            source_invoice_id=invoice, now=STAMP,
        )

    assert db.execute(
        "SELECT obligation_id FROM invoices WHERE invoice_id=?", (invoice,)
    ).fetchone()[0] == str(original.obligation_id)
    assert db.execute(
        "SELECT COUNT(*) FROM obligations WHERE obligation_id=?", (second_id,)
    ).fetchone()[0] == 0
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=? AND change_type='obligation_state_transition'",
        (second_id,),
    ).fetchone()[0] == 0
    db.close()
