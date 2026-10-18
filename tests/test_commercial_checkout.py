"""Commercial checkout acceptance tests: persisted prices, stock movements, and replay."""
from datetime import datetime, timezone
import sqlite3
import uuid

import pytest

from agent_core.commerce_checkout import CommercialCheckoutApplication
from agent_core.inventory_read import InventoryQuantityStatus
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError
from agent_core.application import ProductApplication, SaleApplication
from agent_core.offering_application import OfferingApplication
from agent_core.offering_price_application import OfferingPriceApplication

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
               effective_from,effective_to,actor_context_ref,created_at
           ) VALUES(?,?,1250,'SAR',?,NULL,'checkout-test',?)""",
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
    db.commit()
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
    db.commit()
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



def test_price_change_closes_open_window_and_records_actor_history(tmp_path):
    db, request = setup_checkout(tmp_path)
    app = OfferingPriceApplication()

    result = app.set_price(
        db, offering_id=request["offering_id"], unit_price_minor=1500,
        currency_code="SAR", actor_context_ref=request["actor_person_id"],
        effective_from=NOW, now=NOW,
    )

    rows = db.execute(
        """SELECT unit_price_minor,effective_from,effective_to,actor_context_ref
           FROM offering_prices WHERE offering_id=? ORDER BY effective_from""",
        (request["offering_id"],),
    ).fetchall()
    assert len(rows) == 2
    assert rows[0][0] == 1250 and rows[0][2] == NOW.isoformat(timespec="microseconds").replace("+00:00", "Z")
    assert rows[1][0] == 1500 and rows[1][2] is None
    assert rows[1][3] == request["actor_person_id"]
    assert result["unit_price_minor"] == 1500
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE change_type IN ('offering_price_set','offering_price_window_closed')"
    ).fetchone()[0] == 2



def test_concurrent_checkouts_cannot_sell_the_last_unit_twice(tmp_path):
    import threading
    from datetime import timedelta

    db, base_request = setup_checkout(tmp_path, quantity=1)
    base_request["quantity"] = 1
    database_path = tmp_path / "checkout.sqlite"
    db.close()
    barrier = threading.Barrier(2)
    outcomes = []
    outcome_lock = threading.Lock()

    def worker(index):
        connection = connect_database(database_path)
        request = dict(base_request, idempotency_key=f"checkout-concurrent-{index}", sale_id=uid())
        grants = []
        for action, scope in (
            ("create_sale", request["offering_id"]),
            ("confirm_sale", request["sale_id"]),
            ("fulfill_sale", request["sale_id"]),
        ):
            grant_id = uid()
            connection.execute(
                """INSERT INTO authorization_grants(
                       authorization_grant_id,subject_person_id,agent_id,action_code,scope_ref,
                       context_ref,delegation_id,state,effective_from,effective_to,created_at,updated_at
                   ) VALUES(?,?,NULL,?,?,?,NULL,'active',?,NULL,?,?)""",
                (grant_id, request["actor_person_id"], action, scope, request["activity_id"],
                 "2026-01-01T00:00:00Z", BEFORE, BEFORE),
            )
            grants.append(grant_id)
        request["create_sale_grant_id"], request["confirm_sale_grant_id"], request["fulfill_sale_grant_id"] = grants
        connection.commit()
        barrier.wait()
        try:
            CommercialCheckoutApplication().checkout(
                connection, **request, now=NOW + timedelta(seconds=index + 1)
            )
            outcome = ("success", "")
        except Exception as exc:
            outcome = ("rejected", f"{type(exc).__name__}: {exc}")
        finally:
            connection.close()
        with outcome_lock:
            outcomes.append(outcome)

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(2)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(timeout=10)

    assert all(not thread.is_alive() for thread in threads), "checkout concurrency test timed out"
    assert sorted(item[0] for item in outcomes) == ["rejected", "success"], repr(outcomes)
    verify = connect_database(database_path)
    assert verify.execute("SELECT COUNT(*) FROM sales").fetchone()[0] == 1
    assert verify.execute("SELECT SUM(quantity_delta) FROM inventory_movements").fetchone()[0] == -1
    assert verify.execute("SELECT COUNT(*) FROM commercial_checkout_receipts").fetchone()[0] == 1
    verify.close()

def test_checkout_uses_current_effective_price_not_stale_catalog_price(tmp_path):
    from datetime import timedelta

    db, request = setup_checkout(tmp_path)
    app = CommercialCheckoutApplication()
    prices = OfferingPriceApplication()
    changed_at = NOW + timedelta(minutes=1)
    prices.set_price(
        db,
        offering_id=request["offering_id"],
        unit_price_minor=1500,
        currency_code="SAR",
        actor_context_ref=request["actor_person_id"],
        effective_from=changed_at,
        now=changed_at,
    )

    # The caller may have rendered the earlier 1,250-minor-unit catalog price;
    # checkout must price from the effective server-side record at commit time.
    result = app.checkout(db, **request, now=NOW + timedelta(minutes=2))

    assert result["unit_price_minor"] == 1500
    assert result["total_minor"] == 3000
    assert result["quantity"] == 2
    assert db.execute("SELECT COUNT(*) FROM sales").fetchone()[0] == 1
    assert db.execute("SELECT COUNT(*) FROM inventory_movements").fetchone()[0] == 1

def test_checkout_idempotency_survives_database_reopen(tmp_path):
    from datetime import timedelta

    db, request = setup_checkout(tmp_path)
    app = CommercialCheckoutApplication()
    first = app.checkout(db, **request, now=NOW)
    db.close()

    # Simulate a process restart after commit but before the client receives
    # the response. A retry must recover the original receipt, not sell again.
    reopened = connect_database(tmp_path / "checkout.sqlite")
    replay = app.checkout(
        reopened,
        **request,
        now=NOW + timedelta(minutes=10),
    )

    assert replay == first
    assert reopened.execute("SELECT COUNT(*) FROM sales").fetchone()[0] == 1
    assert reopened.execute("SELECT COUNT(*) FROM sale_lines").fetchone()[0] == 1
    assert reopened.execute("SELECT COUNT(*) FROM inventory_movements").fetchone()[0] == 1
    assert reopened.execute("SELECT COUNT(*) FROM commercial_checkout_receipts").fetchone()[0] == 1
    assert reopened.execute("SELECT SUM(quantity_delta) FROM inventory_movements").fetchone()[0] == -2
    reopened.close()


def test_checkout_receipt_does_not_imply_financial_recognition_or_ledger_posting(tmp_path):
    db, request = setup_checkout(tmp_path)
    app = CommercialCheckoutApplication()

    # A commercial sale/receipt is not, by itself, evidence that money moved
    # or that an approved accounting-recognition event occurred.
    assert db.execute("SELECT COUNT(*) FROM financial_transactions").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM ledger_entries").fetchone()[0] == 0

    result = app.checkout(db, **request, now=NOW)

    assert result["state"] == "fulfilled"
    assert db.execute("SELECT COUNT(*) FROM commercial_checkout_receipts").fetchone()[0] == 1
    assert db.execute("SELECT COUNT(*) FROM sales").fetchone()[0] == 1
    assert db.execute("SELECT COUNT(*) FROM financial_transactions").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM ledger_entries").fetchone()[0] == 0



@pytest.mark.parametrize(
    "occurred_at",
    ["2026-10-17T10:59:59Z", "2026-10-17T11:30:00Z"],
)
def test_inventory_movement_must_fall_inside_position_effective_window(tmp_path, occurred_at):
    db, request = setup_checkout(tmp_path)
    sale_app = SaleApplication()
    db.execute(
        "UPDATE inventory_positions SET effective_to=? WHERE inventory_position_id=("
        "SELECT inventory_position_id FROM inventory_positions WHERE offering_id=?)",
        ("2026-10-17T11:30:00Z", request["offering_id"]),
    )
    db.commit()

    sale_app.create_sale(
        db, request["offering_id"], request["activity_id"], request["actor_person_id"],
        request["create_sale_grant_id"], now=NOW, sale_id=request["sale_id"],
    )
    sale_app.confirm_sale(
        db, request["sale_id"], request["actor_person_id"],
        request["confirm_sale_grant_id"], now=NOW,
    )
    sale_app.fulfill_sale(
        db, request["sale_id"], request["actor_person_id"],
        request["fulfill_sale_grant_id"], now=NOW,
    )
    position_id = db.execute(
        "SELECT inventory_position_id FROM inventory_positions WHERE offering_id=?",
        (request["offering_id"],),
    ).fetchone()[0]
    db.execute(
        """INSERT INTO sale_lines(
               sale_line_id, sale_id, offering_id, product_id, quantity,
               unit_price_minor, currency_code, line_total_minor, created_at
           ) VALUES(?,?,?,?,1,1250,'SAR',1250,?)""",
        (uid(), request["sale_id"], request["offering_id"],
         db.execute("SELECT product_id FROM offerings WHERE offering_id=?",
                    (request["offering_id"],)).fetchone()[0], STAMP),
    )
    db.commit()

    with pytest.raises(sqlite3.IntegrityError, match="within its position effective window"):
        db.execute(
            """INSERT INTO inventory_movements(
                   inventory_movement_id, inventory_position_id, activity_id, product_id,
                   offering_id, location_ref, scope_key, quantity_delta, source_type,
                   source_ref, actor_context_ref, occurred_at, created_at
               ) VALUES(?,?,?,?,?,?,?,-1,'sale',?,?,?,?)""",
            (uid(), position_id, request["activity_id"],
             db.execute("SELECT product_id FROM offerings WHERE offering_id=?",
                        (request["offering_id"],)).fetchone()[0],
             request["offering_id"], request["location_ref"], request["scope_key"],
             request["sale_id"], request["actor_person_id"], occurred_at, STAMP),
        )
    db.rollback()
    assert db.execute("SELECT COUNT(*) FROM inventory_movements").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM sale_lines").fetchone()[0] == 1
    assert db.execute("SELECT state FROM sales WHERE sale_id=?", (request["sale_id"],)).fetchone()[0] in {"completed", "fulfilled"}
    db.close()
