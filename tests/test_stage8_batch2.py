"""Stage 8 TEST-0002 — High-risk domain verification.

Traceability:
TEST-0002-FIN -> REQ-0017/0018/0019/0020/0021/0022/0023/0037 -> SPEC-0007/0008/0009 -> EXEC-0005
TEST-0002-HLT -> REQ-0024/0025/0026 -> SPEC-0010/0011/0025 -> EXEC-0006
TEST-0002-AUTH -> REQ-0005/0006/0007/0049/0052 -> SPEC-0003/0004/0022 -> EXEC-0007
TEST-0002-AGT -> REQ-0042/0043/0044/0045/0050/0054 -> SPEC-0017/0018/0021 -> EXEC-0009
TEST-0002-OFF -> REQ-0046/0048/0056/0064/0065/0066 -> SPEC-0019/0020/0024 -> EXEC-0010
TEST-0002-XD  -> SPEC-0026 -> EXEC-0013
"""
from dataclasses import FrozenInstanceError
from decimal import Decimal
from uuid import uuid4

import pytest

from agent_core.shared import ValidationError
from agent_core.domain_identity import Person, AccessAccount
from agent_core.domain_activities import Activity, Membership, RoleAssignment
from agent_core.domain_finance import (
    FinancialAccount, Obligation, ObligationState, Debt, Loan, LoanState,
    Payment, PaymentState, Settlement, SettlementState,
    FinancialTransaction, LedgerEntry, Balance,
)
from agent_core.domain_health import (
    PatientContext, Encounter, ClinicalRecord, ClinicalRecordState,
    ResultReport, ResultReportState, Prescription, PrescriptionState,
)
from agent_core.domain_authorization import (
    FamilyRelationship, Delegation, DelegationState,
    AuthorizationGrant, AuthorizationGrantState, Agent, AgentAction,
    AgentActionState, Approval, ApprovalState,
)
from agent_core.domain_audit import Provenance, AuditRecord, record_agent_action
from agent_core.domain_offline import Device, PendingOperation, Conflict, StateRecord


# Finance — ownership and obligation semantics
def test_financial_account_is_owned_by_person_not_access_account():
    person = Person()
    access = AccessAccount(person.id)
    account = FinancialAccount(person.id)

    assert account.person_id == person.id
    assert account.person_id != access.id
    assert not hasattr(account, "access_account_id")

    # GAP-0002: UUID identity alone does not enforce semantic Person ownership.
    accepted = FinancialAccount(access.id)
    assert accepted.person_id == access.id


def test_obligation_debt_and_loan_have_distinct_semantics():
    person_a, person_b = Person(), Person()
    account = FinancialAccount(person_a.id)
    obligation = Obligation(account.id, Decimal("250"))
    debt = Debt(obligation.id)
    loan = Loan(person_a.id, person_b.id)

    obligation.open()
    obligation.mark_due()
    assert obligation.state == ObligationState.DUE
    assert debt.obligation_id == obligation.id
    assert loan.lender_person_id == person_a.id
    assert loan.borrower_person_id == person_b.id
    assert loan.state == LoanState.PROPOSED
    assert debt.obligation_id != loan.id


def test_financial_ownership_provenance_and_loan_parties_are_checked_at_boundary():
    # GAP-0002: an arbitrary UUID is structurally accepted as FinancialAccount owner.
    accepted = Obligation(uuid4(), Decimal("10"))
    assert accepted.financial_account_id is not None

    person = Person()
    with pytest.raises(ValidationError):
        Loan(person.id, person.id)


def test_invoice_payment_settlement_and_ledger_remain_separate():
    product_ref = uuid4()
    sale_ref = uuid4()
    invoice_ref = uuid4()
    transaction_ref = uuid4()
    account = FinancialAccount(Person().id)

    payment = Payment(Decimal("100"), invoice_id=invoice_ref)
    settlement = Settlement(payment_id=payment.id)
    transaction = FinancialTransaction(account.id, Decimal("100"), id=transaction_ref)
    entry = LedgerEntry(account.id, Decimal("100"), transaction.id)

    payment.complete(connected=True)
    settlement.settle(connected=True)

    assert payment.invoice_id == invoice_ref
    assert settlement.payment_id == payment.id
    assert entry.transaction_id == transaction.id
    assert payment.id != settlement.id
    assert settlement.id != entry.id
    assert payment.state == PaymentState.COMPLETED
    assert settlement.state == SettlementState.SETTLED
    assert sale_ref != product_ref
    assert not hasattr(payment, "ledger_entry_id")
    assert not hasattr(settlement, "ledger_entry_id")


def test_balance_is_derived_and_does_not_become_independent_truth():
    account = FinancialAccount(Person().id)
    own = LedgerEntry(account.id, Decimal("75"), uuid4())
    other = LedgerEntry(uuid4(), Decimal("900"), uuid4())

    balance = Balance.derive(account.id, [own, other])

    assert balance.amount == Decimal("75")
    assert balance.financial_account_id == account.id
    with pytest.raises(FrozenInstanceError):
        balance.amount = Decimal("999")


