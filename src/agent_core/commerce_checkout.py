"""Stock-gated, idempotent commercial checkout for the first product MVP.

This service is deliberately separate from the general Sale API. It only sells
stock-tracked Offerings for which the database proves a known, effective,
unambiguous quantity and an effective persisted price. All checkout writes use
one SQLite transaction. InventoryPosition remains an observation; stock effects
are append-only InventoryMovement records.
"""
from __future__ import annotations

import hashlib
import json
import sqlite3
from datetime import datetime, timedelta, timezone
from uuid import UUID, uuid4

from agent_core.application import SaleApplication
from agent_core.inventory_read import InventoryQuantityReader, InventoryQuantityStatus
from agent_core.shared import ValidationError


def _uuid(value, label: str) -> str:
    try:
        return str(UUID(str(value)))
    except (ValueError, TypeError, AttributeError) as exc:
        raise ValidationError(f"{label} must be a valid UUID") from exc


def _instant(value, label: str) -> datetime:
    if isinstance(value, str):
        try:
            value = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError as exc:
            raise ValidationError(f"{label} must be a valid ISO-8601 timestamp") from exc
    if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
        raise ValidationError(f"{label} must be timezone-aware")
    return value.astimezone(timezone.utc)


def _stamp(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat(timespec="microseconds").replace("+00:00", "Z")


def _hash_request(value: dict) -> str:
    raw = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False)
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


