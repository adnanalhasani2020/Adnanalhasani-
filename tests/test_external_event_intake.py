import sqlite3
import uuid

import pytest

from agent_core.persistence import connect_database


NOW = "2026-10-18T10:00:00Z"
DIGEST = "a" * 64


def _insert_intake(db, **overrides):
    values = {
        "external_event_intake_id": str(uuid.uuid4()),
        "source_system": "hospital",
        "source_event_key": "hospital:invoice:12:created:v1",
        "event_type": "hospital.invoice.created",
        "source_aggregate_type": "invoice",
        "source_aggregate_id": "12",
        "payload_sha256": DIGEST,
        "amount_minor": 1500,
        "currency_code": "YER",
        "occurred_at": NOW,
        "status": "received",
        "finance_entity_type": None,
        "finance_entity_id": None,
        "first_seen_at": NOW,
        "last_seen_at": NOW,
        "mapped_at": None,
        "last_error": None,
    }
    values.update(overrides)
    db.execute(
        "INSERT INTO external_event_intake("
        "external_event_intake_id,source_system,source_event_key,event_type,"
        "source_aggregate_type,source_aggregate_id,payload_sha256,amount_minor,"
        "currency_code,occurred_at,status,finance_entity_type,finance_entity_id,"
        "first_seen_at,last_seen_at,mapped_at,last_error"
        ") VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        tuple(values[key] for key in (
            "external_event_intake_id", "source_system", "source_event_key",
            "event_type", "source_aggregate_type", "source_aggregate_id",
            "payload_sha256", "amount_minor", "currency_code", "occurred_at",
            "status", "finance_entity_type", "finance_entity_id", "first_seen_at",
            "last_seen_at", "mapped_at", "last_error",
        )),
    )
    return values["external_event_intake_id"]


def test_external_event_intake_migration_creates_durable_source_identity():
    db = connect_database()
    intake_id = _insert_intake(db)
    row = db.execute(
        "SELECT source_system,source_event_key,status,payload_sha256 "
        "FROM external_event_intake WHERE external_event_intake_id=?",
        (intake_id,),
    ).fetchone()
    assert tuple(row) == ("hospital", "hospital:invoice:12:created:v1", "received", DIGEST)
    assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"


def test_external_event_source_key_is_unique_and_not_overwritable_by_duplicate():
    db = connect_database()
    _insert_intake(db)
    with pytest.raises(sqlite3.IntegrityError):
        _insert_intake(db, external_event_intake_id=str(uuid.uuid4()), payload_sha256="b" * 64)


@pytest.mark.parametrize(
    "overrides",
    [
        {"source_system": "unknown"},
        {"event_type": "hospital.patient.created"},
        {"source_aggregate_type": "payment"},
        {"payload_sha256": "not-a-digest"},
        {"amount_minor": -1},
        {"currency_code": "yer"},
        {"currency_code": "US"},
    ],
)
def test_external_event_intake_rejects_invalid_contract_values(overrides):
    db = connect_database()
    with pytest.raises(sqlite3.IntegrityError):
        _insert_intake(db, **overrides)


def test_mapped_intake_requires_explicit_finance_target_and_timestamp():
    db = connect_database()
    with pytest.raises(sqlite3.IntegrityError):
        _insert_intake(db, status="mapped")

    db = connect_database()
    intake_id = _insert_intake(
        db,
        status="mapped",
        finance_entity_type="payment",
        finance_entity_id=str(uuid.uuid4()),
        mapped_at=NOW,
    )
    row = db.execute(
        "SELECT status,finance_entity_type,finance_entity_id,mapped_at "
        "FROM external_event_intake WHERE external_event_intake_id=?",
        (intake_id,),
    ).fetchone()
    assert tuple(row) == ("mapped", "payment", row[2], NOW)
    assert row[2]


def test_conflict_log_references_original_event_and_stores_digest_not_payload():
    db = connect_database()
    intake_id = _insert_intake(db)
    conflict_id = str(uuid.uuid4())
    db.execute(
        "INSERT INTO external_event_intake_conflicts("
        "external_event_intake_conflict_id,external_event_intake_id,"
        "observed_payload_sha256,observed_at,reason) VALUES(?,?,?,?,?)",
        (conflict_id, intake_id, "b" * 64, NOW, "source key reused with different payload"),
    )
    row = db.execute(
        "SELECT observed_payload_sha256,reason FROM external_event_intake_conflicts "
        "WHERE external_event_intake_conflict_id=?",
        (conflict_id,),
    ).fetchone()
    assert tuple(row) == ("b" * 64, "source key reused with different payload")

    with pytest.raises(sqlite3.IntegrityError):
        db.execute(
            "INSERT INTO external_event_intake_conflicts("
            "external_event_intake_conflict_id,external_event_intake_id,"
            "observed_payload_sha256,observed_at,reason) VALUES(?,?,?,?,?)",
            (str(uuid.uuid4()), str(uuid.uuid4()), "c" * 64, NOW, "missing source"),
        )
