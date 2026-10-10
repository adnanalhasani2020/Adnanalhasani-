"""Stage 8 TEST-0005 — Reliability, edge cases and state consistency.

This batch is verification-only. It records current behavior where runtime
enforcement is intentionally absent; it does not add an idempotency engine,
identity registry, persistence, orchestration, workflow, policy, or new domain
concept.

Traceability is intentionally at the implemented-model level:
TEST-0005 -> existing Stage 5 concepts / Stage 6 specifications -> Stage 7 implementation.
"""
from dataclasses import FrozenInstanceError, fields
from decimal import Decimal
from datetime import datetime, timezone
from uuid import uuid4

import pytest

from agent_core.shared import ValidationError
from agent_core.domain_identity import Person, Identifier, AccessAccount, Session
from agent_core.domain_activities import Activity, Membership, RoleAssignment
from agent_core.domain_inventory import Product, Offering, InventoryPosition, Availability, AvailabilityState
from agent_core.domain_commerce import Sale, Invoice, SaleState, InvoiceState
from agent_core.domain_finance import (
    FinancialAccount, Obligation, Payment, Settlement, FinancialTransaction,
    LedgerEntry, Balance, PaymentState, SettlementState,
)
from agent_core.domain_health import PatientContext, Encounter, ClinicalRecord, ResultReport, Prescription
from agent_core.domain_authorization import Agent, AgentAction, Approval, AuthorizationGrant
from agent_core.domain_offline import Device, PendingOperation, Conflict, StateRecord
from agent_core.domain_audit import Provenance, AuditRecord
from agent_core.domain_exceptions import is_duplicate, preserve_history_on_cancellation, preserve_original_on_reversal


def _field_names(obj):
    return {f.name for f in fields(obj)}


# 01–05 — Identity, missing values, duplicate-person limitation, reuse and account separation.

def test_identity_required_values_are_rejected():
    with pytest.raises(ValidationError):
        Person("not-a-uuid")
    with pytest.raises(ValidationError):
        Identifier(uuid4(), "")
    with pytest.raises(ValidationError):
        Identifier(uuid4(), "   ")
    with pytest.raises(ValidationError):
        AccessAccount("not-a-person")
    with pytest.raises(ValidationError):
        Session("not-an-access-account")


def test_identity_identifier_whitespace_is_normalized_without_changing_owner():
    person = Person()
    identifier = Identifier(person.id, "  national-id  ")
    assert identifier.person_id == person.id
    assert identifier.value == "national-id"


def test_duplicate_person_creation_is_currently_permitted_and_is_gap_0006():
    first = Person()
    second = Person()
    assert first.id != second.id
    # GAP-0006: no runtime identity uniqueness/deduplication enforcement exists.
    assert first.state.value == "active"
    assert second.state.value == "active"


def test_same_person_can_be_reused_across_multiple_activities():
    person = Person()
    activity_a = Activity("care")
    activity_b = Activity("education")
    membership_a = Membership(person.id, activity_a.id)
    membership_b = Membership(person.id, activity_b.id)
    assert membership_a.person_id == person.id
    assert membership_b.person_id == person.id
    assert membership_a.id != membership_b.id


def test_person_access_account_and_financial_account_remain_distinct():
    person = Person()
    access = AccessAccount(person.id)
    finance = FinancialAccount(person.id)
    assert access.person_id == person.id
    assert finance.person_id == person.id
    assert access.id != finance.id
    assert "financial_account_id" not in _field_names(access)
    assert "access_account_id" not in _field_names(finance)


# 06–10 — Activity, references, temporal boundaries and current UUID-only limitation.

def test_activity_and_role_required_values_are_rejected():
    with pytest.raises(ValidationError):
        Activity("")
    with pytest.raises(ValidationError):
        Activity("   ")
    with pytest.raises(ValidationError):
        Membership("not-a-person", uuid4())
    with pytest.raises(ValidationError):
        RoleAssignment(uuid4(), "")


