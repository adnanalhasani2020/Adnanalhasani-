"""Stage 16 — persistence checks for Product/Offering/Sale relationship context.

Traceability:
SPEC-0005 §§5.1, 6.1–6.2, 10.1; invariants 1, 16–17;
AC-01 and AC-04; REQ-DATA-0013 and REQ-FUNC-0038.
This verifies persisted relationships only; it does not add lifecycle or authorization policy.
"""
import uuid

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


def _offering(db, product_id, activity_id):
    offering_id = _id()
    db.execute(
        "INSERT INTO offerings(offering_id,product_id,activity_id,state,effective_from,effective_to,created_at,updated_at) "
        "VALUES(?,?,?,?,?,?,?,?)",
        (offering_id, product_id, activity_id, "active", NOW, None, NOW, NOW),
    )
    return offering_id


def test_shared_product_offerings_and_sales_keep_distinct_activity_context_after_reopen(tmp_path):
    path = tmp_path / "shared-product-activities.sqlite"
    db = connect_database(path)

    product_id = _id()
    db.execute(
        "INSERT INTO products(product_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (product_id, "active", NOW, NOW),
    )
    first_activity_id = _activity(db)
    second_activity_id = _activity(db)
    first_offering_id = _offering(db, product_id, first_activity_id)
    second_offering_id = _offering(db, product_id, second_activity_id)

    first_sale_id, second_sale_id = _id(), _id()
    for sale_id, offering_id, activity_id in (
        (first_sale_id, first_offering_id, first_activity_id),
        (second_sale_id, second_offering_id, second_activity_id),
    ):
        db.execute(
            "INSERT INTO sales(sale_id,offering_id,activity_id,state,occurred_at,created_at,updated_at) "
            "VALUES(?,?,?,?,?,?,?)",
            (sale_id, offering_id, activity_id, "confirmed", NOW, NOW, NOW),
        )
    db.commit()
    db.close()

    db = connect_database(path)
    rows = db.execute(
        "SELECT p.product_id,o.offering_id,o.activity_id,s.sale_id,s.activity_id,s.state "
        "FROM products p JOIN offerings o ON o.product_id=p.product_id "
        "JOIN sales s ON s.offering_id=o.offering_id "
        "WHERE p.product_id=? ORDER BY o.activity_id",
        (product_id,),
    ).fetchall()

    assert len(rows) == 2
    assert {row[0] for row in rows} == {product_id}
    assert {row[1] for row in rows} == {first_offering_id, second_offering_id}
    assert {row[2] for row in rows} == {first_activity_id, second_activity_id}
    assert {row[3] for row in rows} == {first_sale_id, second_sale_id}
    assert all(row[2] == row[4] for row in rows)
    assert all(row[5] == "confirmed" for row in rows)
    assert db.execute("PRAGMA foreign_key_check").fetchall() == []
    db.close()
