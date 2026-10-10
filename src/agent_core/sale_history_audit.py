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
            "SELECT prior_version_ref,current_version_ref FROM domain_history "
            "WHERE target_ref=? AND owner_domain='Commerce' ORDER BY rowid",
            (persisted_id,),
        ).fetchall()

        findings = []
        if not rows:
            findings.append("sale_history_missing")
        elif len(rows) < version:
            findings.append("sale_history_shorter_than_sale_version")
        elif len(rows) > version:
            findings.append("sale_history_exceeds_sale_version")

        for index, (prior_ref, current_ref) in enumerate(rows):
            if current_ref is None or not str(current_ref).strip():
                findings.append(f"history_current_version_missing:{index + 1}")
            if index == 0:
                if prior_ref is not None:
                    findings.append("history_initial_prior_version_unexpected")
            elif prior_ref != rows[index - 1][1]:
                findings.append(f"history_version_chain_break:{index + 1}")

        findings = tuple(dict.fromkeys(findings))
        if any(item.startswith(("history_current_version_missing", "history_initial_prior_version_unexpected", "history_version_chain_break", "sale_history_exceeds_sale_version")) for item in findings):
            status = "inconsistent"
        elif findings:
            status = "incomplete"
        else:
            status = "consistent"
        return SaleHistoryIntegrityResult(
            persisted_id, state, version, len(rows), status, findings
        )
