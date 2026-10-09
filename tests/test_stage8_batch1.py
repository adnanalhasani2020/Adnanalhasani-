from datetime import datetime
"""Stage 8 TEST-0001 — Core integration, regression and boundary verification.

Traceability:
TEST-0001-01 Identity: REQ-0001/0002/0004 -> SPEC-0001 -> EXEC-0002
TEST-0001-02 Activities: REQ-0003/0008/0009/0010 -> SPEC-0002/0003 -> EXEC-0003
TEST-0001-03 Commerce/Inventory: REQ-0013/0014/0015/0016/0037/0038 -> SPEC-0005/0006/0015 -> EXEC-0011/0004
TEST-0001-04 Finance: REQ-0017-0023/0037 -> SPEC-0007/0008/0009 -> EXEC-0005
TEST-0001-05 Health: REQ-0024-0026 -> SPEC-0010/0011/0025 -> EXEC-0006
TEST-0001-06 Family/Auth: REQ-0005-0007/0028/0049/0052 -> SPEC-0003/0004/0022 -> EXEC-0007
TEST-0001-07 Communication: REQ-0030-0032 -> SPEC-0013/0026 -> EXEC-0008
TEST-0001-08 Agent/Audit: REQ-0026/0042-0045/0050/0054 -> SPEC-0011/0017/0018/0021 -> EXEC-0009
TEST-0001-09 Offline: REQ-0046/0048/0056/0064-0066 -> SPEC-0019/0020/0024 -> EXEC-0010
TEST-0001-10 Education: REQ-0027-0029 -> SPEC-0012/0025 -> EXEC-0012
TEST-0001-11 Exceptions: REQ-0041/0050/0051/0061-0063 -> SPEC-0021/0023/0024/0026 -> EXEC-0013
TEST-0001-12 Cross-cutting regression -> SPEC-0026 -> EXEC-0013
"""
from decimal import Decimal
from uuid import uuid4

import pytest

from agent_core.shared import ValidationError
from agent_core.domain_identity import Person, Identifier, AccessAccount, Session
from agent_core.domain_activities import Activity, Organization, Membership, RoleAssignment
from agent_core.domain_inventory import Product, Offering, InventoryPosition, Availability, AvailabilityState, Service
from agent_core.domain_commerce import Sale, Invoice, SaleState
from agent_core.domain_finance import FinancialAccount, Payment, Settlement, FinancialTransaction, LedgerEntry, Balance
from agent_core.domain_health import PatientContext, Encounter, ClinicalRecord, Prescription
from agent_core.domain_authorization import (
    FamilyRelationship, Delegation, AuthorizationGrant, AuthorityPolicy,
    Agent, AgentAction, Approval,
)
from agent_core.domain_communication import Conversation, Message, ChannelContext
from agent_core.domain_audit import Provenance, AuditRecord, record_agent_action
from agent_core.domain_offline import Device, PendingOperation, Conflict, StateRecord
from agent_core.domain_education import validate_education_context, education_access_is_authorized
from agent_core.domain_exceptions import reject, is_duplicate, require_owner_domain_decision


# TEST-0001-01 — Identity / Access / Session
def test_identity_access_session_integration_preserves_ownership():
    person = Person()
    identifier = Identifier(person.id, "national-id")
    account = AccessAccount(person.id)
    session = Session(account.id)
    session.activate()

    assert identifier.person_id == person.id
    assert account.person_id == person.id
    assert session.access_account_id == account.id
    assert not hasattr(person, "financial_account_id")
    assert not hasattr(account, "financial_account_id")
    assert not hasattr(session, "person_id")


# TEST-0001-02 — Activities
def test_activity_membership_role_chain_does_not_become_authorization():
    person = Person()
    activity = Activity("school")
    organization = Organization("Org")
    membership = Membership(person.id, activity.id)
    role = RoleAssignment(membership.id, "participant")
    membership.activate()
    role.activate()

    assert membership.person_id == person.id
    assert membership.activity_id == activity.id
    assert role.membership_id == membership.id
    assert organization.id != activity.id
    assert not hasattr(membership, "authorization_grant_id")
    assert not hasattr(role, "authorization")


# TEST-0001-03 — Commerce + Inventory
def test_product_offering_inventory_sale_invoice_integration_preserves_boundaries():
    product = Product("serviceable product")
    offering = Offering(product.id, uuid4())
    inventory = InventoryPosition(offering.id, uuid4(), scope_key="default")
    availability = Availability(offering.id, AvailabilityState.COMPUTED, datetime.utcnow())
    activity = Activity("sale context")
    sale = Sale(offering.id, activity.id)
    invoice = Invoice(sale.id)

    product.activate()
    offering.activate()
    sale.confirm()
    sale.complete()

    assert offering.product_id == product.id
    assert inventory.offering_id == offering.id
    assert availability.offering_id == offering.id
    assert sale.offering_id == offering.id
    assert invoice.sale_id == sale.id
    assert sale.state == SaleState.FULFILLED
    assert not hasattr(offering, "inventory_state")
    assert not hasattr(sale, "payment_id")
    assert not hasattr(invoice, "ledger_entry_id")


