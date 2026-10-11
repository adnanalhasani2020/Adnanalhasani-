"""Read-only access to persisted conversation participants."""
from dataclasses import dataclass
from uuid import UUID

from agent_core.shared import ValidationError


@dataclass(frozen=True)
class PersistedParticipant:
    participant_id: UUID
    conversation_id: UUID
    participant_person_id: UUID | None
    participant_ref: str | None
    state: str
    joined_at: str
    left_at: str | None
    created_at: str
    updated_at: str
    provenance_id: str | None


class ParticipantApplication:
    @staticmethod
    def _uuid(value):
        try:
            return UUID(str(value))
        except (ValueError, TypeError, AttributeError) as exc:
            raise ValidationError("Conversation identifier must be a valid UUID") from exc

    @staticmethod
    def _record(row):
        (
            participant_id, conversation_id, person_id, participant_ref, state,
            joined_at, left_at, created_at, updated_at, provenance_id,
        ) = row
        return PersistedParticipant(
            UUID(participant_id),
            UUID(conversation_id),
            UUID(person_id) if person_id is not None else None,
            participant_ref,
            state,
            joined_at,
            left_at,
            created_at,
            updated_at,
            provenance_id,
        )

    def list_for_conversation(self, connection, conversation_id) -> tuple[PersistedParticipant, ...]:
        """Return every persisted participant state for an existing conversation.

        This is a read-only listing; membership state does not imply authorization.
        """
        key = str(self._uuid(conversation_id))
        if connection.execute(
            "SELECT 1 FROM conversations WHERE conversation_id=?", (key,)
        ).fetchone() is None:
            raise ValidationError("Conversation does not exist")

        rows = connection.execute(
            "SELECT participant_id,conversation_id,participant_person_id,participant_ref,state,"
            "joined_at,left_at,created_at,updated_at,provenance_id "
            "FROM participants WHERE conversation_id=? "
            "ORDER BY created_at,participant_id",
            (key,),
        ).fetchall()
        return tuple(self._record(row) for row in rows)
