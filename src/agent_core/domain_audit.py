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
