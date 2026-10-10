"""Integration tests for persisted Loan lifecycle, authorization, and atomic history."""
import sqlite3
import uuid

import pytest

from agent_core.loan_application import LoanApplication
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

STAMP = "2026-10-10T12:00:00Z"


def uid():
    return str(uuid.uuid4())


def seed(db):
    lender, borrower, approver, agent, context = [uid() for _ in range(5)]
    for person in (lender, borrower, approver):
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
    return lender, borrower, approver, agent, context


def authorize(db, *, agent, context, loan_id, action_code, approver, state="approved", include_approval=True):
    grant, action = uid(), uid()
    db.execute(
        "INSERT INTO authorization_grants(authorization_grant_id,agent_id,action_code,scope_ref,context_ref,"
        "state,effective_from,created_at,updated_at) VALUES(?,?,?,?,?,'active',?,?,?)",
        (grant, agent, action_code, loan_id, context, "2026-10-01T00:00:00Z", STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO agent_actions(agent_action_id,agent_id,action_code,target_ref,context_ref,"
        "authorization_grant_id,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?, ?,?)",
        (action, agent, action_code, loan_id, context, grant, state, STAMP, STAMP),
    )
    if include_approval:
        db.execute(
            "INSERT INTO approvals(approval_id,agent_action_id,approval_gate_code,state,approver_person_id,"
            "decision_at,created_at,updated_at) VALUES(?,?, 'default','APPROVED',?,?,?,?)",
            (uid(), action, approver, STAMP, STAMP, STAMP),
        )
    db.commit()
    return grant, action


def create_loan(db, app, parties, *, loan_id=None):
    lender, borrower, approver, agent, context = parties
    loan_id = loan_id or uid()
    grant, action = authorize(
        db, agent=agent, context=context, loan_id=loan_id,
        action_code="loan.create", approver=approver,
    )
    loan = app.create_loan(
        db, loan_id=loan_id, lender_person_id=lender, borrower_person_id=borrower,
        principal_minor=25000, currency_code="YER", maturity_at="2027-10-10T12:00:00+00:00",
        context_ref=context, authorization_grant_id=grant, agent_action_id=action, now=STAMP,
    )
    return loan, parties


def test_loan_create_and_lifecycle_persist_without_creating_obligations_or_financial_effects(tmp_path):
    path = tmp_path / "loan.sqlite"
    db = connect_database(path)
    parties = seed(db)
    app = LoanApplication()
    loan, parties = create_loan(db, app, parties)
    assert loan.state == "proposed"
    assert loan.principal_minor == 25000
    assert loan.currency_code == "YER"
    assert loan.version_no == 1
    loan_id = str(loan.loan_id)
    assert db.execute("SELECT COUNT(*) FROM obligations").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM payments").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM settlements").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM financial_transactions").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM ledger_entries").fetchone()[0] == 0
    db.close()

    db = connect_database(path)
    restored = app.get_loan(db, loan_id)
    assert restored.state == "proposed"
    assert restored.maturity_at == "2027-10-10T12:00:00Z"
    lender, borrower, approver, agent, context = parties
    grant, action = authorize(
        db, agent=agent, context=context, loan_id=loan_id,
        action_code="loan.transition.active", approver=approver,
    )
    active = app.transition_loan(
        db, loan_id, "active", expected_version=1, context_ref=context,
        authorization_grant_id=grant, agent_action_id=action, now=STAMP,
    )
    assert active.state == "active"
    assert active.version_no == 2
    grant, action = authorize(
        db, agent=agent, context=context, loan_id=loan_id,
        action_code="loan.transition.closed", approver=approver,
    )
    closed = app.transition_loan(
        db, loan_id, "closed", expected_version=2, context_ref=context,
        authorization_grant_id=grant, agent_action_id=action, now=STAMP,
    )
    assert closed.state == "closed"
    assert closed.version_no == 3
    history = db.execute(
        "SELECT prior_version_ref,current_version_ref,change_payload_ref FROM domain_history "
        "WHERE target_ref=? AND change_type='loan_state_transition' ORDER BY rowid",
        (loan_id,),
    ).fetchall()
    assert history == [
        (None, f"loan-state:{loan_id}:v1", "state:proposed"),
        (f"loan-state:{loan_id}:v1", f"loan-state:{loan_id}:v2", "state:active"),
        (f"loan-state:{loan_id}:v2", f"loan-state:{loan_id}:v3", "state:closed"),
    ]
    db.close()


