import uuid

import pytest

from agent_core.participant_application import ParticipantApplication
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

STAMP = "2026-10-10T12:00:00Z"


def uid():
    return str(uuid.uuid4())


def seed_conversation(db, conversation_id):
    db.execute(
        "INSERT INTO conversations(conversation_id,context_ref,conversation_type,state,created_at,updated_at) "
        "VALUES(?,?,?,'active',?,?)",
        (conversation_id, "context-a", "general", STAMP, STAMP),
    )


def seed_participant(db, *, participant_id, conversation_id, state, created_at=STAMP):
    db.execute(
        "INSERT INTO participants(participant_id,conversation_id,participant_person_id,participant_ref,state,"
        "joined_at,left_at,created_at,updated_at,provenance_id) VALUES(?,?,NULL,?,?,?,?,?,?,NULL)",
        (
            participant_id,
            conversation_id,
            f"external:{participant_id}",
            state,
            STAMP,
            None if state == "active" else STAMP,
            created_at,
            created_at,
        ),
    )


def test_participant_listing_is_scoped_ordered_and_includes_every_state_without_writes():
    db = connect_database()
    first_conversation, other_conversation = uid(), uid()
    seed_conversation(db, first_conversation)
    seed_conversation(db, other_conversation)
    # Equal timestamps exercise the participant_id tie-breaker.
    ids = [uid(), uid(), uid()]
    seed_participant(db, participant_id=ids[0], conversation_id=first_conversation, state="left")
    seed_participant(db, participant_id=ids[1], conversation_id=first_conversation, state="active")
    seed_participant(db, participant_id=ids[2], conversation_id=first_conversation, state="removed")
    other_id = uid()
    seed_participant(db, participant_id=other_id, conversation_id=other_conversation, state="active")
    db.commit()

    before_participants = db.execute(
        "SELECT participant_id,conversation_id,participant_person_id,participant_ref,state,joined_at,left_at,"
        "created_at,updated_at,provenance_id FROM participants ORDER BY participant_id"
    ).fetchall()
    before_conversations = db.execute(
        "SELECT conversation_id,state,created_at,updated_at FROM conversations ORDER BY conversation_id"
    ).fetchall()
    changes_before = db.total_changes

    result = ParticipantApplication().list_for_conversation(db, first_conversation)

    assert [str(participant.participant_id) for participant in result] == sorted(ids)
    assert {participant.state for participant in result} == {"active", "left", "removed"}
    assert all(str(participant.conversation_id) == first_conversation for participant in result)
    assert other_id not in {str(participant.participant_id) for participant in result}
    assert db.execute(
        "SELECT participant_id,conversation_id,participant_person_id,participant_ref,state,joined_at,left_at,"
        "created_at,updated_at,provenance_id FROM participants ORDER BY participant_id"
    ).fetchall() == before_participants
    assert db.execute(
        "SELECT conversation_id,state,created_at,updated_at FROM conversations ORDER BY conversation_id"
    ).fetchall() == before_conversations
    assert db.total_changes == changes_before
    db.close()


def test_participant_listing_rejects_missing_conversation():
    db = connect_database()
    with pytest.raises(ValidationError, match="Conversation does not exist"):
        ParticipantApplication().list_for_conversation(db, uid())
    db.close()


def test_participant_listing_rejects_invalid_conversation_id():
    db = connect_database()
    with pytest.raises(ValidationError, match="valid UUID"):
        ParticipantApplication().list_for_conversation(db, "not-a-uuid")
    db.close()
