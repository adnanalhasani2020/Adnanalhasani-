"""Fail-closed read interface for effective inventory quantity within one scope."""
from dataclasses import dataclass
from datetime import datetime, timezone
from enum import Enum

from agent_core.shared import ValidationError


class InventoryQuantityStatus(str, Enum):
    KNOWN = "known"
    UNKNOWN_QUANTITY = "unknown_quantity"
    NO_RECORD = "no_record"
    AMBIGUOUS = "ambiguous"


@dataclass(frozen=True)
class InventoryQuantityResult:
    status: InventoryQuantityStatus
    scope_key: str
    as_of: datetime
    quantity_minor: int | None = None
    inventory_position_id: str | None = None
    observed_at: datetime | None = None


def _instant(value, label):
    if isinstance(value, str):
        try:
            value = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError as exc:
            raise ValidationError(f"{label} must be a valid ISO-8601 timestamp") from exc
    if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
        raise ValidationError(f"{label} must be timezone-aware")
    return value.astimezone(timezone.utc)


def _stored_instant(value):
    if not isinstance(value, str):
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        return None
    return parsed.astimezone(timezone.utc)


class InventoryQuantityReader:
    """Read one scope's latest valid effective quantity; never aggregate scopes.

    Only rows in state='effective' whose observation and effective period cover
    the requested instant are eligible. The latest observed_at wins. A tie at
    that timestamp is ambiguous and fails closed; NULL quantity is unknown,
    not zero, and never falls back to an older quantity.
    """

    def read_quantity(
        self, connection, scope_key: str, *, as_of: datetime | str | None = None,
        offering_id: str | None = None, activity_id: str | None = None,
    ):
        if not isinstance(scope_key, str) or not scope_key.strip():
            raise ValidationError("Inventory scope_key is required")
        instant = _instant(
            datetime.now(timezone.utc) if as_of is None else as_of,
            "Inventory as_of",
        )

        query = (
            "SELECT inventory_position_id, quantity_minor, observed_at, effective_from, effective_to "
            "FROM inventory_positions WHERE scope_key=? AND state='effective'"
        )
        parameters = [scope_key]
        if offering_id is not None:
            query += " AND offering_id=?"
            parameters.append(str(offering_id))
        if activity_id is not None:
            query += " AND activity_id=?"
            parameters.append(str(activity_id))
        rows = connection.execute(query, parameters).fetchall()

        eligible = []
        for position_id, quantity, observed_raw, starts_raw, ends_raw in rows:
            observed = _stored_instant(observed_raw)
            starts = _stored_instant(starts_raw) if starts_raw is not None else None
            ends = _stored_instant(ends_raw) if ends_raw is not None else None
            # Malformed or naive timestamps cannot establish validity.
            if observed is None:
                continue
            if starts_raw is not None and starts is None:
                continue
            if ends_raw is not None and ends is None:
                continue
            if starts is not None and ends is not None and ends < starts:
                continue
            if observed > instant or (starts is not None and starts > instant):
                continue
            if ends is not None and instant >= ends:
                continue
            eligible.append((observed, position_id, quantity))

        if not eligible:
            return InventoryQuantityResult(
                InventoryQuantityStatus.NO_RECORD, scope_key, instant
            )

        latest_observation = max(row[0] for row in eligible)
        latest = [row for row in eligible if row[0] == latest_observation]
        if len(latest) != 1:
            return InventoryQuantityResult(
                InventoryQuantityStatus.AMBIGUOUS, scope_key, instant,
                observed_at=latest_observation,
            )

        observed, position_id, quantity = latest[0]
        if quantity is None:
            return InventoryQuantityResult(
                InventoryQuantityStatus.UNKNOWN_QUANTITY, scope_key, instant,
                inventory_position_id=position_id, observed_at=observed,
            )
        return InventoryQuantityResult(
            InventoryQuantityStatus.KNOWN, scope_key, instant,
            quantity_minor=quantity, inventory_position_id=position_id,
            observed_at=observed,
        )
