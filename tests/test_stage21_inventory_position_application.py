"""Integration tests for persisted Inventory Position application lifecycle."""
from datetime import datetime, timezone
import uuid

import pytest

from agent_core.application import InventoryPositionApplication
from agent_core.domain_inventory import InventoryPositionState
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

NOW = "2026-10-10T12:00:00Z"


def uid():
    return str(uuid.uuid4())


def setup_refs(db):
    person_id, activity_id, product_id, offering_id = uid(), uid(), uid(), uid()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (person_id, "active", NOW, NOW),
    )
    db.execute(
        "INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) VALUES(?,?,?,?,?)",
        (activity_id, person_id, "active", NOW, NOW),
    )
    db.execute(
        "INSERT INTO products(product_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (product_id, "active", NOW, NOW),
    )
    db.execute(
        "INSERT INTO offerings(offering_id,product_id,activity_id,state,effective_from,created_at,updated_at) "
        "VALUES(?,?,?,?,?,?,?)",
        (offering_id, product_id, activity_id, "active", "2026-10-01T00:00:00Z", NOW, NOW),
    )
    db.commit()
    return activity_id, product_id, offering_id


def test_inventory_position_create_persist_restore_and_transition_after_reopen(tmp_path):
    path = tmp_path / "inventory-position.sqlite"
    db = connect_database(path)
    activity_id, product_id, offering_id = setup_refs(db)
    app = InventoryPositionApplication()
    position = app.create_position(
        db, actor_context_ref="test-context", activity_id=activity_id, offering_id=offering_id, product_id=product_id,
        scope_key="warehouse-A", quantity_minor=17,
        observed_at=datetime(2026, 10, 10, 11, 0, tzinfo=timezone.utc),
        now=datetime(2026, 10, 10, 12, 0, tzinfo=timezone.utc),
    )
    assert position.state is InventoryPositionState.OBSERVED
    created_history = db.execute(
        "SELECT owner_domain, target_ref, change_type, actor_context_ref, prior_version_ref, "
        "current_version_ref, change_payload_ref FROM domain_history"
    ).fetchall()
    assert created_history == [(
        "inventory", str(position.id), "inventory_position_lifecycle", "test-context", None,
        f"inventory_position:{position.id}:v1", "action:create;state:observed",
    )]
    position_id = str(position.id)
    db.close()

    db = connect_database(path)
    restored = app.get_position(db, position_id)
    assert restored.id == position.id
    assert restored.activity_id == position.activity_id
    assert restored.offering_id == position.offering_id
    assert restored.product_id == position.product_id
    assert restored.scope_key == "warehouse-A"
    assert restored.quantity_minor == 17
    assert restored.state is InventoryPositionState.OBSERVED

    effective = app.transition_position(db, position_id, "make_effective", actor_context_ref="test-context", now=NOW)
    assert effective.state is InventoryPositionState.EFFECTIVE
    assert app.get_position(db, position_id).state is InventoryPositionState.EFFECTIVE
    closed = app.transition_position(db, position_id, "close", actor_context_ref="test-context", now="2026-10-10T13:00:00Z")
    assert closed.state is InventoryPositionState.CLOSED
    assert app.get_position(db, position_id).state is InventoryPositionState.CLOSED
    version = db.execute(
        "SELECT version_no FROM inventory_positions WHERE inventory_position_id=?", (position_id,)
    ).fetchone()[0]
    assert version == 3
    history = db.execute(
        "SELECT prior_version_ref,current_version_ref,change_payload_ref "
        "FROM domain_history WHERE target_ref=? ORDER BY rowid", (position_id,)
    ).fetchall()
    assert history == [
        (None, f"inventory_position:{position_id}:v1", "action:create;state:observed"),
        (f"inventory_position:{position_id}:v1", f"inventory_position:{position_id}:v2",
         "action:make_effective;state:effective"),
        (f"inventory_position:{position_id}:v2", f"inventory_position:{position_id}:v3",
         "action:close;state:closed"),
    ]
    db.close()


def test_inventory_position_rejects_mismatched_offering_context_without_write():
    db = connect_database()
    activity_id, product_id, offering_id = setup_refs(db)
    other_activity = uid()
    db.execute(
        "INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) "
        "SELECT ?,owner_person_id,'active',?,? FROM activities WHERE activity_id=?",
        (other_activity, NOW, NOW, activity_id),
    )
    db.commit()
    with pytest.raises(Exception, match="context must match its offering"):
        InventoryPositionApplication().create_position(
            db, actor_context_ref="test-context", activity_id=other_activity, offering_id=offering_id,
            product_id=product_id, scope_key="warehouse-A", quantity_minor=1,
            now=NOW,
        )
    assert db.execute("SELECT COUNT(*) FROM inventory_positions").fetchone()[0] == 0
    db.close()


