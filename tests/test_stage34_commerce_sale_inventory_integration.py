"""Narrow end-to-end integration across existing Commerce, Inventory, and Sale applications.

This test deliberately verifies only the documented read behavior and Sale lifecycle.
It does not define whether inventory states gate Sale creation and does not reserve/deduct stock.
"""
from datetime import datetime, timezone
import sqlite3
import uuid

import pytest

from agent_core.application import InventoryApplication, ProductApplication, SaleApplication
from agent_core.domain_commerce import SaleState
from agent_core.inventory_read import InventoryQuantityStatus as InventoryStatus
from agent_core.offering_application import OfferingApplication
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

NOW = datetime(2026, 10, 12, 12, 0, tzinfo=timezone.utc)
STAMP = "2026-10-12T12:00:00Z"


def _id():
    return str(uuid.uuid4())


def _activity(db, owner_id):
    activity_id = _id()
    db.execute(
        "INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) "
        "VALUES(?,?,?,?,?)",
        (activity_id, owner_id, "active", STAMP, STAMP),
    )
    return activity_id


def _grant(db, actor_id, scope_id, activity_id, action):
    grant_id = _id()
    db.execute(
        "INSERT INTO authorization_grants(authorization_grant_id,subject_person_id,agent_id,"
        "action_code,scope_ref,context_ref,delegation_id,state,effective_from,effective_to,"
        "created_at,updated_at) VALUES(?,?,NULL,?,?,?,?,?,?,?,?,?)",
        (grant_id, actor_id, action, scope_id, activity_id, None, "active",
         "2026-10-01T00:00:00Z", None, STAMP, STAMP),
    )
    return grant_id


def _position(db, *, activity_id, product_id, offering_id, quantity, observed_at):
    position_id = _id()
    db.execute(
        "INSERT INTO inventory_positions(inventory_position_id,activity_id,product_id,offering_id,"
        "location_ref,scope_key,state,quantity_minor,observed_at,effective_from,effective_to,"
        "created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
        (position_id, activity_id, product_id, offering_id, "store", "store-A", "effective",
         quantity, observed_at, "2026-10-01T00:00:00Z", None, STAMP, STAMP),
    )
    return position_id


