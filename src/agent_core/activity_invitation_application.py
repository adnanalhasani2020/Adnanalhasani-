"""Persisted Activity Invitation records without inferred lifecycle or authority.

An invitation records who invited whom to which existing Activity. This service
does not accept/decline invitations, create Memberships, grant permissions, or
infer authorization from an invitation reference.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from uuid import UUID, uuid4
import sqlite3

from agent_core.shared import ValidationError


@dataclass(frozen=True)
class ActivityInvitation:
    invitation_id: UUID
    activity_id: UUID
    inviter_person_id: UUID
    invitee_person_id: UUID
    created_at: str


class ActivityInvitationApplication:
    @staticmethod
    def _uuid(value, label):
        try:
            return UUID(str(value))
        except (ValueError, TypeError, AttributeError) as exc:
            raise ValidationError(f"{label} must be a valid UUID") from exc

    @staticmethod
    def _timestamp(value):
        if isinstance(value, str):
            try:
                value = datetime.fromisoformat(value.replace("Z", "+00:00"))
            except ValueError as exc:
                raise ValidationError("Invitation timestamp must be valid ISO-8601") from exc
        if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
            raise ValidationError("Invitation timestamp must be timezone-aware")
        return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")

    @staticmethod
    def _record(row):
        return ActivityInvitation(
            UUID(row[0]), UUID(row[1]), UUID(row[2]), UUID(row[3]), row[4]
        )

    def create_invitation(
        self, connection, *, activity_id, inviter_person_id, invitee_person_id,
        invitation_id=None, created_at=None,
    ) -> ActivityInvitation:
        invitation_key = str(self._uuid(invitation_id, "Invitation identifier")) if invitation_id is not None else str(uuid4())
        activity_key = str(self._uuid(activity_id, "Activity identifier"))
        inviter_key = str(self._uuid(inviter_person_id, "Inviter Person identifier"))
        invitee_key = str(self._uuid(invitee_person_id, "Invitee Person identifier"))
        timestamp = self._timestamp(created_at or datetime.now(timezone.utc))
        try:
            with connection:
                # Check existence explicitly so missing references fail with a stable
                # application error rather than relying only on SQLite's FK message.
                if connection.execute(
                    "SELECT 1 FROM activities WHERE activity_id=?", (activity_key,)
                ).fetchone() is None:
                    raise ValidationError("Invitation requires an existing Activity")
                for label, person_key in (
                    ("Inviter", inviter_key), ("Invitee", invitee_key)
                ):
                    if connection.execute(
                        "SELECT 1 FROM persons WHERE person_id=?", (person_key,)
                    ).fetchone() is None:
                        raise ValidationError(f"Invitation {label.lower()} requires an existing Person")
                connection.execute(
                    "INSERT INTO activity_invitations(invitation_id,activity_id,inviter_person_id,"
                    "invitee_person_id,created_at) VALUES(?,?,?,?,?)",
                    (invitation_key, activity_key, inviter_key, invitee_key, timestamp),
                )
        except sqlite3.IntegrityError as exc:
            raise ValidationError("Invitation identifier or referenced record violates the persisted contract") from exc
        return self.get_invitation(connection, invitation_key)

    def get_invitation(self, connection, invitation_id) -> ActivityInvitation:
        key = str(self._uuid(invitation_id, "Invitation identifier"))
        row = connection.execute(
            "SELECT invitation_id,activity_id,inviter_person_id,invitee_person_id,created_at "
            "FROM activity_invitations WHERE invitation_id=?", (key,)
        ).fetchone()
        if row is None:
            raise ValidationError("Activity Invitation does not exist")
        return self._record(row)

    def list_for_activity(self, connection, activity_id) -> tuple[ActivityInvitation, ...]:
        key = str(self._uuid(activity_id, "Activity identifier"))
        if connection.execute(
            "SELECT 1 FROM activities WHERE activity_id=?", (key,)
        ).fetchone() is None:
            raise ValidationError("Activity does not exist")
        rows = connection.execute(
            "SELECT invitation_id,activity_id,inviter_person_id,invitee_person_id,created_at "
            "FROM activity_invitations WHERE activity_id=? ORDER BY created_at,invitation_id",
            (key,),
        ).fetchall()
        return tuple(self._record(row) for row in rows)

    def list_for_invitee(self, connection, invitee_person_id) -> tuple[ActivityInvitation, ...]:
        key = str(self._uuid(invitee_person_id, "Invitee Person identifier"))
        if connection.execute(
            "SELECT 1 FROM persons WHERE person_id=?", (key,)
        ).fetchone() is None:
            raise ValidationError("Invitee Person does not exist")
        rows = connection.execute(
            "SELECT invitation_id,activity_id,inviter_person_id,invitee_person_id,created_at "
            "FROM activity_invitations WHERE invitee_person_id=? ORDER BY created_at,invitation_id",
            (key,),
        ).fetchall()
        return tuple(self._record(row) for row in rows)


    def list_for_inviter(self, connection, inviter_person_id) -> tuple[ActivityInvitation, ...]:
        """List invitations explicitly issued by one existing Person.

        This is a read-only query. It does not infer invitation status or
        introduce acceptance, membership, notification, or authorization effects.
        """
        key = str(self._uuid(inviter_person_id, "Inviter Person identifier"))
        if connection.execute(
            "SELECT 1 FROM persons WHERE person_id=?", (key,)
        ).fetchone() is None:
            raise ValidationError("Inviter Person does not exist")
        rows = connection.execute(
            "SELECT invitation_id,activity_id,inviter_person_id,invitee_person_id,created_at "
            "FROM activity_invitations WHERE inviter_person_id=? ORDER BY created_at,invitation_id",
            (key,),
        ).fetchall()
        return tuple(self._record(row) for row in rows)
