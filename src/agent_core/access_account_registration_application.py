"""Explicit persistence for caller-created Access Account records.

This service persists the existing identity record only. It does not authenticate,
create Authenticators or Sessions, grant authority, or define lifecycle transitions.
"""
from __future__ import annotations

import sqlite3
from datetime import datetime, timezone
from uuid import UUID, uuid4

from agent_core.access_account_read import PersistedAccessAccount, PersistedAccessAccountReader
from agent_core.shared import ValidationError


class AccessAccountRegistrationApplication:
    """Create an Access Account under the existing relational schema contract."""

    _STATES = {"active", "suspended", "expired", "recovery"}

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

    def create_access_account(
        self, connection, *, person_id, state, access_account_id=None,
        created_at=None, updated_at=None,
    ) -> PersistedAccessAccount:
        """Persist one explicitly requested account without creating related entities."""
        person_key = self._uuid(person_id, "Person identifier")
        account_key = (
            self._uuid(access_account_id, "Access Account identifier")
            if access_account_id is not None else uuid4()
        )
        if not isinstance(state, str) or state not in self._STATES:
            raise ValidationError("Access Account state must match the persisted schema contract")
        created = self._timestamp(created_at or datetime.now(timezone.utc), "Access Account created_at")
        updated = self._timestamp(updated_at or created, "Access Account updated_at")

        try:
            with connection:
                if connection.execute(
                    "SELECT 1 FROM persons WHERE person_id=?", (str(person_key),)
                ).fetchone() is None:
                    raise ValidationError("Access Account requires an existing Person")
                connection.execute(
                    """INSERT INTO access_accounts(
                           access_account_id, person_id, state, created_at, updated_at, version_no
                       ) VALUES(?, ?, ?, ?, ?, 1)""",
                    (str(account_key), str(person_key), state, created, updated),
                )
        except sqlite3.IntegrityError as exc:
            raise ValidationError(
                "Access Account identifier or referenced Person violates the persisted schema contract"
            ) from exc

        record = PersistedAccessAccountReader().get(connection, account_key)
        if record is None:
            raise ValidationError("Access Account was not persisted")
        return record
