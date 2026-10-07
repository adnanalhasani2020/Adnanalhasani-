"""Stage 8 TEST-0004 — Security, boundaries, adversarial cases and invariants.

Traceability:
TEST-0004-ID -> REQ-0001/0002/0003/0008/0009/0010/0011 -> SPEC-0001/0002/0003 -> EXEC-0002
TEST-0004-ACT -> REQ-0008/0009/0010/0011/0012 -> SPEC-0003/0004 -> EXEC-0003
TEST-0004-COM -> REQ-0013/0014/0015/0016/0037/0038 -> SPEC-0005/0006/0015 -> EXEC-0004/0011
TEST-0004-FIN -> REQ-0017/0018/0019/0020/0021/0022/0023/0037 -> SPEC-0007/0008/0009 -> EXEC-0005
TEST-0004-HLT -> REQ-0024/0025/0026 -> SPEC-0010/0011/0025 -> EXEC-0006
TEST-0004-AUTH -> REQ-0005/0006/0007/0049/0052 -> SPEC-0003/0004/0022 -> EXEC-0007
TEST-0004-AGT -> REQ-0042/0043/0044/0045/0050/0054 -> SPEC-0017/0018/0021 -> EXEC-0009
TEST-0004-OFF -> REQ-0046/0048/0056/0064/0065/0066 -> SPEC-0019/0020/0024 -> EXEC-0010
"""
from dataclasses import FrozenInstanceError, fields
from decimal import Decimal
from uuid import uuid4

import pytest

from agent_core.shared import ValidationError
from agent_core.domain_identity import Person, Identifier, AccessAccount, Session, PrimaryAccountDecisionBoundary
from agent_core.domain_activities import Activity, Organization, Membership, RoleAssignment
from agent_core.domain_inventory import Product, Offering, InventoryPosition, Availability, AvailabilityState
from agent_core.domain_commerce import Sale, SaleState, Invoice, InvoiceState
from agent_core.domain_finance import (
    FinancialAccount, Obligation, Payment, PaymentState, Settlement, SettlementState,
    FinancialTransaction, LedgerEntry, Balance,
)
from agent_core.domain_health import PatientContext, Encounter, ClinicalRecord, ResultReport, Prescription
from agent_core.domain_authorization import (
    FamilyRelationship, Delegation, AuthorizationGrant, Agent, AgentAction,
    AgentActionState, Approval, ApprovalState,
)
from agent_core.domain_audit import Provenance, AuditRecord
from agent_core.domain_offline import Device, PendingOperation, Conflict, StateRecord
from agent_core.domain_exceptions import is_duplicate, preserve_history_on_cancellation, preserve_original_on_reversal


def _field_names(obj):
    return {f.name for f in fields(obj)}


# Identity & access boundaries

def test_identity_objects_remain_distinct_and_non_interchangeable():
    person = Person()
    access = AccessAccount(person.id)
    finance = FinancialAccount(person.id)
    identifier = Identifier(person.id, "ID-001")
    session = Session(access.id)

    assert len({person.id, access.id, finance.id, identifier.id, session.id}) == 5
    assert access.person_id == person.id
    assert finance.person_id == person.id
    assert identifier.person_id == person.id
    assert session.access_account_id == access.id
    assert not hasattr(access, "financial_account_id")
    assert not hasattr(finance, "access_account_id")
    assert not hasattr(identifier, "access_account_id")
    assert not hasattr(session, "person_id")


def test_identifier_never_becomes_identity_or_access_account():
    person = Person()
    identifier = Identifier(person.id, "passport-like")
    assert identifier.person_id == person.id
    assert "person_id" in _field_names(identifier)
    assert "value" not in _field_names(Person())
    assert "access_account_id" not in _field_names(identifier)


def test_session_is_operational_reference_not_source_of_truth():
    person = Person()
    access = AccessAccount(person.id)
    session = Session(access.id)
    session.activate()
    session.expire()

    assert session.access_account_id == access.id
    assert session.state.value == "expired"
    assert not hasattr(session, "person_id")
    assert not hasattr(session, "financial_account_id")
    assert not hasattr(session, "domain_truth")


