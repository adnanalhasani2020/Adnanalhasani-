"""Integration tests for Product persistence and lifecycle."""
import uuid

import pytest

from agent_core.application import ProductApplication
from agent_core.domain_inventory import ProductState
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

NOW = "2026-10-10T12:00:00Z"


def uid():
    return str(uuid.uuid4())


def test_product_create_restore_lifecycle_and_version_after_reopen(tmp_path):
    path = tmp_path / "product.sqlite"
    db = connect_database(path)
    app = ProductApplication()
    created = app.create_product(db, "  Reusable Bottle  ", now=NOW)
    assert created.product.name == "Reusable Bottle"
    assert created.product.state is ProductState.DRAFT
    assert created.version_no == 1
    product_id = str(created.product.id)
    db.close()

    db = connect_database(path)
    restored = app.get_product(db, product_id)
    assert restored.product.name == "Reusable Bottle"
    assert restored.product.state is ProductState.DRAFT
    active = app.transition_product(db, product_id, "activate", expected_version=1, now=NOW)
    assert active.product.state is ProductState.ACTIVE
    assert active.version_no == 2
    retired = app.transition_product(db, product_id, "retire", expected_version=2, now=NOW)
    assert retired.product.state is ProductState.RETIRED
    assert retired.version_no == 3
    assert app.get_product(db, product_id).product.state is ProductState.RETIRED
    db.close()


def test_product_rejects_blank_name_unknown_action_and_stale_version():
    db = connect_database()
    app = ProductApplication()
    with pytest.raises(ValidationError, match="name is required"):
        app.create_product(db, "  ", now=NOW)
    created = app.create_product(db, "Lamp", now=NOW)
    product_id = str(created.product.id)
    with pytest.raises(ValidationError, match="Unsupported Product action"):
        app.transition_product(db, product_id, "delete", expected_version=1, now=NOW)
    app.transition_product(db, product_id, "activate", expected_version=1, now=NOW)
    with pytest.raises(ValidationError, match="version conflict"):
        app.transition_product(db, product_id, "retire", expected_version=1, now=NOW)
    assert app.get_product(db, product_id).product.state is ProductState.ACTIVE
    db.close()


def test_product_name_migration_does_not_fabricate_legacy_names():
    db = connect_database()
    product_id = uid()
    db.execute(
        "INSERT INTO products(product_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (product_id, "active", NOW, NOW),
    )
    db.commit()
    with pytest.raises(ValidationError, match="explicit legacy reconciliation"):
        ProductApplication().get_product(db, product_id)
    db.close()


def test_product_lifecycle_does_not_delete_offering_or_sale_history():
    db = connect_database()
    person_id, activity_id, product_id, offering_id, sale_id = uid(), uid(), uid(), uid(), uid()
    db.execute("INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
               (person_id, "active", NOW, NOW))
    db.execute("INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) VALUES(?,?,?,?,?)",
               (activity_id, person_id, "active", NOW, NOW))
    app = ProductApplication()
    app.create_product(db, "Widget", product_id=product_id, now=NOW)
    db.execute(
        "INSERT INTO offerings(offering_id,product_id,activity_id,state,effective_from,created_at,updated_at) "
        "VALUES(?,?,?,?,?,?,?)",
        (offering_id, product_id, activity_id, "active", NOW, NOW, NOW),
    )
    db.execute(
        "INSERT INTO sales(sale_id,offering_id,activity_id,state,occurred_at,created_at,updated_at) "
        "VALUES(?,?,?,?,?,?,?)",
        (sale_id, offering_id, activity_id, "confirmed", NOW, NOW, NOW),
    )
    db.commit()
    app.transition_product(db, product_id, "retire", expected_version=1, now=NOW)
    assert db.execute("SELECT state FROM products WHERE product_id=?", (product_id,)).fetchone()[0] == "retired"
    assert db.execute("SELECT COUNT(*) FROM offerings WHERE offering_id=?", (offering_id,)).fetchone()[0] == 1
    assert db.execute("SELECT COUNT(*) FROM sales WHERE sale_id=?", (sale_id,)).fetchone()[0] == 1
    db.close()
