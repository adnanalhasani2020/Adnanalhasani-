from uuid import uuid4

import pytest

from agent_core.application import InventoryPositionApplication
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError


STAMP = "2026-10-14T12:00:00Z"


def _activity(db):
    person_id = str(uuid4())
    activity_id = str(uuid4())
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (person_id, "active", STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) "
        "VALUES(?,?,?,?,?)",
        (activity_id, person_id, "active", STAMP, STAMP),
    )
    return activity_id, person_id


def _product(db, name):
    product_id = str(uuid4())
    db.execute(
        "INSERT INTO products(product_id,name,state,created_at,updated_at) VALUES(?,?,?,?,?)",
        (product_id, name, "active", STAMP, STAMP),
    )
    return product_id


def _offering(db, product_id, activity_id):
    offering_id = str(uuid4())
    db.execute(
        "INSERT INTO offerings(offering_id,product_id,activity_id,state,effective_from,created_at,updated_at) "
        "VALUES(?,?,?,'active',?,?,?)",
        (offering_id, product_id, activity_id, STAMP, STAMP, STAMP),
    )
    return offering_id


def test_inventory_position_rejects_activity_mismatching_offering():
    db = connect_database()
    activity_a, actor_id = _activity(db)
    activity_b, _ = _activity(db)
    product_id = _product(db, "Product A")
    offering_id = _offering(db, product_id, activity_a)

    with pytest.raises(ValidationError, match="context must match its offering"):
        InventoryPositionApplication().create_position(
            db,
            activity_id=activity_b,
            product_id=product_id,
            offering_id=offering_id,
            scope_key="store-A",
            actor_context_ref=actor_id,
            now=STAMP,
        )

    assert db.execute("SELECT COUNT(*) FROM inventory_positions").fetchone()[0] == 0
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE owner_domain='inventory'"
    ).fetchone()[0] == 0


def test_inventory_position_rejects_product_mismatching_offering():
    db = connect_database()
    activity_id, actor_id = _activity(db)
    product_a = _product(db, "Product A")
    product_b = _product(db, "Product B")
    offering_id = _offering(db, product_a, activity_id)

    with pytest.raises(ValidationError, match="Product must match its Offering"):
        InventoryPositionApplication().create_position(
            db,
            activity_id=activity_id,
            product_id=product_b,
            offering_id=offering_id,
            scope_key="store-A",
            actor_context_ref=actor_id,
            now=STAMP,
        )

    assert db.execute("SELECT COUNT(*) FROM inventory_positions").fetchone()[0] == 0
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE owner_domain='inventory'"
    ).fetchone()[0] == 0


def test_inventory_position_can_still_be_unlinked_from_an_offering():
    db = connect_database()
    activity_id, actor_id = _activity(db)
    product_id = _product(db, "Standalone product")

    position = InventoryPositionApplication().create_position(
        db,
        activity_id=activity_id,
        product_id=product_id,
        scope_key="store-A",
        actor_context_ref=actor_id,
        now=STAMP,
    )

    assert position.product_id is not None
    assert position.offering_id is None


def test_inventory_position_transition_rejects_legacy_offering_context_mismatch():
    db = connect_database()
    activity_a, actor_id = _activity(db)
    activity_b, _ = _activity(db)
    product_id = _product(db, "Product A")
    offering_id = _offering(db, product_id, activity_a)
    app = InventoryPositionApplication()
    position = app.create_position(
        db,
        activity_id=activity_a,
        product_id=product_id,
        offering_id=offering_id,
        scope_key="store-A",
        actor_context_ref=actor_id,
        now=STAMP,
    )

    # Simulate a legacy database that predates the inventory context triggers.
    # This in-memory connection is isolated to this test; production schema is untouched.
    triggers = db.execute(
        "SELECT name FROM sqlite_master WHERE type='trigger' AND tbl_name='inventory_positions'"
    ).fetchall()
    for (trigger_name,) in triggers:
        safe_name = trigger_name.replace('"', '""')
        db.execute(f'DROP TRIGGER "{safe_name}"')
    db.execute(
        "UPDATE inventory_positions SET activity_id=? WHERE inventory_position_id=?",
        (activity_b, str(position.id)),
    )
    db.commit()

    with pytest.raises(ValidationError, match="context must match its offering"):
        app.transition_position(
            db,
            str(position.id),
            "make_effective",
            actor_context_ref=actor_id,
            now=STAMP,
        )

    persisted = db.execute(
        "SELECT state, version_no FROM inventory_positions WHERE inventory_position_id=?",
        (str(position.id),),
    ).fetchone()
    assert persisted == ("observed", 1)
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE owner_domain='inventory' AND target_ref=?",
        (str(position.id),),
    ).fetchone()[0] == 1