def test_two_person_objects_are_distinct_without_inventing_primary_account():
    first = Person()
    second = Person()
    assert first.id != second.id
    assert first is not second
    boundary = PrimaryAccountDecisionBoundary()
    assert boundary.status == "deferred"
    assert "primary_account_id" not in _field_names(first)


def test_identity_adversarial_inputs_are_rejected_at_structural_boundary():
    with pytest.raises(ValidationError):
        Person("not-a-uuid")
    with pytest.raises(ValidationError):
        Identifier("not-a-person", "ID")
    with pytest.raises(ValidationError):
        AccessAccount("not-a-person")
    with pytest.raises(ValidationError):
        Session("not-an-access-account")


# Activity / membership / role boundaries

def test_activity_organization_membership_and_role_are_separate():
    person = Person()
    activity = Activity("activity")
    organization = Organization("organization")
    membership = Membership(person.id, activity.id)
    role = RoleAssignment(membership.id, "participant")

    assert activity.id != organization.id
    assert membership.activity_id == activity.id
    assert role.membership_id == membership.id
    assert not hasattr(activity, "organization_id")
    assert not hasattr(organization, "activity_id")
    assert not hasattr(role, "person_id")
    assert "actor_id" not in _field_names(role)


def test_role_assignment_records_current_uuid_boundary_limitation():
    accepted = RoleAssignment(uuid4(), "participant")
    # GAP-0002: runtime checks UUID shape, not Membership existence.
    assert accepted.membership_id is not None
    assert "membership_id" in _field_names(accepted)


def test_activity_boundaries_reject_invalid_shapes_without_new_actor_concept():
    with pytest.raises(ValidationError):
        Activity("")
    with pytest.raises(ValidationError):
        Organization("")
    with pytest.raises(ValidationError):
        Membership("not-a-person", uuid4())
    with pytest.raises(ValidationError):
        RoleAssignment(uuid4(), "")
    assert "actor_id" not in _field_names(Activity("valid"))
    assert "employee_id" not in _field_names(Activity("valid"))
    assert "customer_id" not in _field_names(Activity("valid"))


# Commerce invariants and adversarial replay

def test_sale_cannot_complete_before_confirmation():
    sale = Sale(uuid4(), uuid4())
    with pytest.raises(ValidationError):
        sale.complete()
    assert sale.state == SaleState.INITIATED


def test_sale_does_not_treat_inventory_or_availability_as_sale_truth():
    product = Product("product")
    offering = Offering(product.id)
    inventory = InventoryPosition(offering.id)
    availability = Availability(offering.id, AvailabilityState.UNAVAILABLE)
    sale = Sale(offering.id, uuid4())

    assert sale.offering_id == offering.id
    assert inventory.offering_id == offering.id
    assert availability.offering_id == offering.id
    assert sale.state == SaleState.INITIATED
    assert not hasattr(sale, "inventory_position_id")
    assert not hasattr(sale, "availability_id")


def test_commerce_cancellation_and_correction_preserve_identity_and_history():
    product = Product("product")
    offering = Offering(product.id)
    sale = Sale(offering.id, uuid4())
    invoice = Invoice(sale.id)
    sale_id, invoice_id = sale.id, invoice.id

    sale.cancel()
    invoice.void()

    assert sale.id == sale_id
    assert invoice.id == invoice_id
    assert sale.state == SaleState.CANCELLED
    assert invoice.state == InvoiceState.VOID
    assert invoice.sale_id == sale.id
    assert preserve_history_on_cancellation() is True


def test_duplicate_detection_is_explicit_boundary_not_replay_engine():
    key = str(uuid4())
    assert is_duplicate(key, [key]) is True
    assert is_duplicate(key, []) is False
    assert not hasattr(is_duplicate, "registry")


