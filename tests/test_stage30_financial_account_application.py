"""Integration tests for persisted Financial Account lifecycle and atomic history."""
import sqlite3
import uuid

import pytest

from agent_core.financial_account_application import FinancialAccountApplication
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

STAMP = "2026-10-10T12:00:00Z"


def uid():
    return str(uuid.uuid4())


def seed(db):
    owner, approver, agent, context = [uid() for _ in range(4)]
    for person in (owner, approver):
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
    return owner, approver, agent, context


def authorize(db, *, agent, context, account_id, action_code, approver, state="approved", include_approval=True):
    grant, action = uid(), uid()
    db.execute(
        "INSERT INTO authorization_grants(authorization_grant_id,agent_id,action_code,scope_ref,context_ref,"
        "state,effective_from,created_at,updated_at) VALUES(?,?,?,?,?,'active',?,?,?)",
        (grant, agent, action_code, account_id, context, "2026-10-01T00:00:00Z", STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO agent_actions(agent_action_id,agent_id,action_code,target_ref,context_ref,"
        "authorization_grant_id,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?, ?,?)",
        (action, agent, action_code, account_id, context, grant, state, STAMP, STAMP),
    )
    if include_approval:
        db.execute(
            "INSERT INTO approvals(approval_id,agent_action_id,approval_gate_code,state,approver_person_id,"
            "decision_at,created_at,updated_at) VALUES(?,?, 'default','APPROVED',?,?,?,?)",
            (uid(), action, approver, STAMP, STAMP, STAMP),
        )
    db.commit()
    return grant, action


def create_account(db, app, actors, *, account_id=None):
    owner, approver, agent, context = actors
    account_id = account_id or uid()
    grant, action = authorize(
        db, agent=agent, context=context, account_id=account_id,
        action_code="financial_account.create", approver=approver,
    )
    record = app.create_account(
        db, financial_account_id=account_id, person_id=owner, account_type="general",
        context_ref=context, authorization_grant_id=grant, agent_action_id=action, now=STAMP,
    )
    return record, actors


def test_financial_account_create_restore_and_close_are_persisted_without_financial_side_effects(tmp_path):
    path = tmp_path / "account.sqlite"
    db = connect_database(path)
    actors = seed(db)
    app = FinancialAccountApplication()
    account, actors = create_account(db, app, actors)
    assert account.state == "active"
    assert account.account_type == "general"
    assert account.version_no == 1
    account_id = str(account.financial_account_id)
    assert db.execute("SELECT COUNT(*) FROM financial_transactions").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM ledger_entries").fetchone()[0] == 0
    db.close()

    db = connect_database(path)
    restored = app.get_account(db, account_id)
    assert restored.state == "active"
    owner, approver, agent, context = actors
    grant, action = authorize(
        db, agent=agent, context=context, account_id=account_id,
        action_code="financial_account.close", approver=approver,
    )
    closed = app.close_account(
        db, account_id, expected_version=1, context_ref=context,
        authorization_grant_id=grant, agent_action_id=action, now=STAMP,
    )
    assert closed.state == "closed"
    assert closed.version_no == 2
    history = db.execute(
        "SELECT prior_version_ref,current_version_ref,change_payload_ref FROM domain_history "
        "WHERE target_ref=? AND change_type='financial_account_lifecycle' ORDER BY rowid",
        (account_id,),
    ).fetchall()
    assert history == [
        (None, f"financial-account:{account_id}:v1", "state:active"),
        (f"financial-account:{account_id}:v1", f"financial-account:{account_id}:v2", "state:closed"),
    ]
    db.close()


@pytest.mark.parametrize("bad", ["wrong_scope", "wrong_action", "unapproved_action", "missing_approval"])
def test_financial_account_create_rejects_invalid_authority_without_writes(bad):
    db = connect_database()
    owner, approver, agent, context = seed(db)
    account_id = uid()
    grant, action = authorize(
        db, agent=agent, context=context,
        account_id=uid() if bad == "wrong_scope" else account_id,
        action_code="financial_account.close" if bad == "wrong_action" else "financial_account.create",
        approver=approver, state="prepared" if bad == "unapproved_action" else "approved",
        include_approval=bad != "missing_approval",
    )
    with pytest.raises(ValidationError):
        FinancialAccountApplication().create_account(
            db, financial_account_id=account_id, person_id=owner, account_type="general",
            context_ref=context, authorization_grant_id=grant, agent_action_id=action, now=STAMP,
        )
    assert db.execute("SELECT COUNT(*) FROM financial_accounts").fetchone()[0] == 0
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE change_type='financial_account_lifecycle'"
    ).fetchone()[0] == 0
    db.close()


def test_financial_account_rejects_invalid_owner_and_type():
    db = connect_database()
    owner, approver, agent, context = seed(db)
    app = FinancialAccountApplication()
    account_id = uid()
    grant, action = authorize(
        db, agent=agent, context=context, account_id=account_id,
        action_code="financial_account.create", approver=approver,
    )
    with pytest.raises(ValidationError, match="account_type is required"):
        app.create_account(
            db, financial_account_id=account_id, person_id=owner, account_type=" ",
            context_ref=context, authorization_grant_id=grant, agent_action_id=action, now=STAMP,
        )
    with pytest.raises(ValidationError, match="active persisted Person"):
        app.create_account(
            db, financial_account_id=account_id, person_id=uid(), account_type="general",
            context_ref=context, authorization_grant_id=grant, agent_action_id=action, now=STAMP,
        )
    assert db.execute("SELECT COUNT(*) FROM financial_accounts").fetchone()[0] == 0
    db.close()


def test_financial_account_close_rejects_stale_version_and_history_failure_rolls_back():
    db = connect_database()
    actors = seed(db)
    app = FinancialAccountApplication()
    account, actors = create_account(db, app, actors)
    owner, approver, agent, context = actors
    key = str(account.financial_account_id)
    grant, action = authorize(
        db, agent=agent, context=context, account_id=key,
        action_code="financial_account.close", approver=approver,
    )
    with pytest.raises(ValidationError, match="version conflict"):
        app.close_account(
            db, key, expected_version=9, context_ref=context,
            authorization_grant_id=grant, agent_action_id=action, now=STAMP,
        )
    db.execute(
        "CREATE TRIGGER fail_account_history BEFORE INSERT ON domain_history "
        "WHEN NEW.change_type='financial_account_lifecycle' AND NEW.change_payload_ref='state:closed' "
        "BEGIN SELECT RAISE(ABORT, 'history unavailable'); END"
    )
    db.commit()
    with pytest.raises(sqlite3.IntegrityError, match="history unavailable"):
        app.close_account(
            db, key, expected_version=1, context_ref=context,
            authorization_grant_id=grant, agent_action_id=action, now=STAMP,
        )
    current = app.get_account(db, key)
    assert current.state == "active"
    assert current.version_no == 1
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=? AND change_payload_ref='state:closed'",
        (key,),
    ).fetchone()[0] == 0
    db.close()


def test_closed_financial_account_cannot_be_closed_again():
    db = connect_database()
    actors = seed(db)
    app = FinancialAccountApplication()
    account, actors = create_account(db, app, actors)
    _, approver, agent, context = actors
    key = str(account.financial_account_id)
    grant, action = authorize(
        db, agent=agent, context=context, account_id=key,
        action_code="financial_account.close", approver=approver,
    )
    app.close_account(
        db, key, expected_version=1, context_ref=context,
        authorization_grant_id=grant, agent_action_id=action, now=STAMP,
    )
    grant, action = authorize(
        db, agent=agent, context=context, account_id=key,
        action_code="financial_account.close", approver=approver,
    )
    with pytest.raises(ValidationError, match="Only an active"):
        app.close_account(
            db, key, expected_version=2, context_ref=context,
            authorization_grant_id=grant, agent_action_id=action, now=STAMP,
        )
    db.close()
