from datetime import datetime, timezone
from uuid import uuid4
from zoneinfo import ZoneInfo

import pytest

from agent_core.domain_inventory import (
    Availability,
    AvailabilityState,
    InventoryPosition,
)
from agent_core.shared import ValidationError


def test_inventory_position_rejects_empty_effective_window():
    instant = datetime(2026, 10, 14, 12, tzinfo=timezone.utc)

    with pytest.raises(ValidationError, match="cannot precede or equal"):
        InventoryPosition(
            offering_id=None,
            activity_id=uuid4(),
            scope_key="warehouse-a",
            effective_from=instant,
            effective_to=instant,
        )


def test_availability_preserves_legacy_naive_timestamp_compatibility():
    valid_at = datetime(2026, 10, 14, 12)
    freshness_at = datetime(2026, 10, 14, 12, 30)
    result = Availability(
        offering_id=uuid4(),
        state=AvailabilityState.COMPUTED,
        valid_at=valid_at,
        freshness_at=freshness_at,
    )

    assert result.valid_at == valid_at
    assert result.freshness_at == freshness_at


def test_availability_rejects_nonexistent_local_valid_at():
    # 02:30 does not exist in New York on the 2026 spring-forward date.
    nonexistent = datetime(2026, 3, 8, 2, 30, tzinfo=ZoneInfo("America/New_York"))

    with pytest.raises(ValidationError, match="valid local time"):
        Availability(
            offering_id=uuid4(),
            state=AvailabilityState.COMPUTED,
            valid_at=nonexistent,
        )


def test_availability_accepts_aware_utc_timestamps():
    instant = datetime(2026, 10, 14, 12, tzinfo=timezone.utc)
    result = Availability(
        offering_id=uuid4(),
        state=AvailabilityState.COMPUTED,
        valid_at=instant,
        freshness_at=instant,
    )

    assert result.valid_at == instant
    assert result.freshness_at == instant
