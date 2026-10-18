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
