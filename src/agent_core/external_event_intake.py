"""Durable, idempotent intake for validated external Hospital finance events.

This boundary records transport identity only. It does not create Finance entities,
payments, settlements, financial transactions, or ledger entries.
"""
from __future__ import annotations

import hashlib
import json
import sqlite3
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Mapping

from agent_core.shared import ValidationError


@dataclass(frozen=True)
class IntakeResult:
    outcome: str
    intake_id: str
    status: str
    payload_sha256: str


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def _event_record(event: Any) -> dict[str, Any]:
    """Build a deterministic, privacy-minimized fingerprint record."""
    event_type = event.event_type
    if event_type == "hospital.invoice.created":
        aggregate_type = "invoice"
        aggregate_id = str(event.hospital_invoice_id)
    elif event_type == "hospital.payment.received":
        aggregate_type = "payment"
        if event.hospital_payment_id is None:
            raise ValueError("validated payment event requires hospital_payment_id")
        aggregate_id = str(event.hospital_payment_id)
    else:
        raise ValueError("unsupported validated Hospital finance event type")

    record = {
        "source_system": "hospital",
        "source_event_key": event.event_key,
        "event_type": event_type,
        "source_aggregate_type": aggregate_type,
        "source_aggregate_id": aggregate_id,
        "hospital_invoice_id": event.hospital_invoice_id,
        "hospital_payment_id": event.hospital_payment_id,
        "amount_minor": event.amount_minor,
        "currency_code": event.currency,
        "occurred_at": event.occurred_at,
        "invoice_number": event.invoice_number,
        "payment_method": event.payment_method,
    }
    return record


def _fingerprint(record: Mapping[str, Any]) -> str:
    canonical = json.dumps(
        record, sort_keys=True, separators=(",", ":"), ensure_ascii=False
    ).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest()


