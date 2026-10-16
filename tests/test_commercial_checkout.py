"""Commercial checkout acceptance tests: persisted prices, stock movements, and replay."""
from datetime import datetime, timezone
import uuid

import pytest

from agent_core.commerce_checkout import CommercialCheckoutApplication
from agent_core.inventory_read import InventoryQuantityStatus
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError
from agent_core.application import ProductApplication
from agent_core.offering_application import OfferingApplication

NOW = datetime(2026, 10, 17, 12, 0, tzinfo=timezone.utc)
STAMP = "2026-10-17T12:00:00Z"
BEFORE = "2026-10-17T11:00:00Z"


def uid():
    return str(uuid.uuid4())


def setup_checkout(tmp_path, quantity=5):
    db = connect_database(tmp_path / "checkout.sqlite")
    actor = uid()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (actor, "active", BEFORE, BEFORE),
    )
    activity = uid()
    db.execute(
        "INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) VALUES(?,?, 'active',?,?)",
        (activity, actor, BEFORE, BEFORE),
    )
    products = ProductApplication()
    product = products.create_product(db, "Bottle", actor_context_ref="checkout-test", now=BEFORE)
    products.transition_product(
        db, product.product.id, "activate", expected_version=1,
        actor_context_ref="checkout-test", now=BEFORE,
    )
    offerings = OfferingApplication()
    offering = offerings.create_offering(
        db, product_id=product.product.id, activity_id=activity,
        effective_from=BEFORE, actor_context_ref="checkout-test", now=BEFORE,
    )
    offerings.transition_offering(
        db, offering.offering.id, "activate", expected_version=1,
        actor_context_ref="checkout-test", now=BEFORE,
    )
    offering_id = str(offering.offering.id)
    product_id = str(product.product.id)
    position_id = uid()
    db.execute(
        """INSERT INTO inventory_positions(
               inventory_position_id,activity_id,product_id,offering_id,location_ref,
               scope_key,state,quantity_minor,observed_at,effective_from,effective_to,
               created_at,updated_at,provenance_id,version_no
           ) VALUES(?,?,?,?,?,'store-A','effective',?,?,?,NULL,?,?,NULL,1)""",
        (position_id, activity, product_id, offering_id, "front", quantity,
         BEFORE, BEFORE, BEFORE, BEFORE),
    )
    db.execute(
        """INSERT INTO offering_prices(
               offering_price_id,offering_id,unit_price_minor,currency_code,
               effective_from,effective_to,created_at
           ) VALUES(?,?,1250,'SAR',?,NULL,?)""",
        (uid(), offering_id, BEFORE, BEFORE),
    )
    sale_id = uid()

    def grant(action, scope):
        grant_id = uid()
        db.execute(
            """INSERT INTO authorization_grants(
                   authorization_grant_id,subject_person_id,agent_id,action_code,scope_ref,
                   context_ref,delegation_id,state,effective_from,effective_to,created_at,updated_at
               ) VALUES(?,?,NULL,?,?,?,NULL,'active',?,NULL,?,?)""",
            (grant_id, actor, action, scope, activity, "2026-01-01T00:00:00Z", BEFORE, BEFORE),
        )
        return grant_id

    grants = {
        "create": grant("create_sale", offering_id),
        "confirm": grant("confirm_sale", sale_id),
        "fulfill": grant("fulfill_sale", sale_id),
    }
    return db, {
        "actor_person_id": actor, "activity_id": activity, "offering_id": offering_id,
        "quantity": 2, "scope_key": "store-A", "location_ref": "front",
        "idempotency_key": "checkout-001", "sale_id": sale_id,
        "create_sale_grant_id": grants["create"],
        "confirm_sale_grant_id": grants["confirm"],
        "fulfill_sale_grant_id": grants["fulfill"],
    }


