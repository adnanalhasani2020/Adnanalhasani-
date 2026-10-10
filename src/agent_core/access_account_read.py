"""Read persisted Access Accounts without inferring authentication or authority."""
from __future__ import annotations

from dataclasses import dataclass
from uuid import UUID


@dataclass(frozen=True)
class PersistedAccessAccount:
    access_account_id: UUID
    person_id: UUID
    state: str
    created_at: str
    updated_at: str
    version_no: int


class PersistedAccessAccountReader:
    """Read Access Account records from the existing schema; never authenticates."""

    @staticmethod
    def _uuid(value, label: str) -> str:
        try:
            return str(UUID(str(value)))
        except (ValueError, TypeError, AttributeError) as exc:
            raise ValueError(f"{label} must be a UUID") from exc

    @staticmethod
    def _record(row) -> PersistedAccessAccount:
        return PersistedAccessAccount(
            UUID(row[0]), UUID(row[1]), row[2], row[3], row[4], row[5]
        )

    def get(self, connection, access_account_id) -> PersistedAccessAccount | None:
        key = self._uuid(access_account_id, "access_account_id")
        row = connection.execute(
            """SELECT access_account_id, person_id, state, created_at, updated_at, version_no
               FROM access_accounts WHERE access_account_id=?""",
            (key,),
        ).fetchone()
        return None if row is None else self._record(row)

    def list_for_person(self, connection, person_id) -> tuple[PersistedAccessAccount, ...]:
        key = self._uuid(person_id, "person_id")
        if connection.execute("SELECT 1 FROM persons WHERE person_id=?", (key,)).fetchone() is None:
            return ()
        rows = connection.execute(
            """SELECT access_account_id, person_id, state, created_at, updated_at, version_no
               FROM access_accounts WHERE person_id=?
               ORDER BY created_at, access_account_id""",
            (key,),
        ).fetchall()
        return tuple(self._record(row) for row in rows)
