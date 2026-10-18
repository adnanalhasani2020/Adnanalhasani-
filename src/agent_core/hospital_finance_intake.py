"""Durable, idempotent intake for validated Hospital finance outbox events.

This boundary records source events only. It deliberately creates no Finance
Payment, Settlement, FinancialTransaction, or LedgerEntry and never acknowledges
the Hospital outbox.
"""
from __future__ import annotations

import hashlib
import json
import sqlite3
from datetime import datetime, timezone
from uuid import uuid4

from hospital.finance_adapter import validate_hospital_finance_event


def _now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def _canonical_fingerprint(payload_json: str) -> str:
    try:
        payload = json.loads(payload_json)
    except (TypeError, json.JSONDecodeError) as exc:
        raise ValueError("payload_json must be valid JSON") from exc
    if not isinstance(payload, dict):
        raise ValueError("event payload must be a JSON object")
    canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


class _Atomic:
    def __init__(self, connection: sqlite3.Connection):
        self.connection = connection

    def __enter__(self):
        self.nested = self.connection.in_transaction
        self.name = "hospital_intake_" + uuid4().hex
        if self.nested:
            self.connection.execute(f"SAVEPOINT {self.name}")
        else:
            self.connection.execute("BEGIN IMMEDIATE")
        return self

    def __exit__(self, exc_type, exc, tb):
        if exc_type is not None:
            if self.nested:
                self.connection.execute(f"ROLLBACK TO SAVEPOINT {self.name}")
                self.connection.execute(f"RELEASE SAVEPOINT {self.name}")
            else:
                self.connection.rollback()
            return False
        if self.nested:
            self.connection.execute(f"RELEASE SAVEPOINT {self.name}")
        else:
            self.connection.commit()
        return False


def ingest_hospital_finance_event(connection: sqlite3.Connection, outbox_row: dict) -> dict:
    """Validate and durably accept an outbox row idempotently.

    Returns accepted, duplicate, or quarantined plus the intake ID.
    Conflicting reuse never overwrites the original fingerprint. For a record
    already mapped to Finance, its mapping is retained and the conflict is
    audited rather than clearing an existing durable relationship.
    """
    event = validate_hospital_finance_event(outbox_row)
    digest = _canonical_fingerprint(outbox_row["payload_json"])
    now = _now()
    source_key = event.event_key
    aggregate_type = outbox_row["aggregate_type"]
    aggregate_id = str(outbox_row["aggregate_id"])

    with _Atomic(connection):
        existing = connection.execute(
            "SELECT external_event_intake_id,payload_sha256,status "
            "FROM external_event_intake WHERE source_system='hospital' AND source_event_key=?",
            (source_key,),
        ).fetchone()

        if existing is None:
            intake_id = str(uuid4())
            connection.execute(
                "INSERT INTO external_event_intake("
                "external_event_intake_id,source_system,source_event_key,event_type,"
                "source_aggregate_type,source_aggregate_id,payload_sha256,amount_minor,"
                "currency_code,occurred_at,status,first_seen_at,last_seen_at"
                ") VALUES(?,?,?,?,?,?,?,?,?,'received',?,?)",
                (intake_id, "hospital", source_key, event.event_type, aggregate_type,
                 aggregate_id, digest, event.amount_minor, event.currency,
                 event.occurred_at, now, now),
            )
            return {"outcome": "accepted", "intake_id": intake_id, "status": "received"}

        intake_id, original_digest, status = existing
        if original_digest == digest:
            connection.execute(
                "UPDATE external_event_intake SET last_seen_at=? WHERE external_event_intake_id=?",
                (now, intake_id),
            )
            return {"outcome": "duplicate", "intake_id": intake_id, "status": status}

        connection.execute(
            "INSERT INTO external_event_intake_conflicts("
            "external_event_intake_conflict_id,external_event_intake_id,"
            "observed_payload_sha256,observed_at,reason) VALUES(?,?,?,?,?)",
            (str(uuid4()), intake_id, digest, now,
             "source event key reused with a different canonical payload fingerprint"),
        )
        if status != "mapped":
            connection.execute(
                "UPDATE external_event_intake SET status='quarantined',last_error=?,last_seen_at=? "
                "WHERE external_event_intake_id=? AND status<>'quarantined'",
                ("conflicting payload fingerprint for existing source event key", now, intake_id),
            )
            connection.execute(
                "UPDATE external_event_intake SET last_seen_at=? WHERE external_event_intake_id=?",
                (now, intake_id),
            )
            status = "quarantined"
        else:
            connection.execute(
                "UPDATE external_event_intake SET last_error=?,last_seen_at=? "
                "WHERE external_event_intake_id=?",
                ("conflicting payload fingerprint observed after Finance mapping; mapping retained for review",
                 now, intake_id),
            )
        return {"outcome": "quarantined", "intake_id": intake_id, "status": status}