def test_commerce_adversarial_reference_shapes_are_rejected():
    with pytest.raises(ValidationError):
        Product("")
    with pytest.raises(ValidationError):
        Offering("not-a-product")
    with pytest.raises(ValidationError):
        InventoryPosition("not-an-offering")
    with pytest.raises(ValidationError):
        Availability("not-an-offering", AvailabilityState.AVAILABLE)
    with pytest.raises(ValidationError):
        Sale("not-an-offering", uuid4())
    with pytest.raises(ValidationError):
        Invoice("not-a-sale")


# Financial security boundaries

def test_financial_path_preserves_each_semantic_boundary():
    person = Person()
    account = FinancialAccount(person.id)
    obligation = Obligation(account.id, Decimal("100"))
    invoice = Invoice(uuid4())
    payment = Payment(Decimal("100"), obligation.id, invoice.id)
    settlement = Settlement(payment.id)
    transaction = FinancialTransaction(account.id, Decimal("100"))
    entry = LedgerEntry(account.id, Decimal("100"), transaction.id)
    balance = Balance.derive(account.id, [entry])

    assert obligation.financial_account_id == account.id
    assert payment.obligation_id == obligation.id
    assert payment.invoice_id == invoice.id
    assert settlement.payment_id == payment.id
    assert transaction.financial_account_id == account.id
    assert entry.transaction_id == transaction.id
    assert balance.financial_account_id == account.id
    assert balance.amount == Decimal("100")
    assert not hasattr(payment, "ledger_entry_id")
    assert not hasattr(settlement, "ledger_entry_id")
    assert not hasattr(balance, "ledger_entries")


def test_balance_is_derived_and_immutable():
    account = FinancialAccount(Person().id)
    transaction = FinancialTransaction(account.id, Decimal("30"))
    entry = LedgerEntry(account.id, Decimal("30"), transaction.id)
    balance = Balance.derive(account.id, [entry])

    assert balance.amount == Decimal("30")
    with pytest.raises(FrozenInstanceError):
        balance.amount = Decimal("999")


def test_financial_finality_requires_no_inference_from_pending_operation():
    person = Person()
    account = FinancialAccount(person.id)
    device = Device(person.id)
    pending = PendingOperation(device.id, "Finance", "payment-1")
    pending.accept()

    assert pending.state.value == "accepted"
    assert account.state.value == "active"
    assert not hasattr(pending, "financial_finality")
    assert not hasattr(pending, "ledger_entry")


def test_financial_jump_to_finality_is_visible_as_current_limitation_not_implemented():
    account = FinancialAccount(Person().id)
    payment = Payment(Decimal("20"))
    settlement = Settlement(payment.id)

    payment.complete()
    settlement.settle()
    transaction = FinancialTransaction(account.id, Decimal("20"))
    entry = LedgerEntry(account.id, Decimal("20"), transaction.id)

    assert payment.state == PaymentState.COMPLETED
    assert settlement.state == SettlementState.SETTLED
    assert entry.financial_account_id == account.id
    # GAP-0004: no orchestration engine is asserted to have validated the chain.


def test_financial_adversarial_amounts_are_rejected():
    person = Person()
    account = FinancialAccount(person.id)
    with pytest.raises(ValidationError):
        Obligation(account.id, Decimal("0"))
    with pytest.raises(ValidationError):
        Obligation(account.id, Decimal("-1"))
    with pytest.raises(ValidationError):
        Payment(Decimal("0"))
    with pytest.raises(ValidationError):
        Payment(Decimal("-1"))
    with pytest.raises(ValidationError):
        FinancialTransaction(account.id, Decimal("0"))
    with pytest.raises(ValidationError):
        LedgerEntry(account.id, Decimal("0"), uuid4())


def test_financial_replay_correction_does_not_mutate_ledger_truth():
    account = FinancialAccount(Person().id)
    tx = FinancialTransaction(account.id, Decimal("50"))
    entry = LedgerEntry(account.id, Decimal("50"), tx.id)
    balance_before = Balance.derive(account.id, [entry])

    payment = Payment(Decimal("50"))
    payment.cancel()
    replay = Payment(Decimal("50"))
    replay.cancel()
    balance_after = Balance.derive(account.id, [entry])

    assert payment.id != replay.id
    assert balance_before.amount == Decimal("50")
    assert balance_after.amount == Decimal("50")
    assert entry.transaction_id == tx.id


