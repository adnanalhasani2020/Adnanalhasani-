from datetime import datetime, timezone
import uuid

import pytest

from agent_core.conversation_application import ConversationApplication
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

STAMP = "2026-10-10T12:00:00Z"


def uid():
    return str(uuid.uuid4())


def test_conversation_create_restore_and_context_listing_are_persisted():
    db = connect_database()
    app = ConversationApplication()
    later = app.create(db, context_ref="case:1", conversation_type="general", now=STAMP)
    earlier = app.create(db, context_ref="case:1", conversation_type="support", now=STAMP)
    other = app.create(db, context_ref="case:2", conversation_type="general", now=STAMP)

    assert app.get(db, later.conversation_id) == later
    assert [x.conversation_id for x in app.list_for_context(db, "case:1")] == sorted(
        [later.conversation_id, earlier.conversation_id], key=str
    )
    assert app.list_for_context(db, "missing") == ()
    assert other.context_ref == "case:2"
    db.close()


@pytest.mark.parametrize("method,state", [("close", "closed"), ("archive", "archived")])
def test_conversation_lifecycle_state_is_persisted(method, state):
    db = connect_database()
    app = ConversationApplication()
    conversation = app.create(db, context_ref="case:1", conversation_type="general", now=STAMP)
    updated = getattr(app, method)(db, conversation.conversation_id, now="2026-10-10T12:05:00Z")

    assert updated.state == state
    assert updated.updated_at == "2026-10-10T12:05:00Z"
    assert app.get(db, conversation.conversation_id) == updated
    db.close()


def test_conversation_requires_context_type_and_aware_timestamps():
    db = connect_database()
    app = ConversationApplication()
    with pytest.raises(ValidationError, match="context_ref is required"):
        app.create(db, context_ref=" ", conversation_type="general", now=STAMP)
    with pytest.raises(ValidationError, match="conversation_type is required"):
        app.create(db, context_ref="case:1", conversation_type=" ", now=STAMP)
    with pytest.raises(ValidationError, match="timezone-aware"):
        app.create(db, context_ref="case:1", conversation_type="general", now=datetime(2026, 10, 10))
    assert db.execute("SELECT COUNT(*) FROM conversations").fetchone()[0] == 0
    db.close()


def test_conversation_reads_and_lifecycle_reject_unknown_or_invalid_ids():
    db = connect_database()
    app = ConversationApplication()
    with pytest.raises(ValidationError, match="Conversation does not exist"):
        app.get(db, uid())
    with pytest.raises(ValidationError, match="Conversation does not exist"):
        app.close(db, uid(), now=STAMP)
    with pytest.raises(ValidationError, match="valid UUID"):
        app.get(db, "not-a-uuid")
    db.close()
