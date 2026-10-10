"""Explicit persistence for caller-created Person records.

This service does not resolve duplicate identities, merge people, issue identifiers,
or infer authentication or authorization.
"""
from __future__ import annotations

import sqlite3
from dataclasses import dataclass
from datetime import datetime, timezone
from uuid import UUID, uuid4

from agent_core.shared import ValidationError


@dataclass(frozen=True)
class PersistedPersonRegistration:
    person_id: UUID
    state: str
    canonical_person_id: UUID | None
    created_at: str
    updated_at: str
    version_no: int


class PersonRegistrationApplication:
    """Create an explicitly supplied Person record using the existing schema."""

    @staticmethod
    def _uuid(value, label: str) -> UUID:
        try:
            return UUID(str(value))
        except (ValueError, TypeError, AttributeError) as exc:
            raise ValidationError(f"{label} must be a valid UUID") from exc

    @staticmethod
    def _timestamp(value, label: str) -> str:
        if isinstance(value, str):
            try:
                value = datetime.fromisoformat(value.replace("Z", "+00:00"))
            except ValueError as exc:
                raise ValidationError(f"{label} must be a valid ISO-8601 timestamp") from exc
        if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
            raise ValidationError(f"{label} must be timezone-aware")
        return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")

    def create_person(
        self, connection, *, state, person_id=None, created_at=None, updated_at=None
    ) -> PersistedPersonRegistration:
        """Persist one explicit Person record; does not deduplicate natural identity."""
        if not isinstance(state, str) or not state.strip():
            raise ValidationError("Person state is required and must be non-empty")
        key = self._uuid(person_id, "Person identifier") if person_id is not None else uuid4()
        created = self._timestamp(created_at or datetime.now(timezone.utc), "Person created_at")
        updated = self._timestamp(updated_at or created, "Person updated_at")
        try:
            with connection:
                connection.execute(
                    """INSERT INTO persons(
                           person_id, state, canonical_person_id, created_at, updated_at, version_no
                       ) VALUES(?, ?, NULL, ?, ?, 1)""",
                    (str(key), state.strip(), created, updated),
                )
                row = connection.execute(
                    """SELECT person_id, state, canonical_person_id, created_at, updated_at, version_no
                       FROM persons WHERE person_id=?""",
                    (str(key),),
                ).fetchone()
        except sqlite3.IntegrityError as exc:
            raise ValidationError(
                "Person identifier or state violates the persisted schema contract"
            ) from exc
        if row is None:
            raise ValidationError("Person was not persisted")
        return PersistedPersonRegistration(
            UUID(row[0]), row[1], UUID(row[2]) if row[2] else None,
            row[3], row[4], row[5],
        )
