from dataclasses import dataclass, field
from enum import Enum
from typing import Optional
from uuid import UUID
from agent_core.shared import ValidationError, new_id

class FamilyRelationshipState(str,Enum): ACTIVE="active"; ENDED="ended"
class DelegationState(str,Enum): PROPOSED="proposed"; ACTIVE="active"; SUSPENDED="suspended"; REVOKED="revoked"; EXPIRED="expired"
class AuthorizationGrantState(str,Enum): PROPOSED="proposed"; ACTIVE="active"; SUSPENDED="suspended"; REVOKED="revoked"; EXPIRED="expired"
class AuthorityPolicyState(str,Enum): ACTIVE="active"; INACTIVE="inactive"
class AgentActionState(str,Enum): PREPARED="prepared"; APPROVED="approved"; REJECTED="rejected"; EXECUTED="executed"; FAILED="failed"
class ApprovalState(str,Enum): PENDING="pending"; APPROVED="approved"; REJECTED="rejected"

@dataclass
class FamilyRelationship:
    person_a_id: UUID; person_b_id: UUID; relationship: str; id: UUID=field(default_factory=new_id); state: FamilyRelationshipState=FamilyRelationshipState.ACTIVE
    def __post_init__(self):
        if not isinstance(self.person_a_id,UUID) or not isinstance(self.person_b_id,UUID): raise ValidationError("FamilyRelationship must reference Persons")
        if self.person_a_id==self.person_b_id: raise ValidationError("FamilyRelationship parties must differ")
        if not isinstance(self.relationship,str) or not self.relationship.strip(): raise ValidationError("FamilyRelationship.relationship is required")
    def end(self): self.state=FamilyRelationshipState.ENDED

@dataclass
class Delegation:
    delegator_id: UUID; delegatee_id: UUID; scope: str; purpose: Optional[str]=None; id: UUID=field(default_factory=new_id); state: DelegationState=DelegationState.PROPOSED
    def __post_init__(self):
        if not isinstance(self.delegator_id,UUID) or not isinstance(self.delegatee_id,UUID): raise ValidationError("Delegation must reference Persons")
        if self.delegator_id==self.delegatee_id: raise ValidationError("Delegation parties must differ")
        if not isinstance(self.scope,str) or not self.scope.strip(): raise ValidationError("Delegation.scope is required")
    def activate(self): self.state=DelegationState.ACTIVE
    def suspend(self): self.state=DelegationState.SUSPENDED
    def revoke(self): self.state=DelegationState.REVOKED
    def expire(self): self.state=DelegationState.EXPIRED

@dataclass
class AuthorizationGrant:
    subject_id: UUID; action: str; scope: str; purpose: Optional[str]=None; id: UUID=field(default_factory=new_id); state: AuthorizationGrantState=AuthorizationGrantState.PROPOSED
    def __post_init__(self):
        if not isinstance(self.subject_id,UUID): raise ValidationError("AuthorizationGrant.subject_id is required")
        if not isinstance(self.action,str) or not self.action.strip(): raise ValidationError("AuthorizationGrant.action is required")
        if not isinstance(self.scope,str) or not self.scope.strip(): raise ValidationError("AuthorizationGrant.scope is required")
    def activate(self): self.state=AuthorizationGrantState.ACTIVE
    def suspend(self): self.state=AuthorizationGrantState.SUSPENDED
    def revoke(self): self.state=AuthorizationGrantState.REVOKED
    def expire(self): self.state=AuthorizationGrantState.EXPIRED

@dataclass
class AuthorityPolicy:
    name: str; id: UUID=field(default_factory=new_id); state: AuthorityPolicyState=AuthorityPolicyState.ACTIVE
    def __post_init__(self):
        if not isinstance(self.name,str) or not self.name.strip(): raise ValidationError("AuthorityPolicy.name is required")
    def deactivate(self): self.state=AuthorityPolicyState.INACTIVE

@dataclass
class Agent:
    name: str; id: UUID=field(default_factory=new_id)
    def __post_init__(self):
        if not isinstance(self.name,str) or not self.name.strip(): raise ValidationError("Agent.name is required")

@dataclass
class AgentAction:
    agent_id: UUID; action: str; id: UUID=field(default_factory=new_id); state: AgentActionState=AgentActionState.PREPARED
    def __post_init__(self):
        if not isinstance(self.agent_id,UUID): raise ValidationError("AgentAction must reference Agent")
        if not isinstance(self.action,str) or not self.action.strip(): raise ValidationError("AgentAction.action is required")
    def approve(self): self.state=AgentActionState.APPROVED
    def reject(self): self.state=AgentActionState.REJECTED
    def execute(self): self.state=AgentActionState.EXECUTED
    def fail(self): self.state=AgentActionState.FAILED

@dataclass
class Approval:
    agent_action_id: UUID; id: UUID=field(default_factory=new_id); state: ApprovalState=ApprovalState.PENDING
    def __post_init__(self):
        if not isinstance(self.agent_action_id,UUID): raise ValidationError("Approval must reference AgentAction")
    def approve(self): self.state=ApprovalState.APPROVED
    def reject(self): self.state=ApprovalState.REJECTED
