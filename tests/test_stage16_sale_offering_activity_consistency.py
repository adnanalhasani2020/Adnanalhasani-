"""Stage 16 — database-enforced Sale/Offering Activity consistency.

Traceability: SPEC-0005 §10.1, §§28.6–28.7, invariants 16–17, AC-04.
The migration must preserve pre-existing rows and must not silently repair history.
"""
import sqlite3
import uuid

import pytest

from agent_core.persistence import MIGRATIONS_DIR, connect_database
from agent_core.application import SaleApplication
from agent_core.shared import ValidationError


NOW = "2026-10-09T00:00:00Z"


def _id():
    return str(uuid.uuid4())


def _activity(db):
    person_id, activity_id = _id(), _id()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (person_id, "active", NOW, NOW),
    )
    db.execute(
        "INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) VALUES(?,?,?,?,?)",
        (activity_id, person_id, "active", NOW, NOW),
    )
    return activity_id


def _product(db):
    product_id = _id()
    db.execute(
        "INSERT INTO products(product_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (product_id, "active", NOW, NOW),
    )
    return product_id


def _offering(db, product_id, activity_id):
    offering_id = _id()
    db.execute(
        "INSERT INTO offerings(offering_id,product_id,activity_id,state,effective_from,effective_to,created_at,updated_at) "
        "VALUES(?,?,?,?,?,?,?,?)",
        (offering_id, product_id, activity_id, "active", NOW, None, NOW, NOW),
    )
    return offering_id


def _sale(db, offering_id, activity_id, sale_id=None):
    sale_id = sale_id or _id()
    db.execute(
        "INSERT INTO sales(sale_id,offering_id,activity_id,state,occurred_at,created_at,updated_at) "
        "VALUES(?,?,?,?,?,?,?)",
        (sale_id, offering_id, activity_id, "confirmed", NOW, NOW, NOW),
    )
    return sale_id


def _invoice(db, sale_id, invoice_id=None):
    invoice_id = invoice_id or _id()
    db.execute(
        "INSERT INTO invoices(invoice_id,sale_id,obligation_id,state,issuer_ref,invoice_number,issue_at,created_at,updated_at) "
        "VALUES(?,?,NULL,?,?,?,?,?,?)",
        (invoice_id, sale_id, "issued", "issuer-test", f"INV-{invoice_id}", NOW, NOW, NOW),
    )
    return invoice_id


def test_matching_sales_and_multiple_offerings_for_one_product_across_activities(tmp_path):
    db = connect_database(tmp_path / "multi-activity.sqlite")
    activity_a, activity_b = _activity(db), _activity(db)
    product_id = _product(db)
    offering_a = _offering(db, product_id, activity_a)
    offering_b = _offering(db, product_id, activity_b)

    sale_a = _sale(db, offering_a, activity_a)
    sale_b = _sale(db, offering_b, activity_b)
    db.commit()

    assert db.execute(
        "SELECT offering_id,activity_id FROM sales ORDER BY offering_id"
    ).fetchall() == sorted([(offering_a, activity_a), (offering_b, activity_b)])
    assert db.execute(
        "SELECT COUNT(DISTINCT product_id) FROM offerings WHERE product_id=?",
        (product_id,),
    ).fetchone()[0] == 1
    assert sale_a != sale_b
    assert db.execute("PRAGMA foreign_key_check").fetchall() == []
    db.close()


def test_insert_rejects_sale_with_activity_different_from_offering(tmp_path):
    db = connect_database(tmp_path / "reject-insert.sqlite")
    offering_activity = _activity(db)
    other_activity = _activity(db)
    offering_id = _offering(db, _product(db), offering_activity)

    with pytest.raises(sqlite3.IntegrityError, match="sale activity must match offering activity"):
        _sale(db, offering_id, other_activity)
    db.close()


def test_update_rejects_sale_activity_or_offering_change_that_breaks_context(tmp_path):
    db = connect_database(tmp_path / "reject-sale-update.sqlite")
    activity_a, activity_b = _activity(db), _activity(db)
    product_id = _product(db)
    offering_a = _offering(db, product_id, activity_a)
    offering_b = _offering(db, product_id, activity_b)
    sale_id = _sale(db, offering_a, activity_a)

    with pytest.raises(sqlite3.IntegrityError, match="sale activity must match offering activity"):
        db.execute("UPDATE sales SET activity_id=? WHERE sale_id=?", (activity_b, sale_id))
    with pytest.raises(sqlite3.IntegrityError, match="sale activity must match offering activity"):
        db.execute("UPDATE sales SET offering_id=? WHERE sale_id=?", (offering_b, sale_id))

    assert db.execute(
        "SELECT offering_id,activity_id FROM sales WHERE sale_id=?", (sale_id,)
    ).fetchone() == (offering_a, activity_a)
    db.close()


