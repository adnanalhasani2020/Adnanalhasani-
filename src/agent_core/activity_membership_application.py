"""Persisted Activity and Membership data-entry/query path.

This module stores explicit caller-supplied records in the existing schema.
It does not infer authorization from ownership, membership, or role data.
"""
from dataclasses import dataclass
from datetime import datetime, timezone
from uuid import UUID, uuid4

from agent_core.shared import ValidationError


@dataclass(frozen=True)
class PersistedActivity:
    activity_id: UUID
    owner_person_id: UUID
    state: str
    created_at: str
    updated_at: str
    version_no: int


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


class ActivityMembershipApplication:
    ACTIVITY_STATES = {"active", "suspended", "ended"}
    MEMBERSHIP_STATES = {"active", "suspended", "ended"}

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
    def _required_state(value, allowed, label):
        if not isinstance(value, str) or value not in allowed:
            raise ValidationError(f"{label} must be one of: {', '.join(sorted(allowed))}")
        return value

    @staticmethod
    def _activity(row):
        return PersistedActivity(UUID(row[0]), UUID(row[1]), row[2], row[3], row[4], row[5])

    @staticmethod
    def _membership(row):
        return PersistedMembership(
            UUID(row[0]), UUID(row[1]), UUID(row[2]), row[3], row[4], row[5],
            row[6], row[7], row[8],
        )

    def create_activity(self, connection, *, owner_person_id, state, activity_id=None, now=None):
        owner = self._uuid(owner_person_id, "Activity owner Person identifier")
        activity_state = self._required_state(state, self.ACTIVITY_STATES, "Activity state")
        key = self._uuid(activity_id, "Activity identifier") if activity_id is not None else uuid4()
        timestamp = self._timestamp(now or datetime.now(timezone.utc), "Activity timestamp")
        with connection:
            if connection.execute(
                "SELECT 1 FROM persons WHERE person_id=?", (str(owner),)
            ).fetchone() is None:
                raise ValidationError("Activity requires an existing owner Person")
            connection.execute(
                "INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) "
                "VALUES(?,?,?,?,?)",
                (str(key), str(owner), activity_state, timestamp, timestamp),
            )
        return self.get_activity(connection, key)

    def get_activity(self, connection, activity_id) -> PersistedActivity:
        key = str(self._uuid(activity_id, "Activity identifier"))
        row = connection.execute(
            "SELECT activity_id,owner_person_id,state,created_at,updated_at,version_no "
            "FROM activities WHERE activity_id=?", (key,)
        ).fetchone()
        if row is None:
            raise ValidationError("Activity does not exist")
        return self._activity(row)

    def create_membership(
        self, connection, *, person_id, activity_id, state, effective_from,
        effective_to=None, membership_id=None, now=None,
    ) -> PersistedMembership:
        person = self._uuid(person_id, "Membership Person identifier")
        activity = self._uuid(activity_id, "Membership Activity identifier")
        membership_state = self._required_state(state, self.MEMBERSHIP_STATES, "Membership state")
        start = self._timestamp(effective_from, "Membership effective_from")
        end = self._timestamp(effective_to, "Membership effective_to") if effective_to is not None else None
        if end is not None and datetime.fromisoformat(end.replace("Z", "+00:00")) < datetime.fromisoformat(start.replace("Z", "+00:00")):
            raise ValidationError("Membership effective_to cannot precede effective_from")
        key = self._uuid(membership_id, "Membership identifier") if membership_id is not None else uuid4()
        timestamp = self._timestamp(now or datetime.now(timezone.utc), "Membership timestamp")
        with connection:
            if connection.execute(
                "SELECT 1 FROM persons WHERE person_id=?", (str(person),)
            ).fetchone() is None:
                raise ValidationError("Membership requires an existing Person")
            if connection.execute(
                "SELECT 1 FROM activities WHERE activity_id=?", (str(activity),)
            ).fetchone() is None:
                raise ValidationError("Membership requires an existing Activity")
            connection.execute(
                "INSERT INTO memberships(membership_id,person_id,activity_id,state,effective_from,effective_to,"
                "created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
                (str(key), str(person), str(activity), membership_state, start, end, timestamp, timestamp),
            )
        return self.get_membership(connection, key)

    def get_membership(self, connection, membership_id) -> PersistedMembership:
        key = str(self._uuid(membership_id, "Membership identifier"))
        row = connection.execute(
            "SELECT membership_id,person_id,activity_id,state,effective_from,effective_to,created_at,updated_at,version_no "
            "FROM memberships WHERE membership_id=?", (key,)
        ).fetchone()
        if row is None:
            raise ValidationError("Membership does not exist")
        return self._membership(row)

    def list_memberships_for_activity(self, connection, activity_id) -> tuple[PersistedMembership, ...]:
        activity = str(self._uuid(activity_id, "Activity identifier"))
        if connection.execute("SELECT 1 FROM activities WHERE activity_id=?", (activity,)).fetchone() is None:
            raise ValidationError("Activity does not exist")
        rows = connection.execute(
            "SELECT membership_id,person_id,activity_id,state,effective_from,effective_to,created_at,updated_at,version_no "
            "FROM memberships WHERE activity_id=? ORDER BY effective_from,membership_id", (activity,)
        ).fetchall()
        return tuple(self._membership(row) for row in rows)

    def list_memberships_for_person(self, connection, person_id) -> tuple[PersistedMembership, ...]:
        person = str(self._uuid(person_id, "Person identifier"))
        if connection.execute("SELECT 1 FROM persons WHERE person_id=?", (person,)).fetchone() is None:
            raise ValidationError("Person does not exist")
        rows = connection.execute(
            "SELECT membership_id,person_id,activity_id,state,effective_from,effective_to,created_at,updated_at,version_no "
            "FROM memberships WHERE person_id=? ORDER BY effective_from,membership_id", (person,)
        ).fetchall()
        return tuple(self._membership(row) for row in rows)
