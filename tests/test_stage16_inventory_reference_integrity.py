"""Stage 16 — negative persistence checks for supplied Inventory Position references.

Traceability:
SPEC-0006 §§5–6, invariants 1–2 and 24; REQ-DATA-0014 (SPEC-0005 primary,
SPEC-0006 supporting). Existing schema declares foreign keys for non-null
product_id and offering_id. This tests those constraints without changing schema.
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


@pytest.mark.parametrize(
    ("product_id", "offering_id"),
    [
        ("missing-product-reference", None),
        (None, "missing-offering-reference"),
    ],
)
def test_inventory_position_rejects_each_supplied_nonexistent_product_or_offering_reference(
    tmp_path, product_id, offering_id
):
    db = connect_database(tmp_path / f"invalid-inventory-reference-{uuid.uuid4()}.sqlite")
    activity_id = _activity(db)

    with pytest.raises(sqlite3.IntegrityError):
        db.execute(
            "INSERT INTO inventory_positions(inventory_position_id,activity_id,product_id,offering_id,"
            "scope_key,state,observed_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
            (_id(), activity_id, product_id, offering_id, "activity:offering", "observed", NOW, NOW, NOW),
        )

    db.rollback()
    assert db.execute("PRAGMA foreign_key_check").fetchall() == []
    db.close()
