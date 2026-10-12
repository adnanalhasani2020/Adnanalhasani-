"""Validation of Offering's half-open effective period."""
from datetime import datetime
from uuid import uuid4

import pytest

from agent_core.domain_inventory import Offering
from agent_core.shared import ValidationError


def test_offering_rejects_empty_effective_window_by_instant():
    # Different wall-clock representations can denote the same instant.
    starts = datetime.fromisoformat("2026-10-12T12:00:00+00:00")
    ends = datetime.fromisoformat("2026-10-12T14:00:00+02:00")

    with pytest.raises(ValidationError, match="effective_to must be later"):
        Offering(
            product_id=uuid4(),
            activity_id=uuid4(),
            effective_from=starts,
            effective_to=ends,
        )


def test_offering_accepts_nonempty_effective_window():
    starts = datetime.fromisoformat("2026-10-12T12:00:00+00:00")
    ends = datetime.fromisoformat("2026-10-12T12:00:01+00:00")

    offering = Offering(
        product_id=uuid4(),
        activity_id=uuid4(),
        effective_from=starts,
        effective_to=ends,
    )

    assert offering.effective_from == starts
    assert offering.effective_to == ends
