import hashlib
import json
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



def test_supplier_invoice_restore_rejects_unsupported_persisted_state():
    db, actor, activity, purchase_id, line = fixture()
    invoice = record_invoice(db, actor, activity, purchase_id, line)
    db.execute("PRAGMA ignore_check_constraints=ON")
    db.execute(
        "UPDATE supplier_invoices SET state='void' WHERE supplier_invoice_id=?",
        (str(invoice.id),),
    )
    db.execute("PRAGMA ignore_check_constraints=OFF")

    with pytest.raises(ValidationError, match="unsupported state"):
        SupplierInvoiceApplication().get_supplier_invoice(db, invoice.id)
    db.close()


def test_supplier_invoice_cannot_be_voided_until_void_workflow_exists():
    db, actor, activity, purchase_id, line = fixture()
    invoice = record_invoice(db, actor, activity, purchase_id, line)
    with pytest.raises(sqlite3.IntegrityError):
        db.execute(
            "UPDATE supplier_invoices SET state='void' WHERE supplier_invoice_id=?",
            (str(invoice.id),),
        )
    assert SupplierInvoiceApplication().get_supplier_invoice(db, invoice.id).state == "recorded"
    db.close()


def test_supplier_invoice_price_variance_is_explicit_read_only_and_signed():
    db, actor, activity, purchase_id, line = fixture()
    invoice = record_invoice(db, actor, activity, purchase_id, line, qty=2)
    app = SupplierInvoiceApplication()

    variances = app.get_supplier_invoice_price_variances(db, invoice.id)
    assert len(variances) == 1
    assert variances[0].expected_unit_price_minor == 1000
    assert variances[0].invoiced_unit_price_minor == 1250
    assert variances[0].unit_variance_minor == 250
    assert variances[0].total_variance_minor == 500
    assert variances[0].currency_code == "YER"
    assert db.execute("SELECT COUNT(*) FROM obligations").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM financial_transactions").fetchone()[0] == 0

    # A lower supplier price must be reported as a negative variance, not normalized away.
    db.execute(
        "UPDATE supplier_invoice_lines SET unit_price_minor=750,line_total_minor=1500 "
        "WHERE supplier_invoice_id=?", (str(invoice.id),),
    )
    db.execute(
        "UPDATE supplier_invoices SET subtotal_minor=1500,total_minor=1650 "
        "WHERE supplier_invoice_id=?", (str(invoice.id),),
    )
    db.execute(
        "UPDATE domain_history SET change_payload_ref=? WHERE target_ref=? "
        "AND change_type='supplier_invoice_recorded'",
        (f"purchase:{purchase_id};total_minor:1650;currency:YER", str(invoice.id)),
    )
    # Keep the synthetic record internally coherent: this test is about signed
    # variance reporting, not persistence-fingerprint corruption.
    header = db.execute(
        "SELECT purchase_id,recorded_by_ref,invoice_number,currency_code,tax_minor,freight_minor "
        "FROM supplier_invoices WHERE supplier_invoice_id=?",
        (str(invoice.id),),
    ).fetchone()
    stored_lines = db.execute(
        "SELECT purchase_line_id,quantity_units,unit_price_minor FROM supplier_invoice_lines "
        "WHERE supplier_invoice_id=? ORDER BY rowid",
        (str(invoice.id),),
    ).fetchall()
    fingerprint_payload = {
        "operation": "record_supplier_invoice",
        "purchase_id": header[0],
        "actor": header[1],
        "invoice_number": header[2],
        "currency_code": header[3],
        "tax_minor": header[4],
        "freight_minor": header[5],
        "lines": [
            {"purchase_line_id": line_id, "quantity_units": quantity, "unit_price_minor": price}
            for line_id, quantity, price in stored_lines
        ],
    }
    fingerprint = hashlib.sha256(json.dumps(
        fingerprint_payload, sort_keys=True, separators=(",", ":"), ensure_ascii=True
    ).encode("utf-8")).hexdigest()
    db.execute(
        "UPDATE supplier_invoices SET request_fingerprint=? WHERE supplier_invoice_id=?",
        (fingerprint, str(invoice.id)),
    )
    lower = app.get_supplier_invoice_price_variances(db, invoice.id)
    assert lower[0].unit_variance_minor == -250
    assert lower[0].total_variance_minor == -500
    db.close()


def test_supplier_invoice_restore_rejects_line_price_arithmetic_corruption():
    db, actor, activity, purchase_id, line = fixture()
    invoice = record_invoice(db, actor, activity, purchase_id, line)

    # Simulate damaged persisted data even though normal SQLite writes enforce CHECKs.
    db.execute("PRAGMA ignore_check_constraints=ON")
    db.execute(
        "UPDATE supplier_invoice_lines SET unit_price_minor=9999 WHERE supplier_invoice_id=?",
        (str(invoice.id),),
    )
    db.execute("PRAGMA ignore_check_constraints=OFF")

    with pytest.raises(ValidationError, match="line total disagrees with quantity and unit price"):
        SupplierInvoiceApplication().get_supplier_invoice(db, invoice.id)
    db.close()


