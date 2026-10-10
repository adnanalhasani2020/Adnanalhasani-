"""Persisted communication message entry/read path.

This application uses the existing relational contract: messages belong to an
existing active conversation, reference either a persisted Person or an
external sender reference, and store a content reference rather than assuming
a transport or delivery mechanism. It does not send notifications or change
conversation membership.
"""
from dataclasses import dataclass
from datetime import datetime, timezone
from uuid import UUID, uuid4

from agent_core.shared import ValidationError


@dataclass(frozen=True)
class PersistedMessage:
    message_id: UUID
    conversation_id: UUID
    sender_person_id: UUID | None
    sender_ref: str | None
    message_type: str
    content_ref: str
    state: str
    occurred_at: str
    created_at: str
    updated_at: str


class MessageApplication:
    @staticmethod
    def _uuid(value, label):
        try:
            return UUID(str(value))
        except (ValueError, TypeError, AttributeError) as exc:
            raise ValidationError(f"{label} must be a valid UUID") from exc

    @staticmethod
    def _timestamp(value, label):
        if isinstance(value, str):
            try:
                value = datetime.fromisoformat(value.replace("Z", "+00:00"))
            except ValueError as exc:
                raise ValidationError(f"{label} must be a valid ISO-8601 timestamp") from exc
        if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
            raise ValidationError(f"{label} must be timezone-aware")
        return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")

    @staticmethod
    def _record(row):
        (mid, conversation, sender, sender_ref, kind, content, state,
         occurred, created, updated) = row
        return PersistedMessage(
            UUID(mid), UUID(conversation), UUID(sender) if sender else None,
            sender_ref, kind, content, state, occurred, created, updated,
        )

    def create_message(
        self, connection, *, conversation_id, message_type, content_ref,
        sender_person_id=None, sender_ref=None, message_id=None, occurred_at=None,
        now=None,
    ) -> PersistedMessage:
        conversation_key = str(self._uuid(conversation_id, "Conversation identifier"))
        sender_key = str(self._uuid(sender_person_id, "Sender Person identifier")) if sender_person_id is not None else None
        if sender_key is None and (not isinstance(sender_ref, str) or not sender_ref.strip()):
            raise ValidationError("Message requires a persisted sender Person or non-empty sender_ref")
        if sender_ref is not None and (not isinstance(sender_ref, str) or not sender_ref.strip()):
            raise ValidationError("Message sender_ref must be non-empty when provided")
        if not isinstance(message_type, str) or not message_type.strip():
            raise ValidationError("Message message_type is required")
        if not isinstance(content_ref, str) or not content_ref.strip():
            raise ValidationError("Message content_ref is required")
        key = str(self._uuid(message_id, "Message identifier")) if message_id is not None else str(uuid4())
        timestamp = self._timestamp(now or datetime.now(timezone.utc), "Message timestamp")
        occurred = self._timestamp(occurred_at or now or datetime.now(timezone.utc), "Message occurred_at")

        with connection:
            conversation = connection.execute(
                "SELECT state FROM conversations WHERE conversation_id=?", (conversation_key,)
            ).fetchone()
            if conversation is None:
                raise ValidationError("Message requires an existing Conversation")
            if conversation[0] != "active":
                raise ValidationError("Message requires an active Conversation")
            if sender_key is not None:
                sender = connection.execute(
                    "SELECT state FROM persons WHERE person_id=?", (sender_key,)
                ).fetchone()
                if sender is None or sender[0] != "active":
                    raise ValidationError("Message sender Person must be active and persisted")
            connection.execute(
                "INSERT INTO messages(message_id,conversation_id,sender_person_id,sender_ref,"
                "message_type,content_ref,state,occurred_at,created_at,updated_at) "
                "VALUES(?,?,?,?,?,?,'active',?,?,?)",
                (key, conversation_key, sender_key, sender_ref.strip() if sender_ref is not None else None,
                 message_type.strip(), content_ref.strip(), occurred, timestamp, timestamp),
            )
        return self.get_message(connection, key)

    def get_message(self, connection, message_id) -> PersistedMessage:
        key = str(self._uuid(message_id, "Message identifier"))
        row = connection.execute(
            "SELECT message_id,conversation_id,sender_person_id,sender_ref,message_type,content_ref,"
            "state,occurred_at,created_at,updated_at FROM messages WHERE message_id=?", (key,)
        ).fetchone()
        if row is None:
            raise ValidationError("Message does not exist")
        return self._record(row)

    def list_messages(self, connection, conversation_id) -> tuple[PersistedMessage, ...]:
        key = str(self._uuid(conversation_id, "Conversation identifier"))
        if connection.execute(
            "SELECT 1 FROM conversations WHERE conversation_id=?", (key,)
        ).fetchone() is None:
            raise ValidationError("Conversation does not exist")
        rows = connection.execute(
            "SELECT message_id,conversation_id,sender_person_id,sender_ref,message_type,content_ref,"
            "state,occurred_at,created_at,updated_at FROM messages "
            "WHERE conversation_id=? ORDER BY occurred_at,created_at,message_id", (key,)
        ).fetchall()
        return tuple(self._record(row) for row in rows)
