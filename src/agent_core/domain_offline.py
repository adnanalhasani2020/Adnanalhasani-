from dataclasses import dataclass, field
from enum import Enum
from typing import Iterable, Optional
from uuid import UUID
from agent_core.shared import ValidationError, new_id

class DeviceState(str, Enum):
    ACTIVE="active"; LOST="lost"; REVOKED="revoked"
class PendingOperationState(str, Enum):
    PENDING="pending"; SUBMITTED="submitted"; ACCEPTED="accepted"; REJECTED="rejected"; CANCELLED="cancelled"
class ConflictState(str, Enum):
    OPEN="open"; REVIEWED="reviewed"; RESOLVED="resolved"
class StateRecordState(str, Enum):
    OBSERVED="observed"; PENDING="pending"; ACCEPTED="accepted"; REJECTED="rejected"

@dataclass
class Device:
    owner_ref: UUID
    id: UUID=field(default_factory=new_id)
    state: DeviceState=DeviceState.ACTIVE
    def __post_init__(self):
        if not isinstance(self.owner_ref, UUID): raise ValidationError("Device.owner_ref must reference an existing owner")
    def mark_lost(self, pending_operations: Iterable["PendingOperation"] = ()):
        for operation in pending_operations:
            if operation.device_id == self.id and operation.state in (PendingOperationState.PENDING, PendingOperationState.SUBMITTED):
                operation.cancel()
        self.state=DeviceState.LOST
    def revoke(self): self.state=DeviceState.REVOKED

@dataclass
class PendingOperation:
    device_id: UUID
    owner_domain: str
    operation_ref: str
    id: UUID=field(default_factory=new_id)
    state: PendingOperationState=PendingOperationState.PENDING
    def __post_init__(self):
        if not isinstance(self.device_id, UUID): raise ValidationError("PendingOperation must reference Device")
        if not self.owner_domain.strip() or not self.operation_ref.strip(): raise ValidationError("PendingOperation references are required")
    def submit(self): self.state=PendingOperationState.SUBMITTED
    def accept(self): self.state=PendingOperationState.ACCEPTED
    def reject(self): self.state=PendingOperationState.REJECTED
    def cancel(self): self.state=PendingOperationState.CANCELLED

@dataclass
class Conflict:
    owner_domain: str
    local_ref: str
    remote_ref: str
    id: UUID=field(default_factory=new_id)
    state: ConflictState=ConflictState.OPEN
    def __post_init__(self):
        if not self.owner_domain.strip() or not self.local_ref.strip() or not self.remote_ref.strip():
            raise ValidationError("Conflict references are required")
        if self.local_ref == self.remote_ref:
            raise ValidationError("Conflict requires distinct state references")
    def review(self): self.state=ConflictState.REVIEWED
    def mark_resolved_by_owner(self): self.state=ConflictState.RESOLVED

@dataclass(frozen=True)
class StateRecord:
    owner_domain: str
    subject_ref: str
    state: str
    id: UUID=field(default_factory=new_id)
    def __post_init__(self):
        if not self.owner_domain.strip() or not self.subject_ref.strip() or not self.state.strip():
            raise ValidationError("StateRecord fields are required")


# Persisted operational queue. These records remain non-final and do not
# execute domain effects or decide owner-domain outcomes.
from datetime import datetime, timezone
import sqlite3


@dataclass(frozen=True)
class PersistedPendingOperation:
    pending_operation_id: UUID
    device_id: UUID
    namespace: str
    operation_id: str
    operation_kind: str
    request_fingerprint: str
    state: str
    queued_at: str
    created_at: str
    updated_at: str


