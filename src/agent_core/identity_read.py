"""Read persisted Person identity records without inferring identity policy."""
from __future__ import annotations

from dataclasses import dataclass
from uuid import UUID


@dataclass(frozen=True)
class PersistedIdentifier:
    identifier_id: UUID
    person_id: UUID
    identifier_type: str
    normalized_value: str
    uniqueness_scope: str
    state: str
    created_at: str
    updated_at: str


@dataclass(frozen=True)
class PersistedPerson:
    person_id: UUID
    state: str
    canonical_person_id: UUID | None
    created_at: str
    updated_at: str
    version_no: int


class PersistedIdentityReader:
    """Read Person and its identifiers from the existing relational schema.

    This API is read-only. It does not merge people, resolve identifier
    uniqueness, authenticate accounts, or infer authorization.
    """

    def get_person(self, connection, person_id: str | UUID) -> PersistedPerson | None:
        try:
            key = str(UUID(str(person_id)))
        except (ValueError, TypeError, AttributeError) as exc:
            raise ValueError("person_id must be a UUID") from exc
        row = connection.execute(
            """SELECT person_id, state, canonical_person_id, created_at, updated_at, version_no
               FROM persons WHERE person_id=?""", (key,),
        ).fetchone()
        if row is None:
            return None
        return PersistedPerson(UUID(row[0]), row[1], UUID(row[2]) if row[2] else None,
                              row[3], row[4], row[5])

    def list_identifiers(self, connection, person_id: str | UUID) -> tuple[PersistedIdentifier, ...]:
        try:
            key = str(UUID(str(person_id)))
        except (ValueError, TypeError, AttributeError) as exc:
            raise ValueError("person_id must be a UUID") from exc
        exists = connection.execute("SELECT 1 FROM persons WHERE person_id=?", (key,)).fetchone()
        if exists is None:
            return ()
        rows = connection.execute(
            """SELECT identifier_id, person_id, identifier_type, normalized_value,
                      uniqueness_scope, state, created_at, updated_at
               FROM identifiers WHERE person_id=?
               ORDER BY created_at, identifier_id""", (key,),
        ).fetchall()
        return tuple(PersistedIdentifier(UUID(r[0]), UUID(r[1]), r[2], r[3], r[4],
                                         r[5], r[6], r[7]) for r in rows)
