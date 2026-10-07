from dataclasses import dataclass,field
from datetime import datetime,timezone
from uuid import UUID
from agent_core.shared import *
@dataclass
class Person:
    id: UUID=field(default_factory=new_id); state: PersonState=PersonState.ACTIVE
    def __post_init__(self):
        if not isinstance(self.id,UUID): raise ValidationError("Person.id must be UUID")
    def deactivate(self): self.state=PersonState.INACTIVE
    def correct(self): self.state=PersonState.CORRECTED
@dataclass
class Identifier:
    person_id: UUID; value: str; id: UUID=field(default_factory=new_id); state: IdentifierState=IdentifierState.ISSUED
    def __post_init__(self):
        if not isinstance(self.person_id,UUID): raise ValidationError("Identifier must reference Person")
        if not isinstance(self.value,str) or not self.value.strip(): raise ValidationError("Identifier.value is required")
        self.value=self.value.strip()
@dataclass
class AccessAccount:
    person_id: UUID; id: UUID=field(default_factory=new_id); state: AccessAccountState=AccessAccountState.ACTIVE
    def __post_init__(self):
        if not isinstance(self.person_id,UUID): raise ValidationError("AccessAccount must reference Person")
    def suspend(self): self.state=AccessAccountState.SUSPENDED
    def expire(self): self.state=AccessAccountState.EXPIRED
    def recover(self):
        if self.state!=AccessAccountState.EXPIRED: raise ValidationError("recovery requires expired Access Account")
        self.state=AccessAccountState.RECOVERY
@dataclass
class Authenticator:
    access_account_id: UUID; id: UUID=field(default_factory=new_id); state: AuthenticatorState=AuthenticatorState.ENROLLED
@dataclass
class Session:
    access_account_id: UUID; started_at: datetime=field(default_factory=lambda:datetime.now(timezone.utc)); id: UUID=field(default_factory=new_id); state: SessionState=SessionState.STARTED
    def __post_init__(self):
        if not isinstance(self.access_account_id,UUID): raise ValidationError("Session must reference Access Account")
    def activate(self):
        if self.state!=SessionState.STARTED: raise ValidationError("only started Session can activate")
        self.state=SessionState.ACTIVE
    def expire(self): self.state=SessionState.EXPIRED
    def cancel(self): self.state=SessionState.CANCELLED
@dataclass(frozen=True)
class PrimaryAccountDecisionBoundary: status: str="deferred"
