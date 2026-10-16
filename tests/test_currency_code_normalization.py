"""Cross-domain currency-code normalization regressions."""
from agent_core.loan_application import LoanApplication
from agent_core.obligation_application import ObligationApplication
from agent_core.persistence import connect_database

from test_stage27_obligation_application import seed as seed_obligation, authorize as authorize_obligation, uid as obligation_uid, STAMP as OBLIGATION_STAMP
from test_stage28_loan_application import seed as seed_loan, authorize as authorize_loan, uid as loan_uid, STAMP as LOAN_STAMP


def test_obligation_creation_normalizes_currency_code():
    db = connect_database()
    creditor, debtor, approver, agent, context = seed_obligation(db)
    obligation_id = obligation_uid()
    grant, action = authorize_obligation(
        db, agent=agent, context=context, obligation_id=obligation_id,
        action_code="obligation.create", approver=approver,
    )
    record = ObligationApplication().create_obligation(
        db, obligation_id=obligation_id, creditor_person_id=creditor,
        debtor_person_id=debtor, amount_minor=100, currency_code=" yer ",
        context_ref=context, authorization_grant_id=grant,
        agent_action_id=action, now=OBLIGATION_STAMP,
    )
    assert record.currency_code == "YER"
    assert db.execute(
        "SELECT currency_code FROM obligations WHERE obligation_id=?", (obligation_id,)
    ).fetchone()[0] == "YER"
    db.close()


def test_loan_creation_normalizes_currency_code():
    db = connect_database()
    lender, borrower, approver, agent, context = seed_loan(db)
    loan_id = loan_uid()
    grant, action = authorize_loan(
        db, agent=agent, context=context, loan_id=loan_id,
        action_code="loan.create", approver=approver,
    )
    record = LoanApplication().create_loan(
        db, loan_id=loan_id, lender_person_id=lender, borrower_person_id=borrower,
        principal_minor=100, currency_code=" yer ", context_ref=context,
        authorization_grant_id=grant, agent_action_id=action, now=LOAN_STAMP,
    )
    assert record.currency_code == "YER"
    assert db.execute(
        "SELECT currency_code FROM loans WHERE loan_id=?", (loan_id,)
    ).fetchone()[0] == "YER"
    db.close()
