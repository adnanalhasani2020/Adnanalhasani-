"""Integration tests for persisted Health Encounter application lifecycle."""
from datetime import datetime, timezone
import uuid

import pytest

from agent_core.application import EncounterApplication
from agent_core.domain_health import EncounterState
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

NOW = datetime(2026, 10, 10, 12, 0, tzinfo=timezone.utc)
STAMP = "2026-10-10T12:00:00Z"


def uid():
    return str(uuid.uuid4())


def fixture(db, *, context_state="active", service_state="active"):
    person, context, service = uid(), uid(), uid()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (person, "active", STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO patient_contexts(patient_context_id,person_id,context_ref,state,created_at,updated_at) "
        "VALUES(?,?,?,?,?,?)",
        (context, person, "health-context", context_state, STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO services(service_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (service, service_state, STAMP, STAMP),
    )
    return context, service


def test_encounter_lifecycle_persists_and_round_trips_after_reopen(tmp_path):
    path = tmp_path / "encounter.sqlite"
    db = connect_database(path)
    context, service = fixture(db)
    app = EncounterApplication()

    encounter = app.create_encounter(
        db, context, "consultation", service_id=service, now=NOW
    )
    assert encounter.state is EncounterState.PLANNED
    assert db.execute(
        "SELECT patient_context_id,encounter_type,service_id,state,version_no "
        "FROM encounters WHERE encounter_id=?", (str(encounter.id),)
    ).fetchone() == (context, "consultation", service, "planned", 1)

    started = app.transition_encounter(db, encounter.id, "start", now=NOW)
    assert started.state is EncounterState.IN_PROGRESS
    completed = app.transition_encounter(db, encounter.id, "complete", now=NOW)
    assert completed.state is EncounterState.COMPLETED
    row = db.execute(
        "SELECT state,ended_at,version_no FROM encounters WHERE encounter_id=?",
        (str(encounter.id),),
    ).fetchone()
    assert row == ("completed", STAMP, 3)
    db.close()

    db = connect_database(path)
    restored = app.get_encounter(db, encounter.id)
    assert restored.id == encounter.id
    assert restored.patient_context_id == encounter.patient_context_id
    assert restored.service_id == encounter.service_id
    assert restored.state is EncounterState.COMPLETED
    db.close()


@pytest.mark.parametrize("context_state", ["closed", "restricted"])
def test_encounter_rejects_non_active_patient_context_without_writing(context_state):
    db = connect_database()
    context, _service = fixture(db, context_state=context_state)
    with pytest.raises(ValidationError, match="active PatientContext"):
        EncounterApplication().create_encounter(db, context, "consultation", now=NOW)
    assert db.execute("SELECT count(*) FROM encounters").fetchone() == (0,)
    db.close()


def test_encounter_rejects_missing_context_and_invalid_service_without_writing():
    db = connect_database()
    _context, inactive_service = fixture(db, service_state="retired")
    db.commit()
    app = EncounterApplication()
    with pytest.raises(ValidationError, match="existing PatientContext"):
        app.create_encounter(db, uid(), "consultation", now=NOW)
    context = db.execute("SELECT patient_context_id FROM patient_contexts").fetchone()[0]
    with pytest.raises(ValidationError, match="active Service"):
        app.create_encounter(
            db, context, "consultation", service_id=inactive_service, now=NOW
        )
    with pytest.raises(ValidationError, match="Encounter type"):
        app.create_encounter(db, context, "  ", now=NOW)
    assert db.execute("SELECT count(*) FROM encounters").fetchone() == (0,)
    db.close()


def test_encounter_rejects_invalid_transitions_without_mutating_persisted_state():
    db = connect_database()
    context, _service = fixture(db)
    app = EncounterApplication()
    encounter = app.create_encounter(db, context, "consultation", now=NOW)
    with pytest.raises(ValidationError, match="Invalid Encounter transition"):
        app.transition_encounter(db, encounter.id, "complete", now=NOW)
    app.transition_encounter(db, encounter.id, "cancel", now=NOW)
    with pytest.raises(ValidationError, match="Invalid Encounter transition"):
        app.transition_encounter(db, encounter.id, "start", now=NOW)
    assert db.execute(
        "SELECT state,version_no FROM encounters WHERE encounter_id=?", (str(encounter.id),)
    ).fetchone() == ("cancelled", 2)
    db.close()
