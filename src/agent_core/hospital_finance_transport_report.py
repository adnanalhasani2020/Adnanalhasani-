"""Read-only reconciliation of Hospital outbox and Finance intake transport state.

This report compares delivery identities only. It does not prove settlement,
accounting recognition, ledger persistence, or financial reconciliation.
"""
from __future__ import annotations

from collections import Counter
from typing import Any
import sqlite3


def build_hospital_finance_transport_report(
    hospital_db: sqlite3.Connection,
    finance_db: sqlite3.Connection,
) -> dict[str, Any]:
    """Compare event keys across the two stores without mutating either one.

    Output intentionally excludes event payloads, patient identifiers, payment
    references, and error text. A Finance mapped status means only that a
    source-to-Finance identity mapping exists; it is not proof of ledger posting.
    """
    outbox_rows = hospital_db.execute(
        "SELECT event_key,event_type,status FROM finance_outbox ORDER BY id"
    ).fetchall()
    intake_rows = finance_db.execute(
        "SELECT source_event_key,event_type,status FROM external_event_intake "
        "WHERE source_system='hospital' ORDER BY first_seen_at,source_event_key"
    ).fetchall()

    intake_by_key = {
        row["source_event_key"] if isinstance(row, sqlite3.Row) else row[0]: row
        for row in intake_rows
    }
    outbox_keys = {
        row["event_key"] if isinstance(row, sqlite3.Row) else row[0]
        for row in outbox_rows
    }

    events = []
    for row in outbox_rows:
        if isinstance(row, sqlite3.Row):
            key, event_type, outbox_status = row["event_key"], row["event_type"], row["status"]
        else:
            key, event_type, outbox_status = row[0], row[1], row[2]
        intake = intake_by_key.get(key)
        if intake is None:
            intake_status = None
            disposition = "delivered_without_intake" if outbox_status == "delivered" else "awaiting_intake"
        else:
            intake_status = intake["status"] if isinstance(intake, sqlite3.Row) else intake[2]
            if intake_status == "quarantined":
                disposition = "quarantined"
            elif intake_status == "rejected":
                disposition = "rejected"
            elif intake_status == "mapped":
                disposition = "mapped_source_only"
            else:
                disposition = "received_not_mapped"
        events.append({
            "event_key": key,
            "event_type": event_type,
            "outbox_status": outbox_status,
            "intake_status": intake_status,
            "disposition": disposition,
        })

    intake_without_outbox = []
    for row in intake_rows:
        if isinstance(row, sqlite3.Row):
            key, event_type, status = row["source_event_key"], row["event_type"], row["status"]
        else:
            key, event_type, status = row[0], row[1], row[2]
        if key not in outbox_keys:
            intake_without_outbox.append({
                "event_key": key,
                "event_type": event_type,
                "intake_status": status,
            })

    counts = Counter(event["disposition"] for event in events)
    return {
        "scope": (
            "transport identity/status only; not financial reconciliation, "
            "settlement confirmation, or proof of ledger persistence"
        ),
        "outbox_event_count": len(events),
        "intake_without_outbox_count": len(intake_without_outbox),
        "by_disposition": {
            key: counts.get(key, 0)
            for key in (
                "awaiting_intake",
                "delivered_without_intake",
                "received_not_mapped",
                "mapped_source_only",
                "quarantined",
                "rejected",
            )
        },
        "events": events,
        "intake_without_outbox": intake_without_outbox,
    }
