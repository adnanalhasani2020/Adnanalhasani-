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


@pytest.mark.parametrize("invalid_timestamp", ["", 0, False])
def test_opening_stock_rejects_explicit_falsey_recorded_timestamp_without_writes(invalid_timestamp):
    db = connect_database()
    activity_id, product_id = seed(db)
    with pytest.raises(ValidationError, match="timestamp"):
        create_opening(db, activity_id, product_id, now=invalid_timestamp)
    assert db.execute("SELECT COUNT(*) FROM inventory_positions").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM inventory_opening_stock").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM domain_history").fetchone()[0] == 0
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


def test_opening_stock_rejects_existing_position_in_same_scope():
    db = connect_database()
    activity_id, product_id = seed(db)
    InventoryPositionApplication().create_position(
        db, activity_id=activity_id, product_id=product_id,
        scope_key="warehouse-A", quantity_minor=12,
        actor_context_ref="person:existing-position", now=STAMP,
    )
    with pytest.raises(ValidationError, match="already exists for this Product scope"):
        create_opening(db, activity_id, product_id)
    assert db.execute("SELECT COUNT(*) FROM inventory_positions").fetchone()[0] == 1
    assert db.execute("SELECT COUNT(*) FROM inventory_opening_stock").fetchone()[0] == 0
    db.close()


def test_product_quantity_reader_isolates_locations_with_same_scope_key():
    db = connect_database()
    activity_id, product_id = seed(db)
    create_opening(
        db, activity_id, product_id, location_ref="location-east",
        idempotency_key="opening-east", source_ref="count-east",
    )
    create_opening(
        db, activity_id, product_id, location_ref="location-west",
        idempotency_key="opening-west", source_ref="count-west", quantity_minor=29,
    )

    reader = InventoryApplication()
    east = reader.read_product_quantity(
        db, product_id, activity_id, "warehouse-A",
        location_ref="location-east", as_of=STAMP,
    )
    west = reader.read_product_quantity(
        db, product_id, activity_id, "warehouse-A",
        location_ref="location-west", as_of=STAMP,
    )
    default_location = reader.read_product_quantity(
        db, product_id, activity_id, "warehouse-A", as_of=STAMP,
    )
    assert east.status is InventoryQuantityStatus.KNOWN
    assert east.quantity_minor == 17
    assert west.status is InventoryQuantityStatus.KNOWN
    assert west.quantity_minor == 29
    assert default_location.status is InventoryQuantityStatus.NO_RECORD
    db.close()


def test_opening_stock_audit_row_cannot_be_changed_or_deleted():
    db = connect_database()
    activity_id, product_id = seed(db)
    create_opening(db, activity_id, product_id)
    with pytest.raises(Exception, match="immutable"):
        db.execute(
            "UPDATE inventory_opening_stock SET quantity_minor=99"
        )
    with pytest.raises(Exception, match="immutable"):
        db.execute("DELETE FROM inventory_opening_stock")
    assert db.execute(
        "SELECT quantity_minor FROM inventory_opening_stock"
    ).fetchone()[0] == 17
    db.close()


def test_opening_position_quantity_cannot_be_silently_rewritten_after_opening():
    db = connect_database()
    activity_id, product_id = seed(db)
    position = create_opening(db, activity_id, product_id)
    with pytest.raises(Exception, match="opening Inventory Position snapshot is immutable"):
        db.execute(
            "UPDATE inventory_positions SET quantity_minor=99 WHERE inventory_position_id=?",
            (str(position.id),),
        )
    assert db.execute(
        "SELECT quantity_minor FROM inventory_positions WHERE inventory_position_id=?",
        (str(position.id),),
    ).fetchone()[0] == 17
    # A lifecycle transition is still permitted; it does not rewrite the opening fact.
    closed = InventoryPositionApplication().transition_position(
        db, str(position.id), "close", actor_context_ref="person:close", now=STAMP,
    )
    assert closed.state.value == "closed"
    assert db.execute(
        "SELECT quantity_minor FROM inventory_opening_stock"
    ).fetchone()[0] == 17
    db.close()


def test_opening_stock_database_guard_rejects_mismatched_position_quantity():
    db = connect_database()
    activity_id, product_id = seed(db)
    position = create_opening(db, activity_id, product_id)
    with pytest.raises(Exception, match="must match its effective Inventory Position"):
        db.execute(
            "INSERT INTO inventory_opening_stock("
            "opening_stock_id,inventory_position_id,activity_id,product_id,offering_id,"
            "location_ref,scope_key,quantity_minor,source_ref,idempotency_key,"
            "actor_context_ref,opening_at,recorded_at) "
            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (
                uid(), str(position.id), activity_id, product_id, None, None,
                "warehouse-A", 999, "direct-write", "direct-write-key",
                "person:direct-write", STAMP, STAMP,
            ),
        )
    assert db.execute("SELECT COUNT(*) FROM inventory_opening_stock").fetchone()[0] == 1
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


def test_opening_stock_rejects_timestamp_outside_offering_validity_window():
    db = connect_database()
    activity_id, product_id = seed(db)
    offering_id = uid()
    db.execute(
        "INSERT INTO offerings(offering_id,product_id,activity_id,state,effective_from,created_at,updated_at) "
        "VALUES(?,?,?,'active',?,?,?)",
        (offering_id, product_id, activity_id, "2026-10-11T00:00:00Z", STAMP, STAMP),
    )
    db.commit()

    with pytest.raises(ValidationError, match="before its Offering validity window"):
        create_opening(
            db, activity_id, product_id,
            offering_id=offering_id,
            opening_at=STAMP,
            idempotency_key="opening-before-offering-start",
        )

    assert db.execute("SELECT COUNT(*) FROM inventory_positions").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM inventory_opening_stock").fetchone()[0] == 0
    db.close()
