from uuid import uuid4
import sqlite3
import pytest
from agent_core.financial_policy_settings_application import FinancialPolicySettingsApplication
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

def test_defaults_and_validation():
    app = FinancialPolicySettingsApplication()
    policy = app.validate_policy()
    assert policy["enabled_currencies"] == ("SAR", "YER", "USD")
    assert policy["default_currency"] == "YER"
    assert policy["display_decimal_places"] == 2
    assert policy["settlement_cardinality"] == "single"
    assert policy["posted_voucher_policy"] == "reversal_only"
    assert policy["require_full_cycle_validation"] is True
    assert app.validate_policy(enabled_currencies=("usd", "YER"), default_currency="USD")["default_currency"] == "USD"

@pytest.mark.parametrize("kwargs", [
    {"enabled_currencies": ()}, {"enabled_currencies": ("EUR",)},
    {"enabled_currencies": ("YER",), "default_currency": "USD"},
    {"display_decimal_places": -1}, {"display_decimal_places": 5},
    {"display_decimal_places": True}, {"settlement_cardinality": "sometimes"},
    {"settlement_cardinality": "multiple"},
    {"posted_voucher_policy": "delete_posted"}, {"require_full_cycle_validation": 1},
])
def test_rejects_invalid_policy(kwargs):
    with pytest.raises(ValidationError):
        FinancialPolicySettingsApplication.validate_policy(**kwargs)

def test_get_settings_returns_safe_defaults_without_writes():
    db = connect_database()
    person, activity = str(uuid4()), str(uuid4())
    stamp = "2026-10-16T12:00:00Z"
    db.execute("INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
               (person, stamp, stamp))
    db.execute("INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) VALUES(?,?,'active',?,?)",
               (activity, person, stamp, stamp))
    db.commit()
    before = db.execute("SELECT COUNT(*) FROM domain_history").fetchone()[0]
    settings = FinancialPolicySettingsApplication().get_settings(db, activity_id=activity)
    assert settings.default_currency == "YER"
    assert settings.enabled_currencies == ("SAR", "YER", "USD")
    assert settings.version_no == 0
    assert db.execute("SELECT COUNT(*) FROM domain_history").fetchone()[0] == before
    with pytest.raises(ValidationError, match="does not exist"):
        FinancialPolicySettingsApplication().get_settings(db, activity_id=str(uuid4()))
    db.close()


def test_authorized_settings_update_is_versioned_and_audited():
    db = connect_database()
    stamp = "2026-10-16T12:00:00Z"
    person, activity, agent, grant, action = (str(uuid4()) for _ in range(5))
    db.execute("INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
               (person, stamp, stamp))
    db.execute("INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) "
               "VALUES(?,?,'active',?,?)", (activity, person, stamp, stamp))
    db.execute("INSERT INTO agents(agent_id,owner_person_id,configured_identity_ref,context_ref,state,created_at,updated_at) "
               "VALUES(?,?,?,?, 'active',?,?)", (agent, person, "policy-test-agent", activity, stamp, stamp))
    code = FinancialPolicySettingsApplication.ACTION_CODE
    db.execute("INSERT INTO authorization_grants(authorization_grant_id,agent_id,action_code,scope_ref,context_ref,"
               "state,effective_from,created_at,updated_at) VALUES(?,?,?,?,?,'active',?,?,?)",
               (grant, agent, code, activity, activity, stamp, stamp, stamp))
    db.execute("INSERT INTO agent_actions(agent_action_id,agent_id,action_code,target_ref,context_ref,"
               "authorization_grant_id,state,created_at,updated_at) VALUES(?,?,?,?,?,?,'approved',?,?)",
               (action, agent, code, activity, activity, grant, stamp, stamp))
    db.execute("INSERT INTO approvals(approval_id,agent_action_id,state,approver_person_id,decision_at,created_at,updated_at) "
               "VALUES(?,?,'APPROVED',?,?,?,?)", (str(uuid4()), action, person, stamp, stamp, stamp))
    db.commit()
    app = FinancialPolicySettingsApplication()
    result = app.update_settings(db, activity_id=activity, enabled_currencies=("YER", "USD"),
                                 default_currency="USD", display_decimal_places=3,
                                 authorization_grant_id=grant, agent_action_id=action, now=stamp,
                                 expected_version_no=0)
    assert result.default_currency == "USD"
    assert result.display_decimal_places == 3
    assert result.version_no == 1

    with pytest.raises(ValidationError, match="Stale Finance policy settings version"):
        app.update_settings(db, activity_id=activity, enabled_currencies=("YER", "USD"),
                            default_currency="YER", authorization_grant_id=grant,
                            agent_action_id=action, now=stamp, expected_version_no=0)

    current = app.get_settings(db, activity_id=activity)
    assert current.version_no == 1
    assert current.default_currency == "USD"
    history = db.execute("SELECT change_type,current_version_ref FROM domain_history "
                         "WHERE owner_domain='FinancePolicySettings'").fetchall()
    assert history == [("created", "1")]
    db.close()


