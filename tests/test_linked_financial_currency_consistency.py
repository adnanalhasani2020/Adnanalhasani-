"""Database guards for currencies on linked financial records."""
import sqlite3
import uuid

import pytest

from agent_core.persistence import connect_database

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
