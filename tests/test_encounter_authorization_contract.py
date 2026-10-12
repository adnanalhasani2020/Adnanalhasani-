"""Tests for the Encounter authorization contract and fail-closed behavior."""
from datetime import datetime, timezone
import uuid

import pytest

from agent_core.application import EncounterApplication, EncounterAuthorizationError
from agent_core.domain_health import EncounterState
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

NOW = datetime(2026, 10, 10, 12, 0, tzinfo=timezone.utc)
STAMP = "2026-10-10T12:00:00Z"
PURPOSE = "care_delivery"


def uid():
    return str(uuid.uuid4())


def setup_context(db):
    actor, patient, context, service = uid(), uid(), uid(), uid()
    for person in (actor, patient):
        db.execute(
            "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
            (person, "active", STAMP, STAMP),
        )
    db.execute(
        "INSERT INTO patient_contexts(patient_context_id,person_id,context_ref,state,created_at,updated_at) "
        "VALUES(?,?,?,?,?,?)",
        (context, patient, "health-context", "active", STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO services(service_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (service, "active", STAMP, STAMP),
    )
    return actor, context, service


def grant(db, actor, action, scope, context, *, purpose=PURPOSE, state="active",
          start=STAMP, end=None, delegation_id=None, agent_id=None):
    grant_id = uid()
    db.execute(
        "INSERT INTO authorization_grants(authorization_grant_id,subject_person_id,agent_id,action_code,"
        "scope_ref,context_ref,delegation_id,state,effective_from,effective_to,created_at,updated_at,"
        "provenance_id,version_no,purpose) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,NULL,1,?)",
        (grant_id, actor, agent_id, action, scope, context, delegation_id, state, start, end,
         STAMP, STAMP, purpose),
    )
    return grant_id


def test_create_read_and_transition_require_separate_exact_grants_and_audit():
    db = connect_database()
    actor, context, service = setup_context(db)
    app = EncounterApplication()
    context_ref = f"patient_context:{context}"
    grant(db, actor, "health.encounter.create", context_ref, context_ref)
    encounter = app.create_encounter(
        db, context, "consultation", actor_person_id=actor, purpose=PURPOSE,
        service_id=service, now=NOW,
    )
    assert encounter.state is EncounterState.PLANNED

    with pytest.raises(EncounterAuthorizationError, match="No active Authorization Grant"):
        app.get_encounter(db, encounter.id, actor_person_id=actor, purpose=PURPOSE, now=NOW)

    grant(db, actor, "health.encounter.read", f"encounter:{encounter.id}", context_ref)
    assert app.get_encounter(
        db, encounter.id, actor_person_id=actor, purpose=PURPOSE, now=NOW
    ).id == encounter.id

    grant(db, actor, "health.encounter.start", f"encounter:{encounter.id}", context_ref)
    assert app.transition_encounter(
        db, encounter.id, "start", actor_person_id=actor, purpose=PURPOSE, now=NOW
    ).state is EncounterState.IN_PROGRESS

    with pytest.raises(EncounterAuthorizationError, match="No active Authorization Grant"):
        app.transition_encounter(
            db, encounter.id, "complete", actor_person_id=actor, purpose=PURPOSE, now=NOW
        )
    assert db.execute(
        "SELECT state FROM encounters WHERE encounter_id=?", (str(encounter.id),)
    ).fetchone() == ("active",)
    audits = db.execute(
        "SELECT result_status FROM audit_records WHERE target_ref LIKE 'encounter:%' ORDER BY occurred_at"
    ).fetchall()
    assert ("allowed",) in audits and ("denied",) in audits
    db.close()


@pytest.mark.parametrize("grant_state,start,end", [
    ("revoked", STAMP, None),
    ("suspended", STAMP, None),
    ("active", "2026-10-11T00:00:00Z", None),
    ("active", STAMP, "2026-10-10T11:00:00Z"),
])
def test_inactive_or_out_of_window_grants_fail_closed(grant_state, start, end):
    db = connect_database()
    actor, context, _service = setup_context(db)
    scope = f"patient_context:{context}"
    grant(db, actor, "health.encounter.create", scope, scope, state=grant_state, start=start, end=end)
    with pytest.raises(EncounterAuthorizationError):
        EncounterApplication().create_encounter(
            db, context, "consultation", actor_person_id=actor, purpose=PURPOSE, now=NOW
        )
    assert db.execute("SELECT count(*) FROM encounters").fetchone() == (0,)
    assert db.execute(
        "SELECT result_status FROM audit_records WHERE event_type='health.encounter.create'"
    ).fetchone() == ("denied",)
    db.close()


def test_mismatched_scope_context_purpose_and_family_relationship_do_not_authorize():
    db = connect_database()
    actor, context, _service = setup_context(db)
    other_context = uid()
    scope = f"patient_context:{context}"
    grant(db, actor, "health.encounter.create", scope, f"patient_context:{other_context}")
    with pytest.raises(EncounterAuthorizationError):
        EncounterApplication().create_encounter(
            db, context, "consultation", actor_person_id=actor, purpose=PURPOSE, now=NOW
        )
    grant(db, actor, "health.encounter.create", scope, scope, purpose="billing")
    with pytest.raises(EncounterAuthorizationError):
        EncounterApplication().create_encounter(
            db, context, "consultation", actor_person_id=actor, purpose=PURPOSE, now=NOW
        )
    assert db.execute("SELECT count(*) FROM encounters").fetchone() == (0,)
    db.close()


def test_delegated_grant_fails_closed_until_chain_can_be_verified():
    db = connect_database()
    actor, context, _service = setup_context(db)
    delegator = uid()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (delegator, "active", STAMP, STAMP),
    )
    delegation_id = uid()
    db.execute(
        "INSERT INTO delegations(delegation_id,delegator_person_id,subject_person_id,context_ref,state,"
        "effective_from,effective_to,created_at,updated_at,provenance_id,version_no) "
        "VALUES(?,?,?,?,?,?,?,?,?,NULL,1)",
        (delegation_id, delegator, actor, f"patient_context:{context}", "active", STAMP, None, STAMP, STAMP),
    )
    scope = f"patient_context:{context}"
    grant(db, actor, "health.encounter.create", scope, scope, delegation_id=delegation_id)
    with pytest.raises(EncounterAuthorizationError, match="Delegated Encounter grants are disabled"):
        EncounterApplication().create_encounter(
            db, context, "consultation", actor_person_id=actor, purpose=PURPOSE, now=NOW
        )
    assert db.execute("SELECT count(*) FROM encounters").fetchone() == (0,)
    db.close()


def test_agent_grant_and_missing_purpose_are_not_accepted():
    db = connect_database()
    actor, context, _service = setup_context(db)
    scope = f"patient_context:{context}"
    grant(db, actor, "health.encounter.create", scope, scope, agent_id=uid())
    with pytest.raises(EncounterAuthorizationError):
        EncounterApplication().create_encounter(
            db, context, "consultation", actor_person_id=actor, purpose=PURPOSE, now=NOW
        )
    with pytest.raises((EncounterAuthorizationError, ValidationError)):
        EncounterApplication().create_encounter(
            db, context, "consultation", actor_person_id=actor, purpose=" ", now=NOW
        )
    db.close()
