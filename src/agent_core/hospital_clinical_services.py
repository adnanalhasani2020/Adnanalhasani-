"""Foundational laboratory and pharmacy workflow primitives.

These domain objects do not replace clinical authorization, patient identity,
persistent inventory transactions, or local regulatory requirements.
"""
from dataclasses import dataclass, field
from datetime import datetime
from decimal import Decimal
from enum import Enum
from uuid import UUID

from agent_core.shared import ValidationError, new_id


class LabOrderState(str, Enum):
    REQUESTED = "requested"
    COLLECTED = "collected"
    IN_PROCESS = "in-process"
    COMPLETED = "completed"
    CANCELLED = "cancelled"


class LabResultState(str, Enum):
    DRAFT = "draft"
    VERIFIED = "verified"
    AMENDED = "amended"


class DispenseState(str, Enum):
    DISPENSED = "dispensed"
    REVERSED = "reversed"


@dataclass
class LaboratoryOrder:
    patient_id: UUID
    encounter_id: UUID
    requested_by_person_id: UUID
    test_code: str
    requested_at: datetime
    id: UUID = field(default_factory=new_id)
    state: LabOrderState = LabOrderState.REQUESTED

    def __post_init__(self) -> None:
        for name in ("patient_id", "encounter_id", "requested_by_person_id"):
            if not isinstance(getattr(self, name), UUID):
                raise ValidationError(f"LaboratoryOrder.{name} must be UUID")
        if not isinstance(self.test_code, str) or not self.test_code.strip():
            raise ValidationError("LaboratoryOrder.test_code is required")
        if not isinstance(self.requested_at, datetime) or self.requested_at.tzinfo is None:
            raise ValidationError("LaboratoryOrder.requested_at must be timezone-aware")
        self.test_code = self.test_code.strip()

    def collect(self) -> None:
        self._transition(LabOrderState.REQUESTED, LabOrderState.COLLECTED)

    def begin_processing(self) -> None:
        self._transition(LabOrderState.COLLECTED, LabOrderState.IN_PROCESS)

    def complete(self) -> None:
        self._transition(LabOrderState.IN_PROCESS, LabOrderState.COMPLETED)

    def cancel(self) -> None:
        if self.state in {LabOrderState.COMPLETED, LabOrderState.CANCELLED}:
            raise ValidationError("Cannot cancel a terminal laboratory order")
        self.state = LabOrderState.CANCELLED

    def _transition(self, expected: LabOrderState, target: LabOrderState) -> None:
        if self.state != expected:
            raise ValidationError(f"Laboratory order transition requires {expected.value} state")
        self.state = target


@dataclass
class LaboratoryResult:
    order_id: UUID
    value: str
    unit: str | None = None
    reference_range: str | None = None
    id: UUID = field(default_factory=new_id)
    state: LabResultState = LabResultState.DRAFT
    verified_by_person_id: UUID | None = None
    verified_at: datetime | None = None

    def __post_init__(self) -> None:
        if not isinstance(self.order_id, UUID):
            raise ValidationError("LaboratoryResult.order_id must be UUID")
        if not isinstance(self.value, str) or not self.value.strip():
            raise ValidationError("LaboratoryResult.value is required")
        for name in ("unit", "reference_range"):
            item = getattr(self, name)
            if item is not None and not isinstance(item, str):
                raise ValidationError(f"LaboratoryResult.{name} must be text or None")

    def verify(self, verifier_person_id: UUID, at: datetime) -> None:
        if self.state != LabResultState.DRAFT:
            raise ValidationError("Only a draft result can be verified")
        if not isinstance(verifier_person_id, UUID):
            raise ValidationError("Verifier must reference Person")
        if not isinstance(at, datetime) or at.tzinfo is None:
            raise ValidationError("Verification timestamp must be timezone-aware")
        self.verified_by_person_id = verifier_person_id
        self.verified_at = at
        self.state = LabResultState.VERIFIED

    def amend(self, value: str) -> None:
        if self.state != LabResultState.VERIFIED:
            raise ValidationError("Only a verified result can be amended")
        if not isinstance(value, str) or not value.strip():
            raise ValidationError("Amended result value is required")
        self.value = value.strip()
        self.state = LabResultState.AMENDED


@dataclass
class MedicationStock:
    """In-memory stock primitive; not a durable or concurrent inventory ledger."""

    medication_code: str
    display_name: str
    quantity: Decimal
    unit: str
    id: UUID = field(default_factory=new_id)
    active: bool = True

    def __post_init__(self) -> None:
        for name in ("medication_code", "display_name", "unit"):
            value = getattr(self, name)
            if not isinstance(value, str) or not value.strip():
                raise ValidationError(f"MedicationStock.{name} is required")
            setattr(self, name, value.strip())
        if not isinstance(self.quantity, Decimal) or not self.quantity.is_finite() or self.quantity < 0:
            raise ValidationError("MedicationStock.quantity must be a finite non-negative Decimal")

    def deactivate(self) -> None:
        self.active = False


@dataclass
class MedicationDispense:
    prescription_id: UUID
    medication_stock_id: UUID
    quantity: Decimal
    dispensed_by_person_id: UUID
    dispensed_at: datetime
    id: UUID = field(default_factory=new_id)
    state: DispenseState = DispenseState.DISPENSED

    def __post_init__(self) -> None:
        for name in ("prescription_id", "medication_stock_id", "dispensed_by_person_id"):
            if not isinstance(getattr(self, name), UUID):
                raise ValidationError(f"MedicationDispense.{name} must be UUID")
        if not isinstance(self.quantity, Decimal) or not self.quantity.is_finite() or self.quantity <= 0:
            raise ValidationError("MedicationDispense.quantity must be a finite positive Decimal")
        if not isinstance(self.dispensed_at, datetime) or self.dispensed_at.tzinfo is None:
            raise ValidationError("MedicationDispense.dispensed_at must be timezone-aware")

    def reverse(self) -> None:
        if self.state == DispenseState.REVERSED:
            raise ValidationError("Dispense is already reversed")
        self.state = DispenseState.REVERSED
