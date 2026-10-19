"""Read-only consistency check between persisted Invoice versions and Domain History."""
from dataclasses import dataclass
from datetime import datetime
from uuid import UUID

from agent_core.shared import ValidationError


@dataclass(frozen=True)
class InvoiceHistoryIntegrityResult:
    invoice_id: str
    invoice_state: str
    invoice_version: int
    history_record_count: int
    status: str
    findings: tuple[str, ...]


class InvoiceHistoryIntegrityAuditor:
    """Inspect invoice state history without repairing or rewriting persisted data.

    'incomplete' means history is absent or shorter than the current version.
    'inconsistent' means references, state transitions, or the final state disagree.
    """

    _ALLOWED_NEXT = {
        "issued": {"void", "settled"},
        "void": set(),
        "settled": set(),
    }

    def inspect(self, connection, invoice_id: str) -> InvoiceHistoryIntegrityResult:
        try:
            key = str(UUID(str(invoice_id)))
        except (ValueError, TypeError, AttributeError) as exc:
            raise ValidationError("Invoice identifier must be a valid UUID") from exc

        invoice = connection.execute(
            "SELECT invoice_id,state,version_no FROM invoices WHERE invoice_id=?", (key,)
        ).fetchone()
        if invoice is None:
            raise ValidationError("Invoice does not exist")
        persisted_id, state, version = invoice
        rows = connection.execute(
            "SELECT actor_context_ref,historical_at,prior_version_ref,current_version_ref,change_payload_ref "
            "FROM domain_history WHERE target_ref=? AND owner_domain='commerce' "
            "AND change_type='invoice_state_transition' ORDER BY rowid",
            (persisted_id,),
        ).fetchall()

        findings = []
        if not rows:
            findings.append("invoice_history_missing")
        elif len(rows) < version:
            findings.append("invoice_history_shorter_than_invoice_version")
        elif len(rows) > version:
            findings.append("invoice_history_exceeds_invoice_version")

        previous_state = None
        previous_timestamp = None
        for index, (actor_ref, historical_at, prior_ref, current_ref, payload_ref) in enumerate(rows, start=1):
            if not isinstance(actor_ref, str) or not actor_ref.strip():
                findings.append(f"history_actor_context_missing:{index}")

            try:
                parsed_timestamp = datetime.fromisoformat(
                    historical_at.replace("Z", "+00:00")
                ) if isinstance(historical_at, str) else None
                if (parsed_timestamp is None or parsed_timestamp.tzinfo is None
                        or parsed_timestamp.utcoffset() is None):
                    raise ValueError("history timestamp must be timezone-aware")
            except (ValueError, TypeError, AttributeError):
                findings.append(f"history_timestamp_invalid:{index}")
                parsed_timestamp = None
            if parsed_timestamp is not None:
                if previous_timestamp is not None and parsed_timestamp < previous_timestamp:
                    findings.append(f"history_timestamps_not_chronological:{index}")
                previous_timestamp = parsed_timestamp

            if current_ref != f"invoice-state:{persisted_id}:v{index}":
                findings.append(f"history_current_version_mismatch:{index}")
            expected_prior = None if index == 1 else rows[index - 2][1]
            if prior_ref != expected_prior:
                findings.append(f"history_version_chain_break:{index}")

            if not isinstance(payload_ref, str) or not payload_ref.startswith("state:"):
                findings.append(f"history_state_payload_invalid:{index}")
                current_state = None
            else:
                current_state = payload_ref.removeprefix("state:")
                if current_state not in self._ALLOWED_NEXT:
                    findings.append(f"history_state_payload_invalid:{index}")
                    current_state = None

            if index == 1:
                if current_state is not None and current_state != "issued":
                    findings.append("history_initial_state_mismatch")
            elif previous_state is not None and current_state is not None:
                if current_state not in self._ALLOWED_NEXT[previous_state]:
                    findings.append(f"history_state_transition_invalid:{index}")
            previous_state = current_state

        if rows and len(rows) == version and rows[-1][2] != f"state:{state}":
            findings.append("history_final_state_mismatch")

        findings = tuple(dict.fromkeys(findings))
        inconsistent_prefixes = (
            "history_current_version_mismatch:",
            "history_version_chain_break:",
            "history_state_payload_invalid:",
            "history_initial_state_mismatch",
            "history_state_transition_invalid:",
            "history_final_state_mismatch",
            "history_actor_context_missing:",
            "history_timestamp_invalid:",
            "history_timestamps_not_chronological:",
            "invoice_history_exceeds_invoice_version",
        )
        if any(item.startswith(inconsistent_prefixes) for item in findings):
            status = "inconsistent"
        elif findings:
            status = "incomplete"
        else:
            status = "consistent"

        return InvoiceHistoryIntegrityResult(
            persisted_id, state, version, len(rows), status, findings
        )
