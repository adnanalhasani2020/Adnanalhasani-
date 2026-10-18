"""Read-only consistency check between persisted Sale versions and Domain History."""
from dataclasses import dataclass
from uuid import UUID

from agent_core.shared import ValidationError


@dataclass(frozen=True)
class SaleHistoryIntegrityResult:
    sale_id: str
    sale_state: str
    sale_version: int
    history_record_count: int
    status: str
    findings: tuple[str, ...]


class SaleHistoryIntegrityAuditor:
    """Check the recorded Sale history chain without repairing or rewriting it.

    Status is 'consistent', 'incomplete' (history is absent/short), or
    'inconsistent' (broken references or more records than the persisted version).
    This is a structural check, not a claim that audit records prove a Sale action.
    """

    def inspect(self, connection, sale_id: str) -> SaleHistoryIntegrityResult:
        try:
            key = str(UUID(str(sale_id)))
        except (ValueError, TypeError, AttributeError) as exc:
            raise ValidationError("Sale identifier must be a valid UUID") from exc

        sale = connection.execute(
            "SELECT sale_id,state,version_no FROM sales WHERE sale_id=?", (key,)
        ).fetchone()
        if sale is None:
            raise ValidationError("Sale does not exist")
        persisted_id, state, version = sale
        rows = connection.execute(
            "SELECT prior_version_ref,current_version_ref,change_payload_ref FROM domain_history "
            "WHERE target_ref=? AND owner_domain='commerce' "
            "AND change_type='sale_state_transition' ORDER BY rowid",
            (persisted_id,),
        ).fetchall()

        findings = []
        if not rows:
            findings.append("sale_history_missing")
        elif len(rows) < version:
            findings.append("sale_history_shorter_than_sale_version")
        elif len(rows) > version:
            findings.append("sale_history_exceeds_sale_version")

        allowed_next = {
            "initiated": {"confirmed", "cancelled"},
            "confirmed": {"fulfilled", "completed", "cancelled"},
            "fulfilled": {"returned"},
            "completed": {"returned"},
            "cancelled": set(),
            "returned": set(),
        }
        previous_state = None
        for index, (prior_ref, current_ref, payload_ref) in enumerate(rows, start=1):
            expected_current = f"sale-state:{persisted_id}:v{index}"
            if current_ref != expected_current:
                findings.append(f"history_current_version_mismatch:{index}")
            if not isinstance(payload_ref, str) or not payload_ref.startswith("state:"):
                findings.append(f"history_state_payload_invalid:{index}")
                current_state = None
            else:
                current_state = payload_ref.removeprefix("state:")
                if current_state not in allowed_next:
                    findings.append(f"history_state_payload_invalid:{index}")
                    current_state = None

            expected_prior = None if index == 1 else rows[index - 2][1]
            if prior_ref != expected_prior:
                findings.append(f"history_version_chain_break:{index}")

            if index == 1:
                if current_state is not None and current_state != "initiated":
                    findings.append("history_initial_state_mismatch")
            elif previous_state is not None and current_state is not None:
                if current_state not in allowed_next[previous_state]:
                    findings.append(f"history_state_transition_invalid:{index}")
            previous_state = current_state

        if rows and len(rows) == version:
            final_payload = rows[-1][2]
            if final_payload != f"state:{state}":
                # Legacy persisted 'completed' and its matching historical payload
                # remain valid; no silent state normalization is applied here.
                findings.append("history_final_state_mismatch")

        findings = tuple(dict.fromkeys(findings))
        inconsistent_prefixes = (
            "history_current_version_mismatch:",
            "history_state_payload_invalid:",
            "history_initial_prior_version_unexpected",
            "history_initial_state_mismatch",
            "history_version_chain_break:",
            "history_state_transition_invalid:",
            "history_final_state_mismatch",
            "sale_history_exceeds_sale_version",
        )
        if any(
            item.startswith(inconsistent_prefixes)
            for item in findings
        ):
            status = "inconsistent"
        elif findings:
            status = "incomplete"
        else:
            status = "consistent"
        return SaleHistoryIntegrityResult(
            persisted_id, state, version, len(rows), status, findings
        )