def test_product_offering_inventory_and_authorized_sale_round_trip_without_stock_side_effects(tmp_path):
    db = connect_database(tmp_path / "commerce-integration.sqlite")
    actor_id = _id()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (actor_id, "active", STAMP, STAMP),
    )
    activity_a = _activity(db, actor_id)
    activity_b = _activity(db, actor_id)

    products = ProductApplication()
    product = products.create_product(
        db, "Reusable bottle", actor_context_ref="integration-test-context", now=STAMP
    )
    product_id = str(product.product.id)
    products.transition_product(
        db, product_id, "activate", expected_version=1,
        actor_context_ref="integration-test-context", now=STAMP,
    )

    offerings = OfferingApplication()
    offering_a = offerings.create_offering(
        db, product_id=product_id, activity_id=activity_a, effective_from=STAMP,
        actor_context_ref="integration-test-context", now=STAMP,
    )
    offerings.transition_offering(
        db, offering_a.offering.id, "activate", expected_version=1,
        actor_context_ref="integration-test-context", now=STAMP,
    )
    offering_b = offerings.create_offering(
        db, product_id=product_id, activity_id=activity_b, effective_from=STAMP,
        actor_context_ref="integration-test-context", now=STAMP,
    )
    offerings.transition_offering(
        db, offering_b.offering.id, "activate", expected_version=1,
        actor_context_ref="integration-test-context", now=STAMP,
    )
    offering_a_id = str(offering_a.offering.id)
    offering_b_id = str(offering_b.offering.id)

    _position(
        db, activity_id=activity_a, product_id=product_id, offering_id=offering_a_id,
        quantity=5, observed_at="2026-10-12T11:00:00Z",
    )
    _position(
        db, activity_id=activity_b, product_id=product_id, offering_id=offering_b_id,
        quantity=99, observed_at="2026-10-12T11:00:00Z",
    )

    inventory = InventoryApplication()
    read_a = inventory.read_offering_quantity(db, offering_a_id, "store-A", as_of=NOW)
    read_b = inventory.read_offering_quantity(db, offering_b_id, "store-A", as_of=NOW)
    assert read_a.status is InventoryStatus.KNOWN and read_a.quantity_minor == 5
    assert read_b.status is InventoryStatus.KNOWN and read_b.quantity_minor == 99
    # A row for Activity B cannot provide the quantity for Offering A.
    assert read_a.inventory_position_id != read_b.inventory_position_id

    sales = SaleApplication()
    create_grant_a = _grant(db, actor_id, offering_a_id, activity_a, "create_sale")

    # An explicitly empty timestamp is invalid, not an instruction to use wall-clock time.
    before_invalid_sales = db.execute("SELECT COUNT(*) FROM sales").fetchone()[0]
    before_invalid_history = db.execute("SELECT COUNT(*) FROM domain_history").fetchone()[0]
    with pytest.raises(ValidationError, match="Sale operation timestamp"):
        sales.create_sale(
            db, offering_a_id, activity_a, actor_id, create_grant_a, now=""
        )
    assert db.execute("SELECT COUNT(*) FROM sales").fetchone()[0] == before_invalid_sales
    assert db.execute("SELECT COUNT(*) FROM domain_history").fetchone()[0] == before_invalid_history

    # An explicitly supplied empty Sale identifier must be rejected, not treated as omitted.
    before_empty_id_sales = db.execute("SELECT COUNT(*) FROM sales").fetchone()[0]
    before_empty_id_history = db.execute("SELECT COUNT(*) FROM domain_history").fetchone()[0]
    with pytest.raises(ValidationError, match="Sale identifier must be a valid UUID"):
        sales.create_sale(
            db, offering_a_id, activity_a, actor_id, create_grant_a, now=NOW, sale_id=""
        )
    assert db.execute("SELECT COUNT(*) FROM sales").fetchone()[0] == before_empty_id_sales
    assert db.execute("SELECT COUNT(*) FROM domain_history").fetchone()[0] == before_empty_id_history

    sale = sales.create_sale(
        db, offering_a_id, activity_a, actor_id, create_grant_a, now=NOW
    )
    assert sale.state is SaleState.INITIATED

    # Malformed action containers must fail through the domain validation contract,
    # without touching the persisted Sale or appending history.
    before_invalid_action = db.execute(
        "SELECT state, version_no FROM sales WHERE sale_id=?", (str(sale.id),)
    ).fetchone()
    before_invalid_action_history = db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=?", (str(sale.id),)
    ).fetchone()[0]
    for invalid_action in ([], {}):
        with pytest.raises(ValidationError, match="Unsupported Sale lifecycle action"):
            sales.transition_sale(
                db, sale.id, actor_id, create_grant_a, invalid_action, now=NOW
            )
        assert db.execute(
            "SELECT state, version_no FROM sales WHERE sale_id=?", (str(sale.id),)
        ).fetchone() == before_invalid_action
        assert db.execute(
            "SELECT COUNT(*) FROM domain_history WHERE target_ref=?", (str(sale.id),)
        ).fetchone()[0] == before_invalid_action_history

    # A grant scoped to A cannot create a sale for B; failure must leave no partial Sale/history.
    before_sales = db.execute("SELECT COUNT(*) FROM sales").fetchone()[0]
    before_history = db.execute("SELECT COUNT(*) FROM domain_history").fetchone()[0]
    with pytest.raises(ValidationError, match="scope does not match Sale"):
        sales.create_sale(
            db, offering_b_id, activity_b, actor_id, create_grant_a, now=NOW
        )
    assert db.execute("SELECT COUNT(*) FROM sales").fetchone()[0] == before_sales
    assert db.execute("SELECT COUNT(*) FROM domain_history").fetchone()[0] == before_history

    confirm_grant = _grant(db, actor_id, str(sale.id), activity_a, "confirm_sale")
    db.execute(
        "CREATE TRIGGER reject_confirm_history BEFORE INSERT ON domain_history "
        "WHEN NEW.target_ref='" + str(sale.id) + "' "
        "AND NEW.change_type='sale_state_transition' "
        "AND NEW.change_payload_ref='state:confirmed' "
        "BEGIN SELECT RAISE(ABORT, 'forced integration history failure'); END"
    )
    db.commit()
    with pytest.raises(sqlite3.IntegrityError, match="forced integration history failure"):
        sales.confirm_sale(db, sale.id, actor_id, confirm_grant, now=NOW)
    assert db.execute(
        "SELECT state,version_no FROM sales WHERE sale_id=?", (str(sale.id),)
    ).fetchone() == ("initiated", 1)
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=?", (str(sale.id),)
    ).fetchone()[0] == 1

    db.execute("DROP TRIGGER reject_confirm_history")
    db.commit()
    confirmed = sales.confirm_sale(db, sale.id, actor_id, confirm_grant, now=NOW)
    assert confirmed.state is SaleState.CONFIRMED

    # Sale confirmation has no inventory side effect under the current application contract.
    after_sale_stock = inventory.read_offering_quantity(
        db, offering_a_id, "store-A", as_of=NOW
    )
    assert after_sale_stock.status is InventoryStatus.KNOWN
    assert after_sale_stock.quantity_minor == 5
    assert db.execute(
        "SELECT COUNT(*) FROM invoices WHERE sale_id=?", (str(sale.id),)
    ).fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM payments").fetchone()[0] == 0

    db.close()
    db = connect_database(tmp_path / "commerce-integration.sqlite")
    restored = sales.get_sale(db, sale.id)
    assert restored.state is SaleState.CONFIRMED
    assert restored.history == (SaleState.INITIATED, SaleState.CONFIRMED)
    history = db.execute(
        "SELECT prior_version_ref,current_version_ref,change_payload_ref "
        "FROM domain_history WHERE target_ref=? ORDER BY rowid", (str(sale.id),)
    ).fetchall()
    assert history == [
        (None, f"sale-state:{sale.id}:v1", "state:initiated"),
        (f"sale-state:{sale.id}:v1", f"sale-state:{sale.id}:v2", "state:confirmed"),
    ]
    assert db.execute("PRAGMA foreign_key_check").fetchall() == []
    db.close()