class ExternalEventIntake:
    """Persist validated event identity with idempotency and collision quarantine."""

    def ingest(self, db: sqlite3.Connection, event: Any, *, now: str | None = None) -> IntakeResult:
        record = _event_record(event)
        digest = _fingerprint(record)
        observed_at = now or _utc_now()
        savepoint = f"external_intake_{uuid.uuid4().hex}"
        db.execute(f"SAVEPOINT {savepoint}")
        try:
            existing = db.execute(
                "SELECT external_event_intake_id,payload_sha256,status "
                "FROM external_event_intake "
                "WHERE source_system='hospital' AND source_event_key=?",
                (record["source_event_key"],),
            ).fetchone()

            if existing is None:
                intake_id = str(uuid.uuid4())
                db.execute(
                    "INSERT INTO external_event_intake("
                    "external_event_intake_id,source_system,source_event_key,event_type,"
                    "source_aggregate_type,source_aggregate_id,payload_sha256,amount_minor,"
                    "currency_code,occurred_at,status,first_seen_at,last_seen_at"
                    ") VALUES(?,?,?,?,?,?,?,?,?,?,'received',?,?)",
                    (
                        intake_id,
                        "hospital",
                        record["source_event_key"],
                        record["event_type"],
                        record["source_aggregate_type"],
                        record["source_aggregate_id"],
                        digest,
                        record["amount_minor"],
                        record["currency_code"],
                        record["occurred_at"],
                        observed_at,
                        observed_at,
                    ),
                )
                db.execute(f"RELEASE SAVEPOINT {savepoint}")
                return IntakeResult("received", intake_id, "received", digest)

            intake_id, original_digest, status = existing
            if original_digest == digest:
                db.execute(
                    "UPDATE external_event_intake SET last_seen_at=? "
                    "WHERE external_event_intake_id=?",
                    (observed_at, intake_id),
                )
                db.execute(f"RELEASE SAVEPOINT {savepoint}")
                return IntakeResult("duplicate", intake_id, status, digest)

            db.execute(
                "INSERT INTO external_event_intake_conflicts("
                "external_event_intake_conflict_id,external_event_intake_id,"
                "observed_payload_sha256,observed_at,reason) VALUES(?,?,?,?,?)",
                (
                    str(uuid.uuid4()),
                    intake_id,
                    digest,
                    observed_at,
                    "source event key reused with different validated content",
                ),
            )
            # Never erase an established Finance mapping to represent a collision.
            # Unmapped records are quarantined; mapped records retain their mapping
            # and are surfaced by the conflict report for explicit reconciliation.
            if status != "mapped":
                db.execute(
                    "UPDATE external_event_intake SET status='quarantined',"
                    "last_seen_at=?,last_error=? WHERE external_event_intake_id=?",
                    (
                        observed_at,
                        "source event key reused with different validated content",
                        intake_id,
                    ),
                )
                status = "quarantined"
            else:
                db.execute(
                    "UPDATE external_event_intake SET last_seen_at=?,last_error=? "
                    "WHERE external_event_intake_id=?",
                    (
                        observed_at,
                        "conflicting event observed after Finance mapping; manual reconciliation required",
                        intake_id,
                    ),
                )
            db.execute(f"RELEASE SAVEPOINT {savepoint}")
            return IntakeResult("conflict", intake_id, status, digest)
        except Exception:
            db.execute(f"ROLLBACK TO SAVEPOINT {savepoint}")
            db.execute(f"RELEASE SAVEPOINT {savepoint}")
            raise


    def map_to_finance_entity(
        self,
        db: sqlite3.Connection,
        *,
        source_event_key: str,
        finance_entity_type: str,
        finance_entity_id: str,
        now: str | None = None,
    ) -> IntakeResult:
        """Map an intake event only to a matching, persisted Finance lifecycle record.

        This operation records source correlation only. It does not create or
        complete a Payment, settle an Obligation, recognize a transaction, or
        post a LedgerEntry. Invoice events may map to an Obligation; payment
        events may map to a Payment. Unsupported lifecycle shortcuts are rejected.
        """
        if not isinstance(source_event_key, str) or not source_event_key.strip():
            raise ValidationError("source_event_key is required")
        if finance_entity_type not in {"obligation", "payment"}:
            raise ValidationError("Hospital events may map only to Finance Obligation or Payment")
        try:
            target_id = str(uuid.UUID(str(finance_entity_id)))
        except (ValueError, TypeError, AttributeError) as exc:
            raise ValidationError("Finance entity identifier must be a valid UUID") from exc

        stamp = now or _utc_now()
        try:
            parsed_stamp = datetime.fromisoformat(stamp.replace("Z", "+00:00"))
        except (ValueError, AttributeError) as exc:
            raise ValidationError("Mapping timestamp must be a valid ISO-8601 timestamp") from exc
        if parsed_stamp.tzinfo is None or parsed_stamp.utcoffset() is None:
            raise ValidationError("Mapping timestamp must be timezone-aware")
        stamp = parsed_stamp.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")

        savepoint = f"external_map_{uuid.uuid4().hex}"
        db.execute(f"SAVEPOINT {savepoint}")
        try:
            row = db.execute(
                "SELECT external_event_intake_id,event_type,amount_minor,currency_code,status,"
                "payload_sha256,finance_entity_type,finance_entity_id "
                "FROM external_event_intake WHERE source_system='hospital' AND source_event_key=?",
                (source_event_key.strip(),),
            ).fetchone()
            if row is None:
                raise ValidationError("Hospital source event has not been accepted into Finance intake")

            intake_id, event_type, amount_minor, currency_code, status, digest, mapped_type, mapped_id = row
            expected_type = {
                "hospital.invoice.created": "obligation",
                "hospital.payment.received": "payment",
            }.get(event_type)
            if expected_type != finance_entity_type:
                raise ValidationError("Finance target type does not match the Hospital event lifecycle")
            if db.execute(
                "SELECT 1 FROM external_event_intake_conflicts WHERE external_event_intake_id=? LIMIT 1",
                (intake_id,),
            ).fetchone() is not None:
                raise ValidationError("Conflicted Hospital source events require manual reconciliation")
            if status == "mapped":
                if mapped_type == finance_entity_type and mapped_id == target_id:
                    db.execute(
                        "UPDATE external_event_intake SET last_seen_at=? WHERE external_event_intake_id=?",
                        (stamp, intake_id),
                    )
                    db.execute(f"RELEASE SAVEPOINT {savepoint}")
                    return IntakeResult("duplicate", intake_id, "mapped", digest)
                raise ValidationError("Hospital source event is already mapped to a different Finance target")
            if status != "received":
                raise ValidationError("Only a clean received Hospital event can be mapped")

            if finance_entity_type == "obligation":
                target = db.execute(
                    "SELECT amount_minor,currency_code,state FROM obligations WHERE obligation_id=?",
                    (target_id,),
                ).fetchone()
                if target is None:
                    raise ValidationError("Mapped Finance Obligation does not exist")
                target_amount, target_currency, target_state = target
                if target_state == "cancelled":
                    raise ValidationError("Cancelled Finance Obligation cannot be mapped")
            else:
                target = db.execute(
                    "SELECT amount_minor,currency_code,state FROM payments WHERE payment_id=?",
                    (target_id,),
                ).fetchone()
                if target is None:
                    raise ValidationError("Mapped Finance Payment does not exist")
                target_amount, target_currency, target_state = target
                if target_state in {"failed", "cancelled"}:
                    raise ValidationError("Failed or cancelled Finance Payment cannot be mapped")

            if int(target_amount) != int(amount_minor) or target_currency != currency_code:
                raise ValidationError("Hospital event amount/currency does not match the Finance target")

            db.execute(
                "UPDATE external_event_intake SET status='mapped',finance_entity_type=?,"
                "finance_entity_id=?,mapped_at=?,last_seen_at=?,last_error=NULL "
                "WHERE external_event_intake_id=? AND status='received'",
                (finance_entity_type, target_id, stamp, stamp, intake_id),
            )
            if db.execute("SELECT changes()").fetchone()[0] != 1:
                raise ValidationError("Hospital event mapping changed concurrently; reload and reconcile")
            db.execute(f"RELEASE SAVEPOINT {savepoint}")
            return IntakeResult("mapped", intake_id, "mapped", digest)
        except Exception:
            db.execute(f"ROLLBACK TO SAVEPOINT {savepoint}")
            db.execute(f"RELEASE SAVEPOINT {savepoint}")
            raise