def test_supplier_invoice_restore_rejects_persisted_overbilling():
    db, actor, activity, purchase_id, line = fixture()
    invoice = record_invoice(db, actor, activity, purchase_id, line)
    # Disable the database guard only for this deliberate corruption test;
    # ordinary writes are covered by the guard-specific tests below.
    db.execute("DROP TRIGGER trg_supplier_invoice_line_received_quantity_guard_update")
    db.execute(
        "UPDATE supplier_invoice_lines SET quantity_units=3,line_total_minor=3750 "
        "WHERE supplier_invoice_id=?", (str(invoice.id),),
    )
    db.execute(
        "UPDATE supplier_invoices SET subtotal_minor=3750,total_minor=3900 "
        "WHERE supplier_invoice_id=?", (str(invoice.id),),
    )
    db.execute(
        "UPDATE domain_history SET change_payload_ref=? WHERE target_ref=? "
        "AND change_type='supplier_invoice_recorded'",
        (f"purchase:{purchase_id};total_minor:3900;currency:YER", str(invoice.id)),
    )
    with pytest.raises(ValidationError, match="exceed received Purchase quantity"):
        SupplierInvoiceApplication().get_supplier_invoice(db, invoice.id)
    db.close()



def test_supplier_invoice_restore_rejects_malformed_persisted_actor_identifier():
    db, actor, activity, purchase_id, line = fixture()
    invoice = record_invoice(db, actor, activity, purchase_id, line)
    db.execute(
        "UPDATE supplier_invoices SET recorded_by_ref='not-a-uuid' WHERE supplier_invoice_id=?",
        (str(invoice.id),),
    )
    with pytest.raises(ValidationError, match="Persisted supplier invoice actor identifier must be a valid UUID"):
        SupplierInvoiceApplication().get_supplier_invoice(db, invoice.id)
    db.close()



def test_supplier_invoice_restore_rejects_malformed_persisted_timestamp():
    db, actor, activity, purchase_id, line = fixture()
    invoice = record_invoice(db, actor, activity, purchase_id, line)
    db.execute(
        "UPDATE supplier_invoices SET recorded_at='not-a-timestamp' WHERE supplier_invoice_id=?",
        (str(invoice.id),),
    )
    with pytest.raises(ValidationError, match="Persisted supplier invoice timestamp is invalid"):
        SupplierInvoiceApplication().get_supplier_invoice(db, invoice.id)
    db.close()


def test_supplier_invoice_restore_rejects_currency_mismatch_with_purchase_baseline():
    db, actor, activity, purchase_id, line = fixture()
    invoice = record_invoice(db, actor, activity, purchase_id, line)
    db.execute("DROP TRIGGER trg_supplier_invoice_currency_matches_existing_lines_update")
    db.execute(
        "UPDATE supplier_invoices SET currency_code='USD' WHERE supplier_invoice_id=?",
        (str(invoice.id),),
    )
    db.execute(
        "UPDATE domain_history SET change_payload_ref=? WHERE target_ref=? "
        "AND change_type='supplier_invoice_recorded'",
        (f"purchase:{purchase_id};total_minor:{invoice.total_minor};currency:USD", str(invoice.id)),
    )
    with pytest.raises(
        ValidationError,
        match="Persisted supplier invoice currency differs from Purchase line currency",
    ):
        SupplierInvoiceApplication().get_supplier_invoice(db, invoice.id)
    db.close()


def test_supplier_invoice_restore_rejects_request_fingerprint_corruption():
    db, actor, activity, purchase_id, line = fixture()
    invoice = record_invoice(db, actor, activity, purchase_id, line)
    db.execute(
        "UPDATE supplier_invoices SET request_fingerprint='corrupted-fingerprint' WHERE supplier_invoice_id=?",
        (str(invoice.id),),
    )

    with pytest.raises(
        ValidationError,
        match="Supplier invoice request fingerprint disagrees with persisted invoice facts",
    ):
        SupplierInvoiceApplication().get_supplier_invoice(db, invoice.id)
    db.close()


def test_database_guard_rejects_supplier_invoice_for_different_supplier():
    db, actor, activity, purchase_id, line = fixture()
    invoice = record_invoice(db, actor, activity, purchase_id, line)
    with pytest.raises(sqlite3.IntegrityError, match="supplier must match its Purchase"):
        db.execute(
            "UPDATE supplier_invoices SET supplier_ref='supplier:forged' WHERE supplier_invoice_id=?",
            (str(invoice.id),),
        )
    assert SupplierInvoiceApplication().get_supplier_invoice(db, invoice.id).supplier_ref == "supplier:alpha"
    db.close()


