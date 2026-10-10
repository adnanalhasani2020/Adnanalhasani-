"""Persist explicit Membership records over the existing relational schema.

This service records a Person's membership in an existing Activity. It does not
accept invitations, infer authorization, or apply unapproved lifecycle policy.
"""
from dataclasses import dataclass
from datetime import datetime, timezone
from uuid import UUID, uuid4
import sqlite3

from agent_core.shared import ValidationError


@dataclass(frozen=True)
class PersistedMembership:
    membership_id: UUID
    person_id: UUID
    activity_id: UUID
    state: str
    effective_from: str
    effective_to: str | None
    created_at: str
    updated_at: str
    version_no: int


class MembershipApplication:
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

    @staticmethod
    def _record(row):
        return PersistedMembership(
            UUID(row[0]), UUID(row[1]), UUID(row[2]), row[3], row[4], row[5],
            row[6], row[7], row[8],
        )

    @staticmethod
    def _columns():
        return ("membership_id, person_id, activity_id, state, effective_from, "
                "effective_to, created_at, updated_at, version_no")

    def create_membership(
        self, connection, *, person_id, activity_id, state, effective_from,
        effective_to=None, membership_id=None, created_at=None, updated_at=None,
    ) -> PersistedMembership:
        key = str(self._uuid(membership_id, "Membership identifier")) if membership_id is not None else str(uuid4())
        person_key = str(self._uuid(person_id, "Person identifier"))
        activity_key = str(self._uuid(activity_id, "Activity identifier"))
        if state not in self._STATES:
            raise ValidationError("Membership state must match the persisted schema")
        starts = self._timestamp(effective_from, "Membership effective_from")
        ends = self._timestamp(effective_to, "Membership effective_to") if effective_to is not None else None
        start_instant = datetime.fromisoformat(starts.replace("Z", "+00:00"))
        end_instant = datetime.fromisoformat(ends.replace("Z", "+00:00")) if ends else None
        if end_instant is not None and end_instant < start_instant:
            raise ValidationError("Membership effective_to cannot precede effective_from")
        created = self._timestamp(created_at or datetime.now(timezone.utc), "Membership created_at")
        updated = self._timestamp(updated_at or created, "Membership updated_at")
        try:
            with connection:
                if connection.execute("SELECT 1 FROM persons WHERE person_id=?", (person_key,)).fetchone() is None:
                    raise ValidationError("Membership requires an existing Person")
                if connection.execute("SELECT 1 FROM activities WHERE activity_id=?", (activity_key,)).fetchone() is None:
                    raise ValidationError("Membership requires an existing Activity")
                connection.execute(
                    "INSERT INTO memberships(membership_id, person_id, activity_id, state, effective_from, "
                    "effective_to, created_at, updated_at, version_no) VALUES(?,?,?,?,?,?,?,?,1)",
                    (key, person_key, activity_key, state, starts, ends, created, updated),
                )
        except sqlite3.IntegrityError as exc:
            raise ValidationError("Membership identifier or referenced record violates the persisted contract") from exc
        return self.get_membership(connection, key)

    def get_membership(self, connection, membership_id) -> PersistedMembership:
        key = str(self._uuid(membership_id, "Membership identifier"))
        row = connection.execute(
            f"SELECT {self._columns()} FROM memberships WHERE membership_id=?", (key,)
        ).fetchone()
        if row is None:
            raise ValidationError("Membership does not exist")
        return self._record(row)

    def list_for_activity(self, connection, activity_id) -> tuple[PersistedMembership, ...]:
        key = str(self._uuid(activity_id, "Activity identifier"))
        if connection.execute("SELECT 1 FROM activities WHERE activity_id=?", (key,)).fetchone() is None:
            raise ValidationError("Activity does not exist")
        rows = connection.execute(
            f"SELECT {self._columns()} FROM memberships WHERE activity_id=? "
            "ORDER BY created_at, membership_id", (key,)
        ).fetchall()
        return tuple(self._record(row) for row in rows)

    def list_for_person(self, connection, person_id) -> tuple[PersistedMembership, ...]:
        key = str(self._uuid(person_id, "Person identifier"))
        if connection.execute("SELECT 1 FROM persons WHERE person_id=?", (key,)).fetchone() is None:
            raise ValidationError("Person does not exist")
        rows = connection.execute(
            f"SELECT {self._columns()} FROM memberships WHERE person_id=? "
            "ORDER BY created_at, membership_id", (key,)
        ).fetchall()
        return tuple(self._record(row) for row in rows)
