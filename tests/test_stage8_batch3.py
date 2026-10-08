from datetime import datetime
"""Stage 8 TEST-0003 — End-to-end scenarios and lifecycle verification.

Traceability:
TEST-0003-01 -> REQ-0001/0003/0008/0009/0010 -> SPEC-0001/0002/0003 -> EXEC-0002/0003
TEST-0003-02 -> REQ-0013/0014/0015/0016/0037/0038 -> SPEC-0005/0006/0015 -> EXEC-0004/0011
TEST-0003-03 -> REQ-0017/0018/0019/0020/0021/0022/0023/0037 -> SPEC-0007/0008/0009 -> EXEC-0005
TEST-0003-04 -> REQ-0024/0025/0026 -> SPEC-0010/0011/0025 -> EXEC-0006
TEST-0003-05 -> REQ-0005/0006/0007/0049/0052 -> SPEC-0003/0004/0022 -> EXEC-0007
TEST-0003-06 -> REQ-0042/0043/0044/0045/0050/0054 -> SPEC-0017/0018/0021 -> EXEC-0009
TEST-0003-07 -> REQ-0046/0048/0056/0064/0065/0066 -> SPEC-0019/0020/0024 -> EXEC-0010
TEST-0003-08 -> REQ-0030/0031/0032 -> SPEC-0013/0026 -> EXEC-0008
TEST-0003-09 -> REQ-0027/0028/0029 -> SPEC-0012/0025 -> EXEC-0012
TEST-0003-10 -> REQ-0041/0050/0051/0061/0062/0063 -> SPEC-0021/0023/0024/0026 -> EXEC-0013
"""
from dataclasses import FrozenInstanceError
from decimal import Decimal
from uuid import uuid4

import pytest

from agent_core.shared import ValidationError
from agent_core.domain_identity import Person, AccessAccount
from agent_core.domain_activities import Activity, Membership, RoleAssignment
from agent_core.domain_inventory import Product, Offering, InventoryPosition, Availability, AvailabilityState
from agent_core.domain_commerce import Sale, SaleState, Invoice, InvoiceState
from agent_core.domain_finance import (
    FinancialAccount, Obligation, ObligationState, Payment, PaymentState,
    Settlement, SettlementState, FinancialTransaction, LedgerEntry, Balance,
)
from agent_core.domain_health import PatientContext, Encounter, ClinicalRecord, ResultReport, Prescription
from agent_core.domain_authorization import (
    FamilyRelationship, Delegation, AuthorizationGrant, Agent, AgentAction,
    AgentActionState, Approval, ApprovalState,
)
from agent_core.domain_audit import Provenance, AuditRecord, record_agent_action
from agent_core.domain_offline import Device, PendingOperation, Conflict, StateRecord
from agent_core.domain_communication import Conversation, Message, MessageState
from agent_core.domain_education import validate_education_context, education_access_is_authorized
from agent_core.domain_exceptions import is_duplicate, preserve_history_on_cancellation, preserve_original_on_reversal


def test_identity_to_activity_to_membership_role_lifecycle():
    person = Person()
    activity = Activity("field activity")
    membership = Membership(person.id, activity.id)
    role = RoleAssignment(membership.id, "participant")

    # GAP-0002: RoleAssignment validates UUID shape, not Membership existence.
    accepted = RoleAssignment(uuid4(), "participant")
    assert accepted.membership_id is not None

    with pytest.raises(ValidationError):
        RoleAssignment(membership.id, "")

    membership.activate()
    role.activate()
    assert membership.state.value == "active"
    assert role.state.value == "active"
    role.revoke()
    membership.end_membership()
    assert role.state.value == "revoked"
    assert membership.state.value == "ended"
    assert person.id == membership.person_id
    assert role.membership_id == membership.id


def test_product_offering_inventory_availability_sale_end_to_end():
    product = Product("product")
    offering = Offering(product.id, uuid4())
    inventory = InventoryPosition(offering.id, uuid4(), scope_key="default")

    product.activate()
    offering.activate()
    availability = Availability(offering.id, AvailabilityState.COMPUTED, datetime.utcnow())
    sale = Sale(offering.id, uuid4())

    with pytest.raises(ValidationError):
        sale.complete()

    sale.confirm()
    sale.complete()
    assert product.state.value == "active"
    assert offering.state.value == "active"
    assert inventory.offering_id == offering.id
    assert availability.offering_id == offering.id
    assert sale.state == SaleState.COMPLETED


