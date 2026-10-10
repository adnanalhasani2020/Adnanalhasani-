"""Tests for read-only persisted workflow queries."""
import pytest

from agent_core.domain_workflow_read import WorkflowReadApplication
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

STAMP = "2026-10-10T12:00:00Z"


def seed_workflow(db, workflow_id="workflow-a", namespace="orders"):
    db.execute(
        "INSERT INTO workflows(workflow_id,workflow_namespace,workflow_kind,state,started_at,"
        "created_at,updated_at,provenance_id,version_no) VALUES(?,?,?,'created',?,?,?,NULL,1)",
        (workflow_id, namespace, "explicit-workflow", STAMP, STAMP, STAMP),
    )


def test_workflow_reads_restore_workflow_and_list_namespace_deterministically():
    db = connect_database()
    seed_workflow(db, "workflow-b")
    seed_workflow(db, "workflow-a")
    app = WorkflowReadApplication()

    assert app.get(db, "workflow-a").workflow_namespace == "orders"
    assert [item.workflow_id for item in app.list_for_namespace(db, "orders")] == [
        "workflow-a", "workflow-b"
    ]
    assert app.list_for_namespace(db, "missing") == ()
    db.close()


def test_workflow_events_are_returned_in_sequence_order():
    db = connect_database()
    seed_workflow(db)
    db.executemany(
        "INSERT INTO workflow_events(workflow_event_id,workflow_id,event_type,state,event_sequence,"
        "occurred_at,detail_ref,created_at) VALUES(?,?,?, ?,?,?,?,?)",
        [
            ("event-2", "workflow-a", "second", "in_progress", 2, STAMP, None, STAMP),
            ("event-1", "workflow-a", "first", "created", 1, STAMP, "detail:1", STAMP),
        ],
    )
    events = WorkflowReadApplication().list_events(db, "workflow-a")
    assert [event.event_sequence for event in events] == [1, 2]
    assert [event.workflow_event_id for event in events] == ["event-1", "event-2"]
    db.close()


@pytest.mark.parametrize("method,args", [
    ("get", ("missing",)),
    ("list_events", ("missing",)),
])
def test_workflow_reads_reject_unknown_workflow(method, args):
    db = connect_database()
    with pytest.raises(ValidationError, match="Workflow does not exist"):
        getattr(WorkflowReadApplication(), method)(db, *args)
    db.close()


def test_workflow_namespace_must_be_explicit():
    db = connect_database()
    with pytest.raises(ValidationError, match="workflow_namespace is required"):
        WorkflowReadApplication().list_for_namespace(db, " ")
    db.close()
