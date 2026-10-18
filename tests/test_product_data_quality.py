"""Focused tests for the read-only missing Product name report."""

import uuid

import pytest

from agent_core.persistence import connect_database
from agent_core.product_data_quality import list_products_missing_names


NOW = "2026-10-10T12:00:00Z"


def uid():
    return str(uuid.uuid4())


def _activity(db):
    owner_id, activity_id = uid(), uid()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (owner_id, "active", NOW, NOW),
    )
    db.execute(
        "INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) "
        "VALUES(?,?,?,?,?)",
        (activity_id, owner_id, "active", NOW, NOW),
    )
    return activity_id


def test_missing_product_name_report_counts_references_without_mutating_data():
    db = connect_database()
    activity_id = _activity(db)
    missing_id, named_id = uid(), uid()
    db.execute(
        "INSERT INTO products(product_id,name,state,created_at,updated_at) VALUES(?,?,?,?,?)",
        (missing_id, None, "active", NOW, NOW),
    )
    db.execute(
        "INSERT INTO products(product_id,name,state,created_at,updated_at) VALUES(?,?,?,?,?)",
        (named_id, "Named product", "active", NOW, NOW),
    )
    offering_ids = [uid(), uid()]
    for offering_id in offering_ids:
        db.execute(
            "INSERT INTO offerings(offering_id,product_id,activity_id,state,effective_from,"
            "created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
            (offering_id, missing_id, activity_id, "active", NOW, NOW, NOW),
        )
    sale_id = uid()
    db.execute(
        "INSERT INTO sales(sale_id,offering_id,activity_id,state,occurred_at,created_at,updated_at) "
        "VALUES(?,?,?,?,?,?,?)",
        (sale_id, offering_ids[0], activity_id, "completed", NOW, NOW, NOW),
    )
    db.commit()

    before_products = db.execute(
        "SELECT product_id,name,state,version_no FROM products ORDER BY product_id"
    ).fetchall()
    before_history = db.execute("SELECT COUNT(*) FROM domain_history").fetchone()[0]

    report = list_products_missing_names(db)

    assert len(report) == 1
    assert report[0].product_id == missing_id
    assert report[0].state == "active"
    assert report[0].offering_count == 2
    assert report[0].sale_count == 1
    assert db.execute(
        "SELECT product_id,name,state,version_no FROM products ORDER BY product_id"
    ).fetchall() == before_products
    assert db.execute("SELECT COUNT(*) FROM domain_history").fetchone()[0] == before_history
    db.close()


def test_missing_product_name_report_includes_blank_names_and_has_stable_pagination():
    db = connect_database()
    ids = [uid(), uid(), uid()]
    for product_id, name in zip(ids, [None, "  ", "Valid name"]):
        db.execute(
            "INSERT INTO products(product_id,name,state,created_at,updated_at) VALUES(?,?,?,?,?)",
            (product_id, name, "draft", NOW, NOW),
        )
    db.commit()

    report = list_products_missing_names(db, limit=1, offset=0)
    all_missing = list_products_missing_names(db, limit=10)
    next_page = list_products_missing_names(db, limit=1, offset=1)

    assert len(all_missing) == 2
    assert report[0].product_id == all_missing[0].product_id
    assert next_page[0].product_id == all_missing[1].product_id
    db.close()


@pytest.mark.parametrize(
    "kwargs",
    [{"limit": 0}, {"limit": 501}, {"limit": True}, {"offset": -1}, {"offset": False}],
)
def test_missing_product_name_report_rejects_invalid_pagination(kwargs):
    db = connect_database()
    with pytest.raises(ValueError):
        list_products_missing_names(db, **kwargs)
    db.close()
