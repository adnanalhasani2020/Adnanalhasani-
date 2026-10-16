"""Integration tests for idempotent opening stock and inventory read integration."""
import uuid

import pytest

from agent_core.application import InventoryApplication, InventoryPositionApplication
from agent_core.inventory_read import InventoryQuantityStatus
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

STAMP = "2026-10-10T12:00:00Z"


def uid():
    return str(uuid.uuid4())


def seed(db, *, product_state="active", activity_state="active"):
    person_id, activity_id, product_id = uid(), uid(), uid()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (person_id, "active", STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) "
        "VALUES(?,?,?,?,?)",
        (activity_id, person_id, activity_state, STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO products(product_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (product_id, product_state, STAMP, STAMP),
    )
    db.commit()
    return activity_id, product_id


def create_opening(db, activity_id, product_id, **overrides):
    values = {
        "activity_id": activity_id,
        "product_id": product_id,
        "scope_key": "warehouse-A",
        "quantity_minor": 17,
        "actor_context_ref": "person:opening-stock",
        "source_ref": "physical-count-2026-10",
        "idempotency_key": "opening-stock-warehouse-A-product-1",
        "opening_at": STAMP,
        "now": STAMP,
    }
    values.update(overrides)
    return InventoryPositionApplication().create_opening_stock(db, **values)


def test_opening_stock_is_effective_and_visible_through_product_reader():
    db = connect_database()
    activity_id, product_id = seed(db)
    position = create_opening(db, activity_id, product_id)

    assert position.state.value == "effective"
    assert position.quantity_minor == 17
    result = InventoryApplication().read_product_quantity(
        db, product_id, activity_id, "warehouse-A", as_of=STAMP
    )
    assert result.status is InventoryQuantityStatus.KNOWN
    assert result.quantity_minor == 17
    assert result.inventory_position_id == str(position.id)
    assert db.execute("SELECT COUNT(*) FROM inventory_opening_stock").fetchone()[0] == 1
    assert db.execute("PRAGMA foreign_key_check").fetchall() == []
    db.close()


def test_opening_stock_idempotent_replay_returns_same_position_without_duplicate_history():
    db = connect_database()
    activity_id, product_id = seed(db)
    first = create_opening(db, activity_id, product_id)
    second = create_opening(db, activity_id, product_id)

    assert second.id == first.id
    assert db.execute("SELECT COUNT(*) FROM inventory_positions").fetchone()[0] == 1
    assert db.execute("SELECT COUNT(*) FROM inventory_opening_stock").fetchone()[0] == 1
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=? "
        "AND change_type='inventory_position_lifecycle'",
        (str(first.id),),
    ).fetchone()[0] == 2
    db.close()


def test_opening_stock_rejects_idempotency_key_reuse_with_changed_payload():
    db = connect_database()
    activity_id, product_id = seed(db)
    create_opening(db, activity_id, product_id)
    with pytest.raises(ValidationError, match="reused with different data"):
        create_opening(db, activity_id, product_id, quantity_minor=18)
    assert db.execute("SELECT COUNT(*) FROM inventory_positions").fetchone()[0] == 1
    db.close()


def test_opening_stock_rejects_second_opening_for_same_product_scope():
    db = connect_database()
    activity_id, product_id = seed(db)
    create_opening(db, activity_id, product_id)
    with pytest.raises(ValidationError, match="already exists"):
        create_opening(
            db, activity_id, product_id,
            idempotency_key="a-different-key",
            source_ref="second-opening-document",
        )
    assert db.execute("SELECT COUNT(*) FROM inventory_opening_stock").fetchone()[0] == 1
    db.close()


@pytest.mark.parametrize(
    ("quantity", "message"),
    [(-1, "non-negative integer"), (1.5, "non-negative integer"), (True, "non-negative integer")],
)
def test_opening_stock_rejects_invalid_quantity_without_writes(quantity, message):
    db = connect_database()
    activity_id, product_id = seed(db)
    with pytest.raises(ValidationError, match=message):
        create_opening(db, activity_id, product_id, quantity_minor=quantity)
    assert db.execute("SELECT COUNT(*) FROM inventory_positions").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM inventory_opening_stock").fetchone()[0] == 0
    db.close()


def test_opening_stock_rejects_inactive_product_and_future_opening_date():
    db = connect_database()
    activity_id, product_id = seed(db, product_state="retired")
    with pytest.raises(ValidationError, match="active Product"):
        create_opening(db, activity_id, product_id)
    assert db.execute("SELECT COUNT(*) FROM inventory_positions").fetchone()[0] == 0
    db.close()

    db = connect_database()
    activity_id, product_id = seed(db)
    with pytest.raises(ValidationError, match="cannot be in the future"):
        create_opening(
            db, activity_id, product_id,
            opening_at="2026-10-11T12:00:00Z",
            now=STAMP,
        )
    assert db.execute("SELECT COUNT(*) FROM inventory_positions").fetchone()[0] == 0
    db.close()


def test_opening_stock_rolls_back_position_and_history_if_audit_write_fails():
    db = connect_database()
    activity_id, product_id = seed(db)
    db.execute(
        "CREATE TRIGGER fail_inventory_history BEFORE INSERT ON domain_history "
        "WHEN NEW.owner_domain='inventory' "
        "BEGIN SELECT RAISE(ABORT, 'audit unavailable'); END"
    )
    db.commit()
    with pytest.raises(Exception, match="audit unavailable"):
        create_opening(db, activity_id, product_id)
    assert db.execute("SELECT COUNT(*) FROM inventory_positions").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM inventory_opening_stock").fetchone()[0] == 0
    db.close()
