"""Compatibility entry point for the read-only intake reconciliation report."""
from agent_core.external_event_intake import build_intake_reconciliation_report


def external_event_intake_report(connection, *, now=None, stale_after_hours=24):
    return build_intake_reconciliation_report(
        connection, now=now, stale_after_hours=stale_after_hours
    )
