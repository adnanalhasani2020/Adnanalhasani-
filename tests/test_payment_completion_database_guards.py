"""Database-level guards for completing Payments against current source state."""
import sqlite3
import uuid

import pytest

from agent_core.persistence import connect_database

STAMP = "2026-10-19T12:00:00Z"


def uid():
    return str(uuid.uuid4())


def seed_context(db, *, invoice_state="issued", obligation_state="open", link_invoice=True):
    creditor, debtor, activity, product, offering, sale = [uid() for _ in range(6)]
    obligation, invoice = uid(), uid()
    for person in (creditor, debtor):
        db.execute(
            "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
            (person, STAMP, STAMP),
        )
    db.execute(
        "INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) VALUES(?,?, 'active',?,?)",
        (activity, creditor, STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO products(product_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
        (product, STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO offerings(offering_id,product_id,activity_id,state,effective_from,created_at,updated_at) "
        "VALUES(?,?,?,'active','2026-10-01T00:00:00Z',?,?)",
        (offering, product, activity, STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO sales(sale_id,offering_id,activity_id,state,occurred_at,created_at,updated_at,version_no) "
        "VALUES(?,?,?,'initiated',?,?,?,1)",
        (sale, offering, activity, STAMP, STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO obligations(obligation_id,creditor_person_id,debtor_person_id,amount_minor,currency_code,"
        "state,created_at,updated_at,version_no) VALUES(?,?,?,?,?,?,?, ?,1)",
        (obligation, creditor, debtor, 1000, "YER", obligation_state, STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO invoices(invoice_id,sale_id,obligation_id,state,issuer_ref,invoice_number,issue_at,"
        "created_at,updated_at,version_no) VALUES(?,?,?,?,?,?,?,?,?,1)",
        (invoice, sale, obligation if link_invoice else None, invoice_state, creditor,
         "INV-" + invoice, STAMP, STAMP, STAMP),
    )
    db.commit()
    return creditor, debtor, obligation, invoice


def insert_payment(db, creditor, debtor, obligation, invoice, *, state="initiated"):
    payment = uid()
    db.execute(
        "INSERT INTO payments(payment_id,obligation_id,invoice_id,payer_person_id,payee_person_id,"
        "amount_minor,currency_code,state,created_at,updated_at,version_no) "
        "VALUES(?,?,?,?,?,1000,'YER',?,?,?,1)",
        (payment, obligation, invoice, debtor, creditor, state, STAMP, STAMP),
    )
    return payment


def test_direct_sql_cannot_complete_payment_after_invoice_voided():
    db = connect_database()
    creditor, debtor, obligation, invoice = seed_context(db, invoice_state="issued")
    payment = insert_payment(db, creditor, debtor, obligation, invoice)
    db.execute("UPDATE invoices SET state='void' WHERE invoice_id=?", (invoice,))
    db.commit()

    with pytest.raises(sqlite3.IntegrityError, match="completed Payment requires"):
        db.execute("UPDATE payments SET state='completed' WHERE payment_id=?", (payment,))

    assert db.execute("SELECT state FROM payments WHERE payment_id=?", (payment,)).fetchone()[0] == "initiated"
    db.close()


def test_direct_sql_cannot_complete_payment_for_terminal_obligation():
    db = connect_database()
    creditor, debtor, obligation, invoice = seed_context(db, obligation_state="open", link_invoice=False)
    payment = insert_payment(db, creditor, debtor, obligation, None)
    db.execute("UPDATE obligations SET state='cancelled' WHERE obligation_id=?", (obligation,))
    db.commit()

    with pytest.raises(sqlite3.IntegrityError, match="completed Payment requires"):
        db.execute("UPDATE payments SET state='completed' WHERE payment_id=?", (payment,))

    assert db.execute("SELECT state FROM payments WHERE payment_id=?", (payment,)).fetchone()[0] == "initiated"
    db.close()


def test_direct_sql_cannot_insert_completed_payment_for_void_invoice():
    db = connect_database()
    creditor, debtor, obligation, invoice = seed_context(db, invoice_state="void")
    with pytest.raises(sqlite3.IntegrityError, match="completed Payment requires"):
        insert_payment(db, creditor, debtor, obligation, invoice, state="completed")

    assert db.execute("SELECT COUNT(*) FROM payments").fetchone()[0] == 0
    db.close()


def test_database_guard_allows_completed_payment_for_issued_invoice_and_open_obligation():
    db = connect_database()
    creditor, debtor, obligation, invoice = seed_context(db)
    payment = insert_payment(db, creditor, debtor, obligation, invoice)
    db.execute("UPDATE payments SET state='completed' WHERE payment_id=?", (payment,))
    assert db.execute("SELECT state FROM payments WHERE payment_id=?", (payment,)).fetchone()[0] == "completed"
    db.close()