def test_commerce_duplicate_boundary_and_cancellation_preserve_history():
    product = Product("product")
    offering = Offering(product.id, uuid4())
    sale = Sale(offering.id, uuid4())
    invoice = Invoice(sale.id)

    assert is_duplicate(str(sale.id), [str(sale.id)]) is True
    assert is_duplicate("new-operation", [str(sale.id)]) is False
    invoice.void()
    sale.cancel()

    assert invoice.state == InvoiceState.VOID
    assert sale.state == SaleState.CANCELLED
    assert invoice.sale_id == sale.id
    assert preserve_history_on_cancellation() is True


def test_sale_invoice_finance_lifecycle_to_derived_balance():
    person = Person()
    account = FinancialAccount(person.id)
    product = Product("product")
    offering = Offering(product.id, uuid4())
    sale = Sale(offering.id, uuid4())
    sale.confirm()
    invoice = Invoice(sale.id)
    obligation = Obligation(account.id, Decimal("125"))
    obligation.open()
    payment = Payment(Decimal("125"), obligation_id=obligation.id, invoice_id=invoice.id)
    payment.complete(connected=True)
    settlement = Settlement(payment_id=payment.id)
    settlement.settle(connected=True)
    transaction = FinancialTransaction(account.id, Decimal("125"))
    entry = LedgerEntry._from_finance(account.id, Decimal("125"), transaction.id)
    balance = Balance.derive(account.id, [entry])

    assert invoice.sale_id == sale.id
    assert obligation.financial_account_id == account.id
    assert payment.obligation_id == obligation.id
    assert settlement.payment_id == payment.id
    assert entry.transaction_id == transaction.id
    assert balance.amount == Decimal("125")
    assert not hasattr(balance, "ledger_entries")


def test_finance_invalid_transition_and_reversal_keep_original_reference():
    person = Person()
    account = FinancialAccount(person.id)
    obligation = Obligation(account.id, Decimal("50"))
    payment = Payment(Decimal("50"), obligation_id=obligation.id)
    settlement = Settlement(payment_id=payment.id)

    with pytest.raises(ValidationError):
        Obligation("not-a-financial-account", Decimal("50"))

    payment.cancel()
    settlement.reverse()
    assert payment.state == PaymentState.CANCELLED
    assert settlement.state == SettlementState.REVERSED
    assert settlement.payment_id == payment.id
    assert preserve_original_on_reversal() is True


def test_health_full_lifecycle_preserves_clinical_truth_references():
    person = Person()
    patient = PatientContext(person.id)
    encounter = Encounter(patient.id)
    record = ClinicalRecord(patient.id, "initial clinical finding", encounter.id)
    report = ResultReport(patient.id, "laboratory result", encounter.id)
    prescription = Prescription(patient.id, "treatment instruction", encounter.id)

    encounter.start()
    record.record()
    report.finalize()
    prescription.issue()
    prescription.activate()

    record.correct()
    report.amend()
    prescription.amend()

    assert patient.person_id == person.id
    assert record.content == "initial clinical finding"
    assert report.content == "laboratory result"
    assert record.patient_context_id == patient.id
    assert report.patient_context_id == patient.id
    assert prescription.patient_context_id == patient.id
    assert record.state.value == "corrected"
    assert report.state.value == "amended"
    assert prescription.state.value == "amended"


def test_health_missing_reference_and_no_finance_ownership():
    person = Person()
    with pytest.raises(ValidationError):
        Encounter("not-a-patient-context")
    patient = PatientContext(person.id)
    record = ClinicalRecord(patient.id, "fact")
    finance = FinancialAccount(person.id)

    assert record.patient_context_id == patient.id
    assert not hasattr(record, "financial_account_id")
    assert not hasattr(patient, "financial_account_id")
    assert finance.person_id == person.id


