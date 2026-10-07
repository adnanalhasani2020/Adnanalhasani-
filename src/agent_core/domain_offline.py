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