def test_invalid_cross_domain_reference_shapes_are_rejected():
    with pytest.raises(ValidationError):
        Offering("not-a-product", uuid4())
    with pytest.raises(ValidationError):
        InventoryPosition("not-an-offering", uuid4(), scope_key="default")
    with pytest.raises(ValidationError):
        Sale("not-an-offering", uuid4())
    with pytest.raises(ValidationError):
        FinancialAccount("not-a-person")
    with pytest.raises(ValidationError):
        PatientContext("not-a-person")


def test_membership_temporal_boundary_rejects_end_before_start():
    from datetime import datetime, timezone, timedelta
    start = datetime.now(timezone.utc)
    with pytest.raises(ValidationError):
        Membership(uuid4(), uuid4(), start=start, end=start - timedelta(seconds=1))


def test_role_temporal_boundary_rejects_expiry_before_effective():
    from datetime import datetime, timezone, timedelta
    effective = datetime.now(timezone.utc)
    with pytest.raises(ValidationError):
        RoleAssignment(uuid4(), "participant", effective=effective, expiry=effective - timedelta(seconds=1))


def test_role_uuid_reference_without_membership_existence_is_gap_0002():
    role = RoleAssignment(uuid4(), "participant")
    # GAP-0002: UUID shape is validated, Membership existence is not.
    assert isinstance(role.membership_id, type(uuid4()))


# 11–15 — Commerce and inventory lifecycle consistency.

def test_product_and_offering_boundary_values_are_enforced():
    with pytest.raises(ValidationError):
        Product("")
    with pytest.raises(ValidationError):
        Product("   ")
    with pytest.raises(ValidationError):
        Offering("not-a-product", uuid4())
    product = Product("minimum-valid-name")
    offering = Offering(product.id, uuid4())
    assert offering.product_id == product.id


def test_sale_requires_confirmation_before_completion():
    sale = Sale(uuid4(), uuid4())
    with pytest.raises(ValidationError):
        sale.complete()
    assert sale.state == SaleState.INITIATED


def test_repeated_confirmation_is_idempotent_in_state_but_not_a_replay_registry():
    sale = Sale(uuid4(), uuid4())
    sale.confirm()
    sale.confirm()
    assert sale.state == SaleState.CONFIRMED
    assert not hasattr(sale, "replay_registry")


def test_repeated_cancellation_preserves_sale_identity_and_state():
    sale = Sale(uuid4(), uuid4())
    sale_id = sale.id
    sale.cancel()
    sale.cancel()
    assert sale.id == sale_id
    assert sale.state == SaleState.CANCELLED


def test_commerce_correction_and_cancellation_preserve_history_references():
    product = Product("product")
    offering = Offering(product.id, uuid4())
    sale = Sale(offering.id, uuid4())
    invoice = Invoice(sale.id)
    sale_id, invoice_id = sale.id, invoice.id
    sale.cancel()
    invoice.void()
    assert sale.id == sale_id
    assert invoice.id == invoice_id
    assert invoice.sale_id == sale.id
    assert preserve_history_on_cancellation() is True


# 16–20 — Inventory/Sale truth boundary and financial value extremes.

def test_inventory_and_availability_do_not_become_sale_truth():
    product = Product("product")
    offering = Offering(product.id, uuid4())
    inventory = InventoryPosition(offering.id, uuid4(), scope_key="default")
    availability = Availability(offering.id, AvailabilityState.INVALID, datetime.now(timezone.utc))
    sale = Sale(offering.id, uuid4())
    assert inventory.offering_id == offering.id
    assert availability.offering_id == offering.id
    assert sale.offering_id == offering.id
    assert "inventory_position_id" not in _field_names(sale)
    assert "availability_id" not in _field_names(sale)


def test_inventory_close_requires_effective_state_and_preserves_identity():
    inventory = InventoryPosition(uuid4(), uuid4(), scope_key="default")
    inventory_id = inventory.id
    with pytest.raises(ValidationError):
        inventory.close()
    assert inventory.id == inventory_id
    assert inventory.state.value == "observed"
    inventory.make_effective()
    inventory.close()
    assert inventory.id == inventory_id
    assert inventory.state.value == "closed"
    with pytest.raises(ValidationError):
        inventory.close()
    assert inventory.id == inventory_id
    assert inventory.state.value == "closed"


