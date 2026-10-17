from datetime import datetime, timezone
from decimal import Decimal
from uuid import uuid4

import pytest

from agent_core.shared import ValidationError
from agent_core.hospital_clinical_services import (
    DispenseState,
    LabOrderState,
    LabResultState,
    LaboratoryOrder,
    LaboratoryResult,
    MedicationDispense,
    MedicationStock,
)


NOW = datetime(2026, 10, 17, 12, 0, tzinfo=timezone.utc)


def test_lab_order_requires_context_and_follows_ordered_lifecycle():
    order = LaboratoryOrder(uuid4(), uuid4(), uuid4(), " CBC ", NOW)
    assert order.test_code == "CBC"
    order.collect()
    order.begin_processing()
    order.complete()
    assert order.state == LabOrderState.COMPLETED
    with pytest.raises(ValidationError):
        order.cancel()
    with pytest.raises(ValidationError):
        LaboratoryOrder(uuid4(), uuid4(), uuid4(), " ", NOW)


def test_lab_order_rejects_naive_time_and_invalid_transition():
    with pytest.raises(ValidationError):
        LaboratoryOrder(uuid4(), uuid4(), uuid4(), "CBC", datetime(2026, 10, 17))
    order = LaboratoryOrder(uuid4(), uuid4(), uuid4(), "CBC", NOW)
    with pytest.raises(ValidationError):
        order.complete()


def test_lab_result_requires_verification_and_amendment_is_explicit():
    result = LaboratoryResult(uuid4(), "4.2", unit="mmol/L")
    with pytest.raises(ValidationError):
        result.amend("4.3")
    verifier = uuid4()
    result.verify(verifier, NOW)
    assert result.state == LabResultState.VERIFIED
    assert result.verified_by_person_id == verifier
    result.amend("4.3")
    assert result.value == "4.3"
    assert result.state == LabResultState.AMENDED
    with pytest.raises(ValidationError):
        result.verify(verifier, NOW)


def test_medication_stock_rejects_invalid_quantities():
    stock = MedicationStock("MED-1", "Example medicine", Decimal("10"), "tablet")
    assert stock.quantity == Decimal("10")
    stock.deactivate()
    assert stock.active is False
    with pytest.raises(ValidationError):
        MedicationStock("MED-2", "Example", Decimal("-1"), "tablet")
    with pytest.raises(ValidationError):
        MedicationStock("MED-2", "Example", Decimal("NaN"), "tablet")


def test_dispense_requires_positive_quantity_and_has_reversal_state():
    dispense = MedicationDispense(
        uuid4(), uuid4(), Decimal("2"), uuid4(), NOW
    )
    dispense.reverse()
    assert dispense.state == DispenseState.REVERSED
    with pytest.raises(ValidationError):
        dispense.reverse()
    with pytest.raises(ValidationError):
        MedicationDispense(uuid4(), uuid4(), Decimal("0"), uuid4(), NOW)
