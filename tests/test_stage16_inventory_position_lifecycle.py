from uuid import uuid4

import pytest

from agent_core.domain_inventory import InventoryPosition, InventoryPositionState
from agent_core.shared import ValidationError


def _position():
    return InventoryPosition(
        offering_id=uuid4(),
        activity_id=uuid4(),
        scope_key="warehouse-A",
    )


def test_inventory_position_follows_observed_effective_closed_lifecycle():
    position = _position()
    assert position.state is InventoryPositionState.OBSERVED

    position.make_effective()
    assert position.state is InventoryPositionState.EFFECTIVE

    position.close()
    assert position.state is InventoryPositionState.CLOSED


@pytest.mark.parametrize(
    ("initial_state", "operation", "message"),
    [
        (InventoryPositionState.EFFECTIVE, "make_effective", "Only an observed"),
        (InventoryPositionState.CLOSED, "make_effective", "Only an observed"),
        (InventoryPositionState.OBSERVED, "close", "Only an effective"),
        (InventoryPositionState.CLOSED, "close", "Only an effective"),
    ],
)
def test_invalid_inventory_position_transition_preserves_state(initial_state, operation, message):
    position = _position()
    position.state = initial_state

    with pytest.raises(ValidationError, match=message):
        getattr(position, operation)()

    assert position.state is initial_state


def test_unavailable_and_retire_aliases_enforce_close_transition():
    observed = _position()
    with pytest.raises(ValidationError, match="Only an effective"):
        observed.make_unavailable()
    assert observed.state is InventoryPositionState.OBSERVED

    effective = _position()
    effective.make_effective()
    effective.retire()
    assert effective.state is InventoryPositionState.CLOSED

    with pytest.raises(ValidationError, match="Only an effective"):
        effective.retire()
    assert effective.state is InventoryPositionState.CLOSED