class PendingOperationApplication:
    """Persist the non-final queue lifecycle allowed by SPEC-0019.

    This boundary intentionally supports queue, submit, cancel, and device-loss
    cancellation only. It does not finalize/reject domain work or resolve conflicts.
    """

    @staticmethod
    def _required(value, label):
        if not isinstance(value, str) or not value.strip():
            raise ValidationError(f"{label} is required")
        return value.strip()

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
    def _from_row(row):
        return PersistedPendingOperation(
            UUID(row[0]), UUID(row[1]), row[2], row[3], row[4], row[5],
            row[6], row[7], row[8], row[9],
        )

    @staticmethod
    def _select_sql():
        return (
            "SELECT pending_operation_id,device_id,namespace,operation_id,operation_kind,"
            "request_fingerprint,state,queued_at,created_at,updated_at FROM pending_operations"
        )

    def _find(self, connection, namespace, operation_id):
        row = connection.execute(
            self._select_sql() + " WHERE namespace=? AND operation_id=?",
            (namespace, operation_id),
        ).fetchone()
        return self._from_row(row) if row is not None else None

    def queue(
        self, connection, *, device_id, namespace, operation_id, operation_kind,
        request_fingerprint, now=None,
    ) -> PersistedPendingOperation:
        device_key = str(self._uuid(device_id, "Device identifier"))
        namespace = self._required(namespace, "namespace")
        operation_id = self._required(operation_id, "operation_id")
        operation_kind = self._required(operation_kind, "operation_kind")
        request_fingerprint = self._required(request_fingerprint, "request_fingerprint")
        timestamp = self._timestamp(now or datetime.now(timezone.utc), "pending operation timestamp")
        try:
            with connection:
                existing = self._find(connection, namespace, operation_id)
                if existing is not None:
                    if (
                        str(existing.device_id) != device_key
                        or existing.operation_kind != operation_kind
                        or existing.request_fingerprint != request_fingerprint
                    ):
                        raise ValidationError(
                            "Pending operation key already exists with different device or request metadata"
                        )
                    return existing
                device = connection.execute(
                    "SELECT state FROM devices WHERE device_id=?", (device_key,)
                ).fetchone()
                if device is None:
                    raise ValidationError("Pending operation requires an existing Device")
                if device[0] != "active":
                    raise ValidationError("Pending operation requires an active Device")
                pending_id = str(new_id())
                connection.execute(
                    "INSERT INTO pending_operations(pending_operation_id,device_id,namespace,operation_id,"
                    "operation_kind,request_fingerprint,state,queued_at,created_at,updated_at) "
                    "VALUES(?,?,?,?,?,?,'queued',?,?,?)",
                    (pending_id, device_key, namespace, operation_id, operation_kind,
                     request_fingerprint, timestamp, timestamp, timestamp),
                )
        except sqlite3.IntegrityError as exc:
            current = self._find(connection, namespace, operation_id)
            if (
                current is None or str(current.device_id) != device_key
                or current.operation_kind != operation_kind
                or current.request_fingerprint != request_fingerprint
            ):
                raise ValidationError("Pending operation key collided with different request metadata") from exc
            return current
        return self._find(connection, namespace, operation_id)

    def get(self, connection, *, namespace, operation_id) -> PersistedPendingOperation:
        namespace = self._required(namespace, "namespace")
        operation_id = self._required(operation_id, "operation_id")
        record = self._find(connection, namespace, operation_id)
        if record is None:
            raise ValidationError("Pending operation does not exist")
        return record

    def list_for_device(self, connection, device_id) -> tuple[PersistedPendingOperation, ...]:
        device_key = str(self._uuid(device_id, "Device identifier"))
        if connection.execute(
            "SELECT 1 FROM devices WHERE device_id=?", (device_key,)
        ).fetchone() is None:
            raise ValidationError("Device does not exist")
        rows = connection.execute(
            self._select_sql() + " WHERE device_id=? ORDER BY queued_at,created_at,pending_operation_id",
            (device_key,),
        ).fetchall()
        return tuple(self._from_row(row) for row in rows)

    def submit(self, connection, *, namespace, operation_id, now=None) -> PersistedPendingOperation:
        return self._transition(
            connection, namespace=namespace, operation_id=operation_id,
            allowed=("queued", "submitted"), target="submitted", now=now,
        )

    def cancel(self, connection, *, namespace, operation_id, now=None) -> PersistedPendingOperation:
        return self._transition(
            connection, namespace=namespace, operation_id=operation_id,
            allowed=("queued", "submitted", "cancelled"), target="cancelled", now=now,
        )

    def _transition(self, connection, *, namespace, operation_id, allowed, target, now=None):
        namespace = self._required(namespace, "namespace")
        operation_id = self._required(operation_id, "operation_id")
        timestamp = self._timestamp(now or datetime.now(timezone.utc), "pending operation transition timestamp")
        with connection:
            current = self._find(connection, namespace, operation_id)
            if current is None:
                raise ValidationError("Pending operation does not exist")
            if current.state == target:
                return current
            if current.state not in allowed:
                raise ValidationError(f"Pending operation in state {current.state} cannot transition to {target}")
            if current.state == "cancelled":
                return current
            cursor = connection.execute(
                "UPDATE pending_operations SET state=?,updated_at=? "
                "WHERE namespace=? AND operation_id=? AND state=?",
                (target, timestamp, namespace, operation_id, current.state),
            )
            if cursor.rowcount != 1:
                raise ValidationError("Pending operation changed concurrently; reload before retrying")
        return self._find(connection, namespace, operation_id)

    def mark_device_lost(self, connection, *, device_id, expected_version, now=None):
        device_key = str(self._uuid(device_id, "Device identifier"))
        if isinstance(expected_version, bool) or not isinstance(expected_version, int) or expected_version < 1:
            raise ValidationError("expected_version must be a positive integer")
        timestamp = self._timestamp(now or datetime.now(timezone.utc), "Device transition timestamp")
        with connection:
            cursor = connection.execute(
                "UPDATE devices SET state='lost',updated_at=?,version_no=version_no+1 "
                "WHERE device_id=? AND state='active' AND version_no=?",
                (timestamp, device_key, expected_version),
            )
            if cursor.rowcount != 1:
                raise ValidationError("Device is missing, not active, or changed concurrently")
            connection.execute(
                "UPDATE pending_operations SET state='cancelled',updated_at=? "
                "WHERE device_id=? AND state IN ('queued','submitted')",
                (timestamp, device_key),
            )
        return {
            "device_id": UUID(device_key),
            "state": "lost",
            "version_no": expected_version + 1,
            "cancelled_count": connection.execute(
                "SELECT changes()"
            ).fetchone()[0],
        }