def test_financial_model_has_no_bank_wallet_currency_or_orchestration_fields():
    account = FinancialAccount(Person().id)
    finance_names = _field_names(account)
    assert "bank_account_id" not in finance_names
    assert "wallet_id" not in finance_names
    assert "currency" not in finance_names
    assert "orchestration_id" not in finance_names


# Health boundaries

def test_health_truth_chain_remains_separate_from_person_and_finance():
    person = Person()
    patient = PatientContext(person.id)
    encounter = Encounter(patient.id)
    record = ClinicalRecord(patient.id, "clinical truth", encounter.id)
    report = ResultReport(patient.id, "result", encounter.id)
    prescription = Prescription(patient.id, "instruction", encounter.id)
    finance = FinancialAccount(person.id)
    invoice = Invoice(uuid4())

    assert patient.person_id == person.id
    assert record.patient_context_id == patient.id
    assert report.patient_context_id == patient.id
    assert prescription.patient_context_id == patient.id
    assert finance.person_id == person.id
    assert not hasattr(patient, "financial_account_id")
    assert not hasattr(record, "financial_account_id")
    assert not hasattr(encounter, "invoice_id")
    assert not hasattr(record, "result_report_id")
    assert invoice.sale_id is not None


def test_health_artifacts_are_semantically_distinct():
    patient = PatientContext(Person().id)
    encounter = Encounter(patient.id)
    record = ClinicalRecord(patient.id, "fact", encounter.id)
    report = ResultReport(patient.id, "result", encounter.id)
    prescription = Prescription(patient.id, "instruction", encounter.id)

    assert len({record.id, report.id, prescription.id}) == 3
    assert "recommendation" not in _field_names(prescription)
    assert "clinical_decision" not in _field_names(prescription)
    assert "clinical_record_id" not in _field_names(report)


def test_family_relationship_does_not_grant_health_authority_implicitly():
    a, b = Person(), Person()
    family = FamilyRelationship(a.id, b.id, "family")
    patient = PatientContext(b.id)
    record = ClinicalRecord(patient.id, "private record")

    assert family.person_a_id == a.id
    assert record.patient_context_id == patient.id
    assert not hasattr(family, "authorization_grant_id")
    assert not hasattr(family, "health_authorization")


def test_agent_and_offline_objects_cannot_become_clinical_truth():
    agent = Agent("health assistant")
    action = AgentAction(agent.id, "review")
    device = Device(Person().id)
    pending = PendingOperation(device.id, "Health", "record-1")

    assert not hasattr(agent, "clinical_record_id")
    assert not hasattr(action, "clinical_record_id")
    assert not hasattr(pending, "clinical_truth")
    assert not hasattr(device, "clinical_record_id")


def test_health_replay_and_correction_keep_original_references():
    patient = PatientContext(Person().id)
    record = ClinicalRecord(patient.id, "original")
    report = ResultReport(patient.id, "original result")
    record_id, report_id = record.id, report.id

    record.record()
    report.finalize()
    record.correct()
    report.correct()

    assert record.id == record_id
    assert report.id == report_id
    assert record.content == "original"
    assert report.content == "original result"


def test_health_adversarial_reference_shapes_are_rejected():
    with pytest.raises(ValidationError):
        PatientContext("not-a-person")
    with pytest.raises(ValidationError):
        Encounter("not-a-patient")
    with pytest.raises(ValidationError):
        ClinicalRecord("not-a-patient", "fact")
    with pytest.raises(ValidationError):
        ResultReport("not-a-patient", "result")
    with pytest.raises(ValidationError):
        Prescription("not-a-patient", "instruction")


# Family / Delegation / Authorization

