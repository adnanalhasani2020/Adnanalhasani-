"""Application-level inventory quantity reads scoped to an Offering context."""
from datetime import datetime, timezone
import uuid

import pytest

from agent_core.application import InventoryApplication
from agent_core.inventory_read import InventoryQuantityStatus as Status
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

NOW = "2026-10-09T12:00:00Z"
AS_OF = datetime(2026, 10, 9, 12, 0, tzinfo=timezone.utc)


def _id():
    return str(uuid.uuid4())


def _context(db):
    person, activity_a, activity_b, product = _id(), _id(), _id(), _id()
    for person_id in (person,):
        db.execute(
            "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
            (person_id, "active", NOW, NOW),
        )
    for activity in (activity_a, activity_b):
        db.execute(
            "INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) VALUES(?,?,?,?,?)",
            (activity, person, "active", NOW, NOW),
        )
    db.execute(
        "INSERT INTO products(product_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (product, "active", NOW, NOW),
    )
    offering_a, offering_b = _id(), _id()
    for offering, activity in ((offering_a, activity_a), (offering_b, activity_b)):
        db.execute(
            "INSERT INTO offerings(offering_id,product_id,activity_id,state,effective_from,effective_to,created_at,updated_at) "
            "VALUES(?,?,?,?,?,?,?,?)",
            (offering, product, activity, "active", "2026-10-01T00:00:00Z", None, NOW, NOW),
        )
    return activity_a, activity_b, product, offering_a, offering_b


def _position(db, activity, *, offering=None, scope="warehouse-A", quantity=12,
              observed="2026-10-09T11:00:00Z", state="effective"):
    position = _id()
    db.execute(
        "INSERT INTO inventory_positions(inventory_position_id,activity_id,product_id,offering_id,location_ref,"
        "scope_key,state,quantity_minor,observed_at,effective_from,effective_to,created_at,updated_at) "
        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
        (position, activity, None, offering, "warehouse", scope, state, quantity,
         observed, "2026-10-09T00:00:00Z", None, NOW, NOW),
    )
    return position


def test_application_reads_quantity_for_requested_offering():
    db = connect_database()
    activity_a, activity_b, _product, offering_a, offering_b = _context(db)
    expected = _position(db, activity_a, offering=offering_a, quantity=24)
    # Same scope is not enough to make another Offering's row eligible.
    _position(db, activity_b, offering=offering_b, quantity=999)
    result = InventoryApplication().read_offering_quantity(
        db, offering_a, "warehouse-A", as_of=AS_OF
    )
    assert result.status is Status.KNOWN
    assert result.quantity_minor == 24
    assert result.inventory_position_id == expected
    db.close()


def test_other_offering_in_same_scope_cannot_supply_quantity():
    db = connect_database()
    activity_a, activity_b, _product, offering_a, offering_b = _context(db)
    _position(db, activity_b, offering=offering_b, quantity=999)
    result = InventoryApplication().read_offering_quantity(
        db, offering_a, "warehouse-A", as_of=AS_OF
    )
    assert result.status is Status.NO_RECORD
    assert result.quantity_minor is None
    db.close()


def test_activity_mismatch_is_excluded_even_when_scope_matches():
    db = connect_database()
    activity_a, activity_b, _product, offering_a, _offering_b = _context(db)
    # Legacy/unscoped position from another Activity must not leak into Offering A.
    _position(db, activity_b, offering=None, quantity=500)
    result = InventoryApplication().read_offering_quantity(
        db, offering_a, "warehouse-A", as_of=AS_OF
    )
    assert result.status is Status.NO_RECORD
    assert result.quantity_minor is None
    db.close()


def test_null_quantity_remains_unknown_not_zero():
    db = connect_database()
    activity_a, _activity_b, _product, offering_a, _offering_b = _context(db)
    position = _position(db, activity_a, offering=offering_a, quantity=None)
    result = InventoryApplication().read_offering_quantity(
        db, offering_a, "warehouse-A", as_of=AS_OF
    )
    assert result.status is Status.UNKNOWN_QUANTITY
    assert result.quantity_minor is None
    assert result.inventory_position_id == position
    db.close()


def test_no_valid_record_returns_no_record():
    db = connect_database()
    activity_a, _activity_b, _product, offering_a, _offering_b = _context(db)
    _position(db, activity_a, offering=offering_a, quantity=4, state="observed")
    result = InventoryApplication().read_offering_quantity(
        db, offering_a, "warehouse-A", as_of=AS_OF
    )
    assert result.status is Status.NO_RECORD
    db.close()


def test_conflicting_latest_records_for_same_offering_are_ambiguous():
    db = connect_database()
    activity_a, _activity_b, _product, offering_a, _offering_b = _context(db)
    _position(db, activity_a, offering=offering_a, quantity=4)
    _position(db, activity_a, offering=offering_a, quantity=40)
    result = InventoryApplication().read_offering_quantity(
        db, offering_a, "warehouse-A", as_of=AS_OF
    )
    assert result.status is Status.AMBIGUOUS
    assert result.quantity_minor is None
    db.close()


def test_different_scope_is_not_aggregated_or_used_as_fallback():
    db = connect_database()
    activity_a, _activity_b, _product, offering_a, _offering_b = _context(db)
    _position(db, activity_a, offering=offering_a, scope="warehouse-B", quantity=800)
    result = InventoryApplication().read_offering_quantity(
        db, offering_a, "warehouse-A", as_of=AS_OF
    )
    assert result.status is Status.NO_RECORD
    assert result.quantity_minor is None
    db.close()


def test_unknown_offering_is_rejected():
    db = connect_database()
    with pytest.raises(ValidationError, match="existing Offering"):
        InventoryApplication().read_offering_quantity(
            db, _id(), "warehouse-A", as_of=AS_OF
        )
    db.close()
