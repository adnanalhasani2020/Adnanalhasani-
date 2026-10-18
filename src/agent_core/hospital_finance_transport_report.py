"""Read-only reconciliation of Hospital outbox and Finance intake transport state.

This report compares delivery identities only. It does not prove settlement,
accounting recognition, ledger persistence, or financial reconciliation.
"""
from __future__ import annotations

from collections import Counter
from typing import Any
import sqlite3


# Guidance is intentionally operational and non-mutating. The report must never
# turn a transport status into an instruction to post, settle, or acknowledge.
_DISPOSITION_GUIDANCE = {
    "awaiting_intake": (
        "Check the approved consumer/transport path and retry through its "
        "supported recovery procedure; do not mark the outbox event delivered manually."
    ),
    "delivered_without_intake": (
        "Reconcile the delivery acknowledgement against durable Finance intake and "
        "recovery logs before replay; do not assume delivery means acceptance."
    ),
    "event_type_mismatch": (
        "Stop automated recovery and reconcile the outbox and intake event types "
        "against the original source evidence; do not replay, remap, or post financially."
    ),
    "received_not_mapped": (
        "Resolve the source identity using approved Finance mapping; do not create "
        "a payment, settlement, or ledger entry from intake status alone."
    ),
    "mapped_source_only": (
        "Verify Finance payment, settlement, and ledger records independently; a "
        "source mapping is not proof of financial recognition."
    ),
    "quarantined": (
        "Perform manual source-key conflict reconciliation and preserve the existing "
        "mapping; do not overwrite the original event identity."
    ),
    "rejected": (
        "Review the source event against the versioned event contract and correct "
        "the source through an approved process; do not bypass validation."
    ),
    "intake_without_outbox": (
        "Check database selection, retention, and source history for the orphaned "
        "identity; do not delete or financially process it automatically."
    ),
}


def _value(row: Any, key: str, index: int) -> Any:
    """Read a named sqlite row or positional tuple uniformly."""
    return row[key] if isinstance(row, sqlite3.Row) else row[index]


def build_hospital_finance_transport_report(
    hospital_db: sqlite3.Connection,
    finance_db: sqlite3.Connection,
) -> dict[str, Any]:
    """Compare event keys across the two stores without mutating either one.

    Output intentionally excludes event payloads, patient identifiers, payment
    references, and error text. A Finance mapped status means only that a
    source-to-Finance identity mapping exists; it is not proof of ledger posting.
    Matching keys with different event types are surfaced as an explicit
    mismatch rather than being incorrectly reported as mapped or received.
    Each row includes conservative operator guidance, never an automated action.
    """
    outbox_rows = hospital_db.execute(
        "SELECT event_key,event_type,status FROM finance_outbox ORDER BY id"
    ).fetchall()
    intake_rows = finance_db.execute(
        "SELECT source_event_key,event_type,status FROM external_event_intake "
        "WHERE source_system='hospital' ORDER BY first_seen_at,source_event_key"
    ).fetchall()

    intake_by_key = {_value(row, "source_event_key", 0): row for row in intake_rows}
    outbox_keys = {_value(row, "event_key", 0) for row in outbox_rows}

    events = []
    for row in outbox_rows:
        key = _value(row, "event_key", 0)
        event_type = _value(row, "event_type", 1)
        outbox_status = _value(row, "status", 2)
        intake = intake_by_key.get(key)
        intake_type = _value(intake, "event_type", 1) if intake is not None else None
        intake_status = _value(intake, "status", 2) if intake is not None else None

        if intake is None:
            disposition = (
                "delivered_without_intake"
                if outbox_status == "delivered"
                else "awaiting_intake"
            )
        elif event_type != intake_type:
            disposition = "event_type_mismatch"
        elif intake_status == "quarantined":
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
            "intake_event_type": intake_type,
            "intake_status": intake_status,
            "disposition": disposition,
            "operator_guidance": _DISPOSITION_GUIDANCE[disposition],
        })

    intake_without_outbox = []
    for row in intake_rows:
        key = _value(row, "source_event_key", 0)
        event_type = _value(row, "event_type", 1)
        status = _value(row, "status", 2)
        if key not in outbox_keys:
            intake_without_outbox.append({
                "event_key": key,
                "event_type": event_type,
                "intake_status": status,
                "operator_guidance": _DISPOSITION_GUIDANCE["intake_without_outbox"],
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
                "event_type_mismatch",
                "received_not_mapped",
                "mapped_source_only",
                "quarantined",
                "rejected",
            )
        },
        "events": events,
        "intake_without_outbox": intake_without_outbox,
    }