# TEST-0001-04 — Finance
def test_finance_chain_keeps_payment_settlement_transaction_ledger_distinct():
    person = Person()
    account = FinancialAccount(person.id)
    transaction = FinancialTransaction(account.id, Decimal("100"))
    entry = LedgerEntry._from_finance(account.id, Decimal("100"), transaction.id)
    payment = Payment(25)
    settlement = Settlement(payment.id, account.id)

    assert account.person_id == person.id
    assert transaction.financial_account_id == account.id
    assert entry.transaction_id == transaction.id
    assert settlement.payment_id == payment.id
    assert Balance.derive(account.id, [entry]).amount == Decimal("100")
    assert not hasattr(account, "access_account_id")
    assert not hasattr(payment, "ledger_entry_id")


# TEST-0001-05 — Health + Finance
def test_health_does_not_own_financial_truth():
    person = Person()
    patient = PatientContext(person.id)
    encounter = Encounter(patient.id)
    record = ClinicalRecord(patient.id, "clinical truth", encounter.id)
    finance = FinancialAccount(person.id)

    assert record.patient_context_id == patient.id
    assert finance.person_id == person.id
    assert not hasattr(patient, "financial_account_id")
    assert not hasattr(encounter, "financial_account_id")
    assert not hasattr(record, "financial_account_id")
    assert not hasattr(record, "agent_id")


# TEST-0001-06 — Family / Delegation / Authorization
def test_family_delegation_authorization_membership_are_not_interchangeable():
    person_a, person_b = uuid4(), uuid4()
    activity = Activity("family activity")
    membership = Membership(person_a, activity.id)
    family = FamilyRelationship(person_a, person_b, "family")
    delegation = Delegation(person_a, person_b, "health")
    grant = AuthorizationGrant(person_b, "read", "clinical")

    assert membership.person_id == person_a
    assert family.person_a_id == person_a
    assert delegation.delegatee_id == person_b
    assert grant.subject_id == person_b
    assert membership.id != grant.id
    assert not hasattr(membership, "authorization_grant_id")
    assert not hasattr(family, "authorization_grant_id")


# TEST-0001-07 — Communication
def test_communication_context_does_not_acquire_authority_or_domain_truth():
    conversation = Conversation()
    message = Message(conversation.id, "hello")
    channel = ChannelContext("voice")

    message.send()
    assert message.conversation_id == conversation.id
    assert channel.channel_type == "voice"
    assert not hasattr(conversation, "authorization")
    assert not hasattr(message, "financial_account_id")
    assert not hasattr(channel, "conversation_id")


# TEST-0001-08 — Agent / Approval / Audit
def test_agent_action_approval_audit_provenance_are_distinct():
    agent = Agent("assistant")
    action = AgentAction(agent.id, "review")
    approval = Approval(action.id)
    audit = record_agent_action(action.id, "clinical-record", "prepared")
    provenance = Provenance("clinical-record", "domain")

    approval.approve()
    assert action.state.value == "prepared"
    assert approval.state.value == "approved"
    assert audit.actor_ref == action.id
    assert provenance.source_ref == "clinical-record"
    assert not hasattr(agent, "domain_truth")
    assert not hasattr(approval, "execution_id")


# TEST-0001-09 — Offline
def test_offline_pending_conflict_state_records_never_become_domain_truth():
    device = Device(uuid4())
    pending = PendingOperation(device.id, "Finance", "operation-1")
    conflict = Conflict("Finance", "local-1", "remote-1")
    state = StateRecord("Finance", "account-1", "pending")

    pending.submit()
    conflict.review()
    assert pending.state.value == "submitted"
    assert conflict.state.value == "reviewed"
    assert state.owner_domain == "Finance"
    assert not hasattr(device, "ledger_entry_id")
    assert not hasattr(pending, "financial_transaction")
    assert not hasattr(conflict, "domain_truth")


# TEST-0001-10 — Education
def test_education_context_reuses_existing_activity_membership_role_authorization():
    person = Person()
    activity = Activity("education")
    membership = Membership(person.id, activity.id)
    role = RoleAssignment(membership.id, "participant")
    grant = AuthorizationGrant(person.id, "read", "education")
    membership.activate()
    role.activate()
    grant.activate()

    assert validate_education_context(person.id, membership, role, authorization=grant)
    assert education_access_is_authorized(grant)
    assert not hasattr(activity, "course_id")
    assert not hasattr(membership, "student_id")
    assert not hasattr(role, "classroom_id")


# TEST-0001-11 — Exceptions
def test_exception_boundaries_preserve_owner_decision_and_history():
    with pytest.raises(ValidationError):
        reject(" ")
    assert is_duplicate("op-1", ["op-1", "op-2"])
    assert not is_duplicate("op-3", ["op-1", "op-2"])
    with pytest.raises(ValidationError):
        require_owner_domain_decision("Finance", None)


