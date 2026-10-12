"""Offering restoration must reject inconsistent durable lifecycle history."""
import uuid

import pytest

from agent_core.offering_application import OfferingApplication
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

STAMP = "2026-10-12T12:00:00Z"


def _id():
    return str(uuid.uuid4())


def _refs(db):
    person_id, activity_id, product_id = _id(), _id(), _id()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (person_id, "active", STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) "
        "VALUES(?,?,?,?,?)",
        (activity_id, person_id, "active", STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO products(product_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (product_id, "active", STAMP, STAMP),
    )
    db.commit()
    return activity_id, product_id


def test_offering_reopen_rejects_history_action_that_conflicts_with_state(tmp_path):
    path = tmp_path / "offering-history.sqlite"
    db = connect_database(path)
    activity_id, product_id = _refs(db)
    app = OfferingApplication()
    record = app.create_offering(
        db, product_id=product_id, activity_id=activity_id, effective_from=STAMP,
        actor_context_ref="actor-A", now=STAMP,
    )
    app.transition_offering(
        db, record.offering.id, "activate", expected_version=1,
        actor_context_ref="actor-A", now=STAMP,
    )
    db.commit()
    offering_id = str(record.offering.id)
    db.close()

    db = connect_database(path)
    # State agrees with the entity, but 'end' cannot produce 'active'.
    db.execute(
        "UPDATE domain_history SET change_payload_ref='action:end;state:active' "
        "WHERE owner_domain='commerce' AND target_ref=? "
        "AND change_type='offering_lifecycle' AND current_version_ref=?",
        (offering_id, f"offering:{offering_id}:v2"),
    )
    db.commit()

    with pytest.raises(ValidationError, match="action and state are inconsistent"):
        app.get_offering(db, offering_id)
    db.close()


def test_legacy_version_one_offering_without_history_is_readable_but_not_transitionable():
    db = connect_database()
    activity_id, product_id = _refs(db)
    offering_id = _id()
    db.execute(
        "INSERT INTO offerings(offering_id,product_id,activity_id,state,effective_from,"
        "created_at,updated_at,version_no) VALUES(?,?,?,'draft',?,?,?,1)",
        (offering_id, product_id, activity_id, STAMP, STAMP, STAMP),
    )
    db.commit()

    app = OfferingApplication()
    restored = app.get_offering(db, offering_id)
    assert restored.version_no == 1
    assert restored.offering.state.value == "draft"

    with pytest.raises(ValidationError, match="explicit legacy reconciliation"):
        app.transition_offering(
            db, offering_id, "activate", expected_version=1,
            actor_context_ref="actor-A", now=STAMP,
        )
    db.close()
