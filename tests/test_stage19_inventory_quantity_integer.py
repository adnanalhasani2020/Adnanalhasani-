"""Persistence guards for integer minor-unit inventory quantities."""
import sqlite3
import uuid
from datetime import datetime, timezone

import pytest

from agent_core.inventory_read import InventoryQuantityReader, InventoryQuantityStatus
from agent_core.persistence import MIGRATIONS_DIR, connect_database

NOW = "2026-10-10T00:00:00Z"


def _id():
    return str(uuid.uuid4())


def _base(db):
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


def _insert_position(db, activity_id, quantity):
    position_id = _id()
    db.execute(
        "INSERT INTO inventory_positions(inventory_position_id,activity_id,scope_key,state,"
        "quantity_minor,observed_at,created_at,updated_at) VALUES(?,?,?,'effective',?,?,?,?)",
        (position_id, activity_id, "site-A", quantity, NOW, NOW, NOW),
    )
    return position_id


@pytest.mark.parametrize("quantity", [1.5, "not-an-integer"])
def test_inventory_position_rejects_non_integer_quantity_on_insert(quantity):
    db = connect_database()
    activity_id = _base(db)

    with pytest.raises(sqlite3.IntegrityError, match="quantity_minor must be an integer"):
        _insert_position(db, activity_id, quantity)

    assert db.execute("SELECT COUNT(*) FROM inventory_positions").fetchone()[0] == 0
    db.close()


def test_inventory_position_rejects_non_integer_quantity_on_update_and_preserves_value():
    db = connect_database()
    activity_id = _base(db)
    position_id = _insert_position(db, activity_id, 7)

    with pytest.raises(sqlite3.IntegrityError, match="quantity_minor must be an integer"):
        db.execute(
            "UPDATE inventory_positions SET quantity_minor=? WHERE inventory_position_id=?",
            (2.5, position_id),
        )

    assert db.execute(
        "SELECT quantity_minor FROM inventory_positions WHERE inventory_position_id=?",
        (position_id,),
    ).fetchone() == (7,)
    db.close()


def test_inventory_position_allows_integer_and_unknown_quantity():
    db = connect_database()
    activity_id = _base(db)
    known_id = _insert_position(db, activity_id, 7)
    unknown_id = _insert_position(db, activity_id, None)

    assert db.execute(
        "SELECT quantity_minor FROM inventory_positions WHERE inventory_position_id=?",
        (known_id,),
    ).fetchone() == (7,)
    assert db.execute(
        "SELECT quantity_minor FROM inventory_positions WHERE inventory_position_id=?",
        (unknown_id,),
    ).fetchone() == (None,)
    db.close()



def test_quantity_guard_migration_preserves_legacy_invalid_row_and_reader_fails_closed(tmp_path):
    path = tmp_path / "legacy-invalid-inventory.sqlite"
    db = sqlite3.connect(path)
    db.execute("PRAGMA foreign_keys=ON")
    for migration_name in (
        "0001_initial_relational_schema.sql",
        "0002_prevent_person_canonical_cycles.sql",
        "0003_enforce_sale_offering_activity_consistency.sql",
        "0004_sale_fulfilled_compatibility.sql",
        "0005_inventory_position_offering_context.sql",
    ):
        db.executescript((MIGRATIONS_DIR / migration_name).read_text(encoding="utf-8"))
        db.execute(
            "INSERT OR IGNORE INTO schema_migrations(version,applied_at) VALUES(?,?)",
            (migration_name.removesuffix(".sql"), NOW),
        )

    activity_id = _id()
    person_id = _id()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (person_id, "active", NOW, NOW),
    )
    db.execute(
        "INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) VALUES(?,?,?,?,?)",
        (activity_id, person_id, "active", NOW, NOW),
    )
    position_id = _id()
    db.execute(
        "INSERT INTO inventory_positions(inventory_position_id,activity_id,scope_key,state,"
        "quantity_minor,observed_at,created_at,updated_at) VALUES(?,?,?,'effective',?,?,?,?)",
        (position_id, activity_id, "site-A", 2.5, NOW, NOW, NOW),
    )
    db.commit()
    db.close()

    # Applying the new guard to an existing database must not silently rewrite history.
    db = connect_database(path)
    assert db.execute(
        "SELECT quantity_minor FROM inventory_positions WHERE inventory_position_id=?",
        (position_id,),
    ).fetchone() == (2.5,)

    result = InventoryQuantityReader().read_quantity(
        db, "site-A", as_of=datetime(2026, 10, 10, 0, 0, tzinfo=timezone.utc)
    )
    assert result.status is InventoryQuantityStatus.INVALID_QUANTITY
    assert result.quantity_minor is None
    assert result.inventory_position_id == position_id

    with pytest.raises(sqlite3.IntegrityError, match="quantity_minor must be an integer"):
        _insert_position(db, activity_id, 3.5)
    db.close()
