"""Integration tests for the internal SPEC-0016 Instrument persistence primitive."""
import uuid
import sqlite3

import pytest

from agent_core.instrument_application import InstrumentApplication
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

STAMP = "2026-10-10T12:00:00Z"


def uid():
    return str(uuid.uuid4())


def test_instrument_lifecycle_persists_versions_and_redemption_history_after_reopen(tmp_path):
    path = tmp_path / "instrument.sqlite"
    db = connect_database(path)
    app = InstrumentApplication()
    instrument = app.create_instrument(
        db, instrument_id=uid(), instrument_type="unspecified",
        issuer_ref="issuer-ref", canonical_identifier="instrument-001",
        actor_context_ref="internal-context", now=STAMP,
    )
    assert instrument.state == "issued"
    instrument_id = str(instrument.instrument_id)
    db.close()

    db = connect_database(path)
    instrument = app.get_instrument(db, instrument_id)
    assert instrument.state == "issued"
    instrument = app.transition_instrument(
        db, instrument_id, "activate", expected_version=1,
        actor_context_ref="internal-context", now=STAMP,
    )
    assert (instrument.state, instrument.version_no) == ("active", 2)
    instrument = app.transition_instrument(
        db, instrument_id, "redeem", expected_version=2,
        actor_context_ref="internal-context", action_ref="opaque-operation-ref",
        context_ref="opaque-context-ref", now=STAMP,
    )
    assert (instrument.state, instrument.version_no) == ("redeemed", 3)
    assert db.execute("SELECT COUNT(*) FROM instrument_usages WHERE instrument_id=?", (instrument_id,)).fetchone()[0] == 1
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE owner_domain='instrument' AND target_ref=?",
        (instrument_id,),
    ).fetchone()[0] == 3
    usages = app.list_instrument_usages(db, instrument_id)
    assert len(usages) == 1
    assert usages[0].instrument_id == instrument.instrument_id
    assert usages[0].action_ref == "opaque-operation-ref"
    assert usages[0].context_ref == "opaque-context-ref"
    assert usages[0].state == "recorded"
    assert usages[0].occurred_at == STAMP
    db.close()



def test_instrument_usage_reader_is_scoped_and_rejects_unknown_instrument():
    db = connect_database()
    app = InstrumentApplication()
    first = app.create_instrument(
        db, instrument_id=uid(), instrument_type="unspecified",
        issuer_ref="issuer-ref", canonical_identifier="instrument-reader-001",
        actor_context_ref="internal-context", now=STAMP,
    )
    second = app.create_instrument(
        db, instrument_id=uid(), instrument_type="unspecified",
        issuer_ref="issuer-ref", canonical_identifier="instrument-reader-002",
        actor_context_ref="internal-context", now=STAMP,
    )
    empty = app.create_instrument(
        db, instrument_id=uid(), instrument_type="unspecified",
        issuer_ref="issuer-ref", canonical_identifier="instrument-reader-empty",
        actor_context_ref="internal-context", now=STAMP,
    )
    for instrument, action_ref in ((first, "first-op"), (second, "second-op")):
        app.transition_instrument(
            db, instrument.instrument_id, "activate", expected_version=1,
            actor_context_ref="internal-context", now=STAMP,
        )
        app.transition_instrument(
            db, instrument.instrument_id, "redeem", expected_version=2,
            actor_context_ref="internal-context", action_ref=action_ref,
            now=STAMP,
        )

    assert [item.action_ref for item in app.list_instrument_usages(db, first.instrument_id)] == ["first-op"]
    assert [item.action_ref for item in app.list_instrument_usages(db, second.instrument_id)] == ["second-op"]
    assert app.list_instrument_usages(db, empty.instrument_id) == ()
    with pytest.raises(ValidationError, match="does not exist"):
        app.list_instrument_usages(db, uid())
    db.close()

