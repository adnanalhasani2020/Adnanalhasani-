"""Integration tests for the persisted Commerce Offering application lifecycle."""
from datetime import datetime, timezone
import uuid

import pytest

from agent_core.offering_application import OfferingApplication
from agent_core.domain_inventory import OfferingState
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

NOW = "2026-10-10T12:00:00Z"


def uid():
    return str(uuid.uuid4())


def setup_refs(db):
    person_id, activity_id, product_id, service_id = uid(), uid(), uid(), uid()
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
        "INSERT INTO services(service_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (service_id, "active", NOW, NOW),
    )
    db.commit()
    return activity_id, product_id, service_id


def test_offering_create_restore_and_lifecycle_survive_reopen(tmp_path):
    path = tmp_path / "offering.sqlite"
    db = connect_database(path)
    activity_id, product_id, service_id = setup_refs(db)
    app = OfferingApplication()
    created = app.create_offering(
        db, product_id=product_id, activity_id=activity_id, service_id=service_id,
        effective_from=NOW, actor_context_ref="internal-test-context", effective_to="2026-10-20T12:00:00+00:00",
        now=datetime(2026, 10, 10, 12, tzinfo=timezone.utc),
    )
    assert created.offering.state is OfferingState.DRAFT
    assert created.version_no == 1
    offering_id = str(created.offering.id)
    db.close()

    db = connect_database(path)
    restored = app.get_offering(db, offering_id)
    assert restored.offering.id == created.offering.id
    assert restored.offering.product_id == created.offering.product_id
    assert restored.offering.activity_id == created.offering.activity_id
    assert restored.offering.service_id == created.offering.service_id
    assert restored.offering.effective_to == datetime(2026, 10, 20, 12, tzinfo=timezone.utc)
    assert restored.version_no == 1

    active = app.transition_offering(db, offering_id, "activate", expected_version=1, actor_context_ref="internal-test-context", now=NOW)
    assert active.offering.state is OfferingState.ACTIVE
    assert active.version_no == 2
    ended = app.transition_offering(
        db, offering_id, "end", expected_version=2, actor_context_ref="internal-test-context", now="2026-10-20T12:00:00Z"
    )
    assert ended.offering.state is OfferingState.ENDED
    assert ended.version_no == 3
    assert app.get_offering(db, offering_id).offering.state is OfferingState.ENDED
    history = db.execute(
        "SELECT prior_version_ref,current_version_ref,change_payload_ref,actor_context_ref "
        "FROM domain_history WHERE owner_domain='commerce' AND target_ref=? "
        "ORDER BY current_version_ref",
        (offering_id,),
    ).fetchall()
    assert history == [
        (None, f"offering:{offering_id}:v1", "state:draft", "internal-test-context"),
        (f"offering:{offering_id}:v1", f"offering:{offering_id}:v2",
         "action:activate;state:active", "internal-test-context"),
        (f"offering:{offering_id}:v2", f"offering:{offering_id}:v3",
         "action:end;state:ended", "internal-test-context"),
    ]
    db.close()


def test_offering_requires_existing_references_and_does_not_write_on_failure():
    db = connect_database()
    app = OfferingApplication()
    with pytest.raises(ValidationError, match="existing Product"):
        app.create_offering(db, product_id=uid(), activity_id=uid(), effective_from=NOW, actor_context_ref="internal-test-context", now=NOW)
    assert db.execute("SELECT COUNT(*) FROM offerings").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM domain_history WHERE owner_domain='commerce'").fetchone()[0] == 0
    db.close()


def test_offering_rejects_invalid_time_and_reversed_period():
    db = connect_database()
    activity_id, product_id, _ = setup_refs(db)
    app = OfferingApplication()
    with pytest.raises(ValidationError, match="timezone-aware"):
        app.create_offering(
            db, product_id=product_id, activity_id=activity_id,
            effective_from="2026-10-10T12:00:00", actor_context_ref="internal-test-context", now=NOW,
        )
    with pytest.raises(ValidationError, match="cannot precede"):
        app.create_offering(
            db, product_id=product_id, activity_id=activity_id,
            effective_from="2026-10-20T12:00:00Z", actor_context_ref="internal-test-context",
            effective_to="2026-10-10T12:00:00Z", now=NOW,
        )
    assert db.execute("SELECT COUNT(*) FROM offerings").fetchone()[0] == 0
    db.close()