def test_database_rejects_unsupported_or_noncanonical_currency_policy():
    db = connect_database()
    person, activity = str(uuid4()), str(uuid4())
    stamp = "2026-10-16T12:00:00Z"
    db.execute("INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
               (person, stamp, stamp))
    db.execute("INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) VALUES(?,?,'active',?,?)",
               (activity, person, stamp, stamp))
    with pytest.raises(sqlite3.IntegrityError, match="enabled currencies"):
        db.execute(
            "INSERT INTO financial_policy_settings(activity_id,enabled_currencies,default_currency,updated_at,updated_by_person_id) "
            "VALUES(?, 'EUR,YER', 'YER', ?, ?)", (activity, stamp, person)
        )
    with pytest.raises(sqlite3.IntegrityError, match="enabled currencies"):
        db.execute(
            "INSERT INTO financial_policy_settings(activity_id,enabled_currencies,default_currency,updated_at,updated_by_person_id) "
            "VALUES(?, 'YER,SAR', 'YER', ?, ?)", (activity, stamp, person)
        )
    with pytest.raises(sqlite3.IntegrityError, match="default currency"):
        db.execute(
            "INSERT INTO financial_policy_settings(activity_id,enabled_currencies,default_currency,updated_at,updated_by_person_id) "
            "VALUES(?, 'SAR,YER,USD', 'EUR', ?, ?)", (activity, stamp, person)
        )
    db.close()

def test_settings_update_rejects_invalid_expected_version_before_database_mutation():
    app = FinancialPolicySettingsApplication()
    with pytest.raises(ValidationError, match="expected_version_no"):
        app.update_settings(
            None,
            activity_id=str(uuid4()),
            authorization_grant_id=str(uuid4()),
            agent_action_id=str(uuid4()),
            expected_version_no=True,
        )


def test_settings_update_rolls_back_when_audit_history_write_fails():
    db = connect_database()
    stamp = "2026-10-16T12:00:00Z"
    person, activity, agent, grant, action = (str(uuid4()) for _ in range(5))
    db.execute("INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
               (person, stamp, stamp))
    db.execute("INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) "
               "VALUES(?,?,'active',?,?)", (activity, person, stamp, stamp))
    db.execute("INSERT INTO agents(agent_id,owner_person_id,configured_identity_ref,context_ref,state,created_at,updated_at) "
               "VALUES(?,?,?,?, 'active',?,?)", (agent, person, "policy-atomicity-test-agent", activity, stamp, stamp))
    code = FinancialPolicySettingsApplication.ACTION_CODE
    db.execute("INSERT INTO authorization_grants(authorization_grant_id,agent_id,action_code,scope_ref,context_ref,"
               "state,effective_from,created_at,updated_at) VALUES(?,?,?,?,?,'active',?,?,?)",
               (grant, agent, code, activity, activity, stamp, stamp, stamp))
    db.execute("INSERT INTO agent_actions(agent_action_id,agent_id,action_code,target_ref,context_ref,"
               "authorization_grant_id,state,created_at,updated_at) VALUES(?,?,?,?,?,?,'approved',?,?)",
               (action, agent, code, activity, activity, grant, stamp, stamp))
    db.execute("INSERT INTO approvals(approval_id,agent_action_id,state,approver_person_id,decision_at,created_at,updated_at) "
               "VALUES(?,?,'APPROVED',?,?,?,?)", (str(uuid4()), action, person, stamp, stamp, stamp))
    db.execute(
        "CREATE TRIGGER reject_finance_policy_audit BEFORE INSERT ON domain_history "
        "WHEN NEW.owner_domain='FinancePolicySettings' "
        "BEGIN SELECT RAISE(ABORT, 'simulated audit persistence failure'); END"
    )
    db.commit()

    app = FinancialPolicySettingsApplication()
    with pytest.raises(sqlite3.IntegrityError, match="simulated audit persistence failure"):
        app.update_settings(
            db, activity_id=activity, enabled_currencies=("YER", "USD"),
            default_currency="USD", display_decimal_places=3,
            authorization_grant_id=grant, agent_action_id=action, now=stamp,
            expected_version_no=0,
        )

    current = app.get_settings(db, activity_id=activity)
    assert current.version_no == 0
    assert current.default_currency == "YER"
    assert db.execute(
        "SELECT COUNT(*) FROM financial_policy_settings WHERE activity_id=?", (activity,)
    ).fetchone()[0] == 0
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE owner_domain='FinancePolicySettings'"
    ).fetchone()[0] == 0
    db.close()


