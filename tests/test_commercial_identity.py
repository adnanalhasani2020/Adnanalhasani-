from datetime import datetime, timezone

import pytest

from agent_core.commercial_identity import CommercialIdentityApplication
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

NOW = datetime(2026, 10, 17, 12, 0, tzinfo=timezone.utc)


def test_register_login_resolve_and_revoke_session(tmp_path):
    db = connect_database(tmp_path / "identity.sqlite")
    app = CommercialIdentityApplication()

    registered = app.register(db, username="Alice_1", password="correct horse battery staple", now=NOW)
    logged_in = app.login(db, username="ALICE_1", password="correct horse battery staple", now=NOW)

    assert registered["person_id"] == logged_in["person_id"]
    assert logged_in["username"] == "alice_1"
    assert app.resolve_session(db, logged_in["session_token"], now=NOW) == registered["person_id"]
    assert app.logout(db, logged_in["session_token"], now=NOW)
    assert app.resolve_session(db, logged_in["session_token"], now=NOW) is None


def test_registration_rejects_duplicate_username_case_insensitively(tmp_path):
    db = connect_database(tmp_path / "identity-duplicate.sqlite")
    app = CommercialIdentityApplication()
    app.register(db, username="StoreUser", password="correct horse battery staple", now=NOW)

    with pytest.raises(ValidationError, match="already registered"):
        app.register(db, username="storeuser", password="another secure password", now=NOW)

    assert db.execute("SELECT COUNT(*) FROM persons").fetchone()[0] == 1
    assert db.execute("SELECT COUNT(*) FROM access_accounts").fetchone()[0] == 1


def test_invalid_password_does_not_create_a_session(tmp_path):
    db = connect_database(tmp_path / "identity-invalid.sqlite")
    app = CommercialIdentityApplication()
    app.register(db, username="buyer", password="correct horse battery staple", now=NOW)

    with pytest.raises(ValidationError, match="Invalid username or password"):
        app.login(db, username="buyer", password="this password is wrong", now=NOW)

    assert db.execute("SELECT COUNT(*) FROM commercial_sessions").fetchone()[0] == 0


def test_expired_session_is_not_resolved(tmp_path):
    db = connect_database(tmp_path / "identity-expired.sqlite")
    app = CommercialIdentityApplication()
    app.register(db, username="buyer2", password="correct horse battery staple", now=NOW)
    logged_in = app.login(db, username="buyer2", password="correct horse battery staple", now=NOW)

    assert app.resolve_session(
        db, logged_in["session_token"], now=datetime(2026, 10, 17, 21, 0, tzinfo=timezone.utc)
    ) is None
