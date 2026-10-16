"""Strict currency and exchange-rate primitives for Finance policy.

This module is deliberately side-effect free. Persistence, administrator
authorization, rate-history storage, and transaction integration belong to the
Finance application layer and must not be inferred from these primitives.
"""
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
from typing import Final

from agent_core.shared import ValidationError


SUPPORTED_CURRENCIES: Final[frozenset[str]] = frozenset({"SAR", "YER", "USD"})
DEFAULT_CURRENCY: Final[str] = "YER"
DECIMAL_PLACES: Final[int] = 2
_MINOR_UNIT: Final[Decimal] = Decimal("0.01")


def normalize_supported_currency(value: str) -> str:
    """Normalize an input currency and reject codes outside the approved set."""
    if not isinstance(value, str):
        raise ValidationError("currency_code must be a string")
    code = value.strip().upper()
    if code not in SUPPORTED_CURRENCIES:
        raise ValidationError(f"Unsupported currency_code: {code or '<empty>'}")
    return code


def decimal_amount(value: Decimal | int | str, *, label: str = "amount") -> Decimal:
    """Parse exact decimal input; floats are intentionally rejected."""
    if isinstance(value, bool) or isinstance(value, float):
        raise ValidationError(f"{label} must use Decimal, integer, or decimal text; floats are not accepted")
    if not isinstance(value, (Decimal, int, str)):
        raise ValidationError(f"{label} must use Decimal, integer, or decimal text")
    try:
        result = Decimal(value) if not isinstance(value, str) else Decimal(value.strip())
    except (InvalidOperation, ValueError) as exc:
        raise ValidationError(f"{label} must be a valid decimal") from exc
    if not result.is_finite():
        raise ValidationError(f"{label} must be finite")
    return result


def round_money(value: Decimal | int | str, *, label: str = "amount") -> Decimal:
    """Round a monetary result to two decimals using explicit ROUND_HALF_UP."""
    return decimal_amount(value, label=label).quantize(_MINOR_UNIT, rounding=ROUND_HALF_UP)


def to_minor_units(value: Decimal | int | str, *, label: str = "amount") -> int:
    """Convert a two-decimal monetary amount to an integer minor-unit count."""
    rounded = round_money(value, label=label)
    return int(rounded * 100)


@dataclass(frozen=True)
class ExchangeRate:
    """An explicit quote: units of quote_currency for one base_currency."""

    base_currency: str
    quote_currency: str
    rate: Decimal
    effective_at: datetime
    source_ref: str

    def __post_init__(self) -> None:
        base = normalize_supported_currency(self.base_currency)
        quote = normalize_supported_currency(self.quote_currency)
        rate = decimal_amount(self.rate, label="exchange rate")
        if base == quote:
            raise ValidationError("base_currency and quote_currency must differ")
        if rate <= 0:
            raise ValidationError("exchange rate must be greater than zero")
        instant = self.effective_at
        if not isinstance(instant, datetime) or instant.tzinfo is None or instant.utcoffset() is None:
            raise ValidationError("effective_at must be timezone-aware")
        if not isinstance(self.source_ref, str) or not self.source_ref.strip():
            raise ValidationError("source_ref is required for exchange-rate auditability")
        object.__setattr__(self, "base_currency", base)
        object.__setattr__(self, "quote_currency", quote)
        object.__setattr__(self, "rate", rate)
        object.__setattr__(self, "effective_at", instant.astimezone(timezone.utc))
        object.__setattr__(self, "source_ref", self.source_ref.strip())


def convert_amount(
    amount: Decimal | int | str,
    *,
    source_currency: str,
    target_currency: str,
    exchange_rate: ExchangeRate | None = None,
) -> Decimal:
    """Convert exactly once at the boundary; reject missing or misdirected rates."""
    source = normalize_supported_currency(source_currency)
    target = normalize_supported_currency(target_currency)
    value = decimal_amount(amount)
    if source == target:
        if exchange_rate is not None:
            raise ValidationError("An exchange rate must not be supplied for same-currency conversion")
        return round_money(value)
    if exchange_rate is None:
        raise ValidationError("An explicit exchange rate is required for cross-currency conversion")
    if (exchange_rate.base_currency, exchange_rate.quote_currency) != (source, target):
        raise ValidationError("Exchange-rate direction must match source and target currencies")
    return round_money(value * exchange_rate.rate)