def test_family_delegation_authorization_action_lifecycle_without_extra_enforcement():
    delegator, delegatee = Person(), Person()
    family = FamilyRelationship(delegator.id, delegatee.id, "family")
    delegation = Delegation(delegator.id, delegatee.id, "care")
    grant = AuthorizationGrant(delegatee.id, "review", "clinical")
    agent = Agent("assistant")
    action = AgentAction(agent.id, "review")
    approval = Approval(action.id)

    delegation.activate()
    grant.activate()
    approval.approve()

    # GAP-0001 approval alone cannot bypass the now-authoritative GAP-0003 Grant binding.
    assert approval.state == ApprovalState.APPROVED
    assert action.state == AgentActionState.PREPARED
    assert family.person_a_id == delegator.id
    assert delegation.delegatee_id == delegatee.id
    assert grant.subject_id == delegatee.id

    with pytest.raises(ValidationError):
        action.execute(approval)
    assert action.state == AgentActionState.PREPARED


def test_authorization_cancellation_does_not_rewrite_family_or_history():
    a, b = Person(), Person()
    family = FamilyRelationship(a.id, b.id, "family")
    delegation = Delegation(a.id, b.id, "finance")
    grant = AuthorizationGrant(b.id, "read", "finance")

    delegation.activate()
    grant.activate()
    delegation.revoke()
    grant.revoke()

    assert family.state.value == "active"
    assert delegation.state.value == "revoked"
    assert grant.state.value == "revoked"
    assert family.person_a_id == a.id
    assert delegation.delegator_id == a.id
    assert grant.subject_id == b.id


def test_agent_execution_provenance_and_audit_follow_action_without_becoming_truth():
    person = Person()
    agent = Agent("assistant")
    action = AgentAction(agent.id, "review")
    approval = Approval(action.id)
    approval.approve()
    grant = AuthorizationGrant(agent.id, "review", "review-scope")
    grant.activate()
    action.bind_authorization_grant(grant)
    action.execute(approval, grant)
    provenance = Provenance(str(action.id), "AgentAction")
    audit = record_agent_action(action.id, str(person.id), "executed")

    assert action.state == AgentActionState.EXECUTED
    assert approval.agent_action_id == action.id
    assert provenance.source_ref == str(action.id)
    assert audit.actor_ref == action.id
    assert audit.subject_ref == str(person.id)
    assert not hasattr(agent, "person_id")
    assert not hasattr(action, "domain_truth")


def test_offline_pending_conflict_acceptance_stops_at_domain_boundary():
    person = Person()
    device = Device(person.id)
    pending = PendingOperation(device.id, "Finance", "payment-1")
    conflict = Conflict("Finance", "local-payment", "remote-payment")
    state = StateRecord("Finance", "payment-1", "pending")

    pending.submit()
    conflict.review()
    pending.accept()
    conflict.mark_resolved_by_owner()

    assert pending.state.value == "accepted"
    assert conflict.state.value == "resolved"
    assert state.owner_domain == "Finance"
    assert state.subject_ref == "payment-1"
    assert not hasattr(pending, "financial_finality")
    assert not hasattr(conflict, "domain_truth")
    assert not hasattr(device, "financial_account_id")


def test_offline_missing_relationship_and_duplicate_boundary():
    with pytest.raises(ValidationError):
        PendingOperation("not-a-device", "Finance", "payment-1")
    assert is_duplicate("payment-1", ["payment-1"]) is True
    assert is_duplicate("payment-2", ["payment-1"]) is False


def test_conversation_message_lifecycle_keeps_ownership_local():
    conversation = Conversation()
    message = Message(conversation.id, "hello")

    with pytest.raises(ValidationError):
        Message("not-a-conversation", "hello")

    message.send()
    message.revoke()
    conversation.close()

    assert message.conversation_id == conversation.id
    assert message.state == MessageState.REVOKED
    assert conversation.state.value == "closed"
    assert not hasattr(message, "authorization_grant_id")
    assert not hasattr(conversation, "financial_account_id")


def test_education_context_reuses_approved_concepts_only():
    person = Person()
    activity = Activity("education context")
    membership = Membership(person.id, activity.id)
    role = RoleAssignment(membership.id, "participant")
    family = FamilyRelationship(person.id, Person().id, "family")
    grant = AuthorizationGrant(person.id, "read", "education")

    membership.activate()
    role.activate()
    grant.activate()

    assert validate_education_context(person.id, membership, role, family, grant) is True
    assert education_access_is_authorized(grant) is True
    assert not hasattr(person, "student_id")
    assert not hasattr(activity, "course_id")