def test_database_guard_rejects_invoice_quantity_beyond_received_total():
    db, actor, activity, purchase_id, line = fixture()
    record_invoice(db, actor, activity, purchase_id, line, qty=2)
    second_invoice_id = str(uuid4())
    db.execute(
        "INSERT INTO supplier_invoices(supplier_invoice_id,purchase_id,supplier_ref,invoice_number,currency_code,"
        "subtotal_minor,tax_minor,freight_minor,total_minor,state,recorded_at,recorded_by_ref,idempotency_key,"
        "request_fingerprint,version_no) VALUES(?,?,?,?,?,?,?,?,?,'recorded',?,?,?,?,1)",
        (second_invoice_id, purchase_id, "supplier:alpha", "SUP-INV-DB-GUARD", "YER",
         1, 0, 0, 1, NOW, actor, "db-guard-key", "placeholder"),
    )
    with pytest.raises(sqlite3.IntegrityError, match="exceeds uninvoiced received quantity"):
        db.execute(
            "INSERT INTO supplier_invoice_lines(supplier_invoice_line_id,supplier_invoice_id,purchase_id,"
            "purchase_line_id,quantity_units,unit_price_minor,line_total_minor) VALUES(?,?,?,?,?,?,?)",
            (str(uuid4()), second_invoice_id, purchase_id, str(line.id), 1, 1, 1),
        )
    db.close()


def test_database_guard_rejects_invoice_currency_different_from_purchase_baseline():
    db, actor, activity, purchase_id, line = fixture()
    invoice_id = str(uuid4())
    db.execute(
        "INSERT INTO supplier_invoices(supplier_invoice_id,purchase_id,supplier_ref,invoice_number,currency_code,"
        "subtotal_minor,tax_minor,freight_minor,total_minor,state,recorded_at,recorded_by_ref,idempotency_key,"
        "request_fingerprint,version_no) VALUES(?,?,?,?,?,?,?,?,?,'recorded',?,?,?,?,1)",
        (invoice_id, purchase_id, "supplier:alpha", "SUP-INV-WRONG-CURRENCY", "USD",
         1, 0, 0, 1, NOW, actor, "wrong-currency-db-key", "placeholder"),
    )
    with pytest.raises(sqlite3.IntegrityError, match="currency must match its Purchase line currency"):
        db.execute(
            "INSERT INTO supplier_invoice_lines(supplier_invoice_line_id,supplier_invoice_id,purchase_id,"
            "purchase_line_id,quantity_units,unit_price_minor,line_total_minor) VALUES(?,?,?,?,?,?,?)",
            (str(uuid4()), invoice_id, purchase_id, str(line.id), 1, 1, 1),
        )
    db.close()



def _create_second_purchase(db, actor, activity):
    product = str(uuid4())
    db.execute(
        "INSERT INTO products(product_id,state,created_at,updated_at,name) "
        "VALUES(?, 'active', ?, ?, 'Second Widget')", (product, NOW, NOW),
    )
    line = PurchaseLine(
        product_id=UUID(product), quantity_units=1, unit_label="piece",
        unit_cost_minor=1000, currency_code="YER",
    )
    app = PurchaseApplication()
    record = app.create_purchase(
        db, "supplier:alpha", activity, (line,), actor,
        grant(db, actor, "create_purchase", activity, activity),
        idempotency_key="purchase-create-second-" + str(uuid4()), now=NOW,
    )
    return str(record.purchase.id), line


def test_database_guard_rejects_invoice_line_from_another_purchase():
    db, actor, activity, purchase_id, line = fixture()
    invoice = record_invoice(db, actor, activity, purchase_id, line, qty=1)
    other_purchase_id, other_line = _create_second_purchase(db, actor, activity)
    with pytest.raises(sqlite3.IntegrityError, match="line Purchase must match its invoice header"):
        db.execute(
            "INSERT INTO supplier_invoice_lines(supplier_invoice_line_id,supplier_invoice_id,purchase_id,"
            "purchase_line_id,quantity_units,unit_price_minor,line_total_minor) VALUES(?,?,?,?,?,?,?)",
            (str(uuid4()), str(invoice.id), other_purchase_id, str(other_line.id), 1, 1000, 1000),
        )
    db.close()


def test_database_guard_rejects_changing_invoice_purchase_away_from_existing_lines():
    db, actor, activity, purchase_id, line = fixture()
    invoice = record_invoice(db, actor, activity, purchase_id, line, qty=1)
    other_purchase_id, _ = _create_second_purchase(db, actor, activity)
    with pytest.raises(sqlite3.IntegrityError, match="Purchase cannot diverge from its lines"):
        db.execute(
            "UPDATE supplier_invoices SET purchase_id=? WHERE supplier_invoice_id=?",
            (other_purchase_id, str(invoice.id)),
        )
    assert str(SupplierInvoiceApplication().get_supplier_invoice(db, invoice.id).purchase_id) == purchase_id
    db.close()


def test_database_guard_rejects_changing_invoice_currency_with_existing_lines():
    db, actor, activity, purchase_id, line = fixture()
    invoice = record_invoice(db, actor, activity, purchase_id, line, qty=1)
    with pytest.raises(sqlite3.IntegrityError, match="currency must match its Purchase line currency"):
        db.execute(
            "UPDATE supplier_invoices SET currency_code='USD' WHERE supplier_invoice_id=?",
            (str(invoice.id),),
        )
    assert SupplierInvoiceApplication().get_supplier_invoice(db, invoice.id).currency_code == "YER"
    db.close()