def test_financial_minimum_positive_and_large_values_remain_distinct():
    account = FinancialAccount(Person().id)
    tiny = Decimal("0.000000000001")
    huge = Decimal("999999999999999999999999999999.99")
    low = Obligation(account.id, tiny)
    high = Obligation(account.id, huge)
    assert low.amount == tiny
    assert high.amount == huge
    assert low.id != high.id


def test_financial_zero_and_negative_values_are_rejected():
    account = FinancialAccount(Person().id)
    with pytest.raises(ValidationError):
        Obligation(account.id, Decimal("0"))
    with pytest.raises(ValidationError):
        Obligation(account.id, Decimal("-0.01"))
    with pytest.raises(ValidationError):
        Payment(Decimal("0"))
    with pytest.raises(ValidationError):
        Payment(Decimal("-0.01"))
    with pytest.raises(ValidationError):
        FinancialTransaction(account.id, Decimal("0"))
    with pytest.raises(ValidationError):
        LedgerEntry._from_finance(account.id, Decimal("0"), uuid4())


def test_balance_is_derived_from_ledger_entries_and_not_stored_as_authority():
    account = FinancialAccount(Person().id)
    transaction = FinancialTransaction(account.id, Decimal("40"))
    entry = LedgerEntry._from_finance(account.id, Decimal("40"), transaction.id)
    balance = Balance.derive(account.id, [entry])
    assert balance.amount == Decimal("40")
    assert "ledger_entries" not in _field_names(balance)
    assert "transaction_id" not in _field_names(balance)


# 21–25 — Finance replay, repeated correction/reversal and component separation.

def test_balance_excludes_entries_owned_by_other_financial_accounts():
    account = FinancialAccount(Person().id)
    other = FinancialAccount(Person().id)
    tx_a = FinancialTransaction(account.id, Decimal("40"))
    tx_b = FinancialTransaction(other.id, Decimal("90"))
    entry_a = LedgerEntry._from_finance(account.id, Decimal("40"), tx_a.id)
    entry_b = LedgerEntry._from_finance(other.id, Decimal("90"), tx_b.id)
    balance = Balance.derive(account.id, [entry_a, entry_b])
    assert balance.amount == Decimal("40")


def test_repeated_payment_cancellation_keeps_same_operation_identity():
    payment = Payment(Decimal("25"))
    payment_id = payment.id
    payment.cancel()
    payment.cancel()
    assert payment.id == payment_id
    assert payment.state == PaymentState.CANCELLED


def test_repeated_settlement_reversal_preserves_reference_and_history():
    payment = Payment(Decimal("25"))
    settlement = Settlement(payment.id)
    settlement.settle(connected=True)
    settlement.reverse()
    settlement.reverse()
    assert settlement.payment_id == payment.id
    assert settlement.state == SettlementState.REVERSED
    assert preserve_original_on_reversal() is True


def test_replay_of_same_operation_does_not_mutate_ledger_truth():
    key = "operation-replay-1"
    account = FinancialAccount(Person().id)
    tx = FinancialTransaction(account.id, Decimal("50"))
    entry = LedgerEntry._from_finance(account.id, Decimal("50"), tx.id)
    before = Balance.derive(account.id, [entry])
    assert is_duplicate(key, [key]) is True
    pending = PendingOperation(Device(Person().id).id, "Finance", key)
    pending.accept()
    after = Balance.derive(account.id, [entry])
    assert before.amount == after.amount == Decimal("50")
    assert pending.state.value == "accepted"


