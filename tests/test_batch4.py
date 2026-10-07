import pytest
from uuid import uuid4
from agent_core.shared import ValidationError
from agent_core.domain_authorization import Agent, AgentAction, Approval, AuthorityPolicy, AuthorizationGrant
from agent_core.domain_audit import Provenance, AuditRecord, record_agent_action
from agent_core.domain_offline import Device, PendingOperation, Conflict, StateRecord
from agent_core.domain_activities import Activity, Membership, RoleAssignment
from agent_core.domain_education import validate_education_context, education_access_is_authorized
from agent_core.domain_exceptions import reject, is_duplicate, require_owner_domain_decision, preserve_history_on_cancellation, preserve_original_on_reversal

def test_agent_audit_and_provenance_are_separate():
    agent=Agent("agent"); action=AgentAction(agent.id,"review"); approval=Approval(action.id)
    audit=record_agent_action(action.id,"clinical-record","prepared")
    prov=Provenance("clinical-record","domain")
    assert audit.id!=prov.id and audit.actor_ref==action.id and approval.agent_action_id==action.id
    assert not hasattr(agent,"domain_truth")

def test_approval_is_not_execution():
    action=AgentAction(uuid4(),"review"); approval=Approval(action.id)
    approval.approve()
    assert action.state.value=="prepared" and approval.state.value=="approved"

def test_authority_policy_does_not_create_new_agent_authority():
    policy=AuthorityPolicy("fixed"); grant=AuthorizationGrant(uuid4(),"read","scope")
    assert policy.id!=grant.id and not hasattr(policy,"permission_role")

def test_provenance_and_audit_validate():
    with pytest.raises(ValidationError): Provenance("","domain")
    with pytest.raises(ValidationError): AuditRecord("","subject")

def test_offline_pending_device_are_not_domain_truth():
    device=Device(uuid4()); pending=PendingOperation(device.id,"Finance","op-1"); state=StateRecord("Finance","account-1","pending")
    pending.submit()
    assert pending.state.value=="submitted" and state.owner_domain=="Finance"
    assert not hasattr(device,"ledger_entry_id")

def test_conflict_has_no_third_truth_and_requires_distinct_refs():
    conflict=Conflict("Health","local-1","remote-1"); conflict.review()
    assert conflict.state.value=="reviewed"
    with pytest.raises(ValidationError): Conflict("Health","same","same")

def test_pending_lifecycle_and_validation():
    device=Device(uuid4()); pending=PendingOperation(device.id,"Education","op-2")
    pending.accept(); assert pending.state.value=="accepted"
    device.mark_lost(); assert device.state.value=="lost"
    with pytest.raises(ValidationError): PendingOperation("bad","Education","op")

def test_education_uses_existing_concepts_only():
    person=uuid4(); activity=Activity("education"); membership=Membership(person,activity.id); membership.activate()
    role=RoleAssignment(membership.id,"participant"); role.activate()
    assert validate_education_context(person,membership,role)
    assert not hasattr(membership,"student_id") and not hasattr(activity,"course_id")

def test_education_family_does_not_imply_authorization():
    person,other=uuid4(),uuid4(); activity=Activity("education"); membership=Membership(person,activity.id)
    family=__import__("agent_core.domain_authorization",fromlist=["FamilyRelationship"]).FamilyRelationship(person,other,"family")
    assert validate_education_context(person,membership,family_relationship=family)
    assert education_access_is_authorized(None) is False

def test_education_authorization_is_explicit():
    person=uuid4(); activity=Activity("education"); membership=Membership(person,activity.id)
    grant=AuthorizationGrant(person,"read","education"); grant.activate()
    assert validate_education_context(person,membership,authorization=grant)
    assert education_access_is_authorized(grant) is True

def test_exception_boundaries():
    with pytest.raises(ValidationError): reject(" ")
    assert is_duplicate("op-1",["op-1","op-2"]) is True
    assert is_duplicate("op-3",["op-1","op-2"]) is False
    with pytest.raises(ValidationError): require_owner_domain_decision("Finance",None)
    assert preserve_history_on_cancellation() is True
    assert preserve_original_on_reversal() is True


# DEC-0013 → CG-002 → REQ-0046/0048/0065 → SPEC-0019/0020/0023 → EXEC-0010
def test_mark_lost_cancels_pending_operations_for_same_device():
    device = Device(uuid4())
    pending = PendingOperation(device.id, "Finance", "op-pending")
    submitted = PendingOperation(device.id, "Health", "op-submitted")
    submitted.submit()
    unrelated = PendingOperation(uuid4(), "Finance", "op-unrelated")

    device.mark_lost([pending, submitted, unrelated])

    assert device.state.value == "lost"
    assert pending.state.value == "cancelled"
    assert submitted.state.value == "cancelled"
    assert unrelated.state.value != "cancelled"


def test_mark_lost_preserves_finalized_operation_and_history():
    device = Device(uuid4())
    accepted = PendingOperation(device.id, "Finance", "op-accepted")
    accepted.accept()
    rejected = PendingOperation(device.id, "Finance", "op-rejected")
    rejected.reject()

    device.mark_lost([accepted, rejected])

    assert accepted.state.value == "accepted"
    assert rejected.state.value == "rejected"
    assert accepted.id != rejected.id


def test_mark_lost_is_idempotent():
    device = Device(uuid4())
    pending = PendingOperation(device.id, "Finance", "op-pending")

    device.mark_lost([pending])
    device.mark_lost([pending])

    assert device.state.value == "lost"
    assert pending.state.value == "cancelled"
