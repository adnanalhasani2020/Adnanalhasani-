"""Read-only operational context for a persisted Sale and its Offering."""
from dataclasses import dataclass
from uuid import UUID

from agent_core.commerce_inventory_read import (
    CommerceInventoryContextReader,
    OfferingInventoryContext,
)
from agent_core.shared import ValidationError


@dataclass(frozen=True)
class SaleOperationalContext:
    sale_id: str
    sale_state: str
    sale_version: int
    sale_activity_id: str
    offering_context: OfferingInventoryContext
    integrity_issues: tuple[str, ...]


class SaleOperationalContextReader:
    """Provide a coherent read of Sale, Offering and linked Inventory facts.

    This reader exposes persisted states and integrity findings only. It does
    not decide whether a sale is permitted, available, sellable, or fulfillable.
    """

    def __init__(self, offering_reader=None):
        self._offering_reader = offering_reader or CommerceInventoryContextReader()

    def read(self, connection, sale_id: str) -> SaleOperationalContext:
        try:
            key = str(UUID(str(sale_id)))
        except (ValueError, TypeError, AttributeError) as exc:
            raise ValidationError("Sale identifier must be a valid UUID") from exc

        row = connection.execute(
            "SELECT sale_id,state,version_no,activity_id,offering_id "
            "FROM sales WHERE sale_id=?", (key,)
        ).fetchone()
        if row is None:
            raise ValidationError("Sale does not exist")

        persisted_id, state, version, sale_activity_id, offering_id = row
        offering = self._offering_reader.read(connection, offering_id)
        issues = list(offering.integrity_issues)
        if sale_activity_id != offering.activity_id:
            issues.append("sale_offering_activity_mismatch")
        return SaleOperationalContext(
            persisted_id, state, version, sale_activity_id, offering,
            tuple(dict.fromkeys(issues)),
        )