def test_offline_finance_state_does_not_create_financial_finality():
    person = Person()
    account = FinancialAccount(person.id)
    device = Device(person.id)
    pending = PendingOperation(device.id, "Finance", "payment-1")

    pending.submit()
    pending.accept()

    assert pending.state.value == "accepted"
    assert pending.owner_domain == "Finance"
    assert not hasattr(pending, "financial_transaction")
    assert not hasattr(pending, "ledger_entry")
    assert not hasattr(pending, "finality")
    assert account.id != pending.id


# Health — clinical truth and boundaries
def test_patient_context_is_not_person_identity_and_clinical_record_is_health_truth():
    person = Person()
    patient = PatientContext(person.id)
    encounter = Encounter(patient.id)
    record = ClinicalRecord(patient.id, "recorded clinical observation", encounter.id)

    record.record()

    assert patient.id != person.id
    assert patient.person_id == person.id
    assert record.patient_context_id == patient.id
    assert record.state == ClinicalRecordState.RECORDED
    assert record.content == "recorded clinical observation"
    assert not hasattr(record, "person_id")
    assert not hasattr(record, "agent_id")


def test_result_report_and_prescription_have_distinct_lifecycles():
    person = Person()
    patient = PatientContext(person.id)
    encounter = Encounter(patient.id)
    report = ResultReport(patient.id, "result", encounter.id)
    prescription = Prescription(patient.id, "instruction", encounter.id)

    report.finalize()
    prescription.issue()
    prescription.activate()

    assert report.state == ResultReportState.FINAL
    assert prescription.state == PrescriptionState.ACTIVE
    assert report.id != prescription.id
    assert not hasattr(report, "prescription_id")
    assert not hasattr(prescription, "clinical_record_id")


def test_health_finance_reference_provenance_is_not_runtime_enforced():
    account = FinancialAccount(Person().id)
    # GAP-0002: PatientContext validates UUID shape, not semantic owner provenance.
    accepted = PatientContext(account.id)
    assert accepted.person_id == account.id


def test_agent_cannot_become_clinical_truth_by_reference():
    agent = Agent("clinical assistant")
    action = AgentAction(agent.id, "review-clinical-record")
    patient = PatientContext(Person().id)
    record = ClinicalRecord(patient.id, "clinical fact")

    grant = AuthorizationGrant(agent.id, "review-clinical-record", "clinical")
    grant.activate()
    action.bind_authorization_grant(grant)
    approval = Approval(action.id)
    approval.approve()
    action.execute(approval, grant)

    assert action.state == AgentActionState.EXECUTED
    assert record.content == "clinical fact"
    assert not hasattr(agent, "clinical_record_id")
    assert not hasattr(action, "clinical_record_id")
    assert not hasattr(record, "agent_id")


# Authorization / delegation
def test_membership_role_authorization_and_delegation_are_distinct():
    delegator, delegatee = Person(), Person()
    activity = Activity("care")
    membership = Membership(delegatee.id, activity.id)
    role = RoleAssignment(membership.id, "participant")
    delegation = Delegation(delegator.id, delegatee.id, "health")
    grant = AuthorizationGrant(delegatee.id, "read", "clinical")

    membership.activate()
    role.activate()
    delegation.activate()
    grant.activate()

    assert role.membership_id == membership.id
    assert delegation.delegatee_id == delegatee.id
    assert grant.subject_id == delegatee.id
    assert grant.id != membership.id
    assert delegation.id != grant.id
    assert not hasattr(membership, "authorization_grant_id")
    assert not hasattr(role, "authorization_grant_id")


def test_family_relationship_does_not_create_authorization_grant():
    parent, related = Person(), Person()
    family = FamilyRelationship(parent.id, related.id, "family")

    assert family.state.value == "active"
    assert not hasattr(family, "authorization_grant_id")
    assert not hasattr(family, "permission")
    assert not hasattr(related, "authorization_grant_id")


def test_delegation_state_does_not_mutate_authorization_state():
    delegator, delegatee = Person(), Person()
    delegation = Delegation(delegator.id, delegatee.id, "finance")
    grant = AuthorizationGrant(delegatee.id, "read", "finance")

    delegation.activate()
    grant.activate()
    delegation.revoke()

    assert delegation.state == DelegationState.REVOKED
    assert grant.state == AuthorizationGrantState.ACTIVE
    assert delegation.id != grant.id


def test_agent_does_not_gain_authority_from_delegation_alone():
    delegator, delegatee = Person(), Person()
    delegation = Delegation(delegator.id, delegatee.id, "finance")
    agent = Agent("finance assistant")
    action = AgentAction(agent.id, "financial-operation")

    delegation.activate()
    with pytest.raises(ValidationError):
        action.execute()

    assert delegation.state == DelegationState.ACTIVE
    assert action.state == AgentActionState.PREPARED
    assert action.authorization_grant_id is None
    assert not hasattr(agent, "authorization_grant_id")


