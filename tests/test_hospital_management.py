from datetime import datetime, timezone, timedelta
from uuid import uuid4

import pytest

from agent_core.shared import ValidationError
from agent_core.hospital_management import (
    AdmissionState,
    AppointmentState,
    HospitalAppointment,
    HospitalPatient,
    HospitalPractitioner,
    InpatientAdmission,
)


def test_hospital_patient_requires_person_and_record_number():
    person_id = uuid4()
    patient = HospitalPatient(person_id, " MR-001 ")
    assert patient.person_id == person_id
    assert patient.medical_record_number == "MR-001"
    patient.deactivate()
    assert patient.active is False

    with pytest.raises(ValidationError):
        HospitalPatient("not-a-person-id", "MR-002")
    with pytest.raises(ValidationError):
        HospitalPatient(person_id, "  ")


def test_practitioner_requires_directory_fields_and_can_be_deactivated():
    practitioner = HospitalPractitioner(uuid4(), " Dr Example ", " Pediatrics ")
    assert practitioner.display_name == "Dr Example"
    assert practitioner.specialty == "Pediatrics"
    practitioner.deactivate()
    assert practitioner.active is False

    with pytest.raises(ValidationError):
        HospitalPractitioner(uuid4(), " ", "General Medicine")


def test_appointment_lifecycle_is_ordered():
    appointment = HospitalAppointment(
        uuid4(), uuid4(), datetime(2026, 10, 20, 9, 0, tzinfo=timezone.utc)
    )
    appointment.confirm()
    appointment.mark_arrived()
    appointment.complete()
    assert appointment.state == AppointmentState.COMPLETED

    with pytest.raises(ValidationError):
        appointment.cancel()


def test_appointment_rejects_naive_time_and_invalid_transitions():
    with pytest.raises(ValidationError):
        HospitalAppointment(uuid4(), uuid4(), datetime(2026, 10, 20, 9, 0))

    appointment = HospitalAppointment(
        uuid4(), uuid4(), datetime(2026, 10, 20, 9, 0, tzinfo=timezone.utc)
    )
    with pytest.raises(ValidationError):
        appointment.mark_arrived()
    appointment.mark_no_show()
    assert appointment.state == AppointmentState.NO_SHOW
    with pytest.raises(ValidationError):
        appointment.confirm()


def test_admission_discharge_requires_valid_order_and_timestamp():
    admitted_at = datetime(2026, 10, 20, 10, 0, tzinfo=timezone.utc)
    admission = InpatientAdmission(uuid4(), admitted_at, "Ward A", "A-12")
    discharged_at = admitted_at + timedelta(hours=5)
    admission.discharge(discharged_at)
    assert admission.state == AdmissionState.DISCHARGED
    assert admission.discharged_at == discharged_at

    with pytest.raises(ValidationError):
        admission.discharge(discharged_at)

    still_admitted = InpatientAdmission(uuid4(), admitted_at, "Ward A", "A-13")
    with pytest.raises(ValidationError):
        still_admitted.discharge(admitted_at - timedelta(minutes=1))
    with pytest.raises(ValidationError):
        still_admitted.discharge(datetime(2026, 10, 20, 11, 0))
