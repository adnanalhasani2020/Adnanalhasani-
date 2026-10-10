"""Read-only projection of persisted Commerce and Inventory facts."""
from dataclasses import dataclass
from uuid import UUID
from agent_core.shared import ValidationError

@dataclass(frozen=True)
class InventoryPositionFact:
    id: str
    product_id: str | None
    activity_id: str
    offering_id: str | None
    scope_key: str
    state: str
    quantity_minor: int | None
    observed_at: str
    effective_from: str | None
    effective_to: str | None

@dataclass(frozen=True)
class OfferingInventoryContext:
    offering_id: str
    offering_state: str
    effective_from: str
    effective_to: str | None
    product_id: str
    product_name: str | None
    product_state: str | None
    activity_id: str
    activity_state: str | None
    service_id: str | None
    service_state: str | None
    inventory_positions: tuple[InventoryPositionFact, ...]
    integrity_issues: tuple[str, ...]

class CommerceInventoryContextReader:
    """Join persisted Offering, Product, Activity, optional Service and positions.

    This is read-only and deliberately does not compute Availability, aggregate
    quantities, infer sellability, or mutate domain-owned records.
    """
    def read(self, connection, offering_id: str) -> OfferingInventoryContext:
        try:
            key = str(UUID(str(offering_id)))
        except (ValueError, TypeError, AttributeError) as exc:
            raise ValidationError("Offering identifier must be a valid UUID") from exc
        row = connection.execute(
            "SELECT o.offering_id,o.state,o.effective_from,o.effective_to,"
            "o.product_id,p.name,p.state,o.activity_id,a.state,o.service_id,s.state "
            "FROM offerings o LEFT JOIN products p ON p.product_id=o.product_id "
            "LEFT JOIN activities a ON a.activity_id=o.activity_id "
            "LEFT JOIN services s ON s.service_id=o.service_id WHERE o.offering_id=?",
            (key,),
        ).fetchone()
        if row is None:
            raise ValidationError("Offering does not exist")
        (oid, ost, starts, ends, pid, pname, pstate, aid, astate, sid, sstate) = row
        issues = []
        if pstate is None:
            issues.append("product_reference_missing")
        if not isinstance(pname, str) or not pname.strip():
            issues.append("product_name_missing")
        if astate is None:
            issues.append("activity_reference_missing")
        if sid is not None and sstate is None:
            issues.append("service_reference_missing")
        rows = connection.execute(
            "SELECT inventory_position_id,product_id,activity_id,offering_id,"
            "scope_key,state,quantity_minor,observed_at,effective_from,effective_to "
            "FROM inventory_positions WHERE offering_id=? "
            "ORDER BY observed_at,inventory_position_id", (oid,)
        ).fetchall()
        positions = []
        for values in rows:
            (ip_id, ip_pid, ip_aid, ip_oid, scope, state, quantity, observed, ip_start, ip_end) = values
            if ip_aid != aid:
                issues.append(f"inventory_position_activity_mismatch:{ip_id}")
            if ip_pid is not None and ip_pid != pid:
                issues.append(f"inventory_position_product_mismatch:{ip_id}")
            if ip_oid != oid:
                issues.append(f"inventory_position_offering_mismatch:{ip_id}")
            positions.append(InventoryPositionFact(
                ip_id, ip_pid, ip_aid, ip_oid, scope, state, quantity, observed, ip_start, ip_end
            ))
        return OfferingInventoryContext(
            oid, ost, starts, ends, pid,
            pname.strip() if isinstance(pname, str) and pname.strip() else None,
            pstate, aid, astate, sid, sstate, tuple(positions),
            tuple(dict.fromkeys(issues))
        )
