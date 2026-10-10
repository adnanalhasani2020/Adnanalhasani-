"""Explicit persistence for provenance records under the existing relational contract."""
from dataclasses import dataclass
from datetime import datetime, timezone
from uuid import UUID

from agent_core.shared import ValidationError, new_id


@dataclass(frozen=True)
class PersistedProvenance:
    provenance_id: UUID
    source_type: str
    source_ref: str
    source_version_ref: str | None
    as_of_at: str | None
    derivation_type: str
    created_at: str


class ProvenanceRecordApplication:
    """Append and retrieve caller-supplied provenance facts.

    This service records provenance explicitly. It does not infer source lineage,
    automatically attach provenance to domain writes, or replace Domain History.
    """

    @staticmethod
    def _required(value, label):
        if not isinstance(value, str) or not value.strip():
            raise ValidationError(f"{label} is required")
        return value.strip()

    @staticmethod
    def _optional(value, label):
        if value is None:
            return None
        return ProvenanceRecordApplication._required(value, label)

    @staticmethod
    def _timestamp(value, label, *, optional=False):
        if value is None and optional:
            return None
        if isinstance(value, str):
            try:
                value = datetime.fromisoformat(value.replace("Z", "+00:00"))
            except ValueError as exc:
                raise ValidationError(f"{label} must be a valid ISO-8601 timestamp") from exc
        if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
            raise ValidationError(f"{label} must be timezone-aware")
        return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")

    @staticmethod
    def _from_row(row):
        return PersistedProvenance(
            UUID(row[0]), row[1], row[2], row[3], row[4], row[5], row[6]
        )

    def append(
        self, connection, *, source_type, source_ref, derivation_type,
        source_version_ref=None, as_of_at=None, provenance_id=None, now=None,
    ) -> PersistedProvenance:
        source_type = self._required(source_type, "source_type")
        source_ref = self._required(source_ref, "source_ref")
        derivation_type = self._required(derivation_type, "derivation_type")
        source_version_ref = self._optional(source_version_ref, "source_version_ref")
        try:
            identifier = UUID(str(provenance_id)) if provenance_id is not None else new_id()
        except (ValueError, TypeError, AttributeError) as exc:
            raise ValidationError("provenance_id must be a valid UUID") from exc
        as_of = self._timestamp(as_of_at, "as_of_at", optional=True)
        created = self._timestamp(now or datetime.now(timezone.utc), "created_at")
        with connection:
            connection.execute(
                "INSERT INTO provenance_records(provenance_id,source_type,source_ref,"
                "source_version_ref,as_of_at,derivation_type,created_at) VALUES(?,?,?,?,?,?,?)",
                (str(identifier), source_type, source_ref, source_version_ref, as_of,
                 derivation_type, created),
            )
        return PersistedProvenance(
            identifier, source_type, source_ref, source_version_ref, as_of,
            derivation_type, created,
        )

    def get(self, connection, provenance_id) -> PersistedProvenance:
        try:
            key = str(UUID(str(provenance_id)))
        except (ValueError, TypeError, AttributeError) as exc:
            raise ValidationError("Provenance identifier must be a valid UUID") from exc
        row = connection.execute(
            "SELECT provenance_id,source_type,source_ref,source_version_ref,as_of_at,"
            "derivation_type,created_at FROM provenance_records WHERE provenance_id=?",
            (key,),
        ).fetchone()
        if row is None:
            raise ValidationError("Provenance record does not exist")
        return self._from_row(row)

    def list_for_source(self, connection, *, source_type, source_ref) -> tuple[PersistedProvenance, ...]:
        source_type = self._required(source_type, "source_type")
        source_ref = self._required(source_ref, "source_ref")
        rows = connection.execute(
            "SELECT provenance_id,source_type,source_ref,source_version_ref,as_of_at,"
            "derivation_type,created_at FROM provenance_records "
            "WHERE source_type=? AND source_ref=? ORDER BY created_at,provenance_id",
            (source_type, source_ref),
        ).fetchall()
        return tuple(self._from_row(row) for row in rows)