@pytest.mark.parametrize("bad", ["wrong_scope", "wrong_action", "unapproved_action", "missing_approval"])
def test_loan_create_rejects_invalid_authority_without_writes(bad):
    db = connect_database()
    lender, borrower, approver, agent, context = seed(db)
    loan_id = uid()
    grant, action = authorize(
        db, agent=agent, context=context,
        loan_id=uid() if bad == "wrong_scope" else loan_id,
        action_code="loan.transition.active" if bad == "wrong_action" else "loan.create",
        approver=approver, state="prepared" if bad == "unapproved_action" else "approved",
        include_approval=bad != "missing_approval",
    )
    with pytest.raises(ValidationError):
        LoanApplication().create_loan(
            db, loan_id=loan_id, lender_person_id=lender, borrower_person_id=borrower,
            principal_minor=1000, currency_code="YER", context_ref=context,
            authorization_grant_id=grant, agent_action_id=action, now=STAMP,
        )
    assert db.execute("SELECT COUNT(*) FROM loans").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM domain_history WHERE change_type='loan_state_transition'").fetchone()[0] == 0
    db.close()


def test_loan_rejects_invalid_parties_principal_and_missing_person_without_writes():
    db = connect_database()
    lender, borrower, approver, agent, context = seed(db)
    app = LoanApplication()
    loan_id = uid()
    grant, action = authorize(
        db, agent=agent, context=context, loan_id=loan_id,
        action_code="loan.create", approver=approver,
    )
    with pytest.raises(ValidationError, match="parties must differ"):
        app.create_loan(
            db, loan_id=loan_id, lender_person_id=lender, borrower_person_id=lender,
            principal_minor=1000, currency_code="YER", context_ref=context,
            authorization_grant_id=grant, agent_action_id=action, now=STAMP,
        )
    with pytest.raises(ValidationError, match="positive integer"):
        app.create_loan(
            db, loan_id=loan_id, lender_person_id=lender, borrower_person_id=borrower,
            principal_minor=0, currency_code="YER", context_ref=context,
            authorization_grant_id=grant, agent_action_id=action, now=STAMP,
        )
    with pytest.raises(ValidationError, match="active persisted Person"):
        app.create_loan(
            db, loan_id=loan_id, lender_person_id=uid(), borrower_person_id=borrower,
            principal_minor=1000, currency_code="YER", context_ref=context,
            authorization_grant_id=grant, agent_action_id=action, now=STAMP,
        )
    assert db.execute("SELECT COUNT(*) FROM loans").fetchone()[0] == 0
    db.close()