def test_instrument_rejects_stale_versions_invalid_transitions_and_offline_writes():
    db = connect_database()
    app = InstrumentApplication()
    instrument = app.create_instrument(
        db, instrument_id=uid(), instrument_type="unspecified",
        issuer_ref="issuer-ref", canonical_identifier="instrument-002",
        actor_context_ref="internal-context", now=STAMP,
    )
    key = str(instrument.instrument_id)
    active = app.transition_instrument(
        db, key, "activate", expected_version=1,
        actor_context_ref="internal-context", now=STAMP,
    )
    with pytest.raises(ValidationError, match="version conflict"):
        app.transition_instrument(
            db, key, "expire", expected_version=1,
            actor_context_ref="internal-context", now=STAMP,
        )
    with pytest.raises(ValidationError, match="Invalid Instrument transition"):
        app.transition_instrument(
            db, key, "activate", expected_version=2,
            actor_context_ref="internal-context", now=STAMP,
        )
    with pytest.raises(ValidationError, match="connectivity"):
        app.transition_instrument(
            db, key, "expire", expected_version=2,
            actor_context_ref="internal-context", now=STAMP, connected=False,
        )
    assert app.get_instrument(db, key).state == active.state == "active"
    assert db.execute("SELECT COUNT(*) FROM domain_history WHERE target_ref=?", (key,)).fetchone()[0] == 2
    db.close()


def test_redeem_requires_operation_reference_and_does_not_invent_financial_effects():
    db = connect_database()
    app = InstrumentApplication()
    instrument = app.create_instrument(
        db, instrument_id=uid(), instrument_type="unspecified",
        issuer_ref="issuer-ref", canonical_identifier="instrument-003",
        actor_context_ref="internal-context", now=STAMP,
    )
    key = str(instrument.instrument_id)
    app.transition_instrument(db, key, "activate", expected_version=1,
                              actor_context_ref="internal-context", now=STAMP)
    with pytest.raises(ValidationError, match="action_ref"):
        app.transition_instrument(db, key, "redeem", expected_version=2,
                                  actor_context_ref="internal-context", now=STAMP)
    assert db.execute("SELECT COUNT(*) FROM instrument_usages").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM payments").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM ledger_entries").fetchone()[0] == 0
    db.close()

@pytest.mark.parametrize("action", [[], {}])
def test_instrument_rejects_non_string_transition_actions_without_mutation(action):
    db = connect_database()
    app = InstrumentApplication()
    instrument = app.create_instrument(
        db, instrument_id=uid(), instrument_type="unspecified",
        issuer_ref="issuer-ref", canonical_identifier="instrument-nonstring-action",
        actor_context_ref="internal-context", now=STAMP,
    )
    with pytest.raises(ValidationError, match="Unsupported Instrument transition"):
        app.transition_instrument(
            db, instrument.instrument_id, action, expected_version=1,
            actor_context_ref="internal-context", now=STAMP,
        )
    current = app.get_instrument(db, instrument.instrument_id)
    assert current.state == "issued"
    assert current.version_no == 1
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=?",
        (str(instrument.instrument_id),),
    ).fetchone()[0] == 1
    db.close()


import sqlite3


