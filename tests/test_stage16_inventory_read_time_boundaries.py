"""Boundary tests for effective inventory quantity reads.

These tests exercise the existing temporal read contract only. They do not
define a Sale gate or mutate/reserve/deduct inventory.
"""
import sqlite3

from agent_core.inventory_read import InventoryQuantityReader, InventoryQuantityStatus


def _db(*, observed_at, effective_from, effective_to, quantity=7):
    db = sqlite3.connect(":memory:")
    db.execute(
        """
        CREATE TABLE inventory_positions (
            inventory_position_id TEXT PRIMARY KEY,
            scope_key TEXT NOT NULL,
            state TEXT NOT NULL,
            offering_id TEXT,
            activity_id TEXT,
            quantity_minor INTEGER,
            observed_at TEXT,
            effective_from TEXT,
            effective_to TEXT
        )
        """
    )
    db.execute(
        """
        INSERT INTO inventory_positions (
            inventory_position_id, scope_key, state, offering_id, activity_id,
            quantity_minor, observed_at, effective_from, effective_to
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        (
            "position-1", "store-A", "effective", "offering-A", "activity-A",
            quantity, observed_at, effective_from, effective_to,
        ),
    )
    return db


def _read(db, as_of):
    return InventoryQuantityReader().read_quantity(
        db, "store-A", as_of=as_of,
        offering_id="offering-A", activity_id="activity-A",
    )


def test_effective_to_is_exclusive_at_exact_boundary():
    db = _db(
        observed_at="2026-10-09T11:00:00Z",
        effective_from="2026-10-09T10:00:00Z",
        effective_to="2026-10-09T12:00:00Z",
    )
    try:
        before_end = _read(db, "2026-10-09T11:59:59Z")
        at_end = _read(db, "2026-10-09T12:00:00Z")

        assert before_end.status is InventoryQuantityStatus.KNOWN
        assert before_end.quantity_minor == 7
        assert at_end.status is InventoryQuantityStatus.NO_RECORD
        assert at_end.quantity_minor is None
    finally:
        db.close()


def test_effective_window_compares_offset_timestamps_as_instants():
    # The row's timestamps use +02:00, but describe the same UTC instants:
    # observed 11:00Z, starts 10:00Z, and ends 12:00Z.
    db = _db(
        observed_at="2026-10-09T13:00:00+02:00",
        effective_from="2026-10-09T12:00:00+02:00",
        effective_to="2026-10-09T14:00:00+02:00",
        quantity=19,
    )
    try:
        result = _read(db, "2026-10-09T11:30:00Z")

        assert result.status is InventoryQuantityStatus.KNOWN
        assert result.quantity_minor == 19
        assert result.observed_at.isoformat() == "2026-10-09T11:00:00+00:00"
    finally:
        db.close()


def test_effective_from_is_inclusive_at_exact_boundary():
    db = _db(
        observed_at="2026-10-09T10:00:00Z",
        effective_from="2026-10-09T10:00:00Z",
        effective_to="2026-10-09T12:00:00Z",
    )
    try:
        at_start = _read(db, "2026-10-09T10:00:00Z")

        assert at_start.status is InventoryQuantityStatus.KNOWN
        assert at_start.quantity_minor == 7
    finally:
        db.close()


def test_inventory_observation_is_not_visible_before_observed_at():
    db = _db(
        observed_at="2026-10-09T11:00:00Z",
        effective_from="2026-10-09T10:00:00Z",
        effective_to="2026-10-09T12:00:00Z",
    )
    try:
        before_observation = _read(db, "2026-10-09T10:59:59Z")

        assert before_observation.status is InventoryQuantityStatus.NO_RECORD
        assert before_observation.quantity_minor is None
    finally:
        db.close()
