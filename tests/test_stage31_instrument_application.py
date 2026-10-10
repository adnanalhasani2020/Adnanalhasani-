"""Integration tests for the internal SPEC-0016 Instrument persistence primitive."""
import uuid

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
