from datetime import datetime, timezone
from decimal import Decimal

import pytest

from agent_core.financial_currency_policy import (
    DEFAULT_CURRENCY,
    DECIMAL_PLACES,
    SUPPORTED_CURRENCIES,
    ExchangeRate,
    convert_amount,
    normalize_supported_currency,
    round_money,
    to_minor_units,
)
from agent_core.shared import ValidationError


STAMP = datetime(2026, 10, 16, 12, 0, tzinfo=timezone.utc)


def test_owner_currency_policy_defaults_and_allowlist():
    assert SUPPORTED_CURRENCIES == frozenset({"SAR", "YER", "USD"})
    assert DEFAULT_CURRENCY == "YER"
    assert DECIMAL_PLACES == 2
    assert normalize_supported_currency(" yer ") == "YER"
    assert normalize_supported_currency("usd") == "USD"
    with pytest.raises(ValidationError, match="Unsupported currency_code"):
        normalize_supported_currency("EUR")


@pytest.mark.parametrize(
    ("value", "expected"),
    [
        ("1.234", Decimal("1.23")),
        ("1.235", Decimal("1.24")),
        ("-1.235", Decimal("-1.24")),
        (Decimal("0"), Decimal("0.00")),
    ],
)
def test_round_money_uses_explicit_half_up_policy(value, expected):
    assert round_money(value) == expected


def test_minor_units_are_integer_and_exact():
    assert to_minor_units("123.45") == 12345
    assert to_minor_units("0.10") == 10
    assert to_minor_units("1.235") == 124


@pytest.mark.parametrize("value", [True, 1.25, float("nan"), float("inf")])
def test_money_rejects_boolean_float_and_nonfinite_inputs(value):
    with pytest.raises(ValidationError):
        round_money(value)


def test_exchange_rate_normalizes_currency_and_captures_direction_and_audit_source():
    rate = ExchangeRate(
        base_currency="usd",
        quote_currency=" yer ",
        rate=Decimal("250.125"),
        effective_at=STAMP,
        source_ref="admin-entry:fx-001",
    )
    assert rate.base_currency == "USD"
    assert rate.quote_currency == "YER"
    assert rate.rate == Decimal("250.125")
    assert rate.effective_at == STAMP
    assert rate.source_ref == "admin-entry:fx-001"


@pytest.mark.parametrize("rate_value", [Decimal("0"), Decimal("-1"), Decimal("NaN"), Decimal("Infinity")])
def test_exchange_rate_rejects_nonpositive_or_nonfinite_rate(rate_value):
    with pytest.raises(ValidationError):
        ExchangeRate("USD", "YER", rate_value, STAMP, "admin-entry:fx")


def test_exchange_rate_requires_distinct_currencies_timezone_and_source():
    with pytest.raises(ValidationError, match="must differ"):
        ExchangeRate("USD", "USD", Decimal("1"), STAMP, "admin-entry:fx")
    with pytest.raises(ValidationError, match="timezone-aware"):
        ExchangeRate("USD", "YER", Decimal("250"), datetime(2026, 10, 16), "admin-entry:fx")
    with pytest.raises(ValidationError, match="source_ref"):
        ExchangeRate("USD", "YER", Decimal("250"), STAMP, "  ")


def test_cross_currency_conversion_uses_explicit_quote_direction_and_rounds_once():
    rate = ExchangeRate("USD", "YER", Decimal("250.125"), STAMP, "admin-entry:fx")
    assert convert_amount(
        "2.00", source_currency="USD", target_currency="YER", exchange_rate=rate
    ) == Decimal("500.25")


def test_cross_currency_conversion_rejects_missing_or_misdirected_rate():
    with pytest.raises(ValidationError, match="explicit exchange rate"):
        convert_amount("10", source_currency="USD", target_currency="YER")
    reverse = ExchangeRate("YER", "USD", Decimal("0.004"), STAMP, "admin-entry:fx")
    with pytest.raises(ValidationError, match="direction"):
        convert_amount("10", source_currency="USD", target_currency="YER", exchange_rate=reverse)


def test_same_currency_conversion_does_not_accept_an_exchange_rate():
    assert convert_amount("1.235", source_currency="YER", target_currency="YER") == Decimal("1.24")
    rate = ExchangeRate("USD", "YER", Decimal("250"), STAMP, "admin-entry:fx")
    with pytest.raises(ValidationError, match="same-currency"):
        convert_amount("1", source_currency="YER", target_currency="YER", exchange_rate=rate)
