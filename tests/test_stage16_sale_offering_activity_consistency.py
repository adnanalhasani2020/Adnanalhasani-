"""Stage 16 — Sale/Offering Activity-context consistency regression tests.

Traceability:
SPEC-0005 §10.1, §28.6–28.7, invariants 16–17, AC-04,
and REQ-FUNC-0038.

These tests deliberately change no domain code, schema, or migration.
"""
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


def test_sale_accepts_offering_when_activity_context_matches(tmp_path):
    db = connect_database(tmp_path / "matching-activity.sqlite")
    activity_id = _activity(db)
    offering_id = _offering(db, _product(db), activity_id)

    db.execute(
        "INSERT INTO sales(sale_id,offering_id,activity_id,state,occurred_at,created_at,updated_at) "
        "VALUES(?,?,?,?,?,?,?)",
        (_id(), offering_id, activity_id, "confirmed", NOW, NOW, NOW),
    )
    db.commit()

    row = db.execute(
        "SELECT s.activity_id,o.activity_id FROM sales s "
        "JOIN offerings o ON o.offering_id=s.offering_id"
    ).fetchone()
    assert row == (activity_id, activity_id)
    db.close()


def test_sale_rejects_offering_from_a_different_activity(tmp_path):
    db = connect_database(tmp_path / "mismatched-activity.sqlite")
    offering_activity_id = _activity(db)
    sale_activity_id = _activity(db)
    offering_id = _offering(db, _product(db), offering_activity_id)

    # SPEC-0005 requires Sale to preserve the Activity context of its Offering.
    # The current schema has separate FKs, so this should be rejected by the
    # persistence boundary; if accepted, the invariant is not enforced.
    with pytest.raises(sqlite3.IntegrityError):
        db.execute(
            "INSERT INTO sales(sale_id,offering_id,activity_id,state,occurred_at,created_at,updated_at) "
            "VALUES(?,?,?,?,?,?,?)",
            (_id(), offering_id, sale_activity_id, "confirmed", NOW, NOW, NOW),
        )
    db.close()