def test_instrument_create_preserves_caller_transaction_on_success_and_history_failure():
    db = connect_database()
    app = InstrumentApplication()
    caller_person = uid()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
        (caller_person, STAMP, STAMP),
    )
    instrument_id = uid()
    app.create_instrument(
        db, instrument_id=instrument_id, instrument_type="unspecified",
        issuer_ref="issuer", canonical_identifier="caller-transaction-success",
        actor_context_ref="internal-context", now=STAMP,
    )
    assert db.in_transaction
    db.rollback()
    assert db.execute("SELECT COUNT(*) FROM instruments WHERE instrument_id=?", (instrument_id,)).fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM persons WHERE person_id=?", (caller_person,)).fetchone()[0] == 0

    caller_person = uid()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
        (caller_person, STAMP, STAMP),
    )
    db.execute(
        "CREATE TRIGGER fail_instrument_history BEFORE INSERT ON domain_history "
        "WHEN NEW.owner_domain='instrument' BEGIN SELECT RAISE(ABORT, 'history unavailable'); END"
    )
    instrument_id = uid()
    with pytest.raises(sqlite3.IntegrityError, match="history unavailable"):
        app.create_instrument(
            db, instrument_id=instrument_id, instrument_type="unspecified",
            issuer_ref="issuer", canonical_identifier="caller-transaction-failure",
            actor_context_ref="internal-context", now=STAMP,
        )
    assert db.in_transaction
    assert db.execute("SELECT COUNT(*) FROM instruments WHERE instrument_id=?", (instrument_id,)).fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM persons WHERE person_id=?", (caller_person,)).fetchone()[0] == 1
    db.rollback()
    db.close()


def test_instrument_redemption_failure_preserves_caller_work_and_usage_atomicity():
    db = connect_database()
    app = InstrumentApplication()
    instrument = app.create_instrument(
        db, instrument_id=uid(), instrument_type="unspecified",
        issuer_ref="issuer", canonical_identifier="redeem-atomicity",
        actor_context_ref="internal-context", now=STAMP,
    )
    key = str(instrument.instrument_id)
    app.transition_instrument(
        db, key, "activate", expected_version=1,
        actor_context_ref="internal-context", now=STAMP,
    )
    db.execute(
        "CREATE TRIGGER fail_instrument_redemption_history BEFORE INSERT ON domain_history "
        "WHEN NEW.owner_domain='instrument' AND NEW.change_payload_ref='action:redeem;state:redeemed' "
        "BEGIN SELECT RAISE(ABORT, 'history unavailable'); END"
    )
    caller_person = uid()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
        (caller_person, STAMP, STAMP),
    )
    with pytest.raises(sqlite3.IntegrityError, match="history unavailable"):
        app.transition_instrument(
            db, key, "redeem", expected_version=2, actor_context_ref="internal-context",
            action_ref="opaque-operation", now=STAMP,
        )
    current = app.get_instrument(db, key)
    assert (current.state, current.version_no) == ("active", 2)
    assert db.execute("SELECT COUNT(*) FROM instrument_usages WHERE instrument_id=?", (key,)).fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM persons WHERE person_id=?", (caller_person,)).fetchone()[0] == 1
    assert db.in_transaction
    db.rollback()
    db.close()



@pytest.mark.parametrize(
    ("transition_at", "expected_message"),
    [
        ("2026-10-10T11:59:59Z", "cannot predate creation"),
        ("2026-10-10T12:04:59Z", "cannot predate current updated_at"),
    ],
)
def test_instrument_rejects_backdated_transitions_without_mutation(transition_at, expected_message):
    db = connect_database()
    app = InstrumentApplication()
    instrument = app.create_instrument(
        db, instrument_id=uid(), instrument_type="unspecified",
        issuer_ref="issuer-ref", canonical_identifier=f"backdated-{transition_at}",
        actor_context_ref="internal-context", now=STAMP,
    )
    active = app.transition_instrument(
        db, instrument.instrument_id, "activate", expected_version=1,
        actor_context_ref="internal-context", now="2026-10-10T12:05:00Z",
    )

    with pytest.raises(ValidationError, match=expected_message):
        app.transition_instrument(
            db, instrument.instrument_id, "expire", expected_version=2,
            actor_context_ref="internal-context", now=transition_at,
        )

    current = app.get_instrument(db, instrument.instrument_id)
    assert (current.state, current.version_no, current.updated_at) == (
        active.state, active.version_no, active.updated_at
    )
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=?",
        (str(instrument.instrument_id),),
    ).fetchone()[0] == 2
    db.close()