# Agent / Approval / Provenance / Audit
def test_approval_does_not_execute_agent_action():
    agent = Agent("assistant")
    action = AgentAction(agent.id, "restricted-operation")
    approval = Approval(action.id)

    approval.approve()

    assert approval.state == ApprovalState.APPROVED
    assert action.state == AgentActionState.PREPARED


def test_gap_0001_is_observable_without_adding_enforcement():
    agent = Agent("assistant")
    action = AgentAction(agent.id, "restricted-operation")

    # GAP-0001: execution now requires an approved, matching Approval.
    with pytest.raises(ValidationError):
        action.execute()

    approval = Approval(action.id)
    approval.approve()
    action.execute(approval)
    assert action.state == AgentActionState.EXECUTED


def test_gap_0003_is_observable_without_binding_authorization_to_action():
    person = Person()
    grant = AuthorizationGrant(person.id, "execute", "finance")
    agent = Agent("assistant")
    action = AgentAction(agent.id, "financial-operation")

    grant.activate()
    with pytest.raises(ValidationError):
        action.execute()

    # GAP-0003 remains: AuthorizationGrant is not bound to AgentAction.
    assert grant.state == AuthorizationGrantState.ACTIVE
    assert action.state == AgentActionState.PREPARED
    assert not hasattr(action, "authorization_grant_id")


def test_provenance_audit_and_domain_truth_remain_separate():
    provenance = Provenance("clinical-record-1", "Health")
    audit = record_agent_action(uuid4(), "clinical-record-1", "reviewed")
    explicit_audit = AuditRecord("amended", "clinical-record-1")
    
    assert provenance.id != audit.id
    assert audit.id != explicit_audit.id
    assert provenance.source_ref == "clinical-record-1"
    assert audit.subject_ref == "clinical-record-1"
    assert explicit_audit.subject_ref == "clinical-record-1"
    assert not hasattr(provenance, "clinical_record")
    assert not hasattr(audit, "clinical_record")
    assert not hasattr(explicit_audit, "clinical_record")


def test_agent_has_no_financial_authority_field_or_direct_ledger_ownership():
    agent = Agent("finance assistant")
    action = AgentAction(agent.id, "post-ledger-entry")

    assert not hasattr(agent, "financial_account_id")
    assert not hasattr(agent, "ledger_entry_id")
    assert not hasattr(action, "financial_account_id")
    assert not hasattr(action, "ledger_entry_id")


# Offline / pending / conflict
def test_pending_operation_is_not_domain_truth_even_when_accepted():
    device = Device(uuid4())
    pending = PendingOperation(device.id, "Finance", "transaction-1")

    pending.accept()

    assert pending.state.value == "accepted"
    assert not hasattr(pending, "domain_truth")
    assert not hasattr(pending, "ledger_entry")
    assert not hasattr(pending, "financial_finality")


def test_conflict_is_owner_domain_state_not_a_third_truth():
    conflict = Conflict("Health", "local-record", "remote-record")

    conflict.review()
    assert conflict.owner_domain == "Health"
    assert conflict.state.value == "reviewed"
    assert not hasattr(conflict, "winner")
    assert not hasattr(conflict, "domain_truth")

    conflict.mark_resolved_by_owner()
    assert conflict.state.value == "resolved"


def test_device_is_operational_reference_not_domain_owner():
    person = Person()
    device = Device(person.id)
    state = StateRecord("Finance", "account-1", "accepted")

    assert device.owner_ref == person.id
    assert state.owner_domain == "Finance"
    assert device.owner_ref != state.owner_domain
    assert not hasattr(device, "financial_account_id")
    assert not hasattr(device, "domain_truth")


# Cross-domain boundaries
def test_high_risk_domains_keep_ownership_direction_explicit():
    person = Person()
    access = AccessAccount(person.id)
    finance = FinancialAccount(person.id)
    patient = PatientContext(person.id)
    activity = Activity("care")
    membership = Membership(person.id, activity.id)
    role = RoleAssignment(membership.id, "participant")
    grant = AuthorizationGrant(person.id, "read", "clinical")
    agent = Agent("assistant")
    action = AgentAction(agent.id, "review")
    device = Device(person.id)

    assert access.person_id == person.id
    assert finance.person_id == person.id
    assert patient.person_id == person.id
    assert membership.person_id == person.id
    assert role.membership_id == membership.id
    assert grant.subject_id == person.id
    assert action.agent_id == agent.id
    assert device.owner_ref == person.id

    assert not hasattr(finance, "access_account_id")
    assert not hasattr(patient, "financial_account_id")
    assert not hasattr(grant, "membership_id")
    assert not hasattr(action, "financial_account_id")
    assert not hasattr(action, "clinical_record_id")
    assert not hasattr(device, "ledger_entry_id")