def test_loan_history_failure_rolls_back_creation_and_transition():
    db = connect_database()
    parties = seed(db)
    app = LoanApplication()
    lender, borrower, approver, agent, context = parties
    loan_id = uid()
    grant, action = authorize(
        db, agent=agent, context=context, loan_id=loan_id,
        action_code="loan.create", approver=approver,
    )
    db.execute(
        "CREATE TRIGGER fail_loan_history BEFORE INSERT ON domain_history "
        "WHEN NEW.change_type='loan_state_transition' BEGIN SELECT RAISE(ABORT, 'history unavailable'); END"
    )
    db.commit()
    with pytest.raises(sqlite3.IntegrityError, match="history unavailable"):
        app.create_loan(
            db, loan_id=loan_id, lender_person_id=lender, borrower_person_id=borrower,
            principal_minor=1000, currency_code="YER", context_ref=context,
            authorization_grant_id=grant, agent_action_id=action, now=STAMP,
        )
    assert db.execute("SELECT COUNT(*) FROM loans").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM domain_history WHERE target_ref=?", (loan_id,)).fetchone()[0] == 0
    db.execute("DROP TRIGGER fail_loan_history")
    db.commit()
    loan, _ = create_loan(db, app, parties, loan_id=loan_id)
    grant, action = authorize(
        db, agent=agent, context=context, loan_id=loan_id,
        action_code="loan.transition.active", approver=approver,
    )
    db.execute(
        "CREATE TRIGGER fail_loan_transition_history BEFORE INSERT ON domain_history "
        "WHEN NEW.change_type='loan_state_transition' AND NEW.change_payload_ref='state:active' "
        "BEGIN SELECT RAISE(ABORT, 'history unavailable'); END"
    )
    db.commit()
    with pytest.raises(sqlite3.IntegrityError, match="history unavailable"):
        app.transition_loan(
            db, loan_id, "active", expected_version=1, context_ref=context,
            authorization_grant_id=grant, agent_action_id=action, now=STAMP,
        )
    current = app.get_loan(db, loan_id)
    assert current.state == "proposed"
    assert current.version_no == 1
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=? AND change_payload_ref='state:active'",
        (loan_id,),
    ).fetchone()[0] == 0
    db.close()


def test_loan_rejects_stale_version_and_unsupported_transition_without_mutation():
    db = connect_database()
    parties = seed(db)
    app = LoanApplication()
    loan, parties = create_loan(db, app, parties)
    lender, borrower, approver, agent, context = parties
    grant, action = authorize(
        db, agent=agent, context=context, loan_id=str(loan.loan_id),
        action_code="loan.transition.active", approver=approver,
    )
    with pytest.raises(ValidationError, match="version conflict"):
        app.transition_loan(
            db, loan.loan_id, "active", expected_version=9, context_ref=context,
            authorization_grant_id=grant, agent_action_id=action, now=STAMP,
        )
    active = app.transition_loan(
        db, loan.loan_id, "active", expected_version=1, context_ref=context,
        authorization_grant_id=grant, agent_action_id=action, now=STAMP,
    )
    assert active.state == "active"
    grant, action = authorize(
        db, agent=agent, context=context, loan_id=str(loan.loan_id),
        action_code="loan.transition.cancelled", approver=approver,
    )
    with pytest.raises(ValidationError, match="cannot transition"):
        app.transition_loan(
            db, loan.loan_id, "cancelled", expected_version=2, context_ref=context,
            authorization_grant_id=grant, agent_action_id=action, now=STAMP,
        )
    current = app.get_loan(db, loan.loan_id)
    assert current.state == "active"
    assert current.version_no == 2
    db.close()


def test_proposed_loan_can_be_cancelled_without_creating_an_obligation():
    db = connect_database()
    parties = seed(db)
    app = LoanApplication()
    loan, parties = create_loan(db, app, parties)
    _, _, approver, agent, context = parties
    grant, action = authorize(
        db, agent=agent, context=context, loan_id=str(loan.loan_id),
        action_code="loan.transition.cancelled", approver=approver,
    )
    cancelled = app.transition_loan(
        db, loan.loan_id, "cancelled", expected_version=1, context_ref=context,
        authorization_grant_id=grant, agent_action_id=action, now=STAMP,
    )
    assert cancelled.state == "cancelled"
    assert cancelled.version_no == 2
    assert db.execute("SELECT COUNT(*) FROM obligations WHERE loan_id=?", (str(loan.loan_id),)).fetchone()[0] == 0
    assert db.execute(
        "SELECT change_payload_ref FROM domain_history WHERE target_ref=? AND change_type='loan_state_transition' ORDER BY rowid",
        (str(loan.loan_id),),
    ).fetchall()[-1] == ("state:cancelled",)
    db.close()
