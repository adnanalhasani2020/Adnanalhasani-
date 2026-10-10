from uuid import uuid4

import pytest

from agent_core.domain_communication import Message, MessageState
from agent_core.shared import ValidationError


def test_message_must_follow_draft_sent_revoked_lifecycle():
    message = Message(conversation_id=uuid4(), content="hello")
    assert message.state is MessageState.DRAFT

    message.send()
    assert message.state is MessageState.SENT

    message.revoke()
    assert message.state is MessageState.REVOKED


@pytest.mark.parametrize("state", [MessageState.SENT, MessageState.REVOKED])
def test_message_cannot_be_sent_more_than_once(state):
    message = Message(conversation_id=uuid4(), content="hello", state=state)
    with pytest.raises(ValidationError, match="Only a draft Message can be sent"):
        message.send()
    assert message.state is state


@pytest.mark.parametrize("state", [MessageState.DRAFT, MessageState.REVOKED])
def test_only_sent_message_can_be_revoked(state):
    message = Message(conversation_id=uuid4(), content="hello", state=state)
    with pytest.raises(ValidationError, match="Only a sent Message can be revoked"):
        message.revoke()
    assert message.state is state

