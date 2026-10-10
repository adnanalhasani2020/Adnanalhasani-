import uuid

import pytest

from agent_core.activity_membership_application import ActivityMembershipApplication
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

STAMP = "2026-10-10T12:00:00Z"


def uid():
    return str(uuid.uuid4())


def seed_person(db):
    person = uid()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
        (person, STAMP, STAMP),
    )
    db.commit()
    return person


def test_activity_and_membership_persist_and_restore_after_connection_reopen(tmp_path):
    path = tmp_path / "membership.sqlite"
    db = connect_database(path)
    person = seed_person(db)
    app = ActivityMembershipApplication()
    activity = app.create_activity(
        db, owner_person_id=person, state="active", now=STAMP,
    )
    membership = app.create_membership(
        db, person_id=person, activity_id=activity.activity_id, state="active",
        effective_from=STAMP, effective_to="2026-12-01T00:00:00Z", now=STAMP,
    )
    membership_id = membership.membership_id
    activity_id = activity.activity_id
    db.close()

    reopened = connect_database(path)
    assert app.get_activity(reopened, activity_id) == activity
    assert app.get_membership(reopened, membership_id) == membership
    assert app.list_memberships_for_activity(reopened, activity_id) == (membership,)
    assert app.list_memberships_for_person(reopened, person) == (membership,)
    reopened.close()


def test_membership_lists_are_deterministic_and_scoped():
    db = connect_database()
    person = seed_person(db)
    app = ActivityMembershipApplication()
    a1 = app.create_activity(db, owner_person_id=person, state="active", now=STAMP)
    a2 = app.create_activity(db, owner_person_id=person, state="active", now=STAMP)
    m2 = app.create_membership(
        db, person_id=person, activity_id=a1.activity_id, state="suspended",
        effective_from=STAMP, now=STAMP,
    )
    m1 = app.create_membership(
        db, person_id=person, activity_id=a1.activity_id, state="active",
        effective_from=STAMP, now=STAMP,
    )
    other = app.create_membership(
        db, person_id=person, activity_id=a2.activity_id, state="ended",
        effective_from=STAMP, now=STAMP,
    )
    assert app.list_memberships_for_activity(db, a1.activity_id) == tuple(
        sorted((m1, m2), key=lambda m: (m.effective_from, str(m.membership_id)))
    )
    assert set(app.list_memberships_for_person(db, person)) == {m1, m2, other}
    db.close()


def test_activity_membership_validate_references_states_and_dates():
    db = connect_database()
    app = ActivityMembershipApplication()
    person = seed_person(db)
    activity = app.create_activity(db, owner_person_id=person, state="active", now=STAMP)

    with pytest.raises(ValidationError, match="existing owner Person"):
        app.create_activity(db, owner_person_id=uid(), state="active", now=STAMP)
    with pytest.raises(ValidationError, match="Membership requires an existing Person"):
        app.create_membership(
            db, person_id=uid(), activity_id=activity.activity_id, state="active",
            effective_from=STAMP, now=STAMP,
        )
    with pytest.raises(ValidationError, match="existing Activity"):
        app.create_membership(
            db, person_id=person, activity_id=uid(), state="active",
            effective_from=STAMP, now=STAMP,
        )
    with pytest.raises(ValidationError, match="Membership effective_to cannot precede"):
        app.create_membership(
            db, person_id=person, activity_id=activity.activity_id, state="active",
            effective_from="2026-10-11T00:00:00Z", effective_to=STAMP, now=STAMP,
        )
    with pytest.raises(ValidationError, match="Membership state must be one of"):
        app.create_membership(
            db, person_id=person, activity_id=activity.activity_id, state="proposed",
            effective_from=STAMP, now=STAMP,
        )
    db.close()


def test_unknown_and_invalid_identifiers_fail_closed():
    db = connect_database()
    app = ActivityMembershipApplication()
    with pytest.raises(ValidationError, match="Membership does not exist"):
        app.get_membership(db, uid())
    with pytest.raises(ValidationError, match="valid UUID"):
        app.get_activity("bad-id")
    with pytest.raises(ValidationError, match="Activity does not exist"):
        app.list_memberships_for_activity(db, uid())
    db.close()
