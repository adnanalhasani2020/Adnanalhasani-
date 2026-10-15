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

    def __post_init__(self):
        if not all(isinstance(value, str) and value.strip() for value in
                   (self.code, self.name, self.dimension)):
            raise ValidationError("unit code, name, and dimension are required")
        if not isinstance(self.id, UUID):
            raise ValidationError("unit id must be a UUID")
        if not isinstance(self.state, UnitState):
            raise ValidationError("unit state must be a UnitState")


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
        if not isinstance(self.product_id, UUID) or not isinstance(self.unit_id, UUID):
            raise ValidationError("product_id and unit_id must be UUIDs")
        if not isinstance(self.role, ProductUnitRole):
            raise ValidationError("role must be a ProductUnitRole")
        if not isinstance(self.state, UnitState):
            raise ValidationError("state must be a UnitState")
        if (not isinstance(self.conversion_numerator, int)
                or isinstance(self.conversion_numerator, bool)
                or not isinstance(self.conversion_denominator, int)
                or isinstance(self.conversion_denominator, bool)
                or self.conversion_numerator <= 0 or self.conversion_denominator <= 0):
            raise ValidationError("conversion ratio must be a positive integer rational")
        if not isinstance(self.version_no, int) or isinstance(self.version_no, bool) or self.version_no < 1:
            raise ValidationError("version_no must be a positive integer")
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