def test_finance_components_remain_distinct_after_a_long_valid_chain():
    account = FinancialAccount(Person().id)
    obligation = Obligation(account.id, Decimal("100"))
    obligation.open()
    obligation.mark_due()
    obligation.mark_overdue()
    payment = Payment(Decimal("100"), obligation_id=obligation.id)
    payment.pending()
    payment.complete(connected=True)
    settlement = Settlement(payment.id)
    settlement.settle(connected=True)
    tx = FinancialTransaction(account.id, Decimal("100"))
    entry = LedgerEntry._from_finance(account.id, Decimal("100"), tx.id)
    balance = Balance.derive(account.id, [entry])
    assert obligation.financial_account_id == account.id
    assert payment.obligation_id == obligation.id
    assert settlement.payment_id == payment.id
    assert entry.transaction_id == tx.id
    assert balance.amount == Decimal("100")
    assert "ledger_entry_id" not in _field_names(payment)
    assert "settlement_id" not in _field_names(payment)


# 26–30 — Health, history, correction and cross-domain separation.

def test_health_required_values_are_rejected():
    with pytest.raises(ValidationError):
        ClinicalRecord(uuid4(), "")
    with pytest.raises(ValidationError):
        ResultReport(uuid4(), " ")
    with pytest.raises(ValidationError):
        Prescription(uuid4(), "")
    with pytest.raises(ValidationError):
        Encounter("not-a-patient")


def test_health_cross_domain_reference_shapes_are_rejected():
    with pytest.raises(ValidationError):
        PatientContext("financial-account-id")
    with pytest.raises(ValidationError):
        ClinicalRecord("financial-account-id", "record")
    with pytest.raises(ValidationError):
        ResultReport("financial-account-id", "result")
    with pytest.raises(ValidationError):
        Prescription("financial-account-id", "instruction")


def test_health_correction_preserves_original_identity_references_and_content():
    patient = PatientContext(Person().id)
    encounter = Encounter(patient.id)
    record = ClinicalRecord(patient.id, "original fact", encounter.id)
    report = ResultReport(patient.id, "original result", encounter.id)
    record_id, report_id = record.id, report.id
    record.record()
    report.finalize()
    record.correct()
    report.correct()
    assert record.id == record_id
    assert report.id == report_id
    assert record.patient_context_id == patient.id
    assert report.patient_context_id == patient.id
    assert record.content == "original fact"
    assert report.content == "original result"


def test_repeated_health_correction_does_not_erase_original_content():
    patient = PatientContext(Person().id)
    record = ClinicalRecord(patient.id, "immutable-original-content")
    record.record()
    record.correct()
    record.correct()
    assert record.state.value == "corrected"
    assert record.content == "immutable-original-content"


def test_health_artifacts_do_not_become_commerce_or_finance_truth():
    patient = PatientContext(Person().id)
    encounter = Encounter(patient.id)
    record = ClinicalRecord(patient.id, "fact", encounter.id)
    report = ResultReport(patient.id, "result", encounter.id)
    prescription = Prescription(patient.id, "instruction", encounter.id)
    assert len({record.id, report.id, prescription.id}) == 3
    assert "financial_account_id" not in _field_names(record)
    assert "sale_id" not in _field_names(report)
    assert "invoice_id" not in _field_names(prescription)


# 31–35 — Pending/Conflict, replay, state sequence and failure isolation.

def test_pending_operation_required_values_are_rejected():
    with pytest.raises(ValidationError):
        PendingOperation("not-a-device", "Finance", "op")
    with pytest.raises(ValidationError):
        PendingOperation(uuid4(), "", "op")
    with pytest.raises(ValidationError):
        PendingOperation(uuid4(), "Finance", "")


def test_pending_replay_does_not_create_domain_truth():
    device = Device(Person().id)
    pending = PendingOperation(device.id, "Finance", "same-operation")
    pending.submit()
    pending.accept()
    pending.accept()
    assert pending.state.value == "accepted"
    assert "domain_truth" not in _field_names(pending)
    assert "ledger_entry" not in _field_names(pending)


def test_conflict_required_values_and_same_reference_are_rejected():
    with pytest.raises(ValidationError):
        Conflict("", "local", "remote")
    with pytest.raises(ValidationError):
        Conflict("Finance", "same", "same")
    with pytest.raises(ValidationError):
        Conflict("Finance", "", "remote")