def test_education_invalid_relationship_does_not_cross_domain():
    person = Person()
    other = Person()
    activity = Activity("education")
    membership = Membership(person.id, activity.id)
    other_membership = Membership(other.id, activity.id)

    with pytest.raises(ValidationError):
        validate_education_context(person.id, other_membership)
    assert membership.person_id == person.id


def test_cross_domain_correction_does_not_delete_original_content():
    patient = PatientContext(Person().id)
    record = ClinicalRecord(patient.id, "original")
    report = ResultReport(patient.id, "original result")

    record.record()
    report.finalize()
    original_record_id = record.id
    original_report_id = report.id
    record.correct()
    report.correct()

    assert record.id == original_record_id
    assert report.id == original_report_id
    assert record.content == "original"
    assert report.content == "original result"
    assert record.state.value == "corrected"
    assert report.state.value == "corrected"


def test_cross_domain_cancellation_reversal_do_not_erase_source_references():
    person = Person()
    account = FinancialAccount(person.id)
    obligation = Obligation(account.id, Decimal("75"))
    payment = Payment(Decimal("75"), obligation_id=obligation.id)
    settlement = Settlement(payment_id=payment.id)
    invoice = Invoice(uuid4())

    obligation.cancel()
    payment.cancel()
    settlement.reverse()
    invoice.void()

    assert obligation.state == ObligationState.CANCELLED
    assert payment.state == PaymentState.CANCELLED
    assert settlement.state == SettlementState.REVERSED
    assert invoice.state == InvoiceState.VOID
    assert obligation.financial_account_id == account.id
    assert payment.obligation_id == obligation.id
    assert settlement.payment_id == payment.id
    assert invoice.sale_id is not None


def test_balance_source_of_truth_remains_ledger_entries_after_lifecycle_changes():
    account = FinancialAccount(Person().id)
    transaction = FinancialTransaction(account.id, Decimal("100"))
    entry = LedgerEntry._from_finance(account.id, Decimal("100"), transaction.id)
    balance_before = Balance.derive(account.id, [entry])

    payment = Payment(Decimal("100"))
    payment.cancel()
    settlement = Settlement(payment_id=payment.id)
    settlement.reverse()
    balance_after = Balance.derive(account.id, [entry])

    assert balance_before.amount == Decimal("100")
    assert balance_after.amount == Decimal("100")
    assert balance_after.financial_account_id == account.id
    with pytest.raises(FrozenInstanceError):
        balance_after.amount = Decimal("0")


def test_end_to_end_missing_required_relationships_are_rejected_at_structural_boundary():
    with pytest.raises(ValidationError):
        Product("")
    with pytest.raises(ValidationError):
        Offering("not-a-product", uuid4())
    with pytest.raises(ValidationError):
        Sale("not-an-offering", uuid4())
    with pytest.raises(ValidationError):
        Invoice("not-a-sale")
    with pytest.raises(ValidationError):
        ClinicalRecord("not-a-patient", "fact")
    with pytest.raises(ValidationError):
        AuthorizationGrant("not-a-person", "read", "scope")
    with pytest.raises(ValidationError):
        AgentAction("not-an-agent", "action")
    with pytest.raises(ValidationError):
        Message("not-a-conversation", "message")


def test_end_to_end_source_ownership_does_not_cross_into_agent_or_offline():
    person = Person()
    access = AccessAccount(person.id)
    finance = FinancialAccount(person.id)
    patient = PatientContext(person.id)
    agent = Agent("assistant")
    action = AgentAction(agent.id, "review")
    device = Device(person.id)
    pending = PendingOperation(device.id, "Health", str(patient.id))

    assert access.person_id == person.id
    assert finance.person_id == person.id
    assert patient.person_id == person.id
    assert action.agent_id == agent.id
    assert device.owner_ref == person.id
    assert pending.owner_domain == "Health"
    assert pending.operation_ref == str(patient.id)
    assert not hasattr(agent, "financial_account_id")
    assert not hasattr(agent, "patient_context_id")
    assert not hasattr(device, "clinical_record_id")