def test_inventory_position_rejects_fractional_quantity_and_invalid_transition():
    db = connect_database()
    activity_id, product_id, offering_id = setup_refs(db)
    app = InventoryPositionApplication()
    with pytest.raises(ValidationError, match="integer"):
        app.create_position(
            db, actor_context_ref="test-context", activity_id=activity_id, offering_id=offering_id, product_id=product_id,
            scope_key="warehouse-A", quantity_minor=1.5, now=NOW,
        )
    position = app.create_position(
        db, actor_context_ref="test-context", activity_id=activity_id, offering_id=offering_id, product_id=product_id,
        scope_key="warehouse-A", quantity_minor=None, now=NOW,
    )
    with pytest.raises(ValidationError, match="Invalid Inventory Position transition"):
        app.transition_position(db, str(position.id), "close", actor_context_ref="test-context", now=NOW)
    assert app.get_position(db, str(position.id)).state is InventoryPositionState.OBSERVED
    db.close()


def test_inventory_position_rejects_unknown_action_and_missing_position():
    db = connect_database()
    app = InventoryPositionApplication()
    with pytest.raises(ValidationError, match="Unsupported Inventory Position"):
        app.transition_position(db, uid(), "delete", actor_context_ref="test-context", now=NOW)
    with pytest.raises(ValidationError, match="does not exist"):
        app.get_position(db, uid())
    db.close()



def test_inventory_position_history_failure_rolls_back_create_and_transition():
    db = connect_database()
    activity_id, product_id, offering_id = setup_refs(db)
    app = InventoryPositionApplication()
    db.execute(
        "CREATE TRIGGER reject_inventory_history BEFORE INSERT ON domain_history "
        "WHEN NEW.owner_domain='inventory' BEGIN SELECT RAISE(ABORT, 'history write rejected'); END"
    )
    with pytest.raises(Exception, match="history write rejected"):
        app.create_position(
            db, actor_context_ref="test-context", activity_id=activity_id,
            offering_id=offering_id, product_id=product_id, scope_key="warehouse-A",
            quantity_minor=9, now=NOW,
        )
    assert db.execute("SELECT COUNT(*) FROM inventory_positions").fetchone()[0] == 0

    db.execute("DROP TRIGGER reject_inventory_history")
    position = app.create_position(
        db, actor_context_ref="test-context", activity_id=activity_id,
        offering_id=offering_id, product_id=product_id, scope_key="warehouse-A",
        quantity_minor=9, now=NOW,
    )
    db.execute(
        "CREATE TRIGGER reject_inventory_history BEFORE INSERT ON domain_history "
        "WHEN NEW.owner_domain='inventory' BEGIN SELECT RAISE(ABORT, 'history write rejected'); END"
    )
    with pytest.raises(Exception, match="history write rejected"):
        app.transition_position(
            db, str(position.id), "make_effective",
            actor_context_ref="test-context", now=NOW,
        )
    persisted = db.execute(
        "SELECT state,version_no FROM inventory_positions WHERE inventory_position_id=?",
        (str(position.id),),
    ).fetchone()
    assert persisted == ("observed", 1)
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=?", (str(position.id),)
    ).fetchone()[0] == 1
    db.close()


def test_inventory_position_requires_explicit_actor_context_for_writes():
    db = connect_database()
    activity_id, product_id, offering_id = setup_refs(db)
    app = InventoryPositionApplication()
    with pytest.raises(ValidationError, match="actor_context_ref is required"):
        app.create_position(
            db, activity_id=activity_id, offering_id=offering_id,
            product_id=product_id, scope_key="warehouse-A", now=NOW,
        )
    position = app.create_position(
        db, actor_context_ref="test-context", activity_id=activity_id,
        offering_id=offering_id, product_id=product_id, scope_key="warehouse-A", now=NOW,
    )
    with pytest.raises(ValidationError, match="actor_context_ref is required"):
        app.transition_position(db, str(position.id), "make_effective", now=NOW)
    assert app.get_position(db, str(position.id)).state is InventoryPositionState.OBSERVED
    db.close()
