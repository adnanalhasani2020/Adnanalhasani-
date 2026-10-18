import json

import pytest

from hospital.finance_adapter import (
    FinanceEventValidationError,
    validate_hospital_finance_event,
)


def _invoice_row(payload=None):
    payload = payload or {
        "invoice_id": 12,
        "invoice_no": "INV-12",
        "total_minor": 4500,
        "currency": "YER",
        "created_at": "2026-10-18T10:01:00Z",
    }
    return {
        "event_key": "hospital:invoice:12:created:v1",
        "event_type": "hospital.invoice.created",
        "aggregate_type": "invoice",
        "aggregate_id": "12",
        "payload_json": json.dumps(payload),
    }


def _payment_row(payload=None):
    payload = payload or {
        "payment_id": 21,
        "invoice_id": 12,
        "amount_minor": 1200,
        "currency": "YER",
        "method": "cash",
        "reference": None,
        "received_at": "2026-10-18T10:02:00Z",
    }
    return {
        "event_key": "hospital:payment:21:received:v1",
        "event_type": "hospital.payment.received",
        "aggregate_type": "payment",
        "aggregate_id": "21",
        "payload_json": json.dumps(payload),
    }


def test_valid_invoice_event_is_normalized_without_patient_identity():
    event = validate_hospital_finance_event(_invoice_row())
    assert event.hospital_invoice_id == 12
    assert event.amount_minor == 4500
    assert event.currency == "YER"
    assert event.invoice_number == "INV-12"
    assert event.hospital_payment_id is None
    assert not hasattr(event, "patient_id")


def test_valid_payment_event_is_normalized_without_raw_reference():
    event = validate_hospital_finance_event(_payment_row())
    assert event.hospital_invoice_id == 12
    assert event.hospital_payment_id == 21
    assert event.amount_minor == 1200
    assert event.payment_method == "cash"
    assert event.occurred_at == "2026-10-18T10:02:00Z"
    assert not hasattr(event, "reference")


@pytest.mark.parametrize(
    "payload, message",
    [
        (
            {
                "invoice_id": 12, "invoice_no": "INV-12", "total_minor": 4500,
                "currency": "YER", "created_at": "2026-10-18T10:01:00Z",
                "patient_id": 88,
            },
            "allowlist",
        ),
        (
            {
                "invoice_id": 0, "invoice_no": "INV-12", "total_minor": 4500,
                "currency": "YER", "created_at": "2026-10-18T10:01:00Z",
            },
            "positive integer",
        ),
        (
            {
                "invoice_id": 12, "invoice_no": "INV-12", "total_minor": True,
                "currency": "YER", "created_at": "2026-10-18T10:01:00Z",
            },
            "non-negative integer",
        ),
    ],
)
def test_invoice_event_rejects_payloads_outside_contract(payload, message):
    with pytest.raises(FinanceEventValidationError, match=message):
        validate_hospital_finance_event(_invoice_row(payload))


def test_event_identity_must_match_payload_and_aggregate():
    row = _payment_row()
    row["event_key"] = "hospital:payment:22:received:v1"
    with pytest.raises(FinanceEventValidationError, match="event_key"):
        validate_hospital_finance_event(row)

    row = _payment_row()
    row["aggregate_id"] = "99"
    with pytest.raises(FinanceEventValidationError, match="aggregate_id"):
        validate_hospital_finance_event(row)


def test_payment_event_rejects_naive_timestamp_and_nonpositive_amount():
    payload = {
        "payment_id": 21, "invoice_id": 12, "amount_minor": 0,
        "currency": "YER", "method": "cash", "reference": None,
        "received_at": "2026-10-18T10:02:00",
    }
    with pytest.raises(FinanceEventValidationError, match="positive integer"):
        validate_hospital_finance_event(_payment_row(payload))

    payload["amount_minor"] = 1200
    with pytest.raises(FinanceEventValidationError, match="timezone"):
        validate_hospital_finance_event(_payment_row(payload))


def test_event_validator_rejects_unknown_types_and_malformed_json():
    row = _invoice_row()
    row["event_type"] = "hospital.patient.created"
    with pytest.raises(FinanceEventValidationError, match="unsupported"):
        validate_hospital_finance_event(row)

    row = _invoice_row()
    row["payload_json"] = "{"
    with pytest.raises(FinanceEventValidationError, match="invalid JSON"):
        validate_hospital_finance_event(row)


def test_validator_does_not_treat_event_as_financial_posting_authority():
    event = validate_hospital_finance_event(_payment_row())
    assert event.event_type == "hospital.payment.received"
    assert not hasattr(event, "financial_transaction_id")
    assert not hasattr(event, "ledger_entry_id")