def test_product_transition_rejects_malformed_actions_without_mutation(tmp_path):
    db = connect_database(tmp_path / "product-action-validation.sqlite")
    products = ProductApplication()
    record = products.create_product(
        db, "Action validation product",
        actor_context_ref="product-action-test", now=STAMP,
    )
    product_id = str(record.product.id)
    before = db.execute(
        "SELECT state, version_no FROM products WHERE product_id=?", (product_id,)
    ).fetchone()
    before_history = db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=?", (product_id,)
    ).fetchone()[0]

    for invalid_action in ([], {}):
        with pytest.raises(ValidationError, match="Unsupported Product action"):
            products.transition_product(
                db, product_id, invalid_action, expected_version=1,
                actor_context_ref="product-action-test", now=NOW,
            )
        assert db.execute(
            "SELECT state, version_no FROM products WHERE product_id=?", (product_id,)
        ).fetchone() == before
        assert db.execute(
            "SELECT COUNT(*) FROM domain_history WHERE target_ref=?", (product_id,)
        ).fetchone()[0] == before_history

    db.close()

def test_sale_creation_keeps_authorization_validity_atomic_with_persistence(tmp_path, monkeypatch):
    db_path = tmp_path / "sale-authorization-race.sqlite"
    db = connect_database(db_path)
    actor_id = _id()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (actor_id, "active", STAMP, STAMP),
    )
    activity_id = _activity(db, actor_id)
    product = ProductApplication().create_product(
        db, "Authorization race product",
        actor_context_ref="authorization-race-test", now=STAMP,
    )
    product_id = str(product.product.id)
    ProductApplication().transition_product(
        db, product_id, "activate", expected_version=1,
        actor_context_ref="authorization-race-test", now=STAMP,
    )
    offering = OfferingApplication().create_offering(
        db, product_id=product_id, activity_id=activity_id,
        effective_from=STAMP, actor_context_ref="authorization-race-test", now=STAMP,
    )
    OfferingApplication().transition_offering(
        db, offering.offering.id, "activate", expected_version=1,
        actor_context_ref="authorization-race-test", now=STAMP,
    )
    offering_id = str(offering.offering.id)
    grant_id = _grant(db, actor_id, offering_id, activity_id, "create_sale")
    db.commit()

    concurrent_db = connect_database(db_path)
    concurrent_db.execute("PRAGMA busy_timeout=0")
    sales = SaleApplication()
    original_authorize = sales._authorize

    def revoke_after_authorization_check(*args, **kwargs):
        original_authorize(*args, **kwargs)
        # Simulate a concurrent grant revocation in the gap between authorization
        # and Sale INSERT. A correct creation transaction holds the write lock first.
        concurrent_db.execute(
            "UPDATE authorization_grants SET state='revoked' "
            "WHERE authorization_grant_id=?", (grant_id,),
        )
        concurrent_db.commit()

    monkeypatch.setattr(sales, "_authorize", revoke_after_authorization_check)
    sales_before = db.execute("SELECT COUNT(*) FROM sales").fetchone()[0]
    history_before = db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE owner_domain='commerce'"
    ).fetchone()[0]

    with pytest.raises(sqlite3.OperationalError, match="locked"):
        sales.create_sale(db, offering_id, activity_id, actor_id, grant_id, now=NOW)

    assert db.execute("SELECT COUNT(*) FROM sales").fetchone()[0] == sales_before
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE owner_domain='commerce'"
    ).fetchone()[0] == history_before
    assert concurrent_db.execute(
        "SELECT state FROM authorization_grants WHERE authorization_grant_id=?",
        (grant_id,),
    ).fetchone()[0] == "active"
    assert db.execute("PRAGMA foreign_key_check").fetchall() == []
    concurrent_db.close()
    db.close()

