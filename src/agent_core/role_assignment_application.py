"""Persist explicit Role Assignment records over the existing relational schema.

This application does not create Memberships, infer permissions, or mutate role
lifecycle state after creation. Role codes and persisted states are caller-supplied
and must satisfy the existing schema contract.
"""
from dataclasses import dataclass
from datetime import datetime, timezone
from uuid import UUID, uuid4

from agent_core.shared import ValidationError


@dataclass(frozen=True)
class PersistedRoleAssignment:
    role_assignment_id: UUID
    membership_id: UUID
    role_code: str
    state: str
    effective_from: str
    effective_to: str | None
    created_at: str
    updated_at: str
    version_no: int


class RoleAssignmentApplication:
    _STATES = {"active", "suspended", "ended"}

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
                raise ValidationError(f"{label} must be valid ISO-8601") from exc
        if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
            raise ValidationError(f"{label} must be timezone-aware")
        return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")

    @classmethod
    def _record(cls, row):
        return PersistedRoleAssignment(
            UUID(row[0]), UUID(row[1]), row[2], row[3], row[4], row[5],
            row[6], row[7], row[8],
        )

    @staticmethod
    def _select_columns():
        return (
            "role_assignment_id, membership_id, role_code, state, effective_from, "
            "effective_to, created_at, updated_at, version_no"
        )

    def create_role_assignment(
        self, connection, *, membership_id, role_code, state,
        effective_from, effective_to=None, role_assignment_id=None, created_at=None,
        updated_at=None,
    ) -> PersistedRoleAssignment:
        key = str(self._uuid(role_assignment_id, "Role Assignment identifier")) if role_assignment_id is not None else str(uuid4())
        membership_key = str(self._uuid(membership_id, "Membership identifier"))
        if not isinstance(role_code, str) or not role_code.strip():
            raise ValidationError("Role Assignment role_code is required")
        if state not in self._STATES:
            raise ValidationError("Role Assignment state must match the persisted schema")
        starts = self._timestamp(effective_from, "Role Assignment effective_from")
        ends = self._timestamp(effective_to, "Role Assignment effective_to") if effective_to is not None else None
        start_instant = datetime.fromisoformat(starts.replace("Z", "+00:00"))
        end_instant = datetime.fromisoformat(ends.replace("Z", "+00:00")) if ends else None
        if end_instant is not None and end_instant < start_instant:
            raise ValidationError("Role Assignment effective_to cannot precede effective_from")
        created = self._timestamp(created_at or datetime.now(timezone.utc), "Role Assignment created_at")
        updated = self._timestamp(updated_at or created, "Role Assignment updated_at")
        if connection.execute(
            "SELECT 1 FROM memberships WHERE membership_id=?", (membership_key,)
        ).fetchone() is None:
            raise ValidationError("Role Assignment requires an existing Membership")
        try:
            with connection:
                connection.execute(
                    "INSERT INTO role_assignments(role_assignment_id, membership_id, role_code, state, "
                    "effective_from, effective_to, created_at, updated_at, version_no) "
                    "VALUES(?,?,?,?,?,?,?,?,1)",
                    (key, membership_key, role_code.strip(), state, starts, ends, created, updated),
                )
        except Exception as exc:
            import sqlite3
            if isinstance(exc, sqlite3.IntegrityError):
                raise ValidationError("Role Assignment identifier or Membership reference violates the persisted contract") from exc
            raise
        return self.get_role_assignment(connection, key)

    def get_role_assignment(self, connection, role_assignment_id) -> PersistedRoleAssignment:
        key = str(self._uuid(role_assignment_id, "Role Assignment identifier"))
        row = connection.execute(
            f"SELECT {self._select_columns()} FROM role_assignments WHERE role_assignment_id=?",
            (key,),
        ).fetchone()
        if row is None:
            raise ValidationError("Role Assignment does not exist")
        return self._record(row)

    def list_for_membership(self, connection, membership_id) -> tuple[PersistedRoleAssignment, ...]:
        key = str(self._uuid(membership_id, "Membership identifier"))
        if connection.execute(
            "SELECT 1 FROM memberships WHERE membership_id=?", (key,)
        ).fetchone() is None:
            raise ValidationError("Membership does not exist")
        rows = connection.execute(
            f"SELECT {self._select_columns()} FROM role_assignments "
            "WHERE membership_id=? ORDER BY created_at, role_assignment_id",
            (key,),
        ).fetchall()
        return tuple(self._record(row) for row in rows)
