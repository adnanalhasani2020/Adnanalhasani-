"""Regression tests for crash-safe durable operation idempotency."""
import sqlite3

import pytest

from agent_core.integrity import DurableOperationAuthority
from agent_core.shared import ValidationError


def test_operation_reservation_is_committed_before_semantic_effect(tmp_path):
    authority = DurableOperationAuthority(str(tmp_path / "operations.sqlite"))
    observed = []

    def effect():
        record = authority.get("test", "operation-1")
        observed.append(record.status if record is not None else None)
        return {"result": "ok"}

    result = authority.execute(
        namespace="test",
        operation_id="operation-1",
        operation_kind="example",
        request={"value": 1},
        effect=effect,
    )

    assert result == {"result": "ok"}
    assert observed == ["reserved"]
    assert authority.get("test", "operation-1").status == "completed"


def test_outcome_persistence_failure_cannot_erase_reservation_and_repeat_effect(tmp_path):
    path = tmp_path / "operations.sqlite"
    authority = DurableOperationAuthority(str(path))
    with sqlite3.connect(path) as db:
        db.execute(
            """
            CREATE TRIGGER reject_completed_outcome
            BEFORE UPDATE OF status ON operation_records
            WHEN NEW.status = 'completed'
            BEGIN
                SELECT RAISE(ABORT, 'simulated outcome persistence failure');
            END
            """
        )

    effect_calls = []

    def effect():
        effect_calls.append("called")
        return {"result": "external-effect-completed"}

    with pytest.raises(sqlite3.IntegrityError, match="simulated outcome persistence failure"):
        authority.execute(
            namespace="test",
            operation_id="operation-2",
            operation_kind="example",
            request={"value": 2},
            effect=effect,
        )

    record = authority.get("test", "operation-2")
    assert record is not None
    assert record.status == "reserved"
    assert effect_calls == ["called"]

    with pytest.raises(ValidationError, match="replay is incomplete"):
        authority.execute(
            namespace="test",
            operation_id="operation-2",
            operation_kind="example",
            request={"value": 2},
            effect=effect,
        )
    assert effect_calls == ["called"]
