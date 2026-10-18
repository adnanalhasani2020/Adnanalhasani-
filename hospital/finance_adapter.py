"""Validate hospital outbox events before any Finance-side mapping or write.

This module deliberately does not create Payments, Settlements, or ledger facts.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from datetime import datetime
from typing import Any, Mapping


class FinanceEventValidationError(ValueError):
    """An outbox event is malformed or violates the published event contract."""


@dataclass(frozen=True)
class ValidatedHospitalFinanceEvent:
    event_key: str
    event_type: str
    hospital_invoice_id: int
    amount_minor: int
    currency: str
    occurred_at: str
    hospital_payment_id: int | None = None
    invoice_number: str | None = None
    payment_method: str | None = None


_INVOICE_KEYS = {"invoice_id", "invoice_no", "total_minor", "currency", "created_at"}
_PAYMENT_KEYS = {
    "payment_id", "invoice_id", "amount_minor", "currency",
    "method", "reference", "received_at",
}
_CURRENCY = re.compile(r"^[A-Z]{3}$")


def _positive_int(value: Any, label: str, *, allow_zero: bool = False) -> int:
    if type(value) is not int or value < (0 if allow_zero else 1):
        qualifier = "non-negative" if allow_zero else "positive"
        raise FinanceEventValidationError(f"{label} must be a {qualifier} integer")
    return value


def _timestamp(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise FinanceEventValidationError(f"{label} timestamp is required")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise FinanceEventValidationError(f"{label} must be ISO-8601") from exc
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise FinanceEventValidationError(f"{label} must include a timezone")
    return value


def validate_hospital_finance_event(row: Mapping[str, Any]) -> ValidatedHospitalFinanceEvent:
    """Validate one claimed outbox row and return a minimal, typed handoff.

    The exact payload allowlist prevents patient/clinical fields from crossing
    this boundary. A validated event is not authorization to post accounting.
    """
    if not isinstance(row, Mapping):
        raise FinanceEventValidationError("outbox row must be a mapping")

    event_key = row.get("event_key")
    event_type = row.get("event_type")
    aggregate_type = row.get("aggregate_type")
    aggregate_id = row.get("aggregate_id")
    raw_payload = row.get("payload_json")

    if not isinstance(event_key, str) or not event_key.strip():
        raise FinanceEventValidationError("event_key is required")
    if not isinstance(raw_payload, str):
        raise FinanceEventValidationError("payload_json must be a JSON string")
    try:
        payload = json.loads(raw_payload)
    except (json.JSONDecodeError, TypeError) as exc:
        raise FinanceEventValidationError("payload_json is invalid JSON") from exc
    if not isinstance(payload, dict):
        raise FinanceEventValidationError("event payload must be a JSON object")

    if event_type == "hospital.invoice.created":
        if aggregate_type != "invoice":
            raise FinanceEventValidationError("invoice event must use invoice aggregate")
        if set(payload) != _INVOICE_KEYS:
            raise FinanceEventValidationError("invoice event payload does not match the v1 allowlist")
        invoice_id = _positive_int(payload["invoice_id"], "invoice_id")
        _positive_int(payload["total_minor"], "total_minor", allow_zero=True)
        if aggregate_id != str(invoice_id):
            raise FinanceEventValidationError("invoice aggregate_id does not match payload")
        expected_key = f"hospital:invoice:{invoice_id}:created:v1"
        if event_key != expected_key:
            raise FinanceEventValidationError("invoice event_key does not match payload identity")
        invoice_no = payload["invoice_no"]
        if not isinstance(invoice_no, str) or not invoice_no.strip():
            raise FinanceEventValidationError("invoice_no is required")
        method = None
        payment_id = None
        amount = payload["total_minor"]
        occurred_at = _timestamp(payload["created_at"], "created_at")
    elif event_type == "hospital.payment.received":
        if aggregate_type != "payment":
            raise FinanceEventValidationError("payment event must use payment aggregate")
        if set(payload) != _PAYMENT_KEYS:
            raise FinanceEventValidationError("payment event payload does not match the v1 allowlist")
        payment_id = _positive_int(payload["payment_id"], "payment_id")
        invoice_id = _positive_int(payload["invoice_id"], "invoice_id")
        amount = _positive_int(payload["amount_minor"], "amount_minor")
        if aggregate_id != str(payment_id):
            raise FinanceEventValidationError("payment aggregate_id does not match payload")
        expected_key = f"hospital:payment:{payment_id}:received:v1"
        if event_key != expected_key:
            raise FinanceEventValidationError("payment event_key does not match payload identity")
        method = payload["method"]
        if not isinstance(method, str) or not method.strip():
            raise FinanceEventValidationError("payment method is required")
        reference = payload["reference"]
        if reference is not None and not isinstance(reference, str):
            raise FinanceEventValidationError("payment reference must be text or null")
        invoice_no = None
        occurred_at = _timestamp(payload["received_at"], "received_at")
    else:
        raise FinanceEventValidationError("unsupported hospital finance event type")

    currency = payload.get("currency")
    if not isinstance(currency, str) or not _CURRENCY.fullmatch(currency):
        raise FinanceEventValidationError("currency must be a three-letter uppercase code")

    return ValidatedHospitalFinanceEvent(
        event_key=event_key,
        event_type=event_type,
        hospital_invoice_id=invoice_id,
        amount_minor=amount,
        currency=currency,
        occurred_at=occurred_at,
        hospital_payment_id=payment_id,
        invoice_number=invoice_no,
        payment_method=method,
    )
