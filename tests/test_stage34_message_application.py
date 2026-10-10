from datetime import datetime, timezone
import uuid

import pytest

from agent_core.message_application import MessageApplication
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

STAMP = "2026-10-10T12:00:00Z"


def uid():
    return str(uuid.uuid4())


def seed(db, *, state="active"):
    person, conversation = uid(), uid()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
        (person, STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO conversations(conversation_id,context_ref,conversation_type,state,created_at,updated_at) "
        "VALUES(?,?,?, ?,?,?)",
        (conversation, "context-a", "general", state, STAMP, STAMP),
    )
    db.commit()
    return person, conversation


def test_message_create_restore_and_ordered_list_are_persisted():
    db = connect_database()
    sender, conversation = seed(db)
    app = MessageApplication()
    later = app.create_message(
        db, conversation_id=conversation, sender_person_id=sender,
        message_type="text", content_ref="content://message/2", now=STAMP,
        occurred_at="2026-10-10T12:02:00Z",
    )
    earlier = app.create_message(
        db, conversation_id=conversation, sender_ref="external:sender-1",
        message_type="text", content_ref="content://message/1", now=STAMP,
        occurred_at="2026-10-10T12:01:00Z",
    )
    assert later.state == "active"
    assert app.get_message(db, later.message_id) == later
    assert app.list_messages(db, conversation) == (earlier, later)
    db.close()


@pytest.mark.parametrize("state", ["closed", "archived"])
def test_message_creation_rejects_non_active_conversation(state):
    db = connect_database()
    sender, conversation = seed(db, state=state)
    with pytest.raises(ValidationError, match="active Conversation"):
        MessageApplication().create_message(
            db, conversation_id=conversation, sender_person_id=sender,
            message_type="text", content_ref="content://message/1", now=STAMP,
        )
    assert db.execute("SELECT COUNT(*) FROM messages").fetchone()[0] == 0
    db.close()


def test_message_creation_rejects_missing_sender_or_inactive_person():
    db = connect_database()
    sender, conversation = seed(db)
    app = MessageApplication()
    with pytest.raises(ValidationError, match="sender Person or non-empty sender_ref"):
        app.create_message(
            db, conversation_id=conversation, message_type="text",
            content_ref="content://message/1", now=STAMP,
        )
    db.execute("UPDATE persons SET state='deactivated' WHERE person_id=?", (sender,))
    db.commit()
    with pytest.raises(ValidationError, match="must be active and persisted"):
        app.create_message(
            db, conversation_id=conversation, sender_person_id=sender,
            message_type="text", content_ref="content://message/1", now=STAMP,
        )
    assert db.execute("SELECT COUNT(*) FROM messages").fetchone()[0] == 0
    db.close()


def test_message_creation_requires_existing_conversation_and_content_reference():
    db = connect_database()
    sender, conversation = seed(db)
    app = MessageApplication()
    with pytest.raises(ValidationError, match="existing Conversation"):
        app.create_message(
            db, conversation_id=uid(), sender_person_id=sender,
            message_type="text", content_ref="content://message/1", now=STAMP,
        )
    with pytest.raises(ValidationError, match="content_ref is required"):
        app.create_message(
            db, conversation_id=conversation, sender_person_id=sender,
            message_type="text", content_ref=" ", now=STAMP,
        )
    assert db.execute("SELECT COUNT(*) FROM messages").fetchone()[0] == 0
    db.close()
