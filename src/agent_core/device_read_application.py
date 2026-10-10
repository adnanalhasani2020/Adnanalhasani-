"""Read-only application queries for persisted Device records."""
from dataclasses import dataclass
from uuid import UUID

from agent_core.shared import ValidationError


@dataclass(frozen=True)
class PersistedDevice:
    device_id: UUID
    device_fingerprint_ref: str
    state: str
    person_id: UUID | None
    access_account_id: UUID | None
    created_at: str
    updated_at: str
    provenance_id: str | None
    version_no: int


class DeviceReadApplication:
    """Restore and list Device records without changing device or queue state."""

    @staticmethod
    def _uuid(value, label: str) -> UUID:
        try:
            return UUID(str(value))
        except (ValueError, TypeError, AttributeError) as exc:
            raise ValidationError(f"{label} must be a valid UUID") from exc

    @staticmethod
    def _record(row) -> PersistedDevice:
        return PersistedDevice(
            device_id=UUID(row[0]),
            device_fingerprint_ref=row[1],
            state=row[2],
            person_id=UUID(row[3]) if row[3] is not None else None,
            access_account_id=UUID(row[4]) if row[4] is not None else None,
            created_at=row[5],
            updated_at=row[6],
            provenance_id=row[7],
            version_no=row[8],
        )

    @staticmethod
    def _select_sql() -> str:
        return (
            "SELECT device_id,device_fingerprint_ref,state,person_id,access_account_id,"
            "created_at,updated_at,provenance_id,version_no FROM devices"
        )

    def get(self, connection, device_id) -> PersistedDevice:
        key = str(self._uuid(device_id, "Device identifier"))
        row = connection.execute(
            self._select_sql() + " WHERE device_id=?", (key,)
        ).fetchone()
        if row is None:
            raise ValidationError("Device does not exist")
        return self._record(row)

    def list_for_person(self, connection, person_id) -> tuple[PersistedDevice, ...]:
        key = str(self._uuid(person_id, "Person identifier"))
        rows = connection.execute(
            self._select_sql() + " WHERE person_id=? ORDER BY created_at,device_id",
            (key,),
        ).fetchall()
        return tuple(self._record(row) for row in rows)

    def list_for_access_account(
        self, connection, access_account_id
    ) -> tuple[PersistedDevice, ...]:
        key = str(self._uuid(access_account_id, "Access Account identifier"))
        rows = connection.execute(
            self._select_sql()
            + " WHERE access_account_id=? ORDER BY created_at,device_id",
            (key,),
        ).fetchall()
        return tuple(self._record(row) for row in rows)