# TEST-0001-12 — Cross-domain ownership
def test_cross_domain_refs_are_explicit_and_one_way():
    person = Person()
    account = AccessAccount(person.id)
    financial = FinancialAccount(person.id)
    patient = PatientContext(person.id)
    activity = Activity("care")
    encounter = Encounter(patient.id, activity_id=activity.id)

    assert account.person_id == person.id
    assert financial.person_id == person.id
    assert patient.person_id == person.id
    assert encounter.activity_id == activity.id
    assert not hasattr(financial, "access_account_id")
    assert not hasattr(patient, "financial_account_id")
    assert not hasattr(activity, "financial_account_id")


# Boundary / negative tests
def test_invalid_identity_to_finance_reference_is_rejected():
    with pytest.raises(ValidationError):
        FinancialAccount("access-account-id")


def test_invalid_commerce_to_inventory_reference_is_rejected():
    with pytest.raises(ValidationError):
        Offering("not-a-product", uuid4())
    with pytest.raises(ValidationError):
        InventoryPosition("not-an-offering", uuid4(), scope_key="default")


def test_invalid_health_to_finance_reference_is_rejected():
    with pytest.raises(ValidationError):
        PatientContext("financial-account-id")


def test_invalid_authorization_and_membership_reference_types_are_rejected():
    with pytest.raises(ValidationError):
        Membership("person", uuid4())
    with pytest.raises(ValidationError):
        AuthorizationGrant("person", "read", "scope")


def test_agent_does_not_expose_domain_truth_fields():
    agent = Agent("agent")
    action = AgentAction(agent.id, "financial-review")
    assert not hasattr(agent, "financial_account_id")
    assert not hasattr(agent, "clinical_record_id")
    assert not hasattr(action, "ledger_entry_id")
    assert not hasattr(action, "clinical_record_id")


def test_offline_objects_cannot_be_used_as_financial_truth_by_structure():
    device = Device(uuid4())
    pending = PendingOperation(device.id, "Finance", "payment-1")
    conflict = Conflict("Finance", "ledger-local", "ledger-remote")
    assert not hasattr(device, "balance")
    assert not hasattr(pending, "ledger_entry")
    assert not hasattr(conflict, "ledger_entry")


def test_approval_is_not_execution():
    action = AgentAction(uuid4(), "review")
    approval = Approval(action.id)
    approval.approve()
    assert approval.state.value == "approved"
    assert action.state.value == "prepared"


def test_authorization_is_not_membership():
    person = Person()
    grant = AuthorizationGrant(person.id, "read", "health")
    activity = Activity("health")
    membership = Membership(person.id, activity.id)
    assert grant.subject_id == membership.person_id
    assert grant.id != membership.id
    assert not hasattr(grant, "membership_id")


def test_invoice_payment_settlement_ledger_are_distinct():
    product = Product("P")
    offering = Offering(product.id, uuid4())
    sale = Sale(offering.id, Activity("A").id)
    invoice = Invoice(sale.id)
    payment = Payment(10, invoice_id=invoice.id)
    settlement = Settlement(payment.id)
    entry = LedgerEntry._from_finance(uuid4(), 10, uuid4())

    assert invoice.sale_id == sale.id
    assert payment.invoice_id == invoice.id
    assert settlement.payment_id == payment.id
    assert entry.transaction_id != payment.id
    assert not hasattr(invoice, "payment")
    assert not hasattr(payment, "settlement_id")
    assert not hasattr(settlement, "ledger_entry_id")


def test_education_cannot_reinterpret_family_as_authorization():
    person_a, person_b = uuid4(), uuid4()
    activity = Activity("education")
    membership = Membership(person_a, activity.id)
    family = FamilyRelationship(person_a, person_b, "family")
    assert validate_education_context(person_a, membership, family_relationship=family)
    assert education_access_is_authorized(None) is False


def test_conflict_requires_owner_resolution_not_local_assignment():
    conflict = Conflict("Health", "local", "remote")
    conflict.review()
    assert conflict.state.value == "reviewed"
    assert conflict.owner_domain == "Health"
    assert not hasattr(conflict, "winner")
    assert not hasattr(conflict, "domain_truth")


def test_pending_operation_is_not_finality():
    device = Device(uuid4())
    pending = PendingOperation(device.id, "Finance", "payment-2")
    pending.accept()
    assert pending.state.value == "accepted"
    assert not hasattr(pending, "finality")
    assert not hasattr(pending, "ledger_entry")


def test_provenance_is_not_domain_truth():
    provenance = Provenance("record-1", "Health")
    audit = AuditRecord("corrected", "record-1")
    assert provenance.id != audit.id
    assert provenance.source_type == "Health"
    assert not hasattr(provenance, "clinical_record")
    assert not hasattr(audit, "domain_truth")


def test_financial_balance_is_derived_from_ledger_entries_only():
    account = FinancialAccount(uuid4())
    first = LedgerEntry._from_finance(account.id, 20, uuid4())
    other = LedgerEntry._from_finance(uuid4(), 100, uuid4())
    balance = Balance.derive(account.id, [first, other])
    assert balance.amount == Decimal("20")
    assert balance.financial_account_id == account.id
