"""Database guards for currencies on linked financial records."""
import sqlite3
import uuid

import pytest

from agent_core.obligation_application import ObligationApplication
from agent_core.payment_application import PaymentApplication
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

STAMP = "2026-10-16T00:00:00Z"


def uid():
    return str(uuid.uuid4())


def person(db):
    value = uid()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
        (value, STAMP, STAMP),
    )
    return value


def loan(db, lender, borrower, currency="YER"):
    value = uid()
    db.execute(
        "INSERT INTO loans(loan_id,lender_person_id,borrower_person_id,state,principal_minor,"
        "currency_code,created_at,updated_at) VALUES(?,?,?,'proposed',100,?,?,?)",
        (value, lender, borrower, currency, STAMP, STAMP),
    )
    return value


def obligation(db, creditor, debtor, currency="YER", loan_id=None):
    value = uid()
    db.execute(
        "INSERT INTO obligations(obligation_id,creditor_person_id,debtor_person_id,loan_id,amount_minor,"
        "currency_code,state,created_at,updated_at) VALUES(?,?,?,?,?,?,'proposed',?,?)",
        (value, creditor, debtor, loan_id, 100, currency, STAMP, STAMP),
    )
    return value


def payment(db, payer, payee, currency="YER", obligation_id=None):
    value = uid()
    db.execute(
        "INSERT INTO payments(payment_id,obligation_id,payer_person_id,payee_person_id,amount_minor,"
        "currency_code,state,created_at,updated_at) VALUES(?,?,?,?,?,?,'initiated',?,?)",
        (value, obligation_id, payer, payee, 100, currency, STAMP, STAMP),
    )
    return value


def test_obligation_must_match_linked_loan_currency():
    db = connect_database()
    lender, borrower = person(db), person(db)
    loan_id = loan(db, lender, borrower, "YER")
    with pytest.raises(sqlite3.IntegrityError, match="obligation currency_code"):
        obligation(db, lender, borrower, "USD", loan_id)
    obligation_id = obligation(db, lender, borrower, "YER", loan_id)
    with pytest.raises(sqlite3.IntegrityError, match="loan currency_code"):
        db.execute("UPDATE loans SET currency_code='USD' WHERE loan_id=?", (loan_id,))
    with pytest.raises(sqlite3.IntegrityError, match="obligation currency_code"):
        db.execute("UPDATE obligations SET currency_code='USD' WHERE obligation_id=?", (obligation_id,))
    db.close()


def test_payment_must_match_linked_obligation_currency():
    db = connect_database()
    creditor, debtor = person(db), person(db)
    obligation_id = obligation(db, creditor, debtor, "YER")
    with pytest.raises(sqlite3.IntegrityError, match="payment currency_code"):
        payment(db, debtor, creditor, "USD", obligation_id)
    payment_id = payment(db, debtor, creditor, "YER", obligation_id)
    with pytest.raises(sqlite3.IntegrityError, match="obligation currency_code"):
        db.execute("UPDATE obligations SET currency_code='USD' WHERE obligation_id=?", (obligation_id,))
    with pytest.raises(sqlite3.IntegrityError, match="payment currency_code"):
        db.execute("UPDATE payments SET currency_code='USD' WHERE payment_id=?", (payment_id,))
    db.close()


def test_settlement_must_match_payment_and_obligation_currencies():
    db = connect_database()
    creditor, debtor = person(db), person(db)
    obligation_id = obligation(db, creditor, debtor, "YER")
    payment_id = payment(db, debtor, creditor, "USD")
    with pytest.raises(sqlite3.IntegrityError, match="settlement payment and obligation"):
        db.execute(
            "INSERT INTO settlements(settlement_id,payment_id,obligation_id,state,created_at,updated_at) "
            "VALUES(?,?,?,'pending',?,?)",
            (uid(), payment_id, obligation_id, STAMP, STAMP),
        )
    valid_payment_id = payment(db, debtor, creditor, "YER")
    settlement_id = uid()
    db.execute(
        "INSERT INTO settlements(settlement_id,payment_id,obligation_id,state,created_at,updated_at) "
        "VALUES(?,?,?,'pending',?,?)",
        (settlement_id, valid_payment_id, obligation_id, STAMP, STAMP),
    )
    with pytest.raises(sqlite3.IntegrityError, match="payment currency_code"):
        db.execute("UPDATE payments SET currency_code='USD' WHERE payment_id=?", (valid_payment_id,))
    db.close()