def test_family_delegation_and_grant_remain_three_distinct_boundaries():
    a, b = Person(), Person()
    family = FamilyRelationship(a.id, b.id, "family")
    delegation = Delegation(a.id, b.id, "care")
    grant = AuthorizationGrant(b.id, "read", "clinical")

    assert len({family.id, delegation.id, grant.id}) == 3
    assert delegation.delegator_id == a.id
    assert grant.subject_id == b.id
    assert not hasattr(family, "authorization_grant_id")
    assert not hasattr(delegation, "authorization_grant_id")
    assert not hasattr(grant, "membership_id")


def test_family_relationship_alone_does_not_create_authorization():
    a, b = Person(), Person()
    family = FamilyRelationship(a.id, b.id, "family")
    assert family.state.value == "active"
    assert not hasattr(family, "grant_id")
    assert not hasattr(family, "permission_role")


def test_role_assignment_is_not_authorization_grant():
    person = Person()
    activity = Activity("activity")
    membership = Membership(person.id, activity.id)
    role = RoleAssignment(membership.id, "participant")
    grant = AuthorizationGrant(person.id, "read", "scope")

    assert role.membership_id == membership.id
    assert grant.subject_id == person.id
    assert not hasattr(role, "authorization_grant_id")
    assert not hasattr(grant, "membership_id")


def test_authorization_adversarial_party_shapes_are_rejected():
    with pytest.raises(ValidationError):
        FamilyRelationship("a", uuid4(), "family")
    with pytest.raises(ValidationError):
        Delegation("a", uuid4(), "care")
    with pytest.raises(ValidationError):
        AuthorizationGrant("a", "read", "scope")
    with pytest.raises(ValidationError):
        Delegation(uuid4(), uuid4(), "")


# Agent / Approval / Audit

def test_agent_action_approval_execution_are_distinct():
    agent = Agent("assistant")
    action = AgentAction(agent.id, "review")
    approval = Approval(action.id)

    assert agent.id == action.agent_id
    assert approval.agent_action_id == action.id
    assert action.state == AgentActionState.PREPARED
    approval.approve()
    assert approval.state == ApprovalState.APPROVED
    assert action.state == AgentActionState.PREPARED


def test_gap_0001_approval_enforcement_is_explicitly_observed():
    agent = Agent("assistant")
    action = AgentAction(agent.id, "sensitive")
    assert action.state == AgentActionState.PREPARED

    action.execute()
    assert action.state == AgentActionState.EXECUTED


def test_gap_0003_authorization_grant_does_not_bind_agent_action():
    person = Person()
    grant = AuthorizationGrant(person.id, "execute", "finance")
    agent = Agent("assistant")
    action = AgentAction(agent.id, "execute")

    grant.activate()
    assert grant.state.value == "active"
    assert action.state == AgentActionState.PREPARED
    assert not hasattr(action, "authorization_grant_id")


def test_agent_is_not_user_or_domain_owner():
    person = Person()
    agent = Agent("assistant")
    action = AgentAction(agent.id, "review")

    assert agent.id != person.id
    assert not hasattr(agent, "person_id")
    assert not hasattr(agent, "financial_account_id")
    assert not hasattr(agent, "patient_context_id")
    assert not hasattr(action, "domain_truth")


def test_provenance_and_audit_are_not_domain_truth():
    action = AgentAction(Agent("assistant").id, "review")
    provenance = Provenance(str(action.id), "AgentAction")
    audit = AuditRecord("agent_action", str(action.id), actor_ref=action.id, result="observed")

    assert provenance.id != audit.id
    assert provenance.source_ref == str(action.id)
    assert audit.subject_ref == str(action.id)
    assert not hasattr(provenance, "clinical_record")
    assert not hasattr(audit, "financial_transaction")


# Offline / device / conflict

def test_device_is_not_domain_owner_and_pending_is_not_truth():
    person = Person()
    device = Device(person.id)
    pending = PendingOperation(device.id, "Finance", "payment-1")

    pending.submit()
    pending.accept()

    assert device.owner_ref == person.id
    assert pending.device_id == device.id
    assert pending.state.value == "accepted"
    assert not hasattr(device, "financial_account_id")
    assert not hasattr(pending, "financial_finality")
    assert not hasattr(pending, "domain_truth")


