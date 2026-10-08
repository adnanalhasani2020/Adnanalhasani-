"""Stage 15 characterization tests for the existing Availability/Discovery boundary.

These tests characterize current implementation behavior only. They do not
establish freshness, proximity, ranking, or other policy semantics.
"""
from dataclasses import FrozenInstanceError
from datetime import datetime, timezone
from uuid import uuid4

import pytest

from agent_core.domain_inventory import (
    Availability,
    AvailabilityState,
    InventoryPosition,
    InventoryPositionState,
    discovery_result_is_positive,
)
from agent_core.shared import ValidationError


def test_discovery_accepts_only_computed_availability_as_positive():
    now = datetime.now(timezone.utc)
    offering_id = uuid4()

    computed = Availability(offering_id, AvailabilityState.COMPUTED, now)
    stale = Availability(offering_id, AvailabilityState.STALE, now)
    invalid = Availability(offering_id, AvailabilityState.INVALID, now)

    assert discovery_result_is_positive(computed) is True
    assert discovery_result_is_positive(stale) is False
    assert discovery_result_is_positive(invalid) is False


def test_discovery_rejects_non_availability_input():
    with pytest.raises(ValidationError, match="Discovery requires Availability"):
        discovery_result_is_positive(object())


def test_discovery_boundary_does_not_mutate_inventory_position():
    position = InventoryPosition(
        offering_id=uuid4(),
        activity_id=uuid4(),
        product_id=uuid4(),
        scope_key="stage15-test",
        quantity_minor=3,
    )
    before = (
        position.id,
        position.state,
        position.quantity_minor,
        position.observed_at,
        position.effective_from,
        position.effective_to,
    )
    availability = Availability(
        offering_id=position.offering_id,
        state=AvailabilityState.COMPUTED,
        valid_at=datetime.now(timezone.utc),
    )

    assert discovery_result_is_positive(availability) is True
    assert (
        position.id,
        position.state,
        position.quantity_minor,
        position.observed_at,
        position.effective_from,
        position.effective_to,
    ) == before
    assert position.state is InventoryPositionState.OBSERVED


def test_availability_is_immutable_derived_read_data():
    availability = Availability(
        offering_id=uuid4(),
        state=AvailabilityState.COMPUTED,
        valid_at=datetime.now(timezone.utc),
    )

    with pytest.raises(FrozenInstanceError):
        availability.state = AvailabilityState.STALE
