"""Cash receipts and credit-balance operations for the commercial MVP."""
from __future__ import annotations

import hashlib
import json
import sqlite3
from uuid import UUID, uuid4

from agent_core.shared import ValidationError


def _sale_uuid(value):
    try:
        return str(UUID(str(value)))
    except (ValueError, TypeError, AttributeError) as exc:
        raise ValidationError("Sale identifier must be a valid UUID") from exc


def _request_hash(sale_id: str, amount_minor: int) -> str:
    raw = json.dumps(
        {"sale_id": sale_id, "amount_minor": amount_minor},
        sort_keys=True, separators=(",", ":"),
    )
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


class CommercialPaymentsApplication:
    """Record cash received against a persisted sale; derive balances from immutable rows."""

    def record_payment(
        self, connection: sqlite3.Connection, *, buyer_person_id: str,
        sale_id: str, amount_minor: int, idempotency_key: str, now: str,
    ) -> dict:
        if connection.in_transaction:
            raise ValidationError("Payment recording requires an unowned SQLite transaction")
        try:
            buyer = str(UUID(str(buyer_person_id)))
        except (ValueError, TypeError, AttributeError) as exc:
            raise ValidationError("Buyer identifier must be a valid UUID") from exc
        sale = _sale_uuid(sale_id)
        if type(amount_minor) is not int or amount_minor <= 0:
            raise ValidationError("Payment amount must be a positive integer")
        if not isinstance(idempotency_key, str) or not idempotency_key.strip() or len(idempotency_key.strip()) > 200:
            raise ValidationError("Payment idempotency key must contain 1–200 non-space characters")
        key = idempotency_key.strip()
        fingerprint = _request_hash(sale, amount_minor)
        try:
            connection.execute("BEGIN IMMEDIATE")
            prior = connection.execute(
                """SELECT request_hash, response_json FROM commercial_sale_payments
                   WHERE buyer_person_id=? AND idempotency_key=?""",
                (buyer, key),
            ).fetchone()
            if prior is not None:
                if prior[0] != fingerprint:
                    raise ValidationError("Payment idempotency key was reused with a different request")
                response = json.loads(prior[1])
                connection.commit()
                return response

            terms = connection.execute(
                """SELECT t.total_minor,t.currency_code,s.state
                   FROM commercial_sale_terms t JOIN sales s ON s.sale_id=t.sale_id
                   WHERE t.sale_id=? AND t.buyer_person_id=?""",
                (sale, buyer),
            ).fetchone()
            if terms is None:
                raise ValidationError("Sale not found for this buyer")
            total_minor, currency_code, sale_state = terms
            if sale_state not in {"completed", "fulfilled"}:
                raise ValidationError("Payments are accepted only for an active fulfilled sale")
            paid = connection.execute(
                "SELECT COALESCE(SUM(amount_minor),0) FROM commercial_sale_payments WHERE sale_id=?",
                (sale,),
            ).fetchone()[0]
            balance = total_minor - paid
            if amount_minor > balance:
                raise ValidationError("Payment exceeds the outstanding balance")
            payment_id = str(uuid4())
            remaining = balance - amount_minor
            response = {
                "payment_id": payment_id, "sale_id": sale,
                "amount_minor": amount_minor, "currency_code": currency_code,
                "paid_total_minor": paid + amount_minor,
                "balance_remaining_minor": remaining, "method": "cash",
                "occurred_at": now,
            }
            connection.execute(
                """INSERT INTO commercial_sale_payments(
                       payment_id,sale_id,buyer_person_id,amount_minor,currency_code,
                       method,idempotency_key,request_hash,response_json,created_at
                   ) VALUES(?,?,?,?,?,'cash',?,?,?,?)""",
                (payment_id, sale, buyer, amount_minor, currency_code, key,
                 fingerprint, json.dumps(response, sort_keys=True, separators=(",", ":")), now),
            )
            connection.commit()
            return response
        except BaseException:
            if connection.in_transaction:
                connection.rollback()
            raise

    def list_debts(self, connection: sqlite3.Connection, *, buyer_person_id: str) -> tuple[dict, ...]:
        try:
            buyer = str(UUID(str(buyer_person_id)))
        except (ValueError, TypeError, AttributeError) as exc:
            raise ValidationError("Buyer identifier must be a valid UUID") from exc
        rows = connection.execute(
            """SELECT t.sale_id,t.payment_method,t.total_minor,t.currency_code,t.created_at,
                      COALESCE(SUM(p.amount_minor),0) AS paid_minor,s.state
               FROM commercial_sale_terms t
               JOIN sales s ON s.sale_id=t.sale_id
               LEFT JOIN commercial_sale_payments p ON p.sale_id=t.sale_id
               WHERE t.buyer_person_id=?
               GROUP BY t.sale_id,t.payment_method,t.total_minor,t.currency_code,t.created_at,s.state
               ORDER BY t.created_at DESC,t.sale_id""",
            (buyer,),
        ).fetchall()
        return tuple({
            "sale_id": sale_id, "payment_method": method,
            "total_minor": total, "paid_minor": paid,
            "balance_remaining_minor": total - paid, "currency_code": currency,
            "created_at": created, "sale_state": state,
        } for sale_id, method, total, currency, created, paid, state in rows
          if total - paid > 0)
