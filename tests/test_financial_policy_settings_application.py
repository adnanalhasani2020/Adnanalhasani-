from uuid import uuid4
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
