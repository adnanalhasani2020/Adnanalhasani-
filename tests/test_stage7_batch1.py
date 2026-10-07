import pytest
from datetime import datetime,timedelta
from agent_core.domain_identity import AccessAccount,Identifier,Person,Session,PrimaryAccountDecisionBoundary
from agent_core.domain_activities import Activity,Membership,Organization,RoleAssignment
from agent_core.shared import ValidationError,SessionState,MembershipState,RoleAssignmentState
from agent_core.application import IdentityApplication
from agent_core.infrastructure import AuditLogger
from agent_core.runtime import ApplicationRuntime

def test_identity_objects_are_distinct():
    p=Person(); i=Identifier(p.id,"id-1"); a=AccessAccount(p.id); s=Session(a.id)
    assert p is not i and p is not a and a is not s and i.person_id==p.id and a.person_id==p.id
def test_identifier_rejects_blank():
    with pytest.raises(ValidationError): Identifier(Person().id," ")
def test_session_rejects_inactive_access_account():
    a=AccessAccount(Person().id); a.suspend()
    with pytest.raises(ValidationError): IdentityApplication().start_session(a)
def test_session_lifecycle_is_temporary():
    a=AccessAccount(Person().id); s=Session(a.id); s.activate(); s.expire()
    assert s.state==SessionState.EXPIRED and s.id!=a.id
def test_primary_account_decision_is_deferred():
    assert PrimaryAccountDecisionBoundary().status=="deferred"
def test_financial_account_not_in_identity():
    p=Person(); a=AccessAccount(p.id)
    assert not hasattr(p,"financial_account_id") and not hasattr(a,"financial_account_id")
def test_activity_is_not_person():
    assert Activity("A").id!=Person().id
def test_organization_is_not_activity():
    assert Organization("O").id!=Activity("A").id
def test_membership_is_not_role_assignment():
    m=Membership(Person().id,Activity("A").id); r=RoleAssignment(m.id,"Manager")
    assert m.id!=r.id and r.membership_id==m.id
def test_role_assignment_has_no_authorization():
    m=Membership(Person().id,Activity("A").id); r=RoleAssignment(m.id,"Manager")
    assert not hasattr(r,"authorization")
def test_membership_lifecycle_does_not_change_person():
    p=Person(); m=Membership(p.id,Activity("A").id); m.activate(); m.end_membership()
    assert m.state==MembershipState.ENDED and p.state.value=="active"
def test_role_lifecycle_is_independent():
    m=Membership(Person().id,Activity("A").id); r=RoleAssignment(m.id,"Manager")
    m.activate(); r.activate(); r.revoke()
    assert m.state==MembershipState.ACTIVE and r.state==RoleAssignmentState.REVOKED
def test_invalid_periods_are_rejected():
    start=datetime.now(); end=start-timedelta(seconds=1)
    with pytest.raises(ValidationError): Membership(Person().id,Activity("A").id,start=start,end=end)
    m=Membership(Person().id,Activity("A").id)
    with pytest.raises(ValidationError): RoleAssignment(m.id,"Manager",effective=start,expiry=end)
def test_membership_has_no_role_name():
    assert not hasattr(Membership(Person().id,Activity("A").id),"role_name")
def test_session_has_no_person_identity():
    s=Session(AccessAccount(Person().id).id)
    assert not hasattr(s,"person_id")
def test_audit_logger_returns_structured_event():
    e=AuditLogger().record("created","Person","p-1",source="test")
    assert e.action=="created" and e.subject_type=="Person" and e.attributes["source"]=="test"
def test_runtime_composes_boundaries():
    r=ApplicationRuntime.create()
    assert r.identity and r.activities and r.audit