def test_checkout_fulfills_sale_and_records_stock_movement_atomically(tmp_path):
    db, request = setup_checkout(tmp_path)
    app = CommercialCheckoutApplication()

    result = app.checkout(db, **request, now=NOW)

    assert result["state"] == "fulfilled"
    assert result["quantity"] == 2
    assert result["unit_price_minor"] == 1250
    assert result["currency_code"] == "SAR"
    assert result["total_minor"] == 2500
    assert result["quantity_remaining"] == 3
    assert db.execute("SELECT COUNT(*) FROM sale_lines").fetchone()[0] == 1
    assert db.execute("SELECT quantity_delta FROM inventory_movements").fetchone()[0] == -2
    assert db.execute("SELECT state FROM sales WHERE sale_id=?", (request["sale_id"],)).fetchone()[0] in {"completed", "fulfilled"}

    listed = app.list_sellable_offerings(
        db, activity_id=request["activity_id"], scope_key="store-A",
        location_ref="front", as_of=datetime(2026, 10, 17, 12, 1, tzinfo=timezone.utc),
    )
    assert len(listed) == 1
    assert listed[0]["available_quantity"] == 3


def test_checkout_replay_returns_same_result_without_second_deduction(tmp_path):
    db, request = setup_checkout(tmp_path)
    app = CommercialCheckoutApplication()

    first = app.checkout(db, **request, now=NOW)
    replay = app.checkout(
        db, **request,
        now=datetime(2026, 10, 17, 12, 5, tzinfo=timezone.utc),
    )

    assert replay == first
    assert db.execute("SELECT COUNT(*) FROM sales").fetchone()[0] == 1
    assert db.execute("SELECT COUNT(*) FROM inventory_movements").fetchone()[0] == 1


def test_checkout_rejects_quantity_above_available_without_partial_writes(tmp_path):
    db, request = setup_checkout(tmp_path, quantity=1)
    request["quantity"] = 2
    app = CommercialCheckoutApplication()

    with pytest.raises(ValidationError, match="exceeds available stock"):
        app.checkout(db, **request, now=NOW)

    assert db.execute("SELECT COUNT(*) FROM sales").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM sale_lines").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM inventory_movements").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM commercial_checkout_receipts").fetchone()[0] == 0


def test_checkout_rejects_reused_key_with_changed_payload(tmp_path):
    db, request = setup_checkout(tmp_path)
    app = CommercialCheckoutApplication()
    app.checkout(db, **request, now=NOW)
    changed = dict(request, quantity=1)

    with pytest.raises(ValidationError, match="different request"):
        app.checkout(db, **changed, now=NOW)

    assert db.execute("SELECT COUNT(*) FROM sales").fetchone()[0] == 1
    assert db.execute("SELECT COUNT(*) FROM inventory_movements").fetchone()[0] == 1


def test_checkout_rejects_unknown_inventory_without_sale(tmp_path):
    db, request = setup_checkout(tmp_path)
    db.execute("UPDATE inventory_positions SET quantity_minor=NULL")
    app = CommercialCheckoutApplication()

    with pytest.raises(ValidationError, match="known, effective, unambiguous stock"):
        app.checkout(db, **request, now=NOW)

    assert db.execute("SELECT COUNT(*) FROM sales").fetchone()[0] == 0



def test_checkout_rolls_back_sale_and_history_if_stock_movement_write_fails(tmp_path):
    db, request = setup_checkout(tmp_path)
    db.execute(
        """CREATE TRIGGER force_movement_failure
           BEFORE INSERT ON inventory_movements
           BEGIN SELECT RAISE(ABORT, 'forced movement failure'); END"""
    )
    app = CommercialCheckoutApplication()

    with pytest.raises(Exception, match="forced movement failure"):
        app.checkout(db, **request, now=NOW)

    assert db.execute("SELECT COUNT(*) FROM sales").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM sale_lines").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM inventory_movements").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM commercial_checkout_receipts").fetchone()[0] == 0
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=?", (request["sale_id"],)
    ).fetchone()[0] == 0


def test_checkout_rejects_known_zero_stock(tmp_path):
    db, request = setup_checkout(tmp_path, quantity=0)
    app = CommercialCheckoutApplication()

    with pytest.raises(ValidationError, match="exceeds available stock"):
        app.checkout(db, **request, now=NOW)

    assert db.execute("SELECT COUNT(*) FROM sales").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM inventory_movements").fetchone()[0] == 0
