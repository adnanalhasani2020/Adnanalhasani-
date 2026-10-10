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
