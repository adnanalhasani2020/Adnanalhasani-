"""Persistence contract for non-final pending operations under SPEC-0019."""
import uuid

import pytest

from agent_core.domain_offline import PendingOperationApplication
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

STAMP = "2026-10-10T12:00:00Z"


def uid():
    return str(uuid.uuid4())


def seed_device(db, state="active", version=1):
    device_id = uid()
    db.execute(
        "INSERT INTO devices(device_id,device_fingerprint_ref,state,created_at,updated_at,version_no) "
        "VALUES(?,?,?,?,?,?)",
        (device_id, "fingerprint:test-device", state, STAMP, STAMP, version),
    )
    db.commit()
    return device_id


def queue(app, db, device, operation_id="request-1", **overrides):
    values = {
        "device_id": device, "namespace": "offline.test",
        "operation_id": operation_id, "operation_kind": "draft.update",
        "request_fingerprint": "sha256:payload-a", "now": STAMP,
    }
    values.update(overrides)
    return app.queue(db, **values)


def test_pending_operation_persists_and_idempotently_reloads_after_reopen(tmp_path):
    path = tmp_path / "pending.sqlite"
    db = connect_database(path)
    device = seed_device(db)
    app = PendingOperationApplication()
    first = queue(app, db, device)
    assert first.state == "queued"
    db.close()

    db = connect_database(path)
    same = queue(app, db, device)
    assert same.pending_operation_id == first.pending_operation_id
    assert app.get(db, namespace="offline.test", operation_id="request-1") == first
    assert app.list_for_device(db, device) == (first,)
    db.close()


def test_pending_key_cannot_be_rebound_to_different_request_or_device():
    db = connect_database()
    device = seed_device(db)
    other = seed_device(db)
    app = PendingOperationApplication()
    queue(app, db, device)
    with pytest.raises(ValidationError, match="different device or request metadata"):
        queue(app, db, device, request_fingerprint="sha256:payload-b")
    with pytest.raises(ValidationError, match="different device or request metadata"):
        queue(app, db, other)
    assert db.execute("SELECT COUNT(*) FROM pending_operations").fetchone()[0] == 1
    db.close()


def test_queue_requires_existing_active_device():
    db = connect_database()
    app = PendingOperationApplication()
    with pytest.raises(ValidationError, match="existing Device"):
        queue(app, db, uid())
    lost = seed_device(db, state="lost")
    with pytest.raises(ValidationError, match="active Device"):
        queue(app, db, lost)
    db.close()


def test_submit_and_cancel_are_persisted_idempotent_and_non_final():
    db = connect_database()
    device = seed_device(db)
    app = PendingOperationApplication()
    queue(app, db, device)
    submitted = app.submit(db, namespace="offline.test", operation_id="request-1", now=STAMP)
    assert submitted.state == "submitted"
    assert app.submit(db, namespace="offline.test", operation_id="request-1", now=STAMP) == submitted
    cancelled = app.cancel(db, namespace="offline.test", operation_id="request-1", now=STAMP)
    assert cancelled.state == "cancelled"
    assert app.cancel(db, namespace="offline.test", operation_id="request-1", now=STAMP) == cancelled
    with pytest.raises(ValidationError, match="cannot transition to submitted"):
        app.submit(db, namespace="offline.test", operation_id="request-1", now=STAMP)
    assert db.execute("SELECT state FROM pending_operations").fetchone()[0] == "cancelled"
    db.close()


def test_device_loss_atomically_cancels_queued_and_submitted_operations():
    db = connect_database()
    device = seed_device(db, version=3)
    app = PendingOperationApplication()
    queue(app, db, device, "queued-1")
    queue(app, db, device, "queued-2")
    app.submit(db, namespace="offline.test", operation_id="queued-2", now=STAMP)
    result = app.mark_device_lost(db, device_id=device, expected_version=3, now=STAMP)
    assert result["state"] == "lost"
    assert result["version_no"] == 4
    assert result["cancelled_count"] == 2
    assert {row.state for row in app.list_for_device(db, device)} == {"cancelled"}
    with pytest.raises(ValidationError, match="changed concurrently"):
        app.mark_device_lost(db, device_id=device, expected_version=3, now=STAMP)
    db.close()