def test_pending_to_conflict_to_resolution_sequence_keeps_owner_domain_explicit():
    device = Device(Person().id)
    pending = PendingOperation(device.id, "Health", "record-1")
    pending.submit()
    pending.accept()
    conflict = Conflict("Health", "record-local", "record-remote")
    conflict.review()
    record = ClinicalRecord(Person().id, "original")
    record.correct()
    conflict.mark_resolved_by_owner()
    assert pending.owner_domain == "Health"
    assert conflict.owner_domain == "Health"
    assert record.state.value == "corrected"
    assert conflict.state.value == "resolved"
    assert "winner" not in _field_names(conflict)
    assert "domain_truth" not in _field_names(conflict)


def test_conflict_is_not_domain_truth_after_review_and_resolution():
    conflict = Conflict("Finance", "local-1", "remote-1")
    conflict.review()
    conflict.mark_resolved_by_owner()
    assert conflict.state.value == "resolved"
    assert "domain_truth" not in _field_names(conflict)
    assert "ledger_entry" not in _field_names(conflict)


# 36–40 — Agent, Approval and operational-owner boundaries.

def test_device_state_transitions_keep_operational_object_separate():
    device = Device(Person().id)
    device.mark_lost()
    device.mark_lost()
    device.revoke()
    device.revoke()
    assert device.state.value == "revoked"
    assert "financial_account_id" not in _field_names(device)
    assert "clinical_record_id" not in _field_names(device)


def test_agent_required_values_are_rejected():
    with pytest.raises(ValidationError):
        Agent("")
    with pytest.raises(ValidationError):
        Agent("   ")
    with pytest.raises(ValidationError):
        AgentAction("not-an-agent", "review")
    with pytest.raises(ValidationError):
        AgentAction(uuid4(), "")


def test_agent_action_replay_is_permitted_but_has_no_domain_truth_ownership():
    agent = Agent("assistant")
    action = AgentAction(agent.id, "review")
    approval = Approval(action.id)
    approval.approve()
    grant = AuthorizationGrant(agent.id, "review", "review-scope")
    grant.activate()
    action.bind_authorization_grant(grant)
    action.execute(approval, grant)
    action.execute(approval, grant)
    assert action.state.value == "executed"
    assert "financial_account_id" not in _field_names(action)
    assert "clinical_record_id" not in _field_names(action)
    # Current model has no persistent replay/idempotency enforcement: GAP-0005.


def test_approval_does_not_change_agent_action_without_explicit_action_transition():
    agent = Agent("assistant")
    action = AgentAction(agent.id, "restricted-operation")
    approval = Approval(action.id)
    approval.approve()
    assert approval.state.value == "approved"
    assert action.state.value == "prepared"


def test_agent_device_pending_and_conflict_are_not_domain_owners():
    agent = Agent("assistant")
    device = Device(Person().id)
    pending = PendingOperation(device.id, "Finance", "op-1")
    conflict = Conflict("Finance", "local", "remote")
    assert "person_id" not in _field_names(agent)
    assert "financial_account_id" not in _field_names(agent)
    assert "ledger_entry_id" not in _field_names(device)
    assert "domain_truth" not in _field_names(pending)
    assert "domain_truth" not in _field_names(conflict)


# 41–45 — Cross-domain invariants, failure consistency, immutable history and GAP boundaries.

def test_cross_domain_identity_authorization_boundaries_do_not_collapse_person_and_accounts():
    person = Person()
    access = AccessAccount(person.id)
    finance = FinancialAccount(person.id)
    grant = AuthorizationGrant(person.id, "read", "finance")
    assert access.person_id == person.id
    assert finance.person_id == person.id
    assert grant.subject_id == person.id
    assert "access_account_id" not in _field_names(grant)
    assert "financial_account_id" not in _field_names(grant)


