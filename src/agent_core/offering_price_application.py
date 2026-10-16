"""Audited Offering price windows for the commercial MVP."""
from __future__ import annotations

from datetime import datetime, timezone
from uuid import UUID, uuid4

from agent_core.shared import ValidationError


def _uuid(value, label):
    try:
        return str(UUID(str(value)))
    except (ValueError, TypeError, AttributeError) as exc:
        raise ValidationError(f"{label} must be a valid UUID") from exc


def _instant(value, label):
    if isinstance(value, str):
        try:
            value = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError as exc:
            raise ValidationError(f"{label} must be a valid ISO-8601 timestamp") from exc
    if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
        raise ValidationError(f"{label} must be timezone-aware")
    return value.astimezone(timezone.utc)


def _stamp(value):
    return value.astimezone(timezone.utc).isoformat(timespec="microseconds").replace("+00:00", "Z")


class OfferingPriceApplication:
    """Set an effective price without overlapping price windows or losing audit context."""

    def set_price(
        self, connection, *, offering_id, unit_price_minor, currency_code,
        actor_context_ref, effective_from=None, effective_to=None, now=None,
        offering_price_id=None,
    ) -> dict:
        offering = _uuid(offering_id, "Offering identifier")
        price_id = _uuid(offering_price_id, "Offering Price identifier") if offering_price_id is not None else str(uuid4())
        if type(unit_price_minor) is not int or unit_price_minor < 0:
            raise ValidationError("unit_price_minor must be a non-negative integer")
        if currency_code not in {"SAR", "YER", "USD"}:
            raise ValidationError("Currency must be SAR, YER, or USD")
        if not isinstance(actor_context_ref, str) or not actor_context_ref.strip():
            raise ValidationError("Offering price actor context is required")
        instant = _instant(datetime.now(timezone.utc) if effective_from is None else effective_from, "effective_from")
        end = _instant(effective_to, "effective_to") if effective_to is not None else None
        if end is not None and end <= instant:
            raise ValidationError("effective_to must be later than effective_from")
        recorded = _stamp(_instant(datetime.now(timezone.utc) if now is None else now, "recorded_at"))
        starts = _stamp(instant)
        ends = _stamp(end) if end is not None else None
        actor = actor_context_ref.strip()

        if connection.in_transaction:
            raise ValidationError("Offering price changes require an unowned transaction")
        connection.execute("BEGIN IMMEDIATE")
        try:
            if connection.execute("SELECT 1 FROM offerings WHERE offering_id=?", (offering,)).fetchone() is None:
                raise ValidationError("Offering does not exist")
            existing = connection.execute(
                """SELECT offering_price_id,effective_from,effective_to
                   FROM offering_prices WHERE offering_id=? ORDER BY effective_from""",
                (offering,),
            ).fetchall()
            to_close = []
            for old_id, old_start_raw, old_end_raw in existing:
                old_start = _instant(old_start_raw, "Existing price effective_from")
                old_end = _instant(old_end_raw, "Existing price effective_to") if old_end_raw else None
                overlaps = (end is None or old_start < end) and (old_end is None or instant < old_end)
                if not overlaps:
                    continue
                if old_start < instant and old_end is None:
                    to_close.append(old_id)
                else:
                    # Existing bounded windows are immutable; schedule the new price
                    # at their boundary rather than rewriting a prior decision.
                    raise ValidationError("Offering price window overlaps an existing price window")

            for old_id in to_close:
                old_start_raw = connection.execute(
                    "SELECT effective_from FROM offering_prices WHERE offering_price_id=?", (old_id,)
                ).fetchone()[0]
                connection.execute(
                    "UPDATE offering_prices SET effective_to=? WHERE offering_price_id=? AND effective_to IS NULL",
                    (starts, old_id),
                )
                if connection.execute("SELECT changes()").fetchone()[0] != 1:
                    raise ValidationError("Existing price changed concurrently")
                old_ref = f"offering-price:{old_id}:v1"
                connection.execute(
                    """INSERT INTO domain_history(
                           domain_history_id,owner_domain,target_ref,change_type,historical_at,
                           actor_context_ref,prior_version_ref,current_version_ref,change_payload_ref,created_at
                       ) VALUES(?,?,?,?,?,?,?,?,?,?)""",
                    (str(uuid4()), "commerce", old_id, "offering_price_window_closed",
                     recorded, actor, old_ref, f"offering-price:{old_id}:v2",
                     f"effective_from:{old_start_raw};effective_to:{starts}", recorded),
                )

            connection.execute(
                """INSERT INTO offering_prices(
                       offering_price_id,offering_id,unit_price_minor,currency_code,
                       effective_from,effective_to,actor_context_ref,created_at
                   ) VALUES(?,?,?,?,?,?,?,?)""",
                (price_id, offering, unit_price_minor, currency_code, starts, ends, actor, recorded),
            )
            connection.execute(
                """INSERT INTO domain_history(
                       domain_history_id,owner_domain,target_ref,change_type,historical_at,
                       actor_context_ref,prior_version_ref,current_version_ref,change_payload_ref,created_at
                   ) VALUES(?,?,?,?,?,?,?,?,?,?)""",
                (str(uuid4()), "commerce", price_id, "offering_price_set", recorded, actor,
                 None, f"offering-price:{price_id}:v1",
                 f"offering:{offering};amount_minor:{unit_price_minor};currency:{currency_code};from:{starts};to:{ends or 'open'}",
                 recorded),
            )
            connection.commit()
            return {
                "offering_price_id": price_id, "offering_id": offering,
                "unit_price_minor": unit_price_minor, "currency_code": currency_code,
                "effective_from": starts, "effective_to": ends,
            }
        except BaseException:
            if connection.in_transaction:
                connection.rollback()
            raise
