"""InventoryPosition persistence and domain-history integrity across database reopen."""
from datetime import datetime, timezone
import uuid

import pytest

from agent_core.application import InventoryPositionApplication
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

STAMP = "2026-10-12T12:00:00Z"


def _id():
    return str(uuid.uuid4())


def test_inventory_position_reopen_rejects_history_state_that_disagrees_with_entity(tmp_path):
    path = tmp_path / "inventory-position-history.sqlite"
    db = connect_database(path)
    actor_id = _id()
    activity_id = _id()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (actor_id, "active", STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) "
        "VALUES(?,?,?,?,?)",
        (activity_id, actor_id, "active", STAMP, STAMP),
    )
    app = InventoryPositionApplication()
    position = app.create_position(
        db,
        activity_id=activity_id,
        scope_key="store-A",
        quantity_minor=4,
        observed_at=STAMP,
        effective_from=STAMP,
        now=STAMP,
        actor_context_ref=actor_id,
    )
    app.transition_position(
        db, position.id, "make_effective", actor_context_ref=actor_id, now=STAMP
    )
    app.transition_position(
        db, position.id, "close", actor_context_ref=actor_id, now=STAMP
    )
    db.commit()
    position_id = str(position.id)
    db.close()

    db = connect_database(path)
    # Simulate a durable but internally inconsistent audit record: the entity
    # says "closed", while its latest history event claims "effective".
    db.execute(
        "UPDATE domain_history SET change_payload_ref='action:close;state:effective' "
        "WHERE target_ref=? AND owner_domain='inventory' "
        "AND change_type='inventory_position_lifecycle' "
        "AND current_version_ref=?",
        (position_id, f"inventory_position:{position_id}:v3"),
    )
    db.commit()

    with pytest.raises(ValidationError, match="Inventory Position history"):
        app.get_position(db, position_id)
    db.close()

def test_legacy_version_one_inventory_position_without_history_remains_readable(tmp_path):
    path = tmp_path / "legacy-inventory-position.sqlite"
    db = connect_database(path)
    actor_id = _id()
    activity_id = _id()
    position_id = _id()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (actor_id, "active", STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) "
        "VALUES(?,?,?,?,?)",
        (activity_id, actor_id, "active", STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO inventory_positions(inventory_position_id,activity_id,scope_key,state,"
        "quantity_minor,observed_at,effective_from,created_at,updated_at,version_no) "
        "VALUES(?,?,?,'observed',?,?,?, ?, ?,1)",
        (position_id, activity_id, "legacy-store", 3, STAMP, STAMP, STAMP, STAMP),
    )
    db.commit()
    db.close()

    db = connect_database(path)
    restored = InventoryPositionApplication().get_position(db, position_id)
    assert str(restored.id) == position_id
    assert restored.state.value == "observed"
    db.close()


def test_inventory_position_reopen_rejects_history_action_that_conflicts_with_state(tmp_path):
    path = tmp_path / "inventory-position-action-history.sqlite"
    db = connect_database(path)
    actor_id = _id()
    activity_id = _id()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (actor_id, "active", STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) "
        "VALUES(?,?,?,?,?)",
        (activity_id, actor_id, "active", STAMP, STAMP),
    )
    app = InventoryPositionApplication()
    position = app.create_position(
        db, activity_id=activity_id, scope_key="store-A", quantity_minor=4,
        observed_at=STAMP, effective_from=STAMP, now=STAMP,
        actor_context_ref=actor_id,
    )
    app.transition_position(
        db, position.id, "make_effective", actor_context_ref=actor_id, now=STAMP
    )
    db.commit()
    position_id = str(position.id)
    db.close()

    db = connect_database(path)
    # The entity and history state are both effective, but 'close' must yield
    # closed; checking only the state would accept this impossible event.
    db.execute(
        "UPDATE domain_history SET change_payload_ref='action:close;state:effective' "
        "WHERE target_ref=? AND owner_domain='inventory' "
        "AND change_type='inventory_position_lifecycle' "
        "AND current_version_ref=?",
        (position_id, f"inventory_position:{position_id}:v2"),
    )
    db.commit()

    with pytest.raises(ValidationError, match="action and state are inconsistent"):
        app.get_position(db, position_id)
    db.close()
