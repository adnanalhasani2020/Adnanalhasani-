"""Exact commercial document arithmetic shared by sales and procurement.

Amounts are integer minor currency units. Quantities and unit factors use Decimal;
this module intentionally does not choose a jurisdiction's tax policy.
"""
from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP, localcontext
from typing import Literal


def _decimal(value, label: str) -> Decimal:
    if isinstance(value, bool):
        raise ValueError(f"{label} must be a finite decimal")
    try:
        result = value if isinstance(value, Decimal) else Decimal(str(value))
    except (InvalidOperation, ValueError, TypeError) as exc:
        raise ValueError(f"{label} must be a finite decimal") from exc
    if not result.is_finite():
        raise ValueError(f"{label} must be a finite decimal")
    return result


def to_base_quantity(quantity, factor_to_base, *, label: str = "quantity") -> Decimal:
    """Convert a selling/receiving unit to the inventory base unit.

    Example: 2 cartons × 12 pieces/carton = 24 pieces. Fractional base
    quantities are allowed only when the product's base unit supports them;
    the caller must enforce the configured base-unit precision.
    """
    q = _decimal(quantity, label)
    factor = _decimal(factor_to_base, "factor_to_base")
    if q <= 0:
        raise ValueError(f"{label} must be greater than zero")
    if factor <= 0:
        raise ValueError("factor_to_base must be greater than zero")
    # Decimal multiplication otherwise follows the ambient 28-digit context and
    # can silently round large inventory quantities. Size the local context for
    # the exact product of both coefficient digit sequences.
    precision = len(q.as_tuple().digits) + len(factor.as_tuple().digits)
    with localcontext() as ctx:
        ctx.prec = max(ctx.prec, precision)
        return q * factor


def validate_base_quantity_precision(quantity, decimal_places: int, *, label: str = "quantity") -> Decimal:
    """Return a positive base quantity only when it fits the configured precision.

    ``decimal_places=0`` means whole base units; ``3`` allows thousandths.
    The function never rounds stock quantities silently.
    """
    value = _decimal(quantity, label)
    if value <= 0:
        raise ValueError(f"{label} must be greater than zero")
    if type(decimal_places) is not int or decimal_places < 0 or decimal_places > 12:
        raise ValueError("decimal_places must be an integer between 0 and 12")
    quantum = Decimal(1).scaleb(-decimal_places)
    try:
        quantized = value.quantize(quantum)
    except InvalidOperation as exc:
        raise ValueError(f"{label} exceeds supported precision") from exc
    if quantized != value:
        raise ValueError(f"{label} exceeds configured base-unit precision")
    return value


def rounded_minor(amount) -> int:
    """Round a currency amount to the nearest minor unit using half-up rounding."""
    value = _decimal(amount, "amount")
    if value < 0:
        raise ValueError("amount cannot be negative")
    try:
        result = value.quantize(Decimal("1"), rounding=ROUND_HALF_UP)
    except InvalidOperation as exc:
        raise ValueError("amount exceeds supported currency precision") from exc
    return int(result)


@dataclass(frozen=True)
class TaxRule:
    code: str
    rate_percent: Decimal
    inclusive: bool = False
    enabled: bool = True

    def __post_init__(self):
        rate = _decimal(self.rate_percent, "tax rate")
        if not self.code or not self.code.strip():
            raise ValueError("tax code is required")
        if rate < 0 or rate > 100:
            raise ValueError("tax rate must be between 0 and 100")
        object.__setattr__(self, "rate_percent", rate)


@dataclass(frozen=True)
class Discount:
    kind: Literal["fixed", "percent"]
    value: Decimal

    def __post_init__(self):
        value = _decimal(self.value, "discount")
        if value < 0:
            raise ValueError("discount cannot be negative")
        if self.kind == "percent" and value > 100:
            raise ValueError("percentage discount cannot exceed 100")
        if self.kind not in {"fixed", "percent"}:
            raise ValueError("discount kind must be fixed or percent")
        object.__setattr__(self, "value", value)


@dataclass(frozen=True)
class DocumentTotals:
    subtotal_minor: int
    discount_minor: int
    taxable_minor: int
    tax_minor: int
    total_minor: int


def calculate_document_totals(
    *, line_amounts_minor, discount: Discount | None = None,
    tax_rules: tuple[TaxRule, ...] = (),
) -> DocumentTotals:
    """Calculate totals in minor units; percentage discounts apply before tax.

    Tax-inclusive rules are extracted from the post-discount taxable amount.
    Mixed inclusive/exclusive tax rules are rejected because their ordering
    needs an explicit jurisdictional policy rather than an implicit guess.
    """
    amounts = []
    for amount in line_amounts_minor:
        if type(amount) is not int or amount < 0:
            raise ValueError("line amounts must be non-negative integer minor units")
        amounts.append(amount)
    subtotal = sum(amounts)
    if subtotal > (1 << 63) - 1:
        raise ValueError("subtotal exceeds supported integer range")

    if discount is None:
        discount_minor = 0
    elif discount.kind == "fixed":
        discount_minor = rounded_minor(discount.value)
    else:
        discount_minor = rounded_minor(Decimal(subtotal) * discount.value / Decimal(100))
    if discount_minor > subtotal:
        raise ValueError("discount cannot exceed subtotal")

    taxable = subtotal - discount_minor
    active_rules = tuple(rule for rule in tax_rules if rule.enabled)
    codes = [rule.code.strip().casefold() for rule in active_rules]
    if len(codes) != len(set(codes)):
        raise ValueError("active tax rule codes must be unique")
    if len({rule.inclusive for rule in active_rules}) > 1:
        raise ValueError("mixed inclusive and exclusive taxes require an explicit tax policy")

    if active_rules and active_rules[0].inclusive:
        combined_rate = sum((rule.rate_percent for rule in active_rules), Decimal(0))
        tax = rounded_minor(Decimal(taxable) * combined_rate / (Decimal(100) + combined_rate))
        total = taxable
    else:
        tax = sum(rounded_minor(Decimal(taxable) * rule.rate_percent / Decimal(100)) for rule in active_rules)
        total = taxable + tax

    if total > (1 << 63) - 1:
        raise ValueError("document total exceeds supported integer range")
    return DocumentTotals(subtotal, discount_minor, taxable, tax, total)
