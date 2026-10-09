"""Stage 13 — Commerce Core Vertical acceptance tests.

Traceability:
REQ-DATA-0013/0014 -> Product/Offering/Inventory separation.
REQ-FUNC-0015/0016 -> Inventory Position and derived Availability boundaries.
REQ-FUNC-0033/0034/0035 -> Discovery boundary without search/GIS/ranking semantics.
REQ-FUNC-0037/0038 -> Sale lifecycle, Activity context, authorization/finance boundaries.
"""
from datetime import datetime, timezone, timedelta
from dataclasses import FrozenInstanceError
from uuid import uuid4
import pytest

from agent_core.shared import ValidationError
from agent_core.domain_inventory import (
    Product, Service, Offering, InventoryPosition, Availability,
    ProductState, OfferingState, InventoryPositionState, AvailabilityState,
    discovery_result_is_positive,
)
from agent_core.domain_commerce import Sale, SaleState


def test_req_data_0013_product_and_offering_are_distinct_and_offering_is_activity_scoped():
    product = Product("product")
    activity_id = uuid4()
    offering = Offering(product.id, activity_id)
    assert product.id != offering.id
    assert offering.product_id == product.id
    assert offering.activity_id == activity_id
    assert offering.state == OfferingState.DRAFT
    assert not hasattr(product, "activity_id")


def test_req_data_0013_one_product_can_have_offerings_in_multiple_activity_contexts():
    product = Product("shared product")
    first_activity_id, second_activity_id = uuid4(), uuid4()

    first_offering = Offering(product.id, first_activity_id)
    second_offering = Offering(product.id, second_activity_id)

    assert first_offering.product_id == second_offering.product_id == product.id
    assert first_offering.id != second_offering.id
    assert first_offering.activity_id == first_activity_id
    assert second_offering.activity_id == second_activity_id
    assert first_activity_id != second_activity_id


def test_req_data_0014_offering_does_not_imply_inventory():
    product = Product("product")
    offering = Offering(product.id, uuid4())
    assert not hasattr(offering, "inventory_position_id")
    assert not hasattr(offering, "quantity_minor")


def test_req_func_0015_inventory_position_carries_schema_context_without_becoming_availability():
    activity_id, offering_id, product_id = uuid4(), uuid4(), uuid4()
    observed = datetime.now(timezone.utc)
    position = InventoryPosition(
        offering_id=offering_id,
        activity_id=activity_id,
        product_id=product_id,
        location_ref="site-A",
        scope_key="site-A:offering",
        quantity_minor=7,
        observed_at=observed,
    )
    position.make_effective()
    assert position.state == InventoryPositionState.EFFECTIVE
    assert position.quantity_minor == 7
    assert position.location_ref == "site-A"
    assert position.observed_at == observed
    assert not hasattr(position, "availability_id")


def test_req_func_0016_availability_is_derived_temporal_read_result():
    availability = Availability(
        offering_id=uuid4(),
        state=AvailabilityState.COMPUTED,
        valid_at=datetime.now(timezone.utc),
        freshness_at=datetime.now(timezone.utc),
        location_ref="site-A",
    )
    assert availability.state == AvailabilityState.COMPUTED
    assert availability.valid_at is not None
    assert availability.location_ref == "site-A"
    with pytest.raises(FrozenInstanceError):
        availability.state = AvailabilityState.STALE


def test_req_func_0033_discovery_boundary_returns_existing_offering_context_only():
    product = Product("product")
    offering = Offering(product.id, uuid4())
    availability = Availability(offering.id, AvailabilityState.COMPUTED, datetime.now(timezone.utc))
    assert discovery_result_is_positive(availability) is True
    assert offering.activity_id is not None


def test_req_func_0034_proximity_is_not_availability_and_no_proximity_threshold_exists():
    availability = Availability(uuid4(), AvailabilityState.COMPUTED, datetime.now(timezone.utc), location_ref="site-A")
    assert discovery_result_is_positive(availability) is True
    assert not hasattr(availability, "proximity")
    assert not hasattr(availability, "distance")
    assert not hasattr(availability, "proximity_threshold")