def test_recognized_settlement_transaction_must_match_payment_currency():
    db = connect_database()
    creditor, debtor = person(db), person(db)
    account_id = uid()
    db.execute(
        "INSERT INTO financial_accounts(financial_account_id,person_id,account_type,state,created_at,updated_at) "
        "VALUES(?,?,?,'active',?,?)",
        (account_id, creditor, "personal", STAMP, STAMP),
    )
    obligation_id = obligation(db, creditor, debtor, "YER")
    payment_id = payment(db, debtor, creditor, "YER", obligation_id)
    settlement_id = uid()
    db.execute(
        "INSERT INTO settlements(settlement_id,payment_id,obligation_id,state,created_at,updated_at) "
        "VALUES(?,?,?,'settled',?,?)",
        (settlement_id, payment_id, obligation_id, STAMP, STAMP),
    )

    def insert_transaction(currency, transaction_id=None):
        transaction_id = transaction_id or uid()
        db.execute(
            "INSERT INTO financial_transactions(financial_transaction_id,settlement_id,financial_account_id,"
            "amount_minor,currency_code,state,recognition_source_type,recognition_source_ref,recognized_at,"
            "created_at,updated_at) VALUES(?,?,?,?,?,'recognized','settlement',?,?,?,?)",
            (transaction_id, settlement_id, account_id, 100, currency, settlement_id, STAMP, STAMP, STAMP),
        )
        return transaction_id

    with pytest.raises(sqlite3.IntegrityError, match="financial transaction currency_code"):
        insert_transaction("USD")
    transaction_id = insert_transaction("YER")
    with pytest.raises(sqlite3.IntegrityError, match="financial transaction currency_code"):
        db.execute(
            "UPDATE financial_transactions SET currency_code='USD' WHERE financial_transaction_id=?",
            (transaction_id,),
        )
    assert db.execute(
        "SELECT currency_code FROM financial_transactions WHERE financial_transaction_id=?",
        (transaction_id,),
    ).fetchone()[0] == "YER"
    db.close()


def test_obligation_read_fails_closed_on_legacy_loan_currency_mismatch():
    db = connect_database()
    lender, borrower = person(db), person(db)
    loan_id = loan(db, lender, borrower, "YER")
    obligation_id = obligation(db, lender, borrower, "YER", loan_id)
    db.execute("DROP TRIGGER loan_currency_matches_existing_obligations_update")
    db.execute("UPDATE loans SET currency_code='USD' WHERE loan_id=?", (loan_id,))
    with pytest.raises(ValidationError, match="does not match its linked Loan currency"):
        ObligationApplication().get_obligation(db, obligation_id)
    assert db.execute(
        "SELECT currency_code FROM obligations WHERE obligation_id=?", (obligation_id,)
    ).fetchone()[0] == "YER"
    db.close()


def test_payment_read_fails_closed_on_legacy_obligation_currency_mismatch():
    db = connect_database()
    creditor, debtor = person(db), person(db)
    obligation_id = obligation(db, creditor, debtor, "YER")
    payment_id = payment(db, debtor, creditor, "YER", obligation_id)
    db.execute("DROP TRIGGER payment_obligation_currency_matches_update")
    db.execute("UPDATE payments SET currency_code='USD' WHERE payment_id=?", (payment_id,))
    with pytest.raises(ValidationError, match="does not match its linked Obligation currency"):
        PaymentApplication().get_payment(db, payment_id)
    assert db.execute(
        "SELECT currency_code FROM payments WHERE payment_id=?", (payment_id,)
    ).fetchone()[0] == "USD"
    db.close()
