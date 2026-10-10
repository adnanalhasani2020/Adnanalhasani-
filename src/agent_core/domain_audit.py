from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Optional
from uuid import UUID
from agent_core.shared import ValidationError, new_id

class ApprovalDecision(str, Enum):
    PENDING="pending"; APPROVED="approved"; REJECTED="rejected"

@dataclass(frozen=True)
class Provenance:
    source_ref: str
    source_type: str
    id: UUID = field(default_factory=new_id)
    recorded_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    def __post_init__(self):
        if not self.source_ref.strip() or not self.source_type.strip():
            raise ValidationError("Provenance source fields are required")

@dataclass(frozen=True)
class AuditRecord:
    action: str
    subject_ref: str
    actor_ref: Optional[UUID] = None
    result: Optional[str] = None
    id: UUID = field(default_factory=new_id)
    recorded_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    def __post_init__(self):
        if not self.action.strip() or not self.subject_ref.strip():
            raise ValidationError("AuditRecord action and subject are required")

def record_agent_action(action_id: UUID, subject_ref: str, result: str | None = None) -> AuditRecord:
    if not isinstance(action_id, UUID):
        raise ValidationError("Agent Action reference is required")
    return AuditRecord(action="agent_action", subject_ref=subject_ref, actor_ref=action_id, result=result)

def record_provenance(source_ref: str, source_type: str) -> Provenance:
    return Provenance(source_ref=source_ref, source_type=source_type)


@dataclass(frozen=True)
class PersistedAuditRecord:
    audit_record_id: UUID
    event_type: str
    actor_context_ref: str
    target_ref: str
    occurred_at: str
    result_status: str
    operation_ref: str | None
    correlation_ref: str | None
    provenance_id: str | None
    created_at: str


class AuditRecordApplication:
    """Append explicitly supplied audit facts to the existing relational contract.

    This service does not infer actor identity or automatically claim atomicity
    with a domain mutation performed elsewhere.
    """

    @staticmethod
    def _required(value, label):
        if not isinstance(value, str) or not value.strip():
            raise ValidationError(f"{label} is required")
        return value.strip()

    @staticmethod
    def _optional(value, label):
        if value is None:
            return None
        return AuditRecordApplication._required(value, label)

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

    def append(
        self, connection, *, event_type, actor_context_ref, target_ref,
        result_status, occurred_at=None, operation_ref=None, correlation_ref=None,
        provenance_id=None, audit_record_id=None, now=None,
    ) -> PersistedAuditRecord:
        event_type = self._required(event_type, "event_type")
        actor_context_ref = self._required(actor_context_ref, "actor_context_ref")
        target_ref = self._required(target_ref, "target_ref")
        result_status = self._required(result_status, "result_status")
        operation_ref = self._optional(operation_ref, "operation_ref")
        correlation_ref = self._optional(correlation_ref, "correlation_ref")
        provenance_id = self._optional(provenance_id, "provenance_id")
        record_id = UUID(str(audit_record_id)) if audit_record_id is not None else new_id()
        occurred = self._timestamp(occurred_at or now or datetime.now(timezone.utc), "occurred_at")
        created = self._timestamp(now or datetime.now(timezone.utc), "created_at")
        with connection:
            connection.execute(
                "INSERT INTO audit_records(audit_record_id,event_type,actor_context_ref,target_ref,"
                "occurred_at,result_status,operation_ref,correlation_ref,provenance_id,created_at) "
                "VALUES(?,?,?,?,?,?,?,?,?,?)",
                (str(record_id), event_type, actor_context_ref, target_ref, occurred,
                 result_status, operation_ref, correlation_ref, provenance_id, created),
            )
        return PersistedAuditRecord(
            record_id, event_type, actor_context_ref, target_ref, occurred,
            result_status, operation_ref, correlation_ref, provenance_id, created,
        )