def test_instrument_transition_fails_closed_on_malformed_persisted_timestamp():
    db = connect_database()
    app = InstrumentApplication()
    instrument = app.create_instrument(
        db, instrument_id=uid(), instrument_type="unspecified",
        issuer_ref="issuer-ref", canonical_identifier="malformed-persisted-time",
        actor_context_ref="internal-context", now=STAMP,
    )
    db.execute(
        "UPDATE instruments SET updated_at=? WHERE instrument_id=?",
        ("not-a-timestamp", str(instrument.instrument_id)),
    )
    db.commit()

    with pytest.raises(ValidationError, match="Persisted Instrument updated_at"):
        app.transition_instrument(
            db, instrument.instrument_id, "activate", expected_version=1,
            actor_context_ref="internal-context", now="2026-10-10T12:01:00Z",
        )

    current = app.get_instrument(db, instrument.instrument_id)
    assert (current.state, current.version_no, current.updated_at) == (
        "issued", 1, "not-a-timestamp"
    )
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=?",
        (str(instrument.instrument_id),),
    ).fetchone()[0] == 1
    db.close()



@pytest.mark.parametrize("invalid_timestamp", ["", 0, False])
def test_instrument_creation_rejects_explicit_falsey_timestamps(invalid_timestamp):
    db = connect_database()
    app = InstrumentApplication()
    with pytest.raises(ValidationError, match="creation timestamp"):
        app.create_instrument(
            db, instrument_id=uid(), instrument_type="unspecified",
            issuer_ref="issuer-ref", canonical_identifier=f"invalid-create-time-{type(invalid_timestamp).__name__}",
            actor_context_ref="internal-context", now=invalid_timestamp,
        )
    assert db.execute("SELECT COUNT(*) FROM instruments").fetchone()[0] == 0
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE owner_domain='instrument'"
    ).fetchone()[0] == 0
    db.close()


@pytest.mark.parametrize("invalid_timestamp", ["", 0, False])
def test_instrument_transition_rejects_explicit_falsey_timestamps_without_mutation(invalid_timestamp):
    db = connect_database()
    app = InstrumentApplication()
    instrument = app.create_instrument(
        db, instrument_id=uid(), instrument_type="unspecified",
        issuer_ref="issuer-ref", canonical_identifier=f"invalid-transition-time-{type(invalid_timestamp).__name__}",
        actor_context_ref="internal-context", now=STAMP,
    )
    with pytest.raises(ValidationError, match="transition timestamp"):
        app.transition_instrument(
            db, instrument.instrument_id, "activate", expected_version=1,
            actor_context_ref="internal-context", now=invalid_timestamp,
        )
    current = app.get_instrument(db, instrument.instrument_id)
    assert (current.state, current.version_no) == ("issued", 1)
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=?",
        (str(instrument.instrument_id),),
    ).fetchone()[0] == 1
    db.close()


def test_instrument_does_not_infer_voucher_cancellation_from_generic_lifecycle():
    db = connect_database()
    app = InstrumentApplication()
    instrument = app.create_instrument(
        db, instrument_id=uid(), instrument_type="unspecified",
        issuer_ref="issuer-ref", canonical_identifier="voucher-cancel-not-defined",
        actor_context_ref="internal-context", now=STAMP,
    )
    key = str(instrument.instrument_id)

    with pytest.raises(ValidationError, match="Unsupported Instrument transition"):
        app.transition_instrument(
            db, key, "cancel", expected_version=1,
            actor_context_ref="internal-context", now=STAMP,
        )

    current = app.get_instrument(db, key)
    assert (current.state, current.version_no) == ("issued", 1)
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE owner_domain='instrument' AND target_ref=?",
        (key,),
    ).fetchone()[0] == 1
    assert db.execute("SELECT COUNT(*) FROM instrument_usages WHERE instrument_id=?", (key,)).fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM payments").fetchone()[0] == 0
    assert db.execute("SELECT COUNT(*) FROM ledger_entries").fetchone()[0] == 0
    db.close()