def test_settings_audit_failure_preserves_caller_owned_transaction():
    db = connect_database()
    stamp = "2026-10-16T12:00:00Z"
    person, activity, agent, grant, action, caller_person = (str(uuid4()) for _ in range(6))
    db.execute("INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
               (person, stamp, stamp))
    db.execute("INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) "
               "VALUES(?,?,'active',?,?)", (activity, person, stamp, stamp))
    db.execute("INSERT INTO agents(agent_id,owner_person_id,configured_identity_ref,context_ref,state,created_at,updated_at) "
               "VALUES(?,?,?,?, 'active',?,?)", (agent, person, "policy-savepoint-test-agent", activity, stamp, stamp))
    code = FinancialPolicySettingsApplication.ACTION_CODE
    db.execute("INSERT INTO authorization_grants(authorization_grant_id,agent_id,action_code,scope_ref,context_ref,"
               "state,effective_from,created_at,updated_at) VALUES(?,?,?,?,?,'active',?,?,?)",
               (grant, agent, code, activity, activity, stamp, stamp, stamp))
    db.execute("INSERT INTO agent_actions(agent_action_id,agent_id,action_code,target_ref,context_ref,"
               "authorization_grant_id,state,created_at,updated_at) VALUES(?,?,?,?,?,?,'approved',?,?)",
               (action, agent, code, activity, activity, grant, stamp, stamp))
    db.execute("INSERT INTO approvals(approval_id,agent_action_id,state,approver_person_id,decision_at,created_at,updated_at) "
               "VALUES(?,?,'APPROVED',?,?,?,?)", (str(uuid4()), action, person, stamp, stamp, stamp))
    db.execute(
        "CREATE TRIGGER reject_finance_policy_audit BEFORE INSERT ON domain_history "
        "WHEN NEW.owner_domain='FinancePolicySettings' "
        "BEGIN SELECT RAISE(ABORT, 'simulated caller transaction audit failure'); END"
    )
    db.execute("INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
               (caller_person, stamp, stamp))
    assert db.in_transaction

    with pytest.raises(sqlite3.IntegrityError, match="simulated caller transaction audit failure"):
        FinancialPolicySettingsApplication().update_settings(
            db, activity_id=activity, enabled_currencies=("YER", "USD"),
            default_currency="USD", display_decimal_places=3,
            authorization_grant_id=grant, agent_action_id=action, now=stamp,
            expected_version_no=0,
        )

    # The failed settings operation rolls back to its savepoint without
    # discarding unrelated work owned by the caller.
    assert db.in_transaction
    assert db.execute("SELECT COUNT(*) FROM persons WHERE person_id=?", (caller_person,)).fetchone()[0] == 1
    assert db.execute("SELECT COUNT(*) FROM financial_policy_settings WHERE activity_id=?", (activity,)).fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM domain_history WHERE owner_domain='FinancePolicySettings'").fetchone()[0] == 0

    db.rollback()
    assert db.execute("SELECT COUNT(*) FROM persons WHERE person_id=?", (caller_person,)).fetchone()[0] == 0
    db.close()
