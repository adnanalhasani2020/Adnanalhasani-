"""Stage 16: integrated, authorized Sale creation and initial history persistence."""
from datetime import datetime, timezone
import uuid

import pytest

from agent_core.application import SaleApplication
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

NOW = datetime(2026, 10, 9, 12, 0, tzinfo=timezone.utc)
STAMP = "2026-10-09T12:00:00Z"


def _id():
    return str(uuid.uuid4())


def _fixture(db):
    person_id, activity_id, product_id, offering_id = _id(), _id(), _id(), _id()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (person_id, "active", STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) VALUES(?,?,?,?,?)",
        (activity_id, person_id, "active", STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO products(product_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (product_id, "active", STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO offerings(offering_id,product_id,activity_id,state,effective_from,effective_to,created_at,updated_at) "
        "VALUES(?,?,?,?,?,?,?,?)",
        (offering_id, product_id, activity_id, "active", "2026-10-01T00:00:00Z", None, STAMP, STAMP),
    )
    return activity_id, offering_id


def test_sale_application_authorizes_and_persists_sale_with_initial_history():
    db = connect_database()
    activity_id, offering_id = _fixture(db)
    authorization_calls = []

    def authorize(actor_context_ref, requested_activity_id, requested_offering_id):
        authorization_calls.append((actor_context_ref, requested_activity_id, requested_offering_id))
        return requested_activity_id == activity_id and requested_offering_id == offering_id

    sale = SaleApplication().create_sale(
        db, offering_id, activity_id, "actor:person-1", authorize, now=NOW
    )
    stored = db.execute(
        "SELECT sale_id,offering_id,activity_id,state FROM sales WHERE sale_id=?",
        (str(sale.id),),
    ).fetchone()
    history = db.execute(
        "SELECT owner_domain,target_ref,change_type,actor_context_ref,prior_version_ref,current_version_ref "
        "FROM domain_history WHERE target_ref=?",
        (str(sale.id),),
    ).fetchone()

    assert stored == (str(sale.id), offering_id, activity_id, "initiated")
    assert sale.history[0].value == "initiated"
    assert authorization_calls == [("actor:person-1", activity_id, offering_id)]
    assert history == (
        "commerce", str(sale.id), "sale_state_transition", "actor:person-1",
        None, f"sale-state:{sale.id}:v1",
    )
    assert db.execute("PRAGMA foreign_key_check").fetchall() == []
    db.close()


def test_sale_application_rejects_activity_mismatch_before_writing():
    db = connect_database()
    activity_id, offering_id = _fixture(db)
    other_activity = _id()
    with pytest.raises(ValidationError, match="must match Offering Activity"):
        SaleApplication().create_sale(
            db, offering_id, other_activity, "actor:person-1",
            lambda *_: True, now=NOW,
        )
    assert db.execute("SELECT count(*) FROM sales").fetchone()[0] == 0
    db.close()


def test_sale_application_rejects_unauthorized_actor_without_partial_history():
    db = connect_database()
    activity_id, offering_id = _fixture(db)
    with pytest.raises(ValidationError, match="not authorized"):
        SaleApplication().create_sale(
            db, offering_id, activity_id, "actor:person-1",
            lambda *_: False, now=NOW,
        )
    assert db.execute("SELECT count(*) FROM sales").fetchone()[0] == 0
    assert db.execute("SELECT count(*) FROM domain_history").fetchone()[0] == 0
    db.close()


def test_sale_application_rejects_inactive_offering():
    db = connect_database()
    activity_id, offering_id = _fixture(db)
    db.execute("UPDATE offerings SET state='withdrawn' WHERE offering_id=?", (offering_id,))
    with pytest.raises(ValidationError, match="active Offering"):
        SaleApplication().create_sale(
            db, offering_id, activity_id, "actor:person-1",
            lambda *_: True, now=NOW,
        )
    assert db.execute("SELECT count(*) FROM sales").fetchone()[0] == 0
    db.close()


def test_sale_application_rejects_naive_timestamp_and_missing_authorizer():
    db = connect_database()
    activity_id, offering_id = _fixture(db)
    with pytest.raises(ValidationError, match="timezone-aware"):
        SaleApplication().create_sale(
            db, offering_id, activity_id, "actor:person-1",
            lambda *_: True, now=datetime(2026, 10, 9, 12, 0),
        )
    with pytest.raises(ValidationError, match="requires authoritative authorization"):
        SaleApplication().create_sale(
            db, offering_id, activity_id, "actor:person-1", None, now=NOW,
        )
    assert db.execute("SELECT count(*) FROM sales").fetchone()[0] == 0
    db.close()
