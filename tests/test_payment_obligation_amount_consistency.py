"""Regression tests for the existing per-Payment/linked-Obligation amount rule."""
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


def seed():
    db = connect_database()
    creditor, debtor = uid(), uid()
    for person_id in (creditor, debtor):
        db.execute(
            "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
            (person_id, STAMP, STAMP),
        )
    obligation_id, payment_id = uid(), uid()
    db.execute(
        "INSERT INTO obligations(obligation_id,creditor_person_id,debtor_person_id,amount_minor,"
        "currency_code,state,created_at,updated_at) VALUES(?,?,?,100,'YER','open',?,?)",
        (obligation_id, creditor, debtor, STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO payments(payment_id,obligation_id,payer_person_id,payee_person_id,amount_minor,"
        "currency_code,state,created_at,updated_at) VALUES(?,?,?,?,100,'YER','initiated',?,?)",
        (payment_id, obligation_id, debtor, creditor, STAMP, STAMP),
    )
    db.commit()
    return db, obligation_id, payment_id


def test_database_rejects_payment_amount_above_linked_obligation():
    db, obligation_id, payment_id = seed()
    with pytest.raises(sqlite3.IntegrityError, match="payment amount_minor must not exceed"):
        db.execute("UPDATE payments SET amount_minor=101 WHERE payment_id=?", (payment_id,))
    with pytest.raises(sqlite3.IntegrityError, match="payment amount_minor must not exceed"):
        db.execute(
            "INSERT INTO payments(payment_id,obligation_id,payer_person_id,payee_person_id,amount_minor,"
            "currency_code,state,created_at,updated_at) "
            "SELECT ?,obligation_id,payer_person_id,payee_person_id,101,currency_code,'initiated',?,? "
            "FROM payments WHERE payment_id=?",
            (uid(), STAMP, STAMP, payment_id),
        )
    with pytest.raises(sqlite3.IntegrityError, match="obligation amount_minor must cover"):
        db.execute("UPDATE obligations SET amount_minor=99 WHERE obligation_id=?", (obligation_id,))
    assert db.execute("SELECT amount_minor FROM payments WHERE payment_id=?", (payment_id,)).fetchone()[0] == 100
    assert db.execute("SELECT amount_minor FROM obligations WHERE obligation_id=?", (obligation_id,)).fetchone()[0] == 100
    db.close()


def test_payment_and_obligation_readers_fail_closed_on_legacy_amount_mismatch():
    db, obligation_id, payment_id = seed()
    db.execute("DROP TRIGGER payment_amount_within_obligation_update")
    db.execute("DROP TRIGGER obligation_amount_covers_linked_payments_update")
    db.execute("UPDATE payments SET amount_minor=101 WHERE payment_id=?", (payment_id,))
    db.commit()

    with pytest.raises(ValidationError, match="Persisted Payment amount exceeds"):
        PaymentApplication().get_payment(db, payment_id)
    with pytest.raises(ValidationError, match="Persisted linked Payment amount exceeds"):
        ObligationApplication().get_obligation(db, obligation_id)

    # Reads detect corruption but never repair persisted records.
    assert db.execute("SELECT amount_minor FROM payments WHERE payment_id=?", (payment_id,)).fetchone()[0] == 101
    assert db.execute("SELECT amount_minor FROM obligations WHERE obligation_id=?", (obligation_id,)).fetchone()[0] == 100
    db.close()