def test_offering_rejects_invalid_transition_and_stale_version_without_mutation():
    db = connect_database()
    activity_id, product_id, _ = setup_refs(db)
    app = OfferingApplication()
    created = app.create_offering(
        db, product_id=product_id, activity_id=activity_id, effective_from=NOW, actor_context_ref="internal-test-context", now=NOW
    )
    offering_id = str(created.offering.id)
    with pytest.raises(ValidationError, match="Invalid Offering transition"):
        app.transition_offering(db, offering_id, "end", expected_version=1, actor_context_ref="internal-test-context", now=NOW)
    active = app.transition_offering(db, offering_id, "activate", expected_version=1, actor_context_ref="internal-test-context", now=NOW)
    with pytest.raises(ValidationError, match="version conflict"):
        app.transition_offering(db, offering_id, "withdraw", expected_version=1, actor_context_ref="internal-test-context", now=NOW)
    current = app.get_offering(db, offering_id)
    assert current.offering.state is OfferingState.ACTIVE
    assert current.version_no == 2
    db.close()


def test_offering_does_not_create_inventory_or_availability():
    db = connect_database()
    activity_id, product_id, _ = setup_refs(db)
    OfferingApplication().create_offering(
        db, product_id=product_id, activity_id=activity_id, effective_from=NOW, actor_context_ref="internal-test-context", now=NOW
    )
    assert db.execute("SELECT COUNT(*) FROM offerings").fetchone()[0] == 1
    assert db.execute("SELECT COUNT(*) FROM inventory_positions").fetchone()[0] == 0
    tables = {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    assert "availability" not in tables
    db.close()



def test_offering_history_failure_rolls_back_create_and_transition():
    import sqlite3

    db = connect_database()
    activity_id, product_id, _ = setup_refs(db)
    app = OfferingApplication()
    db.execute(
        "CREATE TRIGGER reject_offering_history BEFORE INSERT ON domain_history "
        "WHEN NEW.owner_domain='commerce' AND NEW.change_type='offering_lifecycle' "
        "BEGIN SELECT RAISE(ABORT, 'offering history unavailable'); END"
    )
    db.commit()
    with pytest.raises(sqlite3.IntegrityError, match="offering history unavailable"):
        app.create_offering(
            db, product_id=product_id, activity_id=activity_id, effective_from=NOW,
            actor_context_ref="internal-test-context", now=NOW,
        )
    assert db.execute("SELECT COUNT(*) FROM offerings").fetchone()[0] == 0

    db.execute("DROP TRIGGER reject_offering_history")
    created = app.create_offering(
        db, product_id=product_id, activity_id=activity_id, effective_from=NOW,
        actor_context_ref="internal-test-context", now=NOW,
    )
    db.execute(
        "CREATE TRIGGER reject_offering_history BEFORE INSERT ON domain_history "
        "WHEN NEW.owner_domain='commerce' AND NEW.change_type='offering_lifecycle' "
        "BEGIN SELECT RAISE(ABORT, 'offering history unavailable'); END"
    )
    db.commit()
    with pytest.raises(sqlite3.IntegrityError, match="offering history unavailable"):
        app.transition_offering(
            db, created.offering.id, "activate", expected_version=1,
            actor_context_ref="internal-test-context", now=NOW,
        )
    current = app.get_offering(db, created.offering.id)
    assert current.offering.state is OfferingState.DRAFT
    assert current.version_no == 1
    db.close()


def test_offering_history_requires_non_empty_actor_context():
    db = connect_database()
    activity_id, product_id, _ = setup_refs(db)
    app = OfferingApplication()
    with pytest.raises(ValidationError, match="actor_context_ref"):
        app.create_offering(
            db, product_id=product_id, activity_id=activity_id, effective_from=NOW,
            actor_context_ref=" ", now=NOW,
        )
    assert db.execute("SELECT COUNT(*) FROM offerings").fetchone()[0] == 0
    db.close()