def test_req_func_0035_time_changes_availability_state_without_inventing_freshness_threshold():
    offering_id = uuid4()
    now = datetime.now(timezone.utc)
    current = Availability(offering_id, AvailabilityState.COMPUTED, now, freshness_at=now)
    stale = Availability(offering_id, AvailabilityState.STALE, now-timedelta(hours=1), freshness_at=now-timedelta(hours=1))
    assert current.state == AvailabilityState.COMPUTED
    assert stale.state == AvailabilityState.STALE
    assert not hasattr(current, "freshness_threshold")


def test_req_func_0037_sale_lifecycle_and_return_history_are_explicit():
    sale = Sale(uuid4(), uuid4())
    sale.confirm()
    sale.complete()
    sale.return_sale()
    assert sale.state == SaleState.RETURNED
    assert sale.history == (
        SaleState.INITIATED,
        SaleState.CONFIRMED,
        SaleState.COMPLETED,
        SaleState.RETURNED,
    )
    cancelled = Sale(uuid4(), uuid4())
    cancelled.cancel()
    assert cancelled.history == (SaleState.INITIATED, SaleState.CANCELLED)
    with pytest.raises(ValidationError):
        Sale(uuid4(), uuid4()).complete()


def test_req_func_0038_sale_preserves_activity_and_does_not_cross_authorization_or_finance_boundaries():
    activity_id = uuid4()
    sale = Sale(uuid4(), activity_id)
    assert sale.activity_id == activity_id
    assert not hasattr(sale, "authorization_grant_id")
    assert not hasattr(sale, "role_assignment_id")
    assert not hasattr(sale, "financial_transaction_id")
    assert not hasattr(sale, "ledger_entry_id")

def test_retiring_product_preserves_existing_offering_and_sale_history():
    product = Product("retired product")
    offering = Offering(product.id, uuid4())
    sale = Sale(offering.id, offering.activity_id)
    sale.confirm()
    prior_sale_history = sale.history

    product.retire()

    assert product.state == ProductState.RETIRED
    assert offering.product_id == product.id
    assert offering.state == OfferingState.DRAFT
    assert sale.offering_id == offering.id
    assert sale.history == prior_sale_history == (SaleState.INITIATED, SaleState.CONFIRMED)
    assert sale.state == SaleState.CONFIRMED


@pytest.mark.parametrize(
    "history,state",
    [
        ((SaleState.CONFIRMED,), SaleState.CONFIRMED),
        ((SaleState.INITIATED, SaleState.COMPLETED), SaleState.COMPLETED),
        ((SaleState.INITIATED, SaleState.CANCELLED, SaleState.CONFIRMED), SaleState.CONFIRMED),
        ((SaleState.INITIATED, SaleState.CONFIRMED, SaleState.COMPLETED, SaleState.RETURNED, SaleState.COMPLETED), SaleState.COMPLETED),
        ((SaleState.INITIATED, SaleState.CONFIRMED), SaleState.COMPLETED),
    ],
)
def test_req_func_0037_sale_rejects_invalid_or_inconsistent_history(history, state):
    with pytest.raises(ValidationError):
        Sale(uuid4(), uuid4(), state=state, history=history)


def test_req_func_0037_sale_accepts_valid_history_for_each_terminal_path():
    cancelled = Sale(
        uuid4(), uuid4(),
        state=SaleState.CANCELLED,
        history=(SaleState.INITIATED, SaleState.CANCELLED),
    )
    returned = Sale(
        uuid4(), uuid4(),
        state=SaleState.RETURNED,
        history=(
            SaleState.INITIATED,
            SaleState.CONFIRMED,
            SaleState.COMPLETED,
            SaleState.RETURNED,
        ),
    )
    assert cancelled.state is SaleState.CANCELLED
    assert returned.state is SaleState.RETURNED
