"""Read-only queries for persisted workflow and workflow-event records."""
from dataclasses import dataclass
from uuid import UUID

from agent_core.shared import ValidationError


@dataclass(frozen=True)
class PersistedWorkflow:
    workflow_id: str
    workflow_namespace: str
    workflow_kind: str
    state: str
    started_at: str
    created_at: str
    updated_at: str
    provenance_id: str | None
    version_no: int


@dataclass(frozen=True)
class PersistedWorkflowEvent:
    workflow_event_id: str
    workflow_id: str
    event_type: str
    state: str
    event_sequence: int
    occurred_at: str
    detail_ref: str | None
    created_at: str


class WorkflowReadApplication:
    """Read existing workflow facts without defining lifecycle or authorization policy."""

    @staticmethod
    def _required(value, label):
        if not isinstance(value, str) or not value.strip():
            raise ValidationError(f"{label} is required")
        return value.strip()

    @staticmethod
    def _workflow(row):
        return PersistedWorkflow(*row)

    @staticmethod
    def _event(row):
        return PersistedWorkflowEvent(*row)

    def get(self, connection, workflow_id) -> PersistedWorkflow:
        key = self._required(workflow_id, "workflow_id")
        row = connection.execute(
            "SELECT workflow_id,workflow_namespace,workflow_kind,state,started_at,"
            "created_at,updated_at,provenance_id,version_no FROM workflows WHERE workflow_id=?",
            (key,),
        ).fetchone()
        if row is None:
            raise ValidationError("Workflow does not exist")
        return self._workflow(row)

    def list_for_namespace(self, connection, workflow_namespace) -> tuple[PersistedWorkflow, ...]:
        namespace = self._required(workflow_namespace, "workflow_namespace")
        rows = connection.execute(
            "SELECT workflow_id,workflow_namespace,workflow_kind,state,started_at,"
            "created_at,updated_at,provenance_id,version_no FROM workflows "
            "WHERE workflow_namespace=? ORDER BY started_at,workflow_id",
            (namespace,),
        ).fetchall()
        return tuple(self._workflow(row) for row in rows)

    def list_events(self, connection, workflow_id) -> tuple[PersistedWorkflowEvent, ...]:
        key = self._required(workflow_id, "workflow_id")
        if connection.execute(
            "SELECT 1 FROM workflows WHERE workflow_id=?", (key,)
        ).fetchone() is None:
            raise ValidationError("Workflow does not exist")
        rows = connection.execute(
            "SELECT workflow_event_id,workflow_id,event_type,state,event_sequence,"
            "occurred_at,detail_ref,created_at FROM workflow_events "
            "WHERE workflow_id=? ORDER BY event_sequence",
            (key,),
        ).fetchall()
        return tuple(self._event(row) for row in rows)