def test_conflict_is_not_third_truth():
    conflict = Conflict("Health", "local-1", "remote-1")
    conflict.review()
    conflict.mark_resolved_by_owner()

    assert conflict.state.value == "resolved"
    assert conflict.owner_domain == "Health"
    assert not hasattr(conflict, "winner")
    assert not hasattr(conflict, "domain_truth")


def test_pending_health_cannot_become_clinical_truth():
    device = Device(Person().id)
    pending = PendingOperation(device.id, "Health", "record-1")
    pending.accept()

    assert pending.owner_domain == "Health"
    assert pending.operation_ref == "record-1"
    assert not hasattr(pending, "clinical_truth")
    assert not hasattr(pending, "final_clinical_record")


def test_offline_replay_and_duplicate_detection_do_not_mutate_domain_truth():
    key = "operation-42"
    assert is_duplicate(key, [key]) is True
    assert is_duplicate(key, []) is False

    account = FinancialAccount(Person().id)
    transaction = FinancialTransaction(account.id, Decimal("10"))
    entry = LedgerEntry(account.id, Decimal("10"), transaction.id)
    before = Balance.derive(account.id, [entry])

    pending = PendingOperation(Device(Person().id).id, "Finance", key)
    pending.accept()
    after = Balance.derive(account.id, [entry])

    assert before.amount == after.amount == Decimal("10")
    assert pending.state.value == "accepted"


def test_offline_adversarial_reference_shapes_are_rejected():
    with pytest.raises(ValidationError):
        Device("not-an-owner")
    with pytest.raises(ValidationError):
        PendingOperation("not-a-device", "Finance", "op")
    with pytest.raises(ValidationError):
        Conflict("Finance", "same", "same")
    with pytest.raises(ValidationError):
        StateRecord("", "subject", "pending")


# Cross-domain invariants / forbidden concepts

def test_cross_domain_ownership_directions_remain_explicit():
    person = Person()
    access = AccessAccount(person.id)
    finance = FinancialAccount(person.id)
    patient = PatientContext(person.id)
    activity = Activity("activity")
    membership = Membership(person.id, activity.id)
    role = RoleAssignment(membership.id, "participant")
    agent = Agent("assistant")
    action = AgentAction(agent.id, "review")
    device = Device(person.id)

    assert access.person_id == person.id
    assert finance.person_id == person.id
    assert patient.person_id == person.id
    assert membership.person_id == person.id
    assert role.membership_id == membership.id
    assert action.agent_id == agent.id
    assert device.owner_ref == person.id
    assert not hasattr(finance, "access_account_id")
    assert not hasattr(patient, "financial_account_id")
    assert not hasattr(action, "financial_account_id")
    assert not hasattr(action, "clinical_record_id")
    assert not hasattr(device, "ledger_entry_id")


def test_forbidden_concepts_are_not_introduced_by_domain_objects():
    objects = [
        Person(), AccessAccount(uuid4()), FinancialAccount(uuid4()),
        Activity("a"), Organization("o"), Product("p"), Offering(uuid4()),
        Sale(uuid4(), uuid4()), PatientContext(uuid4()), Agent("a"), Device(uuid4()),
    ]
    forbidden = {
        "actor_id", "primary_account_id", "employee_id", "customer_id",
        "owner_id", "manager_id", "bank_account_id", "wallet_id", "currency",
        "diagnosis_id", "doctor_id", "medication_id", "health_account_id",
        "permission_role", "parent_id", "child_id", "guardian_id", "minor_id",
        "policy_engine_id", "workflow_engine_id", "orchestration_id",
    }
    for obj in objects:
        assert forbidden.isdisjoint(_field_names(obj))


def test_exception_helpers_keep_history_and_owner_domain_explicit():
    assert preserve_history_on_cancellation() is True
    assert preserve_original_on_reversal() is True

    from agent_core.domain_exceptions import reject, require_owner_domain_decision

    with pytest.raises(ValidationError):
        reject("")

    with pytest.raises(ValidationError):
        require_owner_domain_decision("Finance", None)
