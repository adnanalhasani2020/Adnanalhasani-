"""Fail-closed InventoryQuantityReader behavior against persisted rows."""
from datetime import datetime, timezone
import uuid

import pytest

from agent_core.inventory_read import (
    InventoryQuantityReader,
    InventoryQuantityStatus as Status,
)
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

AS_OF = datetime(2026, 10, 9, 12, 0, tzinfo=timezone.utc)
STAMP = "2026-10-09T12:00:00Z"


def _id():
    return str(uuid.uuid4())


def _database():
    db = connect_database()
    person, activity = _id(), _id()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (person, "active", STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) VALUES(?,?,?,?,?)",
        (activity, person, "active", STAMP, STAMP),
    )
    return db, activity


def _position(db, activity, *, scope="warehouse-A", quantity=5, state="effective",
              observed="2026-10-09T11:00:00Z",
              starts="2026-10-09T00:00:00Z", ends=None):
    position = _id()
    db.execute(
        "INSERT INTO inventory_positions(inventory_position_id,activity_id,scope_key,state,quantity_minor,"
        "observed_at,effective_from,effective_to,created_at,updated_at) "
        "VALUES(?,?,?,?,?,?,?,?,?,?)",
        (position, activity, scope, state, quantity, observed, starts, ends, STAMP, STAMP),
    )
    return position


def test_as_of_none_uses_current_utc_time():
    db, _activity = _database()
    before = datetime.now(timezone.utc)
    result = InventoryQuantityReader().read_quantity(db, "warehouse-A", as_of=None)
    after = datetime.now(timezone.utc)

    assert before <= result.as_of <= after
    assert result.as_of.tzinfo is timezone.utc
    assert result.status is Status.NO_RECORD
    db.close()

def test_valid_as_of_string_is_preserved_as_the_requested_instant():
    db, activity = _database()
    position = _position(db, activity, quantity=31, observed="2026-10-09T11:00:00Z", starts="2026-10-09T11:00:00Z")
    result = InventoryQuantityReader().read_quantity(db, "warehouse-A", as_of="2026-10-09T12:00:00+00:00")

    assert result.as_of == AS_OF
    assert result.status is Status.KNOWN
    assert result.quantity_minor == 31
    assert result.inventory_position_id == position
    db.close()

def test_one_valid_record_returns_quantity_and_source():
    db, activity = _database()
    position = _position(db, activity, quantity=17)
    result = InventoryQuantityReader().read_quantity(db, "warehouse-A", as_of=AS_OF)
    assert result.status is Status.KNOWN
    assert result.quantity_minor == 17
    assert result.inventory_position_id == position
    assert result.scope_key == "warehouse-A"
    db.close()


def test_multiple_records_in_same_scope_select_latest_valid_observation():
    db, activity = _database()
    _position(db, activity, quantity=3, observed="2026-10-09T09:00:00Z")
    latest = _position(db, activity, quantity=8, observed="2026-10-09T11:00:00Z")
    result = InventoryQuantityReader().read_quantity(db, "warehouse-A", as_of=AS_OF)
    assert result.status is Status.KNOWN
    assert result.quantity_minor == 8
    assert result.inventory_position_id == latest
    db.close()


def test_different_scopes_are_never_aggregated():
    db, activity = _database()
    _position(db, activity, scope="warehouse-A", quantity=5)
    _position(db, activity, scope="warehouse-B", quantity=900)
    reader = InventoryQuantityReader()
    a = reader.read_quantity(db, "warehouse-A", as_of=AS_OF)
    b = reader.read_quantity(db, "warehouse-B", as_of=AS_OF)
    assert a.quantity_minor == 5
    assert b.quantity_minor == 900
    assert a.quantity_minor != a.quantity_minor + b.quantity_minor
    db.close()


def test_null_quantity_is_unknown_not_zero_and_does_not_fall_back():
    db, activity = _database()
    _position(db, activity, quantity=42, observed="2026-10-09T09:00:00Z")
    latest = _position(db, activity, quantity=None, observed="2026-10-09T11:00:00Z")
    result = InventoryQuantityReader().read_quantity(db, "warehouse-A", as_of=AS_OF)
    assert result.status is Status.UNKNOWN_QUANTITY
    assert result.quantity_minor is None
    assert result.quantity_minor != 0
    assert result.inventory_position_id == latest
    db.close()


def test_non_effective_or_out_of_period_rows_are_not_eligible():
    db, activity = _database()
    _position(db, activity, quantity=20, state="observed")
    _position(db, activity, quantity=30, ends="2026-10-09T10:00:00Z")
    _position(db, activity, quantity=40, starts="2026-10-09T13:00:00Z")
    result = InventoryQuantityReader().read_quantity(db, "warehouse-A", as_of=AS_OF)
    assert result.status is Status.NO_RECORD
    assert result.quantity_minor is None
    db.close()


def test_no_record_returns_explicit_no_record_status():
    db, _activity_id = _database()
    result = InventoryQuantityReader().read_quantity(db, "empty-scope", as_of=AS_OF)
    assert result.status is Status.NO_RECORD
    assert result.quantity_minor is None
    db.close()


def test_tied_latest_observations_are_ambiguous_even_if_quantities_differ():
    db, activity = _database()
    _position(db, activity, quantity=7, observed="2026-10-09T11:00:00Z")
    _position(db, activity, quantity=70, observed="2026-10-09T11:00:00Z")
    result = InventoryQuantityReader().read_quantity(db, "warehouse-A", as_of=AS_OF)
    assert result.status is Status.AMBIGUOUS
    assert result.quantity_minor is None
    db.close()


def test_scope_and_as_of_must_be_valid():
    db, _activity_id = _database()
    reader = InventoryQuantityReader()
    with pytest.raises(ValidationError, match="scope_key"):
        reader.read_quantity(db, "  ", as_of=AS_OF)
    with pytest.raises(ValidationError, match="timezone-aware"):
        reader.read_quantity(db, "warehouse-A", as_of=datetime(2026, 10, 9, 12, 0))
    with pytest.raises(ValidationError, match="valid ISO-8601 timestamp"):
        reader.read_quantity(db, "warehouse-A", as_of="")
    with pytest.raises(ValidationError, match="valid ISO-8601 timestamp"):
        reader.read_quantity(db, "warehouse-A", as_of="not-a-timestamp")
    db.close()



def test_reader_fails_closed_on_legacy_non_integer_quantity_without_fallback():
    db, activity = _database()
    _position(db, activity, quantity=5, observed="2026-10-09T09:00:00Z")
    # Simulate a row written before the integer-quantity guards were installed.
    db.execute("DROP TRIGGER inventory_position_quantity_integer_on_insert")
    invalid_position = _position(db, activity, quantity=2.5, observed="2026-10-09T11:00:00Z")

    result = InventoryQuantityReader().read_quantity(db, "warehouse-A", as_of=AS_OF)

    assert result.status is Status.INVALID_QUANTITY
    assert result.quantity_minor is None
    assert result.inventory_position_id == invalid_position
    db.close()
