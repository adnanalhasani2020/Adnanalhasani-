"""Read-only reconciliation summary for durable external event intake."""
from __future__ import annotations

import sqlite3
from datetime import datetime, timezone


def external_event_intake_report(connection: sqlite3.Connection, *, now: str | None = None) -> dict:
    """Summarize intake states, stale received rows, and recorded conflicts.

    This describes transport/correlation status only; it is not a ledger
    balance, settlement report, or proof that all Hospital activity is accounted.
    """
    instant = now or datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    counts = {
        row[0]: row[1]
        for row in connection.execute(
            "SELECT status,COUNT(*) FROM external_event_intake GROUP BY status"
        )
    }
    conflicts = connection.execute(
        "SELECT COUNT(*) FROM external_event_intake_conflicts"
    ).fetchone()[0]
    unmapped = connection.execute(
        "SELECT COUNT(*) FROM external_event_intake WHERE status IN ('received','rejected','quarantined')"
    ).fetchone()[0]
    oldest = connection.execute(
        "SELECT MIN(first_seen_at) FROM external_event_intake WHERE status='received'"
    ).fetchone()[0]
    stale = connection.execute(
        "SELECT COUNT(*) FROM external_event_intake "
        "WHERE status='received' AND last_seen_at < datetime(?, '-1 day')",
        (instant,),
    ).fetchone()[0]
    return {
        "as_of": instant,
        "by_status": {key: counts.get(key, 0) for key in ("received", "mapped", "rejected", "quarantined")},
        "conflict_count": conflicts,
        "unmapped_or_review_required_count": unmapped,
        "stale_received_over_24h_count": stale,
        "oldest_received_at": oldest,
        "scope_note": "Transport intake only; not proof of settlement, ledger posting, or complete accounting.",
    }
