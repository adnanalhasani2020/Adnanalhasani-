"""Domain types for product measurement units and exact conversions."""
from dataclasses import dataclass
from enum import Enum
from fractions import Fraction
from uuid import UUID

from agent_core.shared import ValidationError


class UnitState(str, Enum):
    ACTIVE = "active"
    RETIRED = "retired"


class ProductUnitRole(str, Enum):
    BASE = "base"
    ALLOWED = "allowed"


@dataclass(frozen=True)
class MeasurementUnit:
    code: str
    name: str
    dimension: str
    id: UUID
    state: UnitState = UnitState.ACTIVE


@dataclass(frozen=True)
class ProductUnitSetting:
    product_id: UUID
    unit_id: UUID
    role: ProductUnitRole
    conversion_numerator: int
    conversion_denominator: int
    state: UnitState
    version_no: int

    def __post_init__(self):
        if self.conversion_numerator <= 0 or self.conversion_denominator <= 0:
            raise ValidationError("conversion ratio must be a positive rational")
        if self.role is ProductUnitRole.BASE and (
            self.conversion_numerator != 1 or self.conversion_denominator != 1
        ):
            raise ValidationError("base unit conversion ratio must be 1/1")

    @property
    def base_units_per_unit(self) -> Fraction:
        return Fraction(self.conversion_numerator, self.conversion_denominator)


def convert_quantity(quantity, source: ProductUnitSetting, target: ProductUnitSetting) -> Fraction:
    """Convert an exact integer/Fraction quantity without float rounding."""
    if not isinstance(quantity, (int, Fraction)) or isinstance(quantity, bool):
        raise ValidationError("quantity must be an integer or Fraction; floats are not accepted")
    if source.product_id != target.product_id:
        raise ValidationError("unit settings must belong to the same product")
    if source.state is not UnitState.ACTIVE or target.state is not UnitState.ACTIVE:
        raise ValidationError("retired product units cannot be used for conversion")
    return Fraction(quantity) * source.base_units_per_unit / target.base_units_per_unit
