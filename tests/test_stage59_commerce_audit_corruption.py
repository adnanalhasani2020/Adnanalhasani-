"""Regression tests for corrupted persisted Sale/Invoice audit fields."""
import uuid

import pytest

from agent_core.invoice_application import InvoiceApplication
from agent_core.invoice_history_audit import InvoiceHistoryIntegrityAuditor
from agent_core.persistence import connect_database
from agent_core.sale_history_audit import SaleHistoryIntegrityAuditor

STAMP = "2026-10-10T12:00:00Z"


def uid():
    return str(uuid.uuid4())


def make_sale(db):
    actor, activity, product, offering, sale = (uid() for _ in range(5))
    db.execute("INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
               (actor, "active", STAMP, STAMP))
    db.execute("INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) VALUES(?,?,?,?,?)",
               (activity, actor, "active", STAMP, STAMP))
    db.execute("INSERT INTO products(product_id,state,created_at,updated_at) VALUES(?,?,?,?)",
               (product, "active", STAMP, STAMP))
    db.execute("INSERT INTO offerings(offering_id,product_id,activity_id,state,effective_from,created_at,updated_at) "
               "VALUES(?,?,?,'active',?,?,?)",
               (offering, product, activity, "2026-10-01T00:00:00Z", STAMP, STAMP))
    db.execute("INSERT INTO sales(sale_id,offering_id,activity_id,state,occurred_at,created_at,updated_at,version_no) "
               "VALUES(?,?,?,'initiated',?,?,?,1)", (sale, offering, activity, STAMP, STAMP, STAMP))
    db.commit()
    return actor, activity, sale


def make_invoice(db):
    actor, activity, sale = make_sale(db)
    grant = uid()
    db.execute("INSERT INTO authorization_grants(authorization_grant_id,subject_person_id,action_code,scope_ref,"
               "context_ref,state,effective_from,created_at,updated_at) "
               "VALUES(?,?, 'issue_invoice', ?, ?, 'active', ?, ?, ?)",
               (grant, actor, sale, activity, "2026-10-01T00:00:00Z", STAMP, STAMP))
    db.commit()
    issued = InvoiceApplication().create_invoice(
        db, sale_id=sale, actor_context_ref=actor, authorization_grant_id=grant,
        issuer_ref=actor, invoice_number="INV-AUDIT-CORRUPTION", now=STAMP,
    )
    return str(issued.invoice.id)


@pytest.mark.parametrize(
    ("column", "value", "finding"),
    [
        ("state", "corrupt-state", "sale_state_invalid"),
        ("version_no", 0, "sale_version_invalid"),
        ("updated_at", "2026-10-10T11:59:59Z", "sale_lifecycle_timestamp_order_invalid"),
    ],
)
def test_sale_history_audit_fails_closed_on_corrupt_persisted_fields(column, value, finding):
    db = connect_database()
    _actor, _activity, sale = make_sale(db)
    db.execute("PRAGMA ignore_check_constraints=ON")
    db.execute(f"UPDATE sales SET {column}=? WHERE sale_id=?", (value, sale))
    db.commit()

    result = SaleHistoryIntegrityAuditor().inspect(db, sale)

    assert result.status == "inconsistent"
    assert finding in result.findings
    db.close()


@pytest.mark.parametrize(
    ("column", "value", "finding"),
    [
        ("state", "corrupt-state", "invoice_state_invalid"),
        ("version_no", 0, "invoice_version_invalid"),
        ("updated_at", "2026-10-10T11:59:59Z", "invoice_lifecycle_timestamp_order_invalid"),
    ],
)
def test_invoice_history_audit_fails_closed_on_corrupt_persisted_fields(column, value, finding):
    db = connect_database()
    invoice_id = make_invoice(db)
    db.execute("PRAGMA ignore_check_constraints=ON")
    db.execute(f"UPDATE invoices SET {column}=? WHERE invoice_id=?", (value, invoice_id))
    db.commit()

    result = InvoiceHistoryIntegrityAuditor().inspect(db, invoice_id)

    assert result.status == "inconsistent"
    assert finding in result.findings
    db.close()
