"""Durable operation identity for SPEC-0023 duplicate-operation boundaries.

This registry records operational identity only. It never executes domain effects,
declares domain finality, or resolves a Conflict on behalf of an Owner Domain.
"""
from dataclasses import dataclass
from datetime import datetime, timezone
import sqlite3
from uuid import UUID, uuid4

from agent_core.shared import ValidationError


@dataclass(frozen=True)
class DurableOperationRecord:
    record_id: UUID
    namespace: str
    operation_id: str
    operation_kind: str
    actor_context_ref: str
    request_fingerprint: str
    state: str
    outcome_ref: str | None
    conflict_id: str | None
    created_at: str
    updated_at: str


class DurableOperationApplication:
    """Reserve a durable operation key and record its outcome idempotently."""

    @staticmethod
    def _required(value, label):
        if not isinstance(value, str) or not value.strip():
            raise ValidationError(f"{label} is required")
        return value.strip()

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
    def _record(row):
        record_id, namespace, operation_id, kind, actor, fingerprint, state, outcome, conflict, created, updated = row
        try:
            parsed_id = UUID(record_id)
        except (ValueError, TypeError, AttributeError):
            parsed_id = UUID(int=0)
        return DurableOperationRecord(
            parsed_id, namespace, operation_id, kind, actor, fingerprint,
            state, outcome, conflict, created, updated,
        )

    def _find(self, connection, namespace, operation_id):
        row = connection.execute(
            "SELECT durable_operation_record_id,namespace,operation_id,operation_kind,"
            "actor_context_ref,request_fingerprint,state,outcome_ref,conflict_id,created_at,updated_at "
            "FROM durable_operation_records WHERE namespace=? AND operation_id=?",
            (namespace, operation_id),
        ).fetchone()
        return self._record(row) if row is not None else None

    def reserve(
        self, connection, *, namespace, operation_id, operation_kind,
        actor_context_ref, request_fingerprint, now=None, connected=True,
    ):
        if connected is not True:
            raise ValidationError("Durable operation writes require connectivity")
        namespace = self._required(namespace, "namespace")
        operation_id = self._required(operation_id, "operation_id")
        operation_kind = self._required(operation_kind, "operation_kind")
        actor_context_ref = self._required(actor_context_ref, "actor_context_ref")
        request_fingerprint = self._required(request_fingerprint, "request_fingerprint")
        timestamp = self._timestamp(now or datetime.now(timezone.utc), "operation timestamp")

        try:
            with connection:
                current = self._find(connection, namespace, operation_id)
                if current is not None:
                    if current.request_fingerprint != request_fingerprint:
                        raise ValidationError(
                            "Durable operation key already exists with a different request fingerprint"
                        )
                    return current
                record_id = str(uuid4())
                connection.execute(
                    "INSERT INTO durable_operation_records(durable_operation_record_id,namespace,operation_id,"
                    "operation_kind,actor_context_ref,request_fingerprint,state,created_at,updated_at) "
                    "VALUES(?,?,?,?,?,?,'reserved',?,?)",
                    (record_id, namespace, operation_id, operation_kind, actor_context_ref,
                     request_fingerprint, timestamp, timestamp),
                )
        except sqlite3.IntegrityError as exc:
            # A concurrent reservation may win the UNIQUE(namespace, operation_id) race.
            current = self._find(connection, namespace, operation_id)
            if current is None or current.request_fingerprint != request_fingerprint:
                raise ValidationError("Durable operation reservation collided with a different request") from exc
            return current
        return self._find(connection, namespace, operation_id)

    def get(self, connection, *, namespace, operation_id):
        namespace = self._required(namespace, "namespace")
        operation_id = self._required(operation_id, "operation_id")
        record = self._find(connection, namespace, operation_id)
        if record is None:
            raise ValidationError("Durable operation does not exist")
        return record

    def record_outcome(
        self, connection, *, namespace, operation_id, state, outcome_ref,
        now=None, connected=True,
    ):
        if connected is not True:
            raise ValidationError("Durable operation writes require connectivity")
        namespace = self._required(namespace, "namespace")
        operation_id = self._required(operation_id, "operation_id")
        if state not in ("completed", "rejected"):
            raise ValidationError("Only completed or rejected outcomes can be recorded here")
        outcome_ref = self._required(outcome_ref, "outcome_ref")
        timestamp = self._timestamp(now or datetime.now(timezone.utc), "operation outcome timestamp")
        with connection:
            current = self._find(connection, namespace, operation_id)
            if current is None:
                raise ValidationError("Durable operation does not exist")
            if current.state != "reserved":
                if current.state == state and current.outcome_ref == outcome_ref:
                    return current
                raise ValidationError("Durable operation outcome is already final or conflicts with this outcome")
            result = connection.execute(
                "UPDATE durable_operation_records SET state=?,outcome_ref=?,updated_at=? "
                "WHERE namespace=? AND operation_id=? AND state='reserved' AND request_fingerprint=?",
                (state, outcome_ref, timestamp, namespace, operation_id, current.request_fingerprint),
            )
            if result.rowcount != 1:
                raise ValidationError("Concurrent durable operation outcome update detected")
        return self._find(connection, namespace, operation_id)