def test_cross_domain_commerce_boundaries_do_not_mix_product_offering_inventory_availability_sale():
    product = Product("product")
    offering = Offering(product.id, uuid4())
    inventory = InventoryPosition(offering.id, uuid4(), scope_key="default")
    availability = Availability(offering.id, AvailabilityState.COMPUTED, datetime.now(timezone.utc))
    sale = Sale(offering.id, Activity("sale").id)
    assert offering.product_id == product.id
    assert inventory.offering_id == offering.id
    assert availability.offering_id == offering.id
    assert sale.offering_id == offering.id
    assert "product_id" not in _field_names(sale)
    assert "inventory_position_id" not in _field_names(sale)
    assert "availability_id" not in _field_names(sale)


def test_cross_domain_finance_boundaries_do_not_mix_invoice_obligation_payment_settlement_ledger():
    invoice = Invoice(uuid4())
    account = FinancialAccount(Person().id)
    obligation = Obligation(account.id, Decimal("10"))
    payment = Payment(Decimal("10"), obligation_id=obligation.id, invoice_id=invoice.id)
    settlement = Settlement(payment.id)
    tx = FinancialTransaction(account.id, Decimal("10"))
    entry = LedgerEntry._from_finance(account.id, Decimal("10"), tx.id)
    assert payment.invoice_id == invoice.id
    assert payment.obligation_id == obligation.id
    assert settlement.payment_id == payment.id
    assert entry.transaction_id == tx.id
    assert "ledger_entry_id" not in _field_names(invoice)
    assert "settlement_id" not in _field_names(payment)
    assert "payment_id" not in _field_names(entry)


def test_cross_domain_health_finance_and_agent_boundaries_do_not_mix_truth():
    person = Person()
    patient = PatientContext(person.id)
    record = ClinicalRecord(patient.id, "clinical fact")
    finance = FinancialAccount(person.id)
    agent = Agent("assistant")
    action = AgentAction(agent.id, "review")
    assert record.patient_context_id == patient.id
    assert finance.person_id == person.id
    assert action.agent_id == agent.id
    assert "financial_account_id" not in _field_names(record)
    assert "clinical_record_id" not in _field_names(action)
    assert "agent_id" not in _field_names(finance)


def test_long_operation_sequence_preserves_identity_references_and_source_of_truth():
    person = Person()
    account = FinancialAccount(person.id)
    tx = FinancialTransaction(account.id, Decimal("10"))
    entry = LedgerEntry._from_finance(account.id, Decimal("10"), tx.id)
    payment = Payment(Decimal("10"))
    for _ in range(5):
        payment.pending()
        payment.complete(connected=True)
        payment.cancel()
    balance = Balance.derive(account.id, [entry])
    assert payment.state == PaymentState.CANCELLED
    assert payment.id is not None
    assert entry.transaction_id == tx.id
    assert balance.amount == Decimal("10")


def test_failed_operation_does_not_partially_mutate_preexisting_domain_state():
    sale = Sale(uuid4(), uuid4())
    sale_id = sale.id
    original_state = sale.state
    with pytest.raises(ValidationError):
        sale.complete()
    assert sale.id == sale_id
    assert sale.state == original_state

    account = FinancialAccount(Person().id)
    tx = FinancialTransaction(account.id, Decimal("20"))
    entry = LedgerEntry._from_finance(account.id, Decimal("20"), tx.id)
    before = Balance.derive(account.id, [entry])
    with pytest.raises(ValidationError):
        Payment(Decimal("-20"))
    after = Balance.derive(account.id, [entry])
    assert before.amount == after.amount == Decimal("20")


def test_historical_frozen_records_cannot_be_mutated_in_place():
    account = FinancialAccount(Person().id)
    tx = FinancialTransaction(account.id, Decimal("20"))
    entry = LedgerEntry._from_finance(account.id, Decimal("20"), tx.id)
    provenance = Provenance(str(tx.id), "Finance")
    audit = AuditRecord("observed", str(tx.id))
    with pytest.raises(FrozenInstanceError):
        entry.amount = Decimal("999")
    with pytest.raises(FrozenInstanceError):
        provenance.source_ref = "changed"
    with pytest.raises(FrozenInstanceError):
        audit.subject_ref = "changed"
