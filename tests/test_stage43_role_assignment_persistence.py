from datetime import datetime, timezone
from uuid import uuid4

import pytest

from agent_core.persistence import connect_database
from agent_core.role_assignment_application import RoleAssignmentApplication
from agent_core.shared import ValidationError

STAMP = "2026-10-10T12:00:00Z"


def seed_membership(db, *, membership_id=None):
    person_id, activity_id = str(uuid4()), str(uuid4())
    membership_id = str(membership_id or uuid4())
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?, 'active', ?, ?)",
        (person_id, STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) "
        "VALUES(?, ?, 'active', ?, ?)",
        (activity_id, person_id, STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO memberships(membership_id,person_id,activity_id,state,effective_from,created_at,updated_at) "
        "VALUES(?, ?, ?, 'active', ?, ?, ?)",
        (membership_id, person_id, activity_id, STAMP, STAMP, STAMP),
    )
    db.commit()
    return membership_id


def test_role_assignment_persists_and_restores_after_reopen(tmp_path):
    path = tmp_path / "roles.sqlite"
    db = connect_database(path)
    membership_id = seed_membership(db)
    app = RoleAssignmentApplication()
    assignment = app.create_role_assignment(
        db, membership_id=membership_id, role_code="organizer", state="active",
        effective_from=datetime(2026, 10, 1, tzinfo=timezone.utc),
        effective_to=datetime(2026, 12, 1, tzinfo=timezone.utc),
    )
    db.close()

    reopened = connect_database(path)
    assert app.get_role_assignment(reopened, assignment.role_assignment_id) == assignment
    assert app.list_for_membership(reopened, membership_id) == (assignment,)
    reopened.close()


def test_role_assignment_lists_deterministically_within_membership():
    db = connect_database()
    membership_id = seed_membership(db)
    app = RoleAssignmentApplication()
    first = app.create_role_assignment(
        db, membership_id=membership_id, role_code="organizer", state="active",
        effective_from=STAMP, role_assignment_id=str(uuid4()),
        created_at="2026-10-10T12:00:00Z",
    )
    second = app.create_role_assignment(
        db, membership_id=membership_id, role_code="coordinator", state="suspended",
        effective_from=STAMP, role_assignment_id=str(uuid4()),
        created_at="2026-10-10T12:00:00Z",
    )
    listed = app.list_for_membership(db, membership_id)
    assert listed == tuple(sorted((first, second), key=lambda item: (item.created_at, str(item.role_assignment_id))))
    db.close()


def test_role_assignment_rejects_missing_membership_and_unknown_assignment():
    db = connect_database()
    app = RoleAssignmentApplication()
    with pytest.raises(ValidationError, match="existing Membership"):
        app.create_role_assignment(
            db, membership_id=str(uuid4()), role_code="organizer", state="active",
            effective_from=STAMP,
        )
    with pytest.raises(ValidationError, match="does not exist"):
        app.get_role_assignment(db, str(uuid4()))
    db.close()


@pytest.mark.parametrize("kwargs", [
    {"membership_id": "bad", "role_code": "organizer", "state": "active", "effective_from": STAMP},
    {"membership_id": str(uuid4()), "role_code": " ", "state": "active", "effective_from": STAMP},
    {"membership_id": str(uuid4()), "role_code": "organizer", "state": "invented", "effective_from": STAMP},
    {"membership_id": str(uuid4()), "role_code": "organizer", "state": "active", "effective_from": "not-a-time"},
    {"membership_id": str(uuid4()), "role_code": "organizer", "state": "active", "effective_from": "2026-10-10T12:00:00"},
])
def test_role_assignment_rejects_invalid_contract_inputs(kwargs):
    db = connect_database()
    with pytest.raises(ValidationError):
        RoleAssignmentApplication().create_role_assignment(db, **kwargs)
    db.close()


def test_role_assignment_rejects_end_before_start():
    db = connect_database()
    membership_id = seed_membership(db)
    with pytest.raises(ValidationError, match="cannot precede"):
        RoleAssignmentApplication().create_role_assignment(
            db, membership_id=membership_id, role_code="organizer", state="active",
            effective_from="2026-12-01T00:00:00Z", effective_to="2026-11-01T00:00:00Z",
        )
    db.close()


def test_role_assignment_database_enforces_membership_foreign_key():
    import sqlite3

    db = connect_database()
    with pytest.raises(sqlite3.IntegrityError):
        db.execute(
            "INSERT INTO role_assignments(role_assignment_id,membership_id,role_code,state,"
            "effective_from,created_at,updated_at) VALUES(?, ?, ?, ?, ?, ?, ?)",
            (str(uuid4()), str(uuid4()), "organizer", "active", STAMP, STAMP, STAMP),
        )
    db.close()
