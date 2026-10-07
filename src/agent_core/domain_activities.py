from dataclasses import dataclass,field
from datetime import datetime
from typing import Optional
from uuid import UUID
from agent_core.shared import *
@dataclass
class Activity:
    name: str; id: UUID=field(default_factory=new_id); state: ActivityState=ActivityState.ESTABLISHED
    def __post_init__(self):
        if not isinstance(self.name,str) or not self.name.strip(): raise ValidationError("Activity.name is required")
@dataclass
class Organization:
    name: str; id: UUID=field(default_factory=new_id); state: OrganizationState=OrganizationState.ESTABLISHED
    def __post_init__(self):
        if not isinstance(self.name,str) or not self.name.strip(): raise ValidationError("Organization.name is required")
@dataclass
class Membership:
    person_id: UUID; activity_id: UUID; id: UUID=field(default_factory=new_id); state: MembershipState=MembershipState.PROPOSED; start: Optional[datetime]=None; end: Optional[datetime]=None
    def __post_init__(self):
        if not isinstance(self.person_id,UUID) or not isinstance(self.activity_id,UUID): raise ValidationError("Membership must reference Person and Activity")
        if self.start is not None and self.end is not None and self.end<self.start: raise ValidationError("Membership.end cannot precede start")
    def activate(self): self.state=MembershipState.ACTIVE
    def suspend(self): self.state=MembershipState.SUSPENDED
    def end_membership(self): self.state=MembershipState.ENDED
@dataclass
class RoleAssignment:
    membership_id: UUID; role_name: str; id: UUID=field(default_factory=new_id); state: RoleAssignmentState=RoleAssignmentState.ASSIGNED; effective: Optional[datetime]=None; expiry: Optional[datetime]=None
    def __post_init__(self):
        if not isinstance(self.membership_id,UUID): raise ValidationError("RoleAssignment must reference Membership")
        if not isinstance(self.role_name,str) or not self.role_name.strip(): raise ValidationError("RoleAssignment.role_name is required")
        if self.effective is not None and self.expiry is not None and self.expiry<self.effective: raise ValidationError("RoleAssignment.expiry cannot precede effective")
    def activate(self): self.state=RoleAssignmentState.ACTIVE
    def revoke(self): self.state=RoleAssignmentState.REVOKED
    def expire(self): self.state=RoleAssignmentState.EXPIRED
