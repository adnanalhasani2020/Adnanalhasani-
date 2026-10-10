"""Persisted Conversation lifecycle and scoped reads over the existing schema."""
from dataclasses import dataclass
from datetime import datetime, timezone
from uuid import UUID, uuid4

from agent_core.shared import ValidationError


@dataclass(frozen=True)
class PersistedConversation:
    conversation_id: UUID
    context_ref: str
    conversation_type: str
    state: str
    created_at: str
    updated_at: str


class ConversationApplication:
    @staticmethod
    def _required(value, label):
        if not isinstance(value, str) or not value.strip():
            raise ValidationError(f"{label} is required")
        return value.strip()

    @staticmethod
    def _uuid(value):
        try:
            return UUID(str(value))
        except (ValueError, TypeError, AttributeError) as exc:
            raise ValidationError("Conversation identifier must be a valid UUID") from exc

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
        return PersistedConversation(UUID(row[0]), *row[1:])

    def create(self, connection, *, context_ref, conversation_type, conversation_id=None, now=None):
        context = self._required(context_ref, "context_ref")
        kind = self._required(conversation_type, "conversation_type")
        key = self._uuid(conversation_id) if conversation_id is not None else uuid4()
        timestamp = self._timestamp(now or datetime.now(timezone.utc), "Conversation timestamp")
        with connection:
            connection.execute(
                "INSERT INTO conversations(conversation_id,context_ref,conversation_type,state,created_at,updated_at) "
                "VALUES(?,?,?,'active',?,?)",
                (str(key), context, kind, timestamp, timestamp),
            )
        return self.get(connection, key)

    def get(self, connection, conversation_id) -> PersistedConversation:
        key = str(self._uuid(conversation_id))
        row = connection.execute(
            "SELECT conversation_id,context_ref,conversation_type,state,created_at,updated_at "
            "FROM conversations WHERE conversation_id=?", (key,)
        ).fetchone()
        if row is None:
            raise ValidationError("Conversation does not exist")
        return self._record(row)

    def list_for_context(self, connection, context_ref) -> tuple[PersistedConversation, ...]:
        context = self._required(context_ref, "context_ref")
        rows = connection.execute(
            "SELECT conversation_id,context_ref,conversation_type,state,created_at,updated_at "
            "FROM conversations WHERE context_ref=? ORDER BY created_at,conversation_id", (context,)
        ).fetchall()
        return tuple(self._record(row) for row in rows)

    def _set_state(self, connection, conversation_id, state, now=None):
        key = str(self._uuid(conversation_id))
        timestamp = self._timestamp(now or datetime.now(timezone.utc), "Conversation timestamp")
        with connection:
            cursor = connection.execute(
                "UPDATE conversations SET state=?,updated_at=? WHERE conversation_id=?",
                (state, timestamp, key),
            )
            if cursor.rowcount != 1:
                raise ValidationError("Conversation does not exist")
        return self.get(connection, key)

    def close(self, connection, conversation_id, *, now=None) -> PersistedConversation:
        return self._set_state(connection, conversation_id, "closed", now)

    def archive(self, connection, conversation_id, *, now=None) -> PersistedConversation:
        return self._set_state(connection, conversation_id, "archived", now)
