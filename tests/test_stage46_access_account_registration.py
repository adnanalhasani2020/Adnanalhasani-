import sqlite3
import uuid
from datetime import datetime, timezone

import pytest

from agent_core.access_account_registration_application import AccessAccountRegistrationApplication
from agent_core.access_account_read import PersistedAccessAccountReader
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError


STAMP = "2026-10-11T12:00:00Z"


def seed_person(db):
    person_id = str(uuid.uuid4())
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (person_id, "active", STAMP, STAMP),
    )
    return person_id


def test_create_access_account_persists_and_reader_restores_after_reopen(tmp_path):
    path = tmp_path / "account-registration.sqlite"
    db = connect_database(path)
    person_id = seed_person(db)
    account = AccessAccountRegistrationApplication().create_access_account(
        db, person_id=person_id, state="suspended",
        created_at="2026-10-11T14:00:00+02:00",
    )
    assert account.person_id == uuid.UUID(person_id)
    assert account.state == "suspended"
    assert account.created_at == "2026-10-11T12:00:00Z"
    assert account.updated_at == account.created_at
    assert account.version_no == 1
    account_id = str(account.access_account_id)
    db.close()

    reopened = connect_database(path)
    restored = PersistedAccessAccountReader().get(reopened, account_id)
    assert restored == account
    assert [row.access_account_id for row in PersistedAccessAccountReader().list_for_person(reopened, person_id)] == [account.access_account_id]
    reopened.close()


def test_create_access_account_generates_id_and_preserves_explicit_timestamps():
    db = connect_database()
    person_id = seed_person(db)
    record = AccessAccountRegistrationApplication().create_access_account(
        db, person_id=person_id, state="active",
        created_at=datetime(2026, 10, 11, 12, tzinfo=timezone.utc),
        updated_at="2026-10-11T13:00:00+01:00",
    )
    assert isinstance(record.access_account_id, uuid.UUID)
    assert record.created_at == "2026-10-11T12:00:00Z"
    assert record.updated_at == "2026-10-11T12:00:00Z"
    db.close()


@pytest.mark.parametrize("state", ["", "proposed", "ACTIVE", "disabled", None])
def test_create_access_account_rejects_states_outside_schema(state):
    db = connect_database()
    person_id = seed_person(db)
    with pytest.raises(ValidationError, match="state must match"):
        AccessAccountRegistrationApplication().create_access_account(
            db, person_id=person_id, state=state
        )
    db.close()


def test_create_access_account_rejects_invalid_ids_and_naive_timestamps():
    db = connect_database()
    person_id = seed_person(db)
    app = AccessAccountRegistrationApplication()
    with pytest.raises(ValidationError, match="Person identifier"):
        app.create_access_account(db, person_id="not-a-uuid", state="active")
    with pytest.raises(ValidationError, match="Access Account identifier"):
        app.create_access_account(db, person_id=person_id, state="active", access_account_id="bad")
    with pytest.raises(ValidationError, match="timezone-aware"):
        app.create_access_account(
            db, person_id=person_id, state="active",
            created_at=datetime(2026, 10, 11, 12),
        )
    db.close()


def test_create_access_account_requires_existing_person_and_does_not_create_related_records():
    db = connect_database()
    app = AccessAccountRegistrationApplication()
    with pytest.raises(ValidationError, match="existing Person"):
        app.create_access_account(db, person_id=str(uuid.uuid4()), state="active")
    assert db.execute("SELECT COUNT(*) FROM access_accounts").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM authenticators").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM sessions").fetchone()[0] == 0
    db.close()


def test_duplicate_account_id_is_rejected_without_overwriting_original():
    db = connect_database()
    person_id = seed_person(db)
    app = AccessAccountRegistrationApplication()
    account_id = str(uuid.uuid4())
    original = app.create_access_account(
        db, person_id=person_id, state="active", access_account_id=account_id
    )
    with pytest.raises(ValidationError, match="identifier or referenced Person"):
        app.create_access_account(
            db, person_id=person_id, state="suspended", access_account_id=account_id
        )
    restored = PersistedAccessAccountReader().get(db, account_id)
    assert restored == original
    assert db.execute("SELECT COUNT(*) FROM access_accounts").fetchone()[0] == 1
    db.close()
