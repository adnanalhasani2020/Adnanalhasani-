from agent_core.domain_identity import *
from agent_core.domain_activities import *
from agent_core.shared import ValidationError
class IdentityApplication:
    def create_person(self): return Person()
    def attach_identifier(self,p,value): return Identifier(p.id,value)
    def create_access_account(self,p): return AccessAccount(p.id)
    def enroll_authenticator(self,a): return Authenticator(a.id)
    def start_session(self,a):
        if a.state.value!="active": raise ValidationError("Session requires active Access Account")
        return Session(a.id)
class ActivityApplication:
    def create_activity(self,name): return Activity(name)
    def create_organization(self,name): return Organization(name)
    def add_membership(self,p,a): return Membership(p.id,a.id)
    def assign_role(self,m,role_name): return RoleAssignment(m.id,role_name)
