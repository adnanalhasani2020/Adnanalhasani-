import sqlite3
from uuid import UUID, uuid4

import pytest

from agent_core.domain_purchases import PurchaseLine
from agent_core.persistence import connect_database
from agent_core.purchase_application import PurchaseApplication
from agent_core.shared import ValidationError
from agent_core.supplier_invoice_application import SupplierInvoiceApplication, SupplierInvoiceLine


NOW = "2026-10-13T12:00:00Z"


def grant(db, actor, action, scope, activity):
    grant_id = str(uuid4())
    db.execute(
        "INSERT INTO authorization_grants(authorization_grant_id,subject_person_id,action_code,scope_ref,"
        "context_ref,state,effective_from,effective_to,created_at,updated_at) "
        "VALUES(?,?,?,?,?,'active','2026-10-01T00:00:00Z',NULL,?,?)",
        (grant_id, actor, action, scope, activity, NOW, NOW),
    )
    return grant_id


def fixture():
    db = connect_database()
    actor, activity, product = str(uuid4()), str(uuid4()), str(uuid4())
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
        (actor, NOW, NOW),
    )
    db.execute(
        "INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) "
        "VALUES(?,?,'active',?,?)", (activity, actor, NOW, NOW),
    )
    db.execute(
        "INSERT INTO products(product_id,state,created_at,updated_at,name) "
        "VALUES(?, 'active', ?, ?, 'Widget')", (product, NOW, NOW),
    )
    db.commit()
    purchase_app = PurchaseApplication()
    line = PurchaseLine(
        product_id=UUID(product), quantity_units=3, unit_label="piece",
        unit_cost_minor=1000, currency_code="YER",
    )
    record = purchase_app.create_purchase(
        db, "supplier:alpha", activity, (line,), actor,
        grant(db, actor, "create_purchase", activity, activity),
        idempotency_key="purchase-create-1", now=NOW,
    )
    purchase_id = str(record.purchase.id)
    for action, action_code in (
        ("submit", "submit_purchase"), ("approve", "approve_purchase"), ("order", "order_purchase")
    ):
        purchase_app.transition_purchase(
            db, purchase_id, actor, grant(db, actor, action_code, purchase_id, activity),
            action, now=NOW,
        )
    purchase_app.receive_purchase(
        db, purchase_id, str(line.id), 2, actor,
        grant(db, actor, "receive_purchase", purchase_id, activity),
        idempotency_key="receipt-1", now=NOW,
    )
    db.commit()
    return db, actor, activity, purchase_id, line


def record_invoice(db, actor, activity, purchase_id, line, *, key="invoice-key", number="SUP-INV-1", qty=2):
    return SupplierInvoiceApplication().record_supplier_invoice(
        db, purchase_id=purchase_id, actor_context_ref=actor,
        authorization_grant_id=grant(db, actor, "record_supplier_invoice", purchase_id, activity),
        invoice_number=number, currency_code="YER",
        lines=(SupplierInvoiceLine(line.id, qty, 1250),),
        idempotency_key=key, tax_minor=100, freight_minor=50, now=NOW,
    )


def test_supplier_invoice_matches_received_quantity_and_keeps_finance_separate():
    db, actor, activity, purchase_id, line = fixture()
    invoice = record_invoice(db, actor, activity, purchase_id, line)
    assert invoice.purchase_id == UUID(purchase_id)
    assert invoice.supplier_ref == "supplier:alpha"
    assert invoice.subtotal_minor == 2500
    assert invoice.total_minor == 2650
    assert invoice.lines[0].quantity_units == 2
    assert invoice.lines[0].line_total_minor == 2500
    assert db.execute("SELECT COUNT(*) FROM supplier_invoices").fetchone()[0] == 1
    assert db.execute("SELECT COUNT(*) FROM obligations").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM payments").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM financial_transactions").fetchone()[0] == 0
    db.close()


def test_supplier_invoice_cannot_exceed_received_or_uninvoiced_quantity():
    db, actor, activity, purchase_id, line = fixture()
    with pytest.raises(ValidationError, match="exceeds received quantity"):
        record_invoice(db, actor, activity, purchase_id, line, qty=3)
    first = record_invoice(db, actor, activity, purchase_id, line, key="invoice-one", number="SUP-INV-1", qty=2)
    with pytest.raises(ValidationError, match="exceeds uninvoiced received quantity"):
        record_invoice(db, actor, activity, purchase_id, line, key="invoice-two", number="SUP-INV-2", qty=1)
    assert db.execute("SELECT COUNT(*) FROM supplier_invoices").fetchone()[0] == 1
    db.close()


def test_supplier_invoice_idempotency_returns_original_and_rejects_changed_payload():
    db, actor, activity, purchase_id, line = fixture()
    first = record_invoice(db, actor, activity, purchase_id, line)
    replay = record_invoice(db, actor, activity, purchase_id, line)
    assert replay.id == first.id
    assert db.execute("SELECT COUNT(*) FROM supplier_invoices").fetchone()[0] == 1
    with pytest.raises(ValidationError, match="different supplier invoice"):
        record_invoice(db, actor, activity, purchase_id, line, qty=1)
    db.close()


def test_supplier_invoice_requires_exact_authorization_and_supplier_number_is_unique():
    db, actor, activity, purchase_id, line = fixture()
    app = SupplierInvoiceApplication()
    wrong_grant = grant(db, actor, "receive_purchase", purchase_id, activity)
    with pytest.raises(ValidationError, match="does not permit action record_supplier_invoice"):
        app.record_supplier_invoice(
            db, purchase_id=purchase_id, actor_context_ref=actor,
            authorization_grant_id=wrong_grant, invoice_number="SUP-INV-1",
            currency_code="YER", lines=(SupplierInvoiceLine(line.id, 1, 1000),),
            idempotency_key="wrong-auth", now=NOW,
        )
    record_invoice(db, actor, activity, purchase_id, line, key="invoice-one", number="SUP-INV-1", qty=1)
    with pytest.raises(sqlite3.IntegrityError):
        record_invoice(db, actor, activity, purchase_id, line, key="invoice-two", number="SUP-INV-1", qty=1)
    assert db.execute("SELECT COUNT(*) FROM supplier_invoices").fetchone()[0] == 1
    db.close()