class CommercialCheckoutApplication:
    """Read genuinely sellable offerings and execute one stock-safe product sale."""

    def __init__(self, sale_application=None, quantity_reader=None):
        self._sales = sale_application or SaleApplication()
        self._quantities = quantity_reader or InventoryQuantityReader()

    def list_sellable_offerings(
        self, connection, *, activity_id, scope_key, location_ref=None, as_of=None
    ) -> tuple[dict, ...]:
        """Return only active offerings with one effective price and known positive stock."""
        activity_key = _uuid(activity_id, "Activity identifier")
        if not isinstance(scope_key, str) or not scope_key.strip():
            raise ValidationError("Inventory scope_key is required")
        if location_ref is not None and (not isinstance(location_ref, str) or not location_ref.strip()):
            raise ValidationError("Inventory location_ref must be a non-empty string")
        instant = _instant(datetime.now(timezone.utc) if as_of is None else as_of, "as_of")
        stamp = _stamp(instant)
        rows = connection.execute(
            """SELECT o.offering_id, o.product_id, p.name, o.activity_id,
                      o.effective_from, o.effective_to
               FROM offerings o
               JOIN products p ON p.product_id=o.product_id
               JOIN activities a ON a.activity_id=o.activity_id
               WHERE o.activity_id=? AND o.state='active'
                 AND p.state='active' AND a.state='active'
               ORDER BY p.name, o.offering_id""",
            (activity_key,),
        ).fetchall()
        result = []
        for offering_id, product_id, name, offering_activity, starts_raw, ends_raw in rows:
            starts = _instant(starts_raw, "Offering effective_from")
            ends = _instant(ends_raw, "Offering effective_to") if ends_raw is not None else None
            if starts > instant or (ends is not None and instant >= ends):
                continue
            price = self._effective_price(connection, offering_id, instant)
            if price is None:
                continue
            quantity = self._read_stock(
                connection, offering_id, product_id, offering_activity, scope_key,
                location_ref, instant,
            )
            if quantity is None or quantity["available_quantity"] <= 0:
                continue
            result.append({
                "offering_id": offering_id,
                "product_id": product_id,
                "product_name": name,
                "activity_id": offering_activity,
                "unit_price_minor": price["unit_price_minor"],
                "currency_code": price["currency_code"],
                "available_quantity": quantity["available_quantity"],
                "inventory_position_id": quantity["inventory_position_id"],
                "scope_key": scope_key,
                "location_ref": location_ref,
            })
        return tuple(result)

    def _effective_price(self, connection, offering_id: str, instant: datetime):
        rows = connection.execute(
            """SELECT offering_price_id, unit_price_minor, currency_code,
                      effective_from, effective_to
               FROM offering_prices WHERE offering_id=?
               ORDER BY effective_from, offering_price_id""",
            (offering_id,),
        ).fetchall()
        active = []
        for price_id, amount, currency, starts_raw, ends_raw in rows:
            starts = _instant(starts_raw, "Offering price effective_from")
            ends = _instant(ends_raw, "Offering price effective_to") if ends_raw is not None else None
            if starts <= instant and (ends is None or instant < ends):
                active.append((price_id, amount, currency))
        # Overlapping price windows are a data integrity error, not a ranking problem.
        if len(active) > 1:
            raise ValidationError("Offering has conflicting effective prices; explicit reconciliation is required")
        if not active:
            return None
        _, amount, currency = active[0]
        if type(amount) is not int or amount < 0 or currency not in {"SAR", "YER", "USD"}:
            raise ValidationError("Offering price is invalid")
        return {"unit_price_minor": amount, "currency_code": currency}

    def _read_stock(
        self, connection, offering_id, product_id, activity_id, scope_key,
        location_ref, instant,
    ):
        result = self._quantities.read_quantity(
            connection, scope_key, as_of=instant, offering_id=offering_id,
            activity_id=activity_id, product_id=product_id,
            location_ref=location_ref, require_no_location=location_ref is None,
        )
        if result.status is not InventoryQuantityStatus.KNOWN or result.quantity_minor is None:
            return None
        # The observation is the baseline. Refuse equal-time cases because order
        # between a snapshot and a movement cannot be proved from timestamps alone.
        if result.observed_at is None or result.observed_at >= instant:
            return None
        movements = connection.execute(
            """SELECT quantity_delta, occurred_at FROM inventory_movements
               WHERE inventory_position_id=? AND activity_id=? AND product_id=?
                 AND offering_id=? AND scope_key=? AND location_ref IS ?""",
            (result.inventory_position_id, activity_id, product_id, offering_id, scope_key, location_ref),
        ).fetchall()
        delta = 0
        for change, occurred_raw in movements:
            occurred = _instant(occurred_raw, "Inventory movement occurred_at")
            if result.observed_at < occurred <= instant:
                delta += change
        available = result.quantity_minor + delta
        if type(available) is not int or available < 0:
            raise ValidationError("Inventory movement history conflicts with the effective stock observation")
        return {
            "available_quantity": available,
            "inventory_position_id": result.inventory_position_id,
            "observed_at": _stamp(result.observed_at),
        }

    def checkout(
        self, connection, *, actor_person_id, activity_id, offering_id, quantity,
        scope_key, location_ref, idempotency_key, sale_id,
        create_sale_grant_id=None, confirm_sale_grant_id=None, fulfill_sale_grant_id=None,
        session_token=None, now=None,
    ) -> dict:
        """Atomically fulfill a sale, snapshot its price, and record its stock movement.

        Grant scopes follow SaleApplication's current contract: create grant scope is
        the Offering; confirm/fulfill grant scopes are the preselected Sale UUID.
        """
        if connection.in_transaction:
            raise ValidationError("Commercial checkout requires an unowned SQLite transaction")
        actor = _uuid(actor_person_id, "Actor identifier")
        activity = _uuid(activity_id, "Activity identifier")
        offering = _uuid(offering_id, "Offering identifier")
        sale_key = _uuid(sale_id, "Sale identifier")
        if type(quantity) is not int or quantity <= 0:
            raise ValidationError("Sale quantity must be a positive integer")
        if not isinstance(scope_key, str) or not scope_key.strip():
            raise ValidationError("Inventory scope_key is required")
        if location_ref is not None and (not isinstance(location_ref, str) or not location_ref.strip()):
            raise ValidationError("Inventory location_ref must be a non-empty string")
        if not isinstance(idempotency_key, str) or not idempotency_key.strip() or len(idempotency_key.strip()) > 200:
            raise ValidationError("Checkout idempotency_key must contain 1–200 non-space characters")
        instant = _instant(datetime.now(timezone.utc) if now is None else now, "Checkout timestamp")
        stamp = _stamp(instant)
        request = {
            "actor_person_id": actor, "activity_id": activity, "offering_id": offering,
            "quantity": quantity, "scope_key": scope_key.strip(),
            "location_ref": location_ref.strip() if location_ref is not None else None,
            "sale_id": sale_key,
            "create_sale_grant_id": _uuid(create_sale_grant_id, "Create Sale grant") if create_sale_grant_id is not None else None,
            "confirm_sale_grant_id": _uuid(confirm_sale_grant_id, "Confirm Sale grant") if confirm_sale_grant_id is not None else None,
            "fulfill_sale_grant_id": _uuid(fulfill_sale_grant_id, "Fulfill Sale grant") if fulfill_sale_grant_id is not None else None,
        }
        fingerprint = _hash_request(request)
        key = idempotency_key.strip()
        try:
            connection.execute("BEGIN IMMEDIATE")
            grant_values = (
                request["create_sale_grant_id"], request["confirm_sale_grant_id"],
                request["fulfill_sale_grant_id"],
            )
            if all(value is None for value in grant_values):
                if not isinstance(session_token, str) or not session_token:
                    raise ValidationError("Authenticated checkout session is required")
                token_hash = hashlib.sha256(session_token.encode("utf-8")).hexdigest()
                session_rows = connection.execute(
                    """SELECT s.person_id, s.expires_at, p.state, a.state, c.state
                       FROM commercial_sessions s
                       JOIN persons p ON p.person_id=s.person_id
                       JOIN access_accounts a ON a.person_id=s.person_id
                       JOIN commercial_credentials c ON c.person_id=s.person_id
                       WHERE s.token_hash=? AND s.revoked_at IS NULL""",
                    (token_hash,),
                ).fetchall()
                if len(session_rows) != 1:
                    raise ValidationError("Checkout session is invalid or revoked")
                session_person, expiry_raw, person_state, account_state, credential_state = session_rows[0]
                if (session_person != actor or person_state != "active" or
                        account_state != "active" or credential_state != "active"):
                    raise ValidationError("Checkout session actor is not active or does not match")
                if _instant(expiry_raw, "Session expires_at") <= instant:
                    raise ValidationError("Checkout session has expired")
                session_mode = True
            elif any(value is None for value in grant_values):
                raise ValidationError("Either all three Sale grants or an authenticated session is required")
            else:
                session_mode = False

            prior = connection.execute(
                """SELECT request_hash, response_json FROM commercial_checkout_receipts
                   WHERE actor_person_id=? AND idempotency_key=?""",
                (actor, key),
            ).fetchone()
            if prior is not None:
                if prior[0] != fingerprint:
                    raise ValidationError("Checkout idempotency key was reused with a different request")
                response = json.loads(prior[1])
                connection.commit()
                return response

            offering_row = connection.execute(
                """SELECT o.product_id, o.activity_id, o.state, o.effective_from, o.effective_to,
                          p.state, a.state
                   FROM offerings o JOIN products p ON p.product_id=o.product_id
                   JOIN activities a ON a.activity_id=o.activity_id
                   WHERE o.offering_id=?""",
                (offering,),
            ).fetchone()
            if offering_row is None:
                raise ValidationError("Offering does not exist")
            product, stored_activity, offering_state, starts_raw, ends_raw, product_state, activity_state = offering_row
            if stored_activity != activity:
                raise ValidationError("Checkout Activity does not match Offering")
            if offering_state != "active" or product_state != "active" or activity_state != "active":
                raise ValidationError("Checkout requires an active Offering, Product, and Activity")
            starts = _instant(starts_raw, "Offering effective_from")
            ends = _instant(ends_raw, "Offering effective_to") if ends_raw is not None else None
            if starts > instant or (ends is not None and instant >= ends):
                raise ValidationError("Offering is outside its effective period")

            price = self._effective_price(connection, offering, instant)
            if price is None:
                raise ValidationError("Offering has no effective persisted price")
            stock = self._read_stock(
                connection, offering, product, activity, scope_key.strip(),
                location_ref.strip() if location_ref is not None else None, instant,
            )
            if stock is None:
                raise ValidationError("Checkout requires known, effective, unambiguous stock")
            if stock["available_quantity"] < quantity:
                raise ValidationError("Requested quantity exceeds available stock")

            create_grant = request["create_sale_grant_id"]
            confirm_grant = request["confirm_sale_grant_id"]
            fulfill_grant = request["fulfill_sale_grant_id"]
            if session_mode:
                expiry = _stamp(instant.replace(microsecond=0) + timedelta(minutes=5))
                create_grant, confirm_grant, fulfill_grant = str(uuid4()), str(uuid4()), str(uuid4())
                for grant_id, action, scope in (
                    (create_grant, "create_sale", offering),
                    (confirm_grant, "confirm_sale", sale_key),
                    (fulfill_grant, "fulfill_sale", sale_key),
                ):
                    connection.execute(
                        """INSERT INTO authorization_grants(
                               authorization_grant_id, subject_person_id, agent_id, action_code,
                               scope_ref, context_ref, delegation_id, state, effective_from,
                               effective_to, created_at, updated_at
                           ) VALUES(?,?,NULL,?,?,?,NULL,'active',?,?,?,?)""",
                        (grant_id, actor, action, scope, activity, stamp, expiry, stamp, stamp),
                    )
            sale = self._sales.create_sale(
                connection, offering, activity, actor, create_grant,
                now=instant, sale_id=sale_key,
            )
            self._sales.confirm_sale(
                connection, sale.id, actor, confirm_grant, now=instant,
            )
            self._sales.fulfill_sale(
                connection, sale.id, actor, fulfill_grant, now=instant,
            )

            line_total = quantity * price["unit_price_minor"]
            connection.execute(
                """INSERT INTO sale_lines(
                       sale_line_id, sale_id, offering_id, product_id, quantity,
                       unit_price_minor, currency_code, line_total_minor, created_at
                   ) VALUES(?,?,?,?,?,?,?,?,?)""",
                (str(uuid4()), sale_key, offering, product, quantity,
                 price["unit_price_minor"], price["currency_code"], line_total, stamp),
            )
            connection.execute(
                """INSERT INTO inventory_movements(
                       inventory_movement_id, inventory_position_id, activity_id, product_id,
                       offering_id, location_ref, scope_key, quantity_delta, source_type,
                       source_ref, actor_context_ref, occurred_at, created_at
                   ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                (str(uuid4()), stock["inventory_position_id"], activity, product, offering,
                 location_ref.strip() if location_ref is not None else None, scope_key.strip(),
                 -quantity, "sale", sale_key, actor, stamp, stamp),
            )
            remaining = stock["available_quantity"] - quantity
            response = {
                "sale_id": sale_key, "state": "fulfilled", "offering_id": offering,
                "product_id": product, "quantity": quantity,
                "unit_price_minor": price["unit_price_minor"], "currency_code": price["currency_code"],
                "total_minor": line_total, "inventory_position_id": stock["inventory_position_id"],
                "scope_key": scope_key.strip(),
                "location_ref": location_ref.strip() if location_ref is not None else None,
                "quantity_remaining": remaining, "occurred_at": stamp,
            }
            connection.execute(
                """INSERT INTO commercial_checkout_receipts(
                       actor_person_id, idempotency_key, request_hash, response_json, created_at
                   ) VALUES(?,?,?,?,?)""",
                (actor, key, fingerprint, json.dumps(response, sort_keys=True, separators=(",", ":")), stamp),
            )
            connection.commit()
            return response
        except BaseException:
            if connection.in_transaction:
                connection.rollback()
            raise