def test_offering_activity_update_is_rejected_when_it_would_break_existing_sales(tmp_path):
    db = connect_database(tmp_path / "reject-offering-update.sqlite")
    activity_a, activity_b = _activity(db), _activity(db)
    offering_id = _offering(db, _product(db), activity_a)
    _sale(db, offering_id, activity_a)

    with pytest.raises(
        sqlite3.IntegrityError,
        match="offering activity update would break sale activity context",
    ):
        db.execute(
            "UPDATE offerings SET activity_id=?,updated_at=? WHERE offering_id=?",
            (activity_b, NOW, offering_id),
        )

    assert db.execute(
        "SELECT activity_id FROM offerings WHERE offering_id=?", (offering_id,)
    ).fetchone()[0] == activity_a
    db.close()


def _make_legacy_populated_database(path):
    """Create a pre-0003 database with sales, invoices, and one historical mismatch."""
    db = sqlite3.connect(path)
    db.execute("PRAGMA foreign_keys=ON")
    for migration_name in (
        "0001_initial_relational_schema.sql",
        "0002_prevent_person_canonical_cycles.sql",
    ):
        db.executescript((MIGRATIONS_DIR / migration_name).read_text(encoding="utf-8"))
        version = migration_name.removesuffix(".sql")
        db.execute(
            "INSERT OR IGNORE INTO schema_migrations(version,applied_at) VALUES(?,?)",
            (version, NOW),
        )

    offering_activity = _activity(db)
    historical_sale_activity = _activity(db)
    product_id = _product(db)
    offering_id = _offering(db, product_id, offering_activity)
    sale_id = _sale(db, offering_id, historical_sale_activity)
    # Legacy completed rows must survive the additive lifecycle migration unchanged.
    db.execute("UPDATE sales SET state='completed' WHERE sale_id=?", (sale_id,))
    invoice_id = _invoice(db, sale_id)
    db.commit()
    db.close()
    return offering_activity, historical_sale_activity, product_id, offering_id, sale_id, invoice_id


def test_existing_database_migration_preserves_sales_invoices_and_history_on_reopen(tmp_path):
    path = tmp_path / "existing-populated-database.sqlite"
    (
        offering_activity,
        historical_sale_activity,
        product_id,
        offering_id,
        sale_id,
        invoice_id,
    ) = _make_legacy_populated_database(path)

    # connect_database applies the additive lifecycle migration to this pre-existing database.
    db = connect_database(path)
    assert db.execute(
        "SELECT version FROM schema_migrations ORDER BY version"
    ).fetchall() == [
        ("0001_initial_relational_schema",),
        ("0002_prevent_person_canonical_cycles",),
        ("0003_enforce_sale_offering_activity_consistency",),
        ("0004_sale_fulfilled_compatibility",),
        ("0005_inventory_position_offering_context",),
        ("0006_inventory_quantity_integer",),
        ("0007_product_name",),
        ("0008_financial_opening_balance",),
        ("0013_inventory_opening_stock",),
        ("0014_product_units",),
        ("0015_purchases",),
        ("0016_supplier_invoices",),
        ("0017_purchase_line_unit_snapshot",),
        ("0018_purchase_unit_snapshot_catalog_guards",),
        ("0019_supplier_invoice_database_guards",),
        ("0020_purchase_receipt_database_guards",),
        ("0021_supplier_invoice_header_line_consistency",),
        ("0026_unit_retirement_is_irreversible",),
        ("0027_unit_code_format_guards",),
        ("0028_currency_code_representation_guards",),
        ("0029_ledger_currency_consistency_guards",),
        ("0030_linked_financial_currency_consistency",),
        ("0031_settlement_recognition_currency_consistency",),
        ("0032_payment_obligation_settlement_link_guard",),
        ("9998_activity_invitations",),
        ("9999_file_attachments",),
    ]
    assert db.execute(
        "SELECT sale_id,offering_id,activity_id,state FROM sales WHERE sale_id=?",
        (sale_id,),
    ).fetchone() == (sale_id, offering_id, historical_sale_activity, "completed")
    # No historical row is rewritten: the canonical column stays NULL for legacy records.
    assert db.execute(
        "SELECT lifecycle_state FROM sales WHERE sale_id=?", (sale_id,)
    ).fetchone() == (None,)
    assert db.execute(
        "SELECT invoice_id,sale_id,state,invoice_number FROM invoices WHERE invoice_id=?",
        (invoice_id,),
    ).fetchone() == (
        invoice_id,
        sale_id,
        "issued",
        f"INV-{invoice_id}",
    )
    assert db.execute(
        "SELECT product_id,state FROM products WHERE product_id=?", (product_id,)
    ).fetchone() == (product_id, "active")

    # Migration does not silently rewrite an old mismatch. The audit remains explicit.
    mismatches = db.execute(
        "SELECT s.sale_id,s.activity_id,o.activity_id "
        "FROM sales AS s JOIN offerings AS o ON o.offering_id=s.offering_id "
        "WHERE s.activity_id <> o.activity_id"
    ).fetchall()
    assert mismatches == [(sale_id, historical_sale_activity, offering_activity)]
    assert db.execute("PRAGMA foreign_key_check").fetchall() == []
    db.commit()
    db.close()

    # The same persisted sales/invoice references survive another connection.
    db = connect_database(path)
    assert db.execute(
        "SELECT sale_id,offering_id,activity_id FROM sales WHERE sale_id=?", (sale_id,)
    ).fetchone() == (sale_id, offering_id, historical_sale_activity)
    assert db.execute(
        "SELECT invoice_id,sale_id FROM invoices WHERE invoice_id=?", (invoice_id,)
    ).fetchone() == (invoice_id, sale_id)
    assert db.execute("PRAGMA foreign_key_check").fetchall() == []
    db.close()


