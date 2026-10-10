"""Integration tests for SPEC-0023 durable operation identity and duplicate handling."""
import uuid

import pytest

from agent_core.durable_operation_application import DurableOperationApplication
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

STAMP = "2026-10-10T12:00:00Z"


def test_reservation_is_idempotent_and_survives_database_reopen(tmp_path):
    path = tmp_path / "operations.sqlite"
    db = connect_database(path)
    app = DurableOperationApplication()
    first = app.reserve(
        db, namespace="commerce.sale", operation_id="request-001",
        operation_kind="sale.create", actor_context_ref="context-1",
        request_fingerprint="sha256:request-a", now=STAMP,
    )
    assert first.state == "reserved"
    db.close()

    db = connect_database(path)
    duplicate = app.reserve(
        db, namespace="commerce.sale", operation_id="request-001",
        operation_kind="sale.create", actor_context_ref="context-1",
        request_fingerprint="sha256:request-a", now=STAMP,
    )
    assert duplicate.record_id == first.record_id
    assert duplicate.state == "reserved"
    assert db.execute("SELECT COUNT(*) FROM durable_operation_records").fetchone()[0] == 1
    db.close()


def test_same_operation_key_with_different_payload_fails_closed_without_conflict_resolution():
    db = connect_database()
    app = DurableOperationApplication()
    app.reserve(
        db, namespace="payments", operation_id="operation-002",
        operation_kind="payment.request", actor_context_ref="context-2",
        request_fingerprint="sha256:payload-a", now=STAMP,
    )
    with pytest.raises(ValidationError, match="different request fingerprint"):
        app.reserve(
            db, namespace="payments", operation_id="operation-002",
            operation_kind="payment.request", actor_context_ref="context-2",
            request_fingerprint="sha256:payload-b", now=STAMP,
        )
    assert db.execute("SELECT COUNT(*) FROM durable_operation_records").fetchone()[0] == 1
    assert db.execute("SELECT COUNT(*) FROM conflicts").fetchone()[0] == 0
    db.close()


def test_outcome_recording_is_idempotent_and_cannot_rewrite_final_outcome():
    db = connect_database()
    app = DurableOperationApplication()
    record = app.reserve(
        db, namespace="commerce.invoice", operation_id=str(uuid.uuid4()),
        operation_kind="invoice.issue", actor_context_ref="context-3",
        request_fingerprint="sha256:payload-c", now=STAMP,
    )
    done = app.record_outcome(
        db, namespace=record.namespace, operation_id=record.operation_id,
        state="completed", outcome_ref="invoice:opaque-ref", now=STAMP,
    )
    assert done.state == "completed"
    assert app.record_outcome(
        db, namespace=record.namespace, operation_id=record.operation_id,
        state="completed", outcome_ref="invoice:opaque-ref", now=STAMP,
    ) == done
    with pytest.raises(ValidationError, match="already final"):
        app.record_outcome(
            db, namespace=record.namespace, operation_id=record.operation_id,
            state="rejected", outcome_ref="rejection:opaque-ref", now=STAMP,
        )
    db.close()


def test_rejects_offline_writes_and_invalid_outcome_state():
    db = connect_database()
    app = DurableOperationApplication()
    with pytest.raises(ValidationError, match="connectivity"):
        app.reserve(
            db, namespace="commerce", operation_id="offline",
            operation_kind="sale.create", actor_context_ref="context",
            request_fingerprint="fp", now=STAMP, connected=False,
        )
    record = app.reserve(
        db, namespace="commerce", operation_id="reserved",
        operation_kind="sale.create", actor_context_ref="context",
        request_fingerprint="fp", now=STAMP,
    )
    with pytest.raises(ValidationError, match="Only completed or rejected"):
        app.record_outcome(
            db, namespace=record.namespace, operation_id=record.operation_id,
            state="conflicted", outcome_ref="conflict-ref", now=STAMP,
        )
    db.close()
