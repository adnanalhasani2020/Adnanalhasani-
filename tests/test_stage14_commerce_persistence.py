"""Stage 14 — relational persistence evidence for the existing Commerce Core schema.

This suite proves database round-trips and relational boundaries only. It deliberately
does not equate SaleState.COMPLETED with SPEC-0005's "fulfilled" terminology.
"""
import sqlite3
import uuid

import pytest

from agent_core.persistence import connect_database

NOW = "2026-10-09T00:00:00Z"


def _id():
    return str(uuid.uuid4())


def _activity(db):
    activity_id, owner_id = _id(), _id()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (owner_id, "active", NOW, NOW),
    )
    db.execute(
        "INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) VALUES(?,?,?,?,?)",
        (activity_id, owner_id, "active", NOW, NOW),
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
        (offering_id, product_id, activity_id, "active", "2026-10-01T00:00:00Z", None, NOW, NOW),
    )
    return offering_id


def test_product_offering_and_inventory_position_round_trip_independently(tmp_path):
    db = connect_database(tmp_path / "commerce-roundtrip.sqlite")
    activity_id = _activity(db)
    product_id = _product(db)
    offering_id = _offering(db, product_id, activity_id)
    position_id = _id()
    db.execute(
        "INSERT INTO inventory_positions(inventory_position_id,activity_id,product_id,offering_id,location_ref,"
        "scope_key,state,quantity_minor,observed_at,effective_from,effective_to,created_at,updated_at) "
        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
        (position_id, activity_id, product_id, offering_id, "site-A", "site-A:offering", "effective", 7,
         "2026-10-08T12:30:00Z", "2026-10-08T12:00:00Z", None, NOW, NOW),
    )
    db.commit()
    db.close()
    db = connect_database(tmp_path / "commerce-roundtrip.sqlite")

    product = db.execute("SELECT product_id,state FROM products WHERE product_id=?", (product_id,)).fetchone()
    offering = db.execute(
        "SELECT offering_id,product_id,activity_id,state,effective_from,effective_to FROM offerings WHERE offering_id=?",
        (offering_id,),
    ).fetchone()
    position = db.execute(
        "SELECT inventory_position_id,activity_id,product_id,offering_id,location_ref,scope_key,state,quantity_minor,"
        "observed_at,effective_from,effective_to FROM inventory_positions WHERE inventory_position_id=?",
        (position_id,),
    ).fetchone()

    assert product == (product_id, "active")
    assert offering == (offering_id, product_id, activity_id, "active", "2026-10-01T00:00:00Z", None)
    assert position == (
        position_id, activity_id, product_id, offering_id, "site-A", "site-A:offering", "effective", 7,
        "2026-10-08T12:30:00Z", "2026-10-08T12:00:00Z", None,
    )
    assert len({product[0], offering[0], position[0]}) == 3
    assert db.execute("PRAGMA foreign_key_check").fetchall() == []
    db.close()


def test_inventory_position_does_not_require_availability_or_discovery_record():
    db = connect_database()
    activity_id = _activity(db)
    product_id = _product(db)
    offering_id = _offering(db, product_id, activity_id)
    db.execute(
        "INSERT INTO inventory_positions(inventory_position_id,activity_id,product_id,offering_id,scope_key,state,"
        "observed_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
        (_id(), activity_id, product_id, offering_id, "activity:offering", "observed",
         "2026-10-09T00:00:00Z", NOW, NOW),
    )
    tables = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    assert "inventory_positions" in tables
    assert "availability" not in tables
    assert "discoveries" not in tables
    db.close()


def test_sale_round_trip_preserves_activity_offering_and_current_state_without_finance_aliases(tmp_path):
    db = connect_database(tmp_path / "sale-roundtrip.sqlite")
    activity_id = _activity(db)
    product_id = _product(db)
    offering_id = _offering(db, product_id, activity_id)
    sale_id = _id()
    db.execute(
        "INSERT INTO sales(sale_id,offering_id,activity_id,state,occurred_at,created_at,updated_at) "
        "VALUES(?,?,?,?,?,?,?)",
        (sale_id, offering_id, activity_id, "confirmed", "2026-10-08T12:00:00Z", NOW, NOW),
    )
    db.commit()
    db.close()
    db = connect_database(tmp_path / "sale-roundtrip.sqlite")
    sale = db.execute(
        "SELECT sale_id,offering_id,activity_id,state,occurred_at,created_at,updated_at "
        "FROM sales WHERE sale_id=?",
        (sale_id,),
    ).fetchone()
    assert sale == (
        sale_id, offering_id, activity_id, "confirmed", "2026-10-08T12:00:00Z", NOW, NOW
    )
    columns = {r[1] for r in db.execute("PRAGMA table_info(sales)")}
    assert "payment_id" not in columns
    assert "settlement_id" not in columns
    assert "financial_transaction_id" not in columns
    assert "ledger_entry_id" not in columns
    db.close()


def test_sale_state_constraint_rejects_unapproved_fulfilled_vocabulary():
    db = connect_database()
    activity_id = _activity(db)
    product_id = _product(db)
    offering_id = _offering(db, product_id, activity_id)
    with pytest.raises(sqlite3.IntegrityError):
        db.execute(
            "INSERT INTO sales(sale_id,offering_id,activity_id,state,occurred_at,created_at,updated_at) "
            "VALUES(?,?,?,?,?,?,?)",
            (_id(), offering_id, activity_id, "fulfilled", NOW, NOW, NOW),
        )
    db.close()