def test_preexisting_mismatch_can_be_explicitly_reconciled_without_losing_invoice(tmp_path):
    path = tmp_path / "explicit-reconciliation.sqlite"
    (
        offering_activity,
        historical_sale_activity,
        _product_id,
        _offering_id,
        sale_id,
        invoice_id,
    ) = _make_legacy_populated_database(path)
    db = connect_database(path)

    # This is an explicit repair action, not part of the migration.
    db.execute(
        "UPDATE sales SET activity_id=?,updated_at=? WHERE sale_id=?",
        (offering_activity, NOW, sale_id),
    )
    assert db.execute(
        "SELECT activity_id FROM sales WHERE sale_id=?", (sale_id,)
    ).fetchone()[0] == offering_activity
    assert db.execute(
        "SELECT sale_id FROM invoices WHERE invoice_id=?", (invoice_id,)
    ).fetchone()[0] == sale_id
    assert db.execute("PRAGMA foreign_key_check").fetchall() == []
    db.close()


def test_sale_restore_rejects_preserved_legacy_offering_activity_mismatch(tmp_path):
    path = tmp_path / "legacy-mismatch-sale-restore.sqlite"
    db = connect_database(path)
    activity_a, activity_b = _activity(db), _activity(db)
    offering_id = _offering(db, _product(db), activity_a)
    sale_id = _sale(db, offering_id, activity_a)
    db.execute(
        "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,"
        "historical_at,actor_context_ref,prior_version_ref,current_version_ref,change_payload_ref,created_at) "
        "VALUES(?,?,?,?,?,?,?,?,?,?)",
        (_id(), "commerce", sale_id, "sale_state_transition", NOW, "legacy-test-actor",
         None, f"sale-state:{sale_id}:v1", "state:initiated", NOW),
    )
    db.commit()

    # Simulate a pre-enforcement/legacy database that retained a cross-Activity row.
    # Current-schema triggers prevent this mismatch during normal writes.
    triggers = db.execute(
        "SELECT name FROM sqlite_master WHERE type='trigger'"
    ).fetchall()
    for (trigger_name,) in triggers:
        quoted = trigger_name.replace('"', '""')
        db.execute(f'DROP TRIGGER "{quoted}"')
    db.execute(
        "UPDATE sales SET activity_id=? WHERE sale_id=?",
        (activity_b, sale_id),
    )
    db.commit()
    db.close()

    db = connect_database(path)
    assert db.execute(
        "SELECT activity_id FROM sales WHERE sale_id=?", (sale_id,)
    ).fetchone()[0] == activity_b
    with pytest.raises(ValidationError, match="does not match its persisted Offering Activity"):
        SaleApplication().get_sale(db, sale_id)
    # Reading fails closed; no silent repair or deletion occurs.
    assert db.execute(
        "SELECT activity_id FROM sales WHERE sale_id=?", (sale_id,)
    ).fetchone()[0] == activity_b
    db.close()
