"""Foundational hospital operations domain.

This module models administrative workflows only. It does not provide diagnosis,
clinical decision support, prescribing rules, or production-ready patient storage.
Clinical records remain governed by domain_health; billing remains separate.
"""
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from uuid import UUID

from agent_core.shared import ValidationError, new_id


class AppointmentState(str, Enum):
    BOOKED = "booked"
    CONFIRMED = "confirmed"
    ARRIVED = "arrived"
    COMPLETED = "completed"
    CANCELLED = "cancelled"
    NO_SHOW = "no-show"


class AdmissionState(str, Enum):
    ADMITTED = "admitted"
    DISCHARGED = "discharged"
    CANCELLED = "cancelled"


@dataclass
class HospitalPatient:
    """Administrative patient identity; clinical details live elsewhere."""

    person_id: UUID
    medical_record_number: str
    id: UUID = field(default_factory=new_id)
    active: bool = True

    def __post_init__(self) -> None:
        if not isinstance(self.person_id, UUID):
            raise ValidationError("HospitalPatient.person_id must reference Person")
        if not isinstance(self.medical_record_number, str) or not self.medical_record_number.strip():
            raise ValidationError("HospitalPatient.medical_record_number is required")
        self.medical_record_number = self.medical_record_number.strip()

    def deactivate(self) -> None:
        self.active = False


@dataclass
class HospitalPractitioner:
    """Directory entry for a clinician; this does not assert credentials."""

    person_id: UUID
    display_name: str
    specialty: str
    id: UUID = field(default_factory=new_id)
    active: bool = True

    def __post_init__(self) -> None:
        if not isinstance(self.person_id, UUID):
            raise ValidationError("HospitalPractitioner.person_id must reference Person")
        if not isinstance(self.display_name, str) or not self.display_name.strip():
            raise ValidationError("HospitalPractitioner.display_name is required")
        if not isinstance(self.specialty, str) or not self.specialty.strip():
            raise ValidationError("HospitalPractitioner.specialty is required")
        self.display_name = self.display_name.strip()
        self.specialty = self.specialty.strip()

    def deactivate(self) -> None:
        self.active = False


@dataclass
class HospitalAppointment:
    """Scheduled visit. Scheduling does not itself create a clinical encounter."""

    patient_id: UUID
    practitioner_id: UUID
    starts_at: datetime
    id: UUID = field(default_factory=new_id)
    state: AppointmentState = AppointmentState.BOOKED
    reason: str | None = None

    def __post_init__(self) -> None:
        if not isinstance(self.patient_id, UUID):
            raise ValidationError("HospitalAppointment.patient_id must be UUID")
        if not isinstance(self.practitioner_id, UUID):
            raise ValidationError("HospitalAppointment.practitioner_id must be UUID")
        if not isinstance(self.starts_at, datetime):
            raise ValidationError("HospitalAppointment.starts_at must be datetime")
        if self.starts_at.tzinfo is None or self.starts_at.utcoffset() is None:
            raise ValidationError("HospitalAppointment.starts_at must be timezone-aware")
        if self.reason is not None and not isinstance(self.reason, str):
            raise ValidationError("HospitalAppointment.reason must be text or None")

    def confirm(self) -> None:
        self._transition(AppointmentState.BOOKED, AppointmentState.CONFIRMED)

    def mark_arrived(self) -> None:
        self._transition(AppointmentState.CONFIRMED, AppointmentState.ARRIVED)

    def complete(self) -> None:
        self._transition(AppointmentState.ARRIVED, AppointmentState.COMPLETED)

    def cancel(self) -> None:
        if self.state in {AppointmentState.COMPLETED, AppointmentState.CANCELLED, AppointmentState.NO_SHOW}:
            raise ValidationError("Cannot cancel a terminal appointment")
        self.state = AppointmentState.CANCELLED

    def mark_no_show(self) -> None:
        if self.state not in {AppointmentState.CONFIRMED, AppointmentState.BOOKED}:
            raise ValidationError("Only a booked or confirmed appointment can be marked no-show")
        self.state = AppointmentState.NO_SHOW

    def _transition(self, expected: AppointmentState, target: AppointmentState) -> None:
        if self.state != expected:
            raise ValidationError(f"Appointment transition requires {expected.value} state")
        self.state = target


@dataclass
class InpatientAdmission:
    """Administrative bed/admission record; bed allocation is not implemented here."""

    patient_id: UUID
    admitted_at: datetime
    ward: str
    bed_label: str
    id: UUID = field(default_factory=new_id)
    state: AdmissionState = AdmissionState.ADMITTED
    discharged_at: datetime | None = None

    def __post_init__(self) -> None:
        if not isinstance(self.patient_id, UUID):
            raise ValidationError("InpatientAdmission.patient_id must be UUID")
        if not isinstance(self.admitted_at, datetime) or self.admitted_at.tzinfo is None:
            raise ValidationError("InpatientAdmission.admitted_at must be timezone-aware")
        if not isinstance(self.ward, str) or not self.ward.strip():
            raise ValidationError("InpatientAdmission.ward is required")
        if not isinstance(self.bed_label, str) or not self.bed_label.strip():
            raise ValidationError("InpatientAdmission.bed_label is required")
        self.ward = self.ward.strip()
        self.bed_label = self.bed_label.strip()

    def discharge(self, at: datetime) -> None:
        if self.state != AdmissionState.ADMITTED:
            raise ValidationError("Only an admitted patient can be discharged")
        if not isinstance(at, datetime) or at.tzinfo is None:
            raise ValidationError("Discharge timestamp must be timezone-aware")
        if at < self.admitted_at:
            raise ValidationError("Discharge cannot precede admission")
        self.discharged_at = at
        self.state = AdmissionState.DISCHARGED
