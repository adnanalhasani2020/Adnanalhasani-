"""Regression test for the invoice issued-per-Sale invariant on UPDATE."""
from datetime import datetime, timezone
import sqlite3
import uuid

import pytest

from agent_core.invoice_application import InvoiceApplication
from agent_core.persistence import connect_database

STAMP = "2026-10-10T12:00:00Z"


def uid():
    return str(uuid.uuid4())


def seed_invoice_context(db):
    actor, activity, product, offering, sale, grant = [uid() for _ in range(6)]
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (actor, "active", STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) VALUES(?,?,?,?,?)",
        (activity, actor, "active", STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO products(product_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (product, "active", STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO offerings(offering_id,product_id,activity_id,state,effective_from,created_at,updated_at) "
        "VALUES(?,?,?,'active',?,?,?)",
        (offering, product, activity, "2026-10-01T00:00:00Z", STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO sales(sale_id,offering_id,activity_id,state,occurred_at,created_at,updated_at,version_no) "
        "VALUES(?,?,?,'initiated',?,?,?,1)",
        (sale, offering, activity, STAMP, STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO authorization_grants(authorization_grant_id,subject_person_id,action_code,scope_ref,"
        "context_ref,state,effective_from,created_at,updated_at) VALUES(?,?,?,?,?,'active',?,?,?)",
        (grant, actor, "issue_invoice", sale, activity, "2026-10-01T00:00:00Z", STAMP, STAMP),
    )
    db.commit()
    return actor, sale, grant


def test_database_guard_rejects_updating_void_invoice_to_second_issued_invoice():
    db = connect_database()
    actor, sale, grant = seed_invoice_context(db)
    first = InvoiceApplication().create_invoice(
        db,
        sale_id=sale,
        actor_context_ref=actor,
        authorization_grant_id=grant,
        issuer_ref=actor,
        invoice_number="INV-UPDATE-GUARD-FIRST",
        now=STAMP,
    )
    second_id = uid()
    db.execute(
        "INSERT INTO invoices(invoice_id,sale_id,obligation_id,state,issuer_ref,invoice_number,"
        "issue_at,due_at,created_at,updated_at,version_no) "
        "VALUES(?,?,NULL,'void',?,?,?,NULL,?,?,1)",
        (second_id, sale, actor, "INV-UPDATE-GUARD-SECOND", STAMP, STAMP, STAMP),
    )
    db.commit()

    with pytest.raises(sqlite3.IntegrityError, match="Sale already has an issued Invoice"):
        db.execute("UPDATE invoices SET state='issued' WHERE invoice_id=?", (second_id,))

    assert db.execute(
        "SELECT state FROM invoices WHERE invoice_id=?", (second_id,)
    ).fetchone()[0] == "void"
    assert db.execute(
        "SELECT COUNT(*) FROM invoices WHERE sale_id=? AND state='issued'", (sale,)
    ).fetchone()[0] == 1
    assert InvoiceApplication().get_invoice(db, first.invoice.id).invoice.state.value == "issued"
    db.close()
