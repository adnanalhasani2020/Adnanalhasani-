from datetime import datetime, timezone
from uuid import uuid4

import pytest

from agent_core.membership_application import MembershipApplication
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

STAMP = "2026-10-10T12:00:00Z"


def seed_person_activity(db):
    person_id, activity_id = str(uuid4()), str(uuid4())
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?, 'active', ?, ?)",
        (person_id, STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) "
        "VALUES(?, ?, 'active', ?, ?)",
        (activity_id, person_id, STAMP, STAMP),
    )
    db.commit()
    return person_id, activity_id


def test_membership_persists_and_restores_after_reopen(tmp_path):
    path = tmp_path / "memberships.sqlite"
    db = connect_database(path)
    person_id, activity_id = seed_person_activity(db)
    app = MembershipApplication()
    membership = app.create_membership(
        db, person_id=person_id, activity_id=activity_id, state="proposed",
        effective_from=datetime(2026, 10, 1, tzinfo=timezone.utc),
    )
    db.close()
    reopened = connect_database(path)
    assert app.get_membership(reopened, membership.membership_id) == membership
    assert app.list_for_person(reopened, person_id) == (membership,)
    assert app.list_for_activity(reopened, activity_id) == (membership,)
    reopened.close()


def test_membership_lists_are_scoped_and_deterministic():
    db = connect_database()
    person_id, activity_id = seed_person_activity(db)
    app = MembershipApplication()
    first = app.create_membership(
        db, person_id=person_id, activity_id=activity_id, state="active",
        effective_from=STAMP, created_at=STAMP,
    )
    second = app.create_membership(
        db, person_id=person_id, activity_id=activity_id, state="suspended",
        effective_from=STAMP, created_at=STAMP,
    )
    listed = app.list_for_activity(db, activity_id)
    assert listed == tuple(sorted((first, second), key=lambda item: (item.created_at, str(item.membership_id))))
    assert app.list_for_person(db, person_id) == listed
    db.close()


@pytest.mark.parametrize("kwargs", [
    {"person_id": "bad", "activity_id": str(uuid4()), "state": "active", "effective_from": STAMP},
    {"person_id": str(uuid4()), "activity_id": str(uuid4()), "state": "unknown", "effective_from": STAMP},
    {"person_id": str(uuid4()), "activity_id": str(uuid4()), "state": "active", "effective_from": "bad-time"},
    {"person_id": str(uuid4()), "activity_id": str(uuid4()), "state": "active", "effective_from": "2026-10-10T12:00:00"},
])
def test_membership_rejects_invalid_contract_inputs(kwargs):
    db = connect_database()
    with pytest.raises(ValidationError):
        MembershipApplication().create_membership(db, **kwargs)
    db.close()


def test_membership_requires_existing_person_and_activity():
    db = connect_database()
    app = MembershipApplication()
    with pytest.raises(ValidationError, match="existing Person"):
        app.create_membership(
            db, person_id=str(uuid4()), activity_id=str(uuid4()),
            state="active", effective_from=STAMP,
        )
    person_id, activity_id = seed_person_activity(db)
    with pytest.raises(ValidationError, match="existing Activity"):
        app.create_membership(
            db, person_id=person_id, activity_id=str(uuid4()),
            state="active", effective_from=STAMP,
        )
    with pytest.raises(ValidationError, match="does not exist"):
        app.get_membership(db, str(uuid4()))
    db.close()


def test_membership_rejects_end_before_start():
    db = connect_database()
    person_id, activity_id = seed_person_activity(db)
    with pytest.raises(ValidationError, match="cannot precede"):
        MembershipApplication().create_membership(
            db, person_id=person_id, activity_id=activity_id, state="active",
            effective_from="2026-12-01T00:00:00Z", effective_to="2026-11-01T00:00:00Z",
        )
    db.close()
