from datetime import datetime, timezone
from uuid import uuid4

import pytest

from agent_core.persistence import connect_database
from agent_core.person_registration_application import PersonRegistrationApplication
from agent_core.shared import ValidationError


def test_person_registration_persists_explicit_record_across_reopen(tmp_path):
    path = tmp_path / "people.sqlite"
    person_id = uuid4()
    created = datetime(2026, 10, 11, 8, 30, tzinfo=timezone.utc)
    updated = datetime(2026, 10, 11, 9, 0, tzinfo=timezone.utc)
    db = connect_database(path)

    record = PersonRegistrationApplication().create_person(
        db, person_id=person_id, state="active",
        created_at=created, updated_at=updated,
    )
    assert record.person_id == person_id
    assert record.state == "active"
    assert record.canonical_person_id is None
    assert record.created_at == "2026-10-11T08:30:00Z"
    assert record.updated_at == "2026-10-11T09:00:00Z"
    assert record.version_no == 1
    db.close()

    reopened = connect_database(path)
    row = reopened.execute(
        "SELECT person_id, state, canonical_person_id, created_at, updated_at, version_no "
        "FROM persons WHERE person_id=?",
        (str(person_id),),
    ).fetchone()
    assert row == (
        str(person_id), "active", None,
        "2026-10-11T08:30:00Z", "2026-10-11T09:00:00Z", 1,
    )
    reopened.close()


def test_person_registration_generates_id_and_normalizes_timezone():
    db = connect_database()
    record = PersonRegistrationApplication().create_person(
        db, state="suspended",
        created_at=datetime(2026, 10, 11, 12, 0, tzinfo=timezone.utc),
    )
    assert record.person_id is not None
    assert record.state == "suspended"
    assert record.created_at == "2026-10-11T12:00:00Z"
    assert record.updated_at == record.created_at
    assert db.execute(
        "SELECT 1 FROM persons WHERE person_id=?", (str(record.person_id),)
    ).fetchone() == (1,)
    db.close()


@pytest.mark.parametrize("kwargs", [
    {"person_id": "not-a-uuid", "state": "active"},
    {"person_id": str(uuid4()), "state": ""},
    {"person_id": str(uuid4()), "state": "   "},
    {"person_id": str(uuid4()), "state": "active", "created_at": datetime(2026, 1, 1)},
    {"person_id": str(uuid4()), "state": "active", "updated_at": "not-a-timestamp"},
])
def test_person_registration_rejects_invalid_inputs(kwargs):
    db = connect_database()
    with pytest.raises(ValidationError):
        PersonRegistrationApplication().create_person(db, **kwargs)
    assert db.execute("SELECT COUNT(*) FROM persons").fetchone()[0] == 0
    db.close()


def test_person_registration_rejects_duplicate_person_id_without_overwriting():
    db = connect_database()
    app = PersonRegistrationApplication()
    person_id = uuid4()
    original = app.create_person(db, person_id=person_id, state="active")
    with pytest.raises(ValidationError, match="Person identifier"):
        app.create_person(db, person_id=person_id, state="suspended")
    row = db.execute(
        "SELECT state FROM persons WHERE person_id=?", (str(person_id),)
    ).fetchone()
    assert row == ("active",)
    assert original.state == "active"
    db.close()
