"""Regression tests for payment/settlement obligation identity integrity."""
import sqlite3
import uuid

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


def obligation(db, creditor, debtor):
    value = uid()
    db.execute(
        "INSERT INTO obligations(obligation_id,creditor_person_id,debtor_person_id,amount_minor,"
        "currency_code,state,created_at,updated_at) VALUES(?,?,?,100,'YER','proposed',?,?)",
        (value, creditor, debtor, STAMP, STAMP),
    )
    return value


def payment(db, payer, payee, obligation_id):
    value = uid()
    db.execute(
        "INSERT INTO payments(payment_id,obligation_id,payer_person_id,payee_person_id,amount_minor,"
        "currency_code,state,created_at,updated_at) VALUES(?,?,?,?,100,'YER','initiated',?,?)",
        (value, obligation_id, payer, payee, STAMP, STAMP),
    )
    return value


def test_settled_payment_cannot_be_relinked_to_another_same_currency_obligation():
    db = connect_database()
    creditor, debtor = person(db), person(db)
    original_obligation = obligation(db, creditor, debtor)
    other_obligation = obligation(db, creditor, debtor)
    payment_id = payment(db, debtor, creditor, original_obligation)
    settlement_id = uid()
    db.execute(
        "INSERT INTO settlements(settlement_id,payment_id,obligation_id,state,created_at,updated_at) "
        "VALUES(?,?,?,'settled',?,?)",
        (settlement_id, payment_id, original_obligation, STAMP, STAMP),
    )

    try:
        import pytest
        with pytest.raises(sqlite3.IntegrityError, match="payment obligation must match existing settlements"):
            db.execute(
                "UPDATE payments SET obligation_id=? WHERE payment_id=?",
                (other_obligation, payment_id),
            )
        assert db.execute(
            "SELECT obligation_id FROM payments WHERE payment_id=?", (payment_id,)
        ).fetchone()[0] == original_obligation
    finally:
        db.close()


def test_settled_payment_cannot_clear_its_obligation_reference():
    db = connect_database()
    creditor, debtor = person(db), person(db)
    obligation_id = obligation(db, creditor, debtor)
    payment_id = payment(db, debtor, creditor, obligation_id)
    db.execute(
        "INSERT INTO settlements(settlement_id,payment_id,obligation_id,state,created_at,updated_at) "
        "VALUES(?,?,?,'settled',?,?)",
        (uid(), payment_id, obligation_id, STAMP, STAMP),
    )

    try:
        import pytest
        with pytest.raises(sqlite3.IntegrityError, match="payment obligation must match existing settlements"):
            db.execute(
                "UPDATE payments SET obligation_id=NULL WHERE payment_id=?", (payment_id,)
            )
        assert db.execute(
            "SELECT obligation_id FROM payments WHERE payment_id=?", (payment_id,)
        ).fetchone()[0] == obligation_id
    finally:
        db.close()
