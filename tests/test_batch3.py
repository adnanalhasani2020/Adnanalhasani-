import pytest
from uuid import uuid4
from decimal import Decimal
from agent_core.shared import ValidationError
from agent_core.domain_finance import FinancialAccount,Obligation,Debt,Loan,Payment,Settlement,FinancialTransaction,LedgerEntry,Balance
from agent_core.domain_health import PatientContext,Encounter,ClinicalRecord,ResultReport,Prescription
from agent_core.domain_authorization import FamilyRelationship,Delegation,AuthorizationGrant,AuthorityPolicy,Agent,AgentAction,Approval
from agent_core.domain_activities import RoleAssignment

def test_financial_boundaries_and_derived_balance():
    person=uuid4(); account=FinancialAccount(person); obligation=Obligation(account.id,100); debt=Debt(obligation.id)
    tx=FinancialTransaction(account.id,100); entry=LedgerEntry(account.id,100,tx.id)
    assert account.id!=person and obligation.id!=account.id and debt.obligation_id==obligation.id
    assert Balance.derive(account.id,[entry]).amount==Decimal("100")
    assert not hasattr(account,"access_account_id")

def test_financial_lifecycle_and_separation():
    account=FinancialAccount(uuid4()); obligation=Obligation(account.id,50); obligation.open(); obligation.mark_due(); obligation.mark_overdue()
    payment=Payment(20,obligation_id=obligation.id); payment.complete(connected=True); settlement=Settlement(payment.id,obligation.id); settlement.settle(connected=True)
    assert payment.state.value=="completed" and settlement.state.value=="settled" and obligation.state.value=="overdue"
    assert not hasattr(payment,"ledger_entry_id")

def test_financial_invariants_reject_bad_values():
    with pytest.raises(ValidationError): FinancialAccount("bad")
    with pytest.raises(ValidationError): Obligation(uuid4(),0)
    with pytest.raises(ValidationError): Payment(0)

def test_loan_is_distinct_from_debt():
    loan=Loan(uuid4(),uuid4()); loan.activate(); assert loan.state.value=="active"

def test_health_boundaries_and_lifecycle():
    person=uuid4(); patient=PatientContext(person); encounter=Encounter(patient.id); encounter.start(); encounter.complete(connected=True)
    record=ClinicalRecord(patient.id,"clinical truth",encounter.id); record.record()
    report=ResultReport(patient.id,"report",encounter.id); report.finalize()
    prescription=Prescription(patient.id,"clinical instruction",encounter.id); prescription.issue(); prescription.activate()
    assert patient.person_id==person and record.state.value=="recorded" and report.state.value=="final" and prescription.state.value=="active"
    assert not hasattr(encounter,"invoice_id")

def test_health_rejects_invalid_context_and_blank_record():
    with pytest.raises(ValidationError): PatientContext("bad")
    with pytest.raises(ValidationError): ClinicalRecord(uuid4()," ")
    with pytest.raises(ValidationError): Prescription(uuid4()," ")

def test_health_truth_is_not_agent_or_finance():
    patient=PatientContext(uuid4()); record=ClinicalRecord(patient.id,"truth")
    assert not hasattr(record,"financial_account_id") and not hasattr(record,"agent_id")

def test_family_delegation_authorization_are_distinct():
    a,b=uuid4(),uuid4(); family=FamilyRelationship(a,b,"family"); delegation=Delegation(a,b,"health","care"); grant=AuthorizationGrant(b,"read","clinical","care")
    delegation.activate(); grant.activate()
    assert family.id!=delegation.id!=grant.id and delegation.state.value=="active" and grant.state.value=="active"

def test_role_assignment_is_not_authorization():
    role=RoleAssignment(uuid4(),"role"); grant=AuthorizationGrant(uuid4(),"read","scope")
    assert role.id!=grant.id and not hasattr(role,"authorization_grant_id")

def test_authorization_lifecycle_and_policy_boundary():
    grant=AuthorizationGrant(uuid4(),"read","scope"); grant.activate(); grant.suspend(); grant.revoke()
    policy=AuthorityPolicy("fixed policy"); policy.deactivate()
    assert grant.state.value=="revoked" and policy.state.value=="inactive"

def test_agent_action_and_approval_are_separate():
    agent=Agent("health-agent"); action=AgentAction(agent.id,"review"); approval=Approval(action.id)
    approval.approve(); action.execute()
    assert approval.agent_action_id==action.id and action.state.value=="executed" and approval.state.value=="approved"
    assert not hasattr(agent,"clinical_truth")

def test_agent_cannot_self_authorize_via_family_or_delegation():
    person=uuid4()
    with pytest.raises(ValidationError): FamilyRelationship(person,person,"family")
    with pytest.raises(ValidationError): Delegation(person,person,"scope")


# DEC-0011 → CG-001 → REQ-0022/0046/0056/0063 → SPEC-0008/0019/0023/0024 → EXEC-0005/0010
def test_financial_finality_requires_connectivity():
    payment = Payment(Decimal("25"))
    with pytest.raises(ValidationError):
        payment.complete(connected=False)
    assert payment.state == PaymentState.PENDING or payment.state == PaymentState.INITIATED

    settlement = Settlement(payment.id)
    with pytest.raises(ValidationError):
        settlement.settle(connected=False)
    assert settlement.state.value == "pending"

    payment.complete(connected=True)
    settlement.settle(connected=True)
    assert payment.state.value == "completed"
    assert settlement.state.value == "settled"


def test_financial_preparation_remains_non_final_until_online():
    payment = Payment(Decimal("25"))
    payment.pending()
    assert payment.state.value == "pending"
    with pytest.raises(ValidationError):
        payment.complete(connected=False)
    assert payment.state.value == "pending"
    payment.complete(connected=True)
    assert payment.state.value == "completed"
