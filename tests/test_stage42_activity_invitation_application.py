from datetime import datetime, timezone
from uuid import uuid4

import pytest

from agent_core.activity_invitation_application import ActivityInvitationApplication
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError


def create_person(db, person_id=None):
    key = str(person_id or uuid4())
    now = "2026-01-01T00:00:00Z"
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?, 'active', ?, ?)",
        (key, now, now),
    )
    return key


def create_activity(db, activity_id=None):
    key = str(activity_id or uuid4())
    now = "2026-01-01T00:00:00Z"
    db.execute(
        "INSERT INTO activities(activity_id,state,created_at,updated_at) VALUES(?, 'active', ?, ?)",
        (key, now, now),
    )
    return key


def test_invitation_persists_and_can_be_restored_after_reopen(tmp_path):
    path = tmp_path / "invitations.sqlite"
    db = connect_database(path)
    inviter, invitee, activity = create_person(db), create_person(db), create_activity(db)
    app = ActivityInvitationApplication()
    invitation = app.create_invitation(
        db, activity_id=activity, inviter_person_id=inviter, invitee_person_id=invitee,
        created_at=datetime(2026, 2, 3, tzinfo=timezone.utc),
    )
    db.close()

    reopened = connect_database(path)
    assert app.get_invitation(reopened, invitation.invitation_id) == invitation
    assert app.list_for_activity(reopened, activity) == (invitation,)
    assert app.list_for_invitee(reopened, invitee) == (invitation,)
    reopened.close()


def test_invitation_requires_existing_activity_and_people():
    db = connect_database()
    app = ActivityInvitationApplication()
    inviter, invitee = create_person(db), create_person(db)
    with pytest.raises(ValidationError, match="existing Activity"):
        app.create_invitation(
            db, activity_id=str(uuid4()), inviter_person_id=inviter, invitee_person_id=invitee,
        )
    activity = create_activity(db)
    with pytest.raises(ValidationError, match="existing Person"):
        app.create_invitation(
            db, activity_id=activity, inviter_person_id=inviter,
            invitee_person_id=str(uuid4()),
        )
    db.close()


@pytest.mark.parametrize("kwargs", [
    {"activity_id": "bad", "inviter_person_id": str(uuid4()), "invitee_person_id": str(uuid4())},
    {"activity_id": str(uuid4()), "inviter_person_id": "bad", "invitee_person_id": str(uuid4())},
    {"activity_id": str(uuid4()), "inviter_person_id": str(uuid4()), "invitee_person_id": "bad"},
])
def test_invitation_rejects_malformed_references(kwargs):
    db = connect_database()
    with pytest.raises(ValidationError):
        ActivityInvitationApplication().create_invitation(db, **kwargs)
    db.close()


def test_missing_invitation_and_invalid_list_references_fail():
    db = connect_database()
    app = ActivityInvitationApplication()
    with pytest.raises(ValidationError, match="does not exist"):
        app.get_invitation(db, str(uuid4()))
    with pytest.raises(ValidationError, match="valid UUID"):
        app.list_for_activity(db, "bad")
    with pytest.raises(ValidationError, match="valid UUID"):
        app.list_for_invitee(db, "bad")
    db.close()
