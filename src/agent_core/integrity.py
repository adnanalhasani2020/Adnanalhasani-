import hashlib
import json
import sqlite3
from dataclasses import dataclass
from pathlib import Path
from threading import RLock
from typing import Any, Callable

from agent_core.domain_audit import Provenance
from agent_core.shared import ValidationError
from uuid import UUID


@dataclass(frozen=True)
class SemanticReference:
    entity_type: str
    entity_id: UUID
    expected_owner_domain: str


@dataclass(frozen=True)
class SemanticEntity:
    entity_type: str
    entity_id: UUID
    owner_domain: str


class SemanticAuthority:
    """Runtime semantic source-of-truth adapter."""

    def __init__(self) -> None:
        self._entities: dict[tuple[str, UUID], SemanticEntity] = {}
        self._provenance: dict[UUID, Provenance] = {}
        self._lock = RLock()

    def register_entity(self, entity_type: str, entity_id: UUID, owner_domain: str) -> None:
        if not entity_type.strip() or not isinstance(entity_id, UUID) or not owner_domain.strip():
            raise ValidationError("Semantic entity type, UUID, and owner domain are required")
        with self._lock:
            self._entities[(entity_type, entity_id)] = SemanticEntity(
                entity_type, entity_id, owner_domain
            )

    def register_provenance(self, provenance: Provenance) -> None:
        with self._lock:
            self._provenance[provenance.id] = provenance

    def resolve(
        self,
        reference: SemanticReference,
        *,
        provenance: Provenance | None = None,
        provenance_required: bool = False,
    ) -> SemanticEntity:
        if not reference.entity_type.strip() or not isinstance(reference.entity_id, UUID):
            raise ValidationError("Semantic reference shape is invalid")
        with self._lock:
            entity = self._entities.get((reference.entity_type, reference.entity_id))
            if entity is None:
                raise ValidationError("Semantic entity does not exist")
            if entity.owner_domain != reference.expected_owner_domain:
                raise ValidationError("Semantic entity ownership mismatch")
            if provenance_required:
                if provenance is None:
                    raise ValidationError("Required provenance is missing")
                registered = self._provenance.get(provenance.id)
                if registered != provenance:
                    raise ValidationError("Required provenance is not authoritative")
                expected_ref = f"{entity.entity_type}:{entity.entity_id}"
                if provenance.source_ref != expected_ref or provenance.source_type != entity.entity_type:
                    raise ValidationError("Provenance does not match semantic entity")
            return entity


@dataclass(frozen=True)
class OperationRecord:
    namespace: str
    operation_id: str
    operation_kind: str
    request_fingerprint: str
    status: str
    outcome: Any


class DurableOperationAuthority:
    """Single durable authority for operation identity, replay, and conflict."""

    def __init__(self, path: str) -> None:
        self.path = path
        if path != ":memory:":
            Path(path).expanduser().parent.mkdir(parents=True, exist_ok=True)
        self._lock = RLock()
        self._initialize()

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.path, timeout=30, isolation_level=None)
        connection.execute("PRAGMA busy_timeout=30000")
        connection.execute("PRAGMA journal_mode=WAL")
        return connection

    def _initialize(self) -> None:
        with self._connect() as db:
            db.execute(
                """
                CREATE TABLE IF NOT EXISTS operation_records (
                    namespace TEXT NOT NULL,
                    operation_id TEXT NOT NULL,
                    operation_kind TEXT NOT NULL,
                    request_fingerprint TEXT NOT NULL,
                    status TEXT NOT NULL,
                    outcome_json TEXT NOT NULL,
                    PRIMARY KEY (namespace, operation_id)
                )
                """
            )

    @staticmethod
    def fingerprint(payload: Any) -> str:
        canonical = json.dumps(
            payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False, default=str
        )
        return hashlib.sha256(canonical.encode("utf-8")).hexdigest()

    @staticmethod
    def _decode_outcome(value: str) -> Any:
        return json.loads(value)

    @staticmethod
    def _encode_outcome(value: Any) -> str:
        return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, default=str)

    def _read(self, db: sqlite3.Connection, namespace: str, operation_id: str) -> OperationRecord | None:
        row = db.execute(
            "SELECT namespace, operation_id, operation_kind, request_fingerprint, status, outcome_json "
            "FROM operation_records WHERE namespace=? AND operation_id=?",
            (namespace, operation_id),
        ).fetchone()
        if row is None:
            return None
        return OperationRecord(*row[:5], self._decode_outcome(row[5]))

    def execute(
        self,
        *,
        namespace: str,
        operation_id: str,
        operation_kind: str,
        request: Any,
        effect: Callable[[], Any],
    ) -> Any:
        if not namespace.strip() or not operation_id.strip() or not operation_kind.strip():
            raise ValidationError("Operation namespace, identity, and kind are required")
        fingerprint = self.fingerprint(request)
        with self._lock:
            with self._connect() as db:
                db.execute("BEGIN IMMEDIATE")
                existing = self._read(db, namespace, operation_id)
                if existing is not None:
                    db.execute("ROLLBACK")
                    if existing.operation_kind == operation_kind and existing.request_fingerprint == fingerprint:
                        if existing.status == "completed":
                            return existing.outcome
                        raise ValidationError("Operation replay is incomplete")
                    raise ValidationError("Operation identity conflict")
                db.execute(
                    "INSERT INTO operation_records "
                    "(namespace, operation_id, operation_kind, request_fingerprint, status, outcome_json) "
                    "VALUES (?, ?, ?, ?, 'reserved', 'null')",
                    (namespace, operation_id, operation_kind, fingerprint),
                )
                db.execute("COMMIT")
        try:
            outcome = effect()
        except Exception as exc:
            with self._connect() as db:
                db.execute(
                    "UPDATE operation_records SET status='rejected', outcome_json=? "
                    "WHERE namespace=? AND operation_id=?",
                    (self._encode_outcome({"error": str(exc)}), namespace, operation_id),
                )
            raise
        with self._connect() as db:
            db.execute(
                "UPDATE operation_records SET status='completed', outcome_json=? "
                "WHERE namespace=? AND operation_id=?",
                (self._encode_outcome(outcome), namespace, operation_id),
            )
        return outcome

    def get(self, namespace: str, operation_id: str) -> OperationRecord | None:
        with self._connect() as db:
            return self._read(db, namespace, operation_id)


class RuntimeIntegrityGate:
    """Single application-runtime gate for semantic and durable operation checks."""

    def __init__(self, semantic: SemanticAuthority, operations: DurableOperationAuthority) -> None:
        self.semantic = semantic
        self.operations = operations

    def execute(
        self,
        *,
        reference: SemanticReference,
        namespace: str,
        operation_id: str,
        operation_kind: str,
        request: Any,
        effect: Callable[[], Any],
        provenance: Provenance | None = None,
        provenance_required: bool = False,
    ) -> Any:
        self.semantic.resolve(
            reference, provenance=provenance, provenance_required=provenance_required
        )
        return self.operations.execute(
            namespace=namespace,
            operation_id=operation_id,
            operation_kind=operation_kind,
            request=request,
            effect=effect,
        )
