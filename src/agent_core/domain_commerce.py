from dataclasses import dataclass, field
from enum import Enum
from typing import Tuple
from uuid import UUID

from agent_core.shared import ValidationError, new_id


class SaleState(str, Enum):
    INITIATED = "initiated"
    CONFIRMED = "confirmed"
    FULFILLED = "fulfilled"
    # Source-compatible alias: COMPLETED is not a separate domain state.
    COMPLETED = "fulfilled"
    CANCELLED = "cancelled"
    RETURNED = "returned"

    @classmethod
    def _missing_(cls, value):
        # Historical persisted vocabulary resolves to the canonical domain state.
        if value == "completed":
            return cls.FULFILLED
        return None


class InvoiceState(str, Enum):
    ISSUED = "issued"
    VOID = "void"
    SETTLED = "settled"


@dataclass
class Sale:
    offering_id: UUID
    activity_id: UUID
    id: UUID = field(default_factory=new_id)
    state: SaleState = SaleState.INITIATED
    history: Tuple[SaleState, ...] = field(default_factory=lambda: (SaleState.INITIATED,))

    def __post_init__(self):
        if not isinstance(self.offering_id, UUID):
            raise ValidationError("Sale must reference Offering")
        if not isinstance(self.activity_id, UUID):
            raise ValidationError("Sale must reference Activity context")
        if not isinstance(self.state, SaleState):
            raise ValidationError("Sale state must be a SaleState")
        if not isinstance(self.history, tuple) or not self.history:
            raise ValidationError("Sale history must be a non-empty tuple")
        if any(not isinstance(item, SaleState) for item in self.history):
            raise ValidationError("Sale history entries must be SaleState values")
        if self.history[0] is not SaleState.INITIATED:
            raise ValidationError("Sale history must start at initiated")

        allowed_next = {
            SaleState.INITIATED: (SaleState.CONFIRMED, SaleState.CANCELLED),
            SaleState.CONFIRMED: (SaleState.FULFILLED, SaleState.COMPLETED, SaleState.CANCELLED),
            SaleState.FULFILLED: (SaleState.RETURNED,),
            SaleState.CANCELLED: (),
            SaleState.RETURNED: (),
        }
        for previous, current in zip(self.history, self.history[1:]):
            if current not in allowed_next[previous]:
                raise ValidationError(
                    f"invalid Sale history transition from {previous.value} to {current.value}"
                )
        if self.history[-1] != self.state:
            raise ValidationError("Sale history must end at current state")

    def _transition(self, state: SaleState, allowed: Tuple[SaleState, ...]):
        if self.state is state:
            return
        if self.state not in allowed:
            raise ValidationError(
                f"invalid Sale transition from {self.state.value} to {state.value}"
            )
        self.state = state
        self.history = self.history + (state,)

    def confirm(self):
        self._transition(SaleState.CONFIRMED, (SaleState.INITIATED,))

    def cancel(self):
        self._transition(
            SaleState.CANCELLED, (SaleState.INITIATED, SaleState.CONFIRMED)
        )

    def complete(self):
        """Fulfil the commercial sale; this does not imply payment or settlement."""
        self._transition(SaleState.FULFILLED, (SaleState.CONFIRMED,))

    def return_sale(self):
        self._transition(
            SaleState.RETURNED, (SaleState.FULFILLED, SaleState.COMPLETED)
        )


@dataclass
class Invoice:
    sale_id: UUID
    id: UUID = field(default_factory=new_id)
    state: InvoiceState = InvoiceState.ISSUED

    def __post_init__(self):
        if not isinstance(self.sale_id, UUID):
            raise ValidationError("Invoice must reference Sale")

    def void(self):
        self.state = InvoiceState.VOID
