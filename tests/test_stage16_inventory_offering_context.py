"""Inventory/commerce persistence guards that do not infer stock quantity policy."""
import sqlite3
import uuid

import pytest

from agent_core.persistence import connect_database

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


def _position(db, *, activity_id, product_id, offering_id, quantity=9):
    position_id = _id()
    db.execute(
        "INSERT INTO inventory_positions(inventory_position_id,activity_id,product_id,offering_id,location_ref,"
        "scope_key,state,quantity_minor,observed_at,effective_from,effective_to,created_at,updated_at) "
        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
        (position_id, activity_id, product_id, offering_id, "site-A", "site-A:offering",
         "effective", quantity, NOW, NOW, None, NOW, NOW),
    )
    return position_id


def test_inventory_position_offering_context_requires_matching_activity_and_product():
    db = connect_database()
    activity_a, activity_b = _activity(db), _activity(db)
    product_a, product_b = _product(db), _product(db)
    offering = _offering(db, product_a, activity_a)

    with pytest.raises(sqlite3.IntegrityError, match="context must match its offering"):
        _position(db, activity_id=activity_b, product_id=product_a, offering_id=offering)
    with pytest.raises(sqlite3.IntegrityError, match="context must match its offering"):
        _position(db, activity_id=activity_a, product_id=product_b, offering_id=offering)

    db.close()


def test_inventory_position_without_optional_product_still_supports_offering_context():
    db = connect_database()
    activity = _activity(db)
    product = _product(db)
    offering = _offering(db, product, activity)

    position = _position(db, activity_id=activity, product_id=None, offering_id=offering)
    row = db.execute(
        "SELECT activity_id,product_id,offering_id,quantity_minor FROM inventory_positions "
        "WHERE inventory_position_id=?",
        (position,),
    ).fetchone()
    assert row == (activity, None, offering, 9)
    db.close()


def test_inventory_position_context_cannot_be_changed_to_mismatch_offering():
    db = connect_database()
    activity_a, activity_b = _activity(db), _activity(db)
    product = _product(db)
    offering = _offering(db, product, activity_a)
    position = _position(db, activity_id=activity_a, product_id=product, offering_id=offering)

    with pytest.raises(sqlite3.IntegrityError, match="context must match its offering"):
        db.execute(
            "UPDATE inventory_positions SET activity_id=? WHERE inventory_position_id=?",
            (activity_b, position),
        )

    assert db.execute(
        "SELECT activity_id,product_id,offering_id,quantity_minor FROM inventory_positions "
        "WHERE inventory_position_id=?",
        (position,),
    ).fetchone() == (activity_a, product, offering, 9)
    db.close()


def test_offering_update_cannot_break_existing_inventory_position_context():
    db = connect_database()
    activity_a, activity_b = _activity(db), _activity(db)
    product_a, product_b = _product(db), _product(db)
    offering = _offering(db, product_a, activity_a)
    position = _position(db, activity_id=activity_a, product_id=product_a, offering_id=offering)

    with pytest.raises(sqlite3.IntegrityError, match="would break inventory position context"):
        db.execute("UPDATE offerings SET activity_id=? WHERE offering_id=?", (activity_b, offering))
    with pytest.raises(sqlite3.IntegrityError, match="would break inventory position context"):
        db.execute("UPDATE offerings SET product_id=? WHERE offering_id=?", (product_b, offering))
    # SQLite NULL comparisons must not let a populated inventory product lose
    # its Offering product context.
    with pytest.raises(sqlite3.IntegrityError, match="would break inventory position context"):
        db.execute("UPDATE offerings SET product_id=NULL WHERE offering_id=?", (offering,))

    assert db.execute(
        "SELECT activity_id,product_id FROM offerings WHERE offering_id=?", (offering,)
    ).fetchone() == (activity_a, product_a)
    assert db.execute(
        "SELECT activity_id,product_id,offering_id,quantity_minor FROM inventory_positions "
        "WHERE inventory_position_id=?",
        (position,),
    ).fetchone() == (activity_a, product_a, offering, 9)
    db.close()


def test_inventory_position_guards_do_not_change_quantity_or_rewrite_existing_rows():
    db = connect_database()
    activity = _activity(db)
    product = _product(db)
    offering = _offering(db, product, activity)
    position = _position(db, activity_id=activity, product_id=product, offering_id=offering, quantity=None)

    assert db.execute(
        "SELECT quantity_minor,scope_key,observed_at,state FROM inventory_positions "
        "WHERE inventory_position_id=?", (position,)
    ).fetchone() == (None, "site-A:offering", NOW, "effective")
    assert db.execute("PRAGMA foreign_key_check").fetchall() == []
    db.close()