def test_sale_and_inventory_foreign_keys_reject_missing_owner_records():
    db = connect_database()
    with pytest.raises(sqlite3.IntegrityError):
        db.execute(
            "INSERT INTO sales(sale_id,offering_id,activity_id,state,occurred_at,created_at,updated_at) "
            "VALUES(?,?,?,?,?,?,?)",
            (_id(), _id(), _id(), "initiated", NOW, NOW, NOW),
        )
    with pytest.raises(sqlite3.IntegrityError):
        db.execute(
            "INSERT INTO inventory_positions(inventory_position_id,activity_id,scope_key,state,observed_at,created_at,updated_at) "
            "VALUES(?,?,?,?,?,?,?)",
            (_id(), _id(), "scope", "observed", NOW, NOW, NOW),
        )
    db.close()


def test_sale_lifecycle_state_and_history_can_be_persisted_without_semantic_equivalence_claim():
    db = connect_database()
    activity_id = _activity(db)
    product_id = _product(db)
    offering_id = _offering(db, product_id, activity_id)
    sale_id = _id()
    db.execute(
        "INSERT INTO sales(sale_id,offering_id,activity_id,state,occurred_at,created_at,updated_at) "
        "VALUES(?,?,?,?,?,?,?)",
        (sale_id, offering_id, activity_id, "initiated", NOW, NOW, NOW),
    )
    transitions = (
        ("initiated", None, "sale-state:initiated:v1"),
        ("confirmed", "sale-state:initiated:v1", "sale-state:confirmed:v2"),
        ("completed", "sale-state:confirmed:v2", "sale-state:completed:v3"),
        ("returned", "sale-state:completed:v3", "sale-state:returned:v4"),
    )
    expected_history = []
    for state, prior_ref, current_ref in transitions:
        db.execute("UPDATE sales SET state=?,updated_at=? WHERE sale_id=?", (state, NOW, sale_id))
        history_id = _id()
        db.execute(
            "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,historical_at,"
            "actor_context_ref,prior_version_ref,current_version_ref,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
            (history_id, "commerce", sale_id, "sale_state_transition", NOW, activity_id,
             prior_ref, current_ref, NOW),
        )
        expected_history.append(
            (history_id, sale_id, "commerce", "sale_state_transition", NOW, activity_id,
             prior_ref, current_ref, NOW)
        )
    db.commit()

    persisted_state = db.execute(
        "SELECT state FROM sales WHERE sale_id=?", (sale_id,)
    ).fetchone()[0]
    assert persisted_state == transitions[-1][0]

    rows = db.execute(
        "SELECT domain_history_id,target_ref,owner_domain,change_type,historical_at,actor_context_ref,"
        "prior_version_ref,current_version_ref,created_at FROM domain_history "
        "WHERE target_ref=? ORDER BY rowid",
        (sale_id,),
    ).fetchall()
    assert rows == expected_history
    # Verify the persisted version-reference chain rather than comparing an
    # in-memory state list with constants. This describes storage only and does
    # not equate "completed" with SPEC-0005's "fulfilled".
    assert rows[0][6] is None
    assert all(rows[i][7] == rows[i + 1][6] for i in range(len(rows) - 1))
    assert rows[-1][7] == "sale-state:returned:v4"
    db.close()



def test_product_retirement_preserves_offering_sale_and_sale_history_after_reopen(tmp_path):
    path = tmp_path / "product-retirement-history.sqlite"
    db = connect_database(path)
    activity_id = _activity(db)
    product_id = _product(db)
    offering_id = _offering(db, product_id, activity_id)
    sale_id = _id()
    db.execute(
        "INSERT INTO sales(sale_id,offering_id,activity_id,state,occurred_at,created_at,updated_at) "
        "VALUES(?,?,?,?,?,?,?)",
        (sale_id, offering_id, activity_id, "confirmed", "2026-10-08T12:00:00Z", NOW, NOW),
    )
    history_id = _id()
    db.execute(
        "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,historical_at,"
        "actor_context_ref,prior_version_ref,current_version_ref,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
        (history_id, "commerce", sale_id, "sale_state_transition", NOW, activity_id,
         "sale-state:initiated:v1", "sale-state:confirmed:v2", NOW),
    )

    # Persist the lifecycle change using the existing schema; no domain/schema changes.
    db.execute("UPDATE products SET state=?,updated_at=? WHERE product_id=?", ("retired", NOW, product_id))
    db.commit()
    db.close()

    db = connect_database(path)
    product = db.execute(
        "SELECT product_id,state FROM products WHERE product_id=?", (product_id,)
    ).fetchone()
    offering = db.execute(
        "SELECT offering_id,product_id,activity_id,state FROM offerings WHERE offering_id=?",
        (offering_id,),
    ).fetchone()
    sale = db.execute(
        "SELECT sale_id,offering_id,activity_id,state FROM sales WHERE sale_id=?", (sale_id,)
    ).fetchone()
    history = db.execute(
        "SELECT domain_history_id,target_ref,owner_domain,change_type,actor_context_ref,"
        "prior_version_ref,current_version_ref FROM domain_history WHERE domain_history_id=?",
        (history_id,),
    ).fetchone()

    assert product == (product_id, "retired")
    assert offering == (offering_id, product_id, activity_id, "active")
    assert sale == (sale_id, offering_id, activity_id, "confirmed")
    assert history == (
        history_id, sale_id, "commerce", "sale_state_transition", activity_id,
        "sale-state:initiated:v1", "sale-state:confirmed:v2",
    )
    assert db.execute("PRAGMA foreign_key_check").fetchall() == []
    db.close()
