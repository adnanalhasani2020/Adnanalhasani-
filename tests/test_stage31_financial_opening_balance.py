"""Integration tests for persisted, authorized opening-balance recognition."""
import sqlite3
import uuid

import pytest

from agent_core.financial_balance_read import FinancialBalanceReader
from agent_core.financial_opening_balance_application import FinancialOpeningBalanceApplication
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

STAMP = "2026-10-10T12:00:00Z"


def uid():
    return str(uuid.uuid4())


def seed(db):
    owner, approver, agent, context, account_id = [uid() for _ in range(5)]
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
    db.execute(
        "INSERT INTO financial_accounts(financial_account_id,person_id,account_type,state,created_at,updated_at) "
        "VALUES(?,?,?,'active',?,?)",
        (account_id, owner, "general", STAMP, STAMP),
    )
    db.commit()
    return owner, approver, agent, context, account_id


def authorize(db, *, agent, approver, context, account_id, state="approved", include_approval=True):
    grant, action = uid(), uid()
    db.execute(
        "INSERT INTO authorization_grants(authorization_grant_id,agent_id,action_code,scope_ref,context_ref,"
        "state,effective_from,created_at,updated_at) VALUES(?,?,?,?,?,'active',?,?,?)",
        (grant, agent, FinancialOpeningBalanceApplication.ACTION_CODE, account_id,
         context, "2026-10-01T00:00:00Z", STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO agent_actions(agent_action_id,agent_id,action_code,target_ref,context_ref,"
        "authorization_grant_id,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?, ?,?)",
        (action, agent, FinancialOpeningBalanceApplication.ACTION_CODE, account_id, context,
         grant, state, STAMP, STAMP),
    )
    if include_approval:
        db.execute(
            "INSERT INTO approvals(approval_id,agent_action_id,approval_gate_code,state,approver_person_id,"
            "decision_at,created_at,updated_at) VALUES(?,?, 'default','APPROVED',?,?,?,?)",
            (uid(), action, approver, STAMP, STAMP, STAMP),
        )
    db.commit()
    return grant, action


def test_opening_balance_is_a_ledger_fact_and_balance_is_derived():
    db = connect_database()
    _, approver, agent, context, account_id = seed(db)
    grant, action = authorize(db, agent=agent, approver=approver, context=context, account_id=account_id)
    result = FinancialOpeningBalanceApplication().record_opening_balance(
        db, financial_account_id=account_id, amount_minor=1250, currency_code="YER",
        source_ref="opening-import-2026", context_ref=context, authorization_grant_id=grant,
        agent_action_id=action, now=STAMP,
    )
    balance = FinancialBalanceReader().read(db, account_id, "YER", as_of=STAMP)
    assert balance.amount_minor == 1250
    assert balance.entry_count == 1
    assert db.execute(
        "SELECT recognition_source_type,state,amount_minor FROM financial_transactions WHERE financial_transaction_id=?",
        (str(result.financial_transaction_id),),
    ).fetchone() == ("opening_balance", "recognized", 1250)
    assert db.execute(
        "SELECT state,amount_minor FROM ledger_entries WHERE ledger_entry_id=?", (str(result.ledger_entry_id),)
    ).fetchone() == ("posted", 1250)
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=? AND change_type='financial_opening_balance'",
        (account_id,),
    ).fetchone()[0] == 1
    assert db.execute("PRAGMA foreign_key_check").fetchall() == []
    db.close()


def test_opening_balance_supports_negative_values_and_rejects_duplicate_currency():
    db = connect_database()
    _, approver, agent, context, account_id = seed(db)
    app = FinancialOpeningBalanceApplication()
    grant, action = authorize(db, agent=agent, approver=approver, context=context, account_id=account_id)
    app.record_opening_balance(
        db, financial_account_id=account_id, amount_minor=-350, currency_code="YER",
        source_ref="opening-debt", context_ref=context, authorization_grant_id=grant,
        agent_action_id=action, now=STAMP,
    )
    grant, action = authorize(db, agent=agent, approver=approver, context=context, account_id=account_id)
    with pytest.raises(ValidationError, match="already exists"):
        app.record_opening_balance(
            db, financial_account_id=account_id, amount_minor=350, currency_code="YER",
            source_ref="second-opening", context_ref=context, authorization_grant_id=grant,
            agent_action_id=action, now=STAMP,
        )
    assert FinancialBalanceReader().read(db, account_id, "YER", as_of=STAMP).amount_minor == -350
    db.close()


@pytest.mark.parametrize(
    ("amount", "currency", "source", "message"),
    [
        (True, "YER", "source", "integer"),
        (0, "YER", "source", "zero opening balance"),
        (100, "yer", "source", "3-letter uppercase"),
        (100, "YER", " ", "source_ref is required"),
    ],
)
def test_opening_balance_rejects_invalid_values_without_writes(amount, currency, source, message):
    db = connect_database()
    _, approver, agent, context, account_id = seed(db)
    grant, action = authorize(db, agent=agent, approver=approver, context=context, account_id=account_id)
    with pytest.raises(ValidationError, match=message):
        FinancialOpeningBalanceApplication().record_opening_balance(
            db, financial_account_id=account_id, amount_minor=amount, currency_code=currency,
            source_ref=source, context_ref=context, authorization_grant_id=grant,
            agent_action_id=action, now=STAMP,
        )
    assert db.execute("SELECT COUNT(*) FROM financial_transactions").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM ledger_entries").fetchone()[0] == 0
    db.close()


@pytest.mark.parametrize("bad", ["unapproved", "missing_approval"])
def test_opening_balance_rejects_invalid_authority_without_financial_writes(bad):
    db = connect_database()
    _, approver, agent, context, account_id = seed(db)
    grant, action = authorize(
        db, agent=agent, approver=approver, context=context, account_id=account_id,
        state="prepared" if bad == "unapproved" else "approved",
        include_approval=bad != "missing_approval",
    )
    with pytest.raises(ValidationError):
        FinancialOpeningBalanceApplication().record_opening_balance(
            db, financial_account_id=account_id, amount_minor=100, currency_code="YER",
            source_ref="source", context_ref=context, authorization_grant_id=grant,
            agent_action_id=action, now=STAMP,
        )
    assert db.execute("SELECT COUNT(*) FROM financial_transactions").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM ledger_entries").fetchone()[0] == 0
    db.close()


def test_opening_balance_rolls_back_transaction_and_history_together_on_failure():
    db = connect_database()
    _, approver, agent, context, account_id = seed(db)
    grant, action = authorize(db, agent=agent, approver=approver, context=context, account_id=account_id)
    db.execute(
        "CREATE TRIGGER fail_opening_history BEFORE INSERT ON domain_history "
        "WHEN NEW.change_type='financial_opening_balance' "
        "BEGIN SELECT RAISE(ABORT, 'history unavailable'); END"
    )
    db.commit()
    with pytest.raises(sqlite3.IntegrityError, match="history unavailable"):
        FinancialOpeningBalanceApplication().record_opening_balance(
            db, financial_account_id=account_id, amount_minor=100, currency_code="YER",
            source_ref="source", context_ref=context, authorization_grant_id=grant,
            agent_action_id=action, now=STAMP,
        )
    assert db.execute("SELECT COUNT(*) FROM financial_transactions").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM ledger_entries").fetchone()[0] == 0
    db.close()
