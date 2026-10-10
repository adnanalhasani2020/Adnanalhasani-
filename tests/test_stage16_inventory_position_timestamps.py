from datetime import datetime, timezone
from uuid import uuid4

import pytest

from agent_core.domain_inventory import InventoryPosition
from agent_core.shared import ValidationError


AWARE = datetime(2026, 10, 9, 12, 0, tzinfo=timezone.utc)
NAIVE = datetime(2026, 10, 9, 12, 0)


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("observed_at", NAIVE),
        ("effective_from", NAIVE),
        ("effective_to", NAIVE),
    ],
)
def test_inventory_position_rejects_naive_timestamps(field, value):
    with pytest.raises(ValidationError, match=f"{field} must be timezone-aware"):
        InventoryPosition(
            offering_id=uuid4(),
            activity_id=uuid4(),
            scope_key="warehouse-A",
            **{field: value},
        )


def test_inventory_position_accepts_aware_timestamps_with_different_offsets():
    position = InventoryPosition(
        offering_id=uuid4(),
        activity_id=uuid4(),
        scope_key="warehouse-A",
        observed_at=AWARE,
        effective_from=datetime.fromisoformat("2026-10-09T13:00:00+01:00"),
        effective_to=datetime.fromisoformat("2026-10-09T15:00:00+01:00"),
    )
    assert position.observed_at == AWARE
    assert position.effective_from.utcoffset().total_seconds() == 3600
    assert position.effective_to > position.effective_from


def test_inventory_position_rejects_reversed_aware_effective_period():
    with pytest.raises(ValidationError, match="cannot precede"):
        InventoryPosition(
            offering_id=uuid4(),
            activity_id=uuid4(),
            scope_key="warehouse-A",
            effective_from=datetime.fromisoformat("2026-10-09T14:00:00+02:00"),
            effective_to=datetime.fromisoformat("2026-10-09T11:00:00Z"),
        )


def test_inventory_position_orders_repeated_local_hour_by_instant():
    from zoneinfo import ZoneInfo

    eastern = ZoneInfo("America/New_York")
    # During fall-back, 01:30 EDT is 05:30 UTC and 01:15 EST is 06:15 UTC.
    # Wall-clock ordering says the end is earlier; instant ordering says it is later.
    position = InventoryPosition(
        offering_id=uuid4(),
        activity_id=uuid4(),
        scope_key="warehouse-A",
        effective_from=datetime(2026, 11, 1, 1, 30, tzinfo=eastern, fold=0),
        effective_to=datetime(2026, 11, 1, 1, 15, tzinfo=eastern, fold=1),
    )
    assert position.effective_to.astimezone(timezone.utc) > position.effective_from.astimezone(timezone.utc)


def test_inventory_position_rejects_reversed_instants_in_repeated_local_hour():
    from zoneinfo import ZoneInfo

    eastern = ZoneInfo("America/New_York")
    # 01:30 EST (06:30 UTC) is after 01:45 EDT (05:45 UTC), despite wall-clock order.
    with pytest.raises(ValidationError, match="cannot precede"):
        InventoryPosition(
            offering_id=uuid4(),
            activity_id=uuid4(),
            scope_key="warehouse-A",
            effective_from=datetime(2026, 11, 1, 1, 30, tzinfo=eastern, fold=1),
            effective_to=datetime(2026, 11, 1, 1, 45, tzinfo=eastern, fold=0),
        )