def build_intake_reconciliation_report(
    db: sqlite3.Connection, *, now: str | None = None, stale_after_hours: int = 24
) -> dict[str, Any]:
    """Return a read-only operational summary; this is not a financial reconciliation."""
    if stale_after_hours < 0:
        raise ValueError("stale_after_hours must be non-negative")
    reference = now or _utc_now()
    counts = {
        row[0]: row[1]
        for row in db.execute(
            "SELECT status,COUNT(*) FROM external_event_intake GROUP BY status"
        )
    }
    conflict_count = db.execute(
        "SELECT COUNT(*) FROM external_event_intake_conflicts"
    ).fetchone()[0]
    conflicted_sources = db.execute(
        "SELECT COUNT(DISTINCT external_event_intake_id) "
        "FROM external_event_intake_conflicts"
    ).fetchone()[0]
    stale_received = db.execute(
        "SELECT COUNT(*) FROM external_event_intake "
        "WHERE status='received' "
        "AND julianday(?) - julianday(last_seen_at) > (? / 24.0)",
        (reference, stale_after_hours),
    ).fetchone()[0]
    mapped_with_conflicts = db.execute(
        "SELECT COUNT(DISTINCT i.external_event_intake_id) "
        "FROM external_event_intake AS i "
        "JOIN external_event_intake_conflicts AS c "
        "ON c.external_event_intake_id=i.external_event_intake_id "
        "WHERE i.status='mapped'"
    ).fetchone()[0]
    return {
        "as_of": reference,
        "scope": "transport intake only; not accounting or ledger reconciliation",
        "by_status": {
            key: counts.get(key, 0)
            for key in ("received", "mapped", "rejected", "quarantined")
        },
        "stale_received": stale_received,
        "conflict_observations": conflict_count,
        "sources_with_conflicts": conflicted_sources,
        "mapped_sources_with_conflicts": mapped_with_conflicts,
    }
