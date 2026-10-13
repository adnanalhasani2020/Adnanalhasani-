from dataclasses import dataclass, field, replace
from enum import Enum
from uuid import UUID
from agent_core.shared import ValidationError, new_id

class PurchaseState(str, Enum):
    DRAFT = "draft"
    SUBMITTED = "submitted"
    APPROVED = "approved"
    ORDERED = "ordered"
    PARTIALLY_RECEIVED = "partially_received"
    RECEIVED = "received"
    CANCELLED = "cancelled"

@dataclass(frozen=True)
class PurchaseLine:
    product_id: UUID
    quantity_units: int
    unit_label: str
    id: UUID = field(default_factory=new_id)
    unit_cost_minor: int | None = None
    currency_code: str | None = None
    received_quantity_units: int = 0

    def __post_init__(self):
        if not isinstance(self.product_id, UUID) or not isinstance(self.id, UUID):
            raise ValidationError("Purchase line identifiers must be UUIDs")
        if type(self.quantity_units) is not int or self.quantity_units <= 0:
            raise ValidationError("quantity_units must be a positive integer")
        if not isinstance(self.unit_label, str) or not self.unit_label.strip():
            raise ValidationError("unit_label is required")
        object.__setattr__(self, "unit_label", self.unit_label.strip())
        if self.unit_cost_minor is not None and (type(self.unit_cost_minor) is not int or self.unit_cost_minor < 0):
            raise ValidationError("unit_cost_minor must be a non-negative integer")
        if self.unit_cost_minor is None:
            if self.currency_code is not None:
                raise ValidationError("currency_code requires unit_cost_minor")
        elif not isinstance(self.currency_code, str) or len(self.currency_code) != 3 or not self.currency_code.isascii() or not self.currency_code.isalpha() or self.currency_code != self.currency_code.upper():
            raise ValidationError("currency_code must be a 3-letter uppercase code")
        if type(self.received_quantity_units) is not int or not 0 <= self.received_quantity_units <= self.quantity_units:
            raise ValidationError("received quantity must be within the ordered quantity")

@dataclass(frozen=True)
class ReceiptEvent:
    line_id: UUID
    quantity_units: int
    id: UUID = field(default_factory=new_id)

    def __post_init__(self):
        if not isinstance(self.line_id, UUID) or not isinstance(self.id, UUID):
            raise ValidationError("Receipt event identifiers must be UUIDs")
        if type(self.quantity_units) is not int or self.quantity_units <= 0:
            raise ValidationError("receipt quantity must be a positive integer")

@dataclass(frozen=True)
class Purchase:
    supplier_ref: str
    activity_id: UUID
    lines: tuple[PurchaseLine, ...]
    id: UUID = field(default_factory=new_id)
    state: PurchaseState = PurchaseState.DRAFT
    history: tuple[PurchaseState, ...] = (PurchaseState.DRAFT,)
    receipts: tuple[ReceiptEvent, ...] = ()

    def __post_init__(self):
        if not isinstance(self.supplier_ref, str) or not self.supplier_ref.strip():
            raise ValidationError("supplier_ref is required")
        object.__setattr__(self, "supplier_ref", self.supplier_ref.strip())
        if not isinstance(self.activity_id, UUID) or not isinstance(self.id, UUID):
            raise ValidationError("Purchase identifiers/context must be UUIDs")
        if not isinstance(self.state, PurchaseState):
            raise ValidationError("state must be a PurchaseState")
        if not isinstance(self.lines, tuple) or not self.lines or any(not isinstance(x, PurchaseLine) for x in self.lines):
            raise ValidationError("Purchase must contain PurchaseLine values")
        ids = [line.id for line in self.lines]
        if len(ids) != len(set(ids)):
            raise ValidationError("Purchase line identifiers must be unique")
        if not isinstance(self.history, tuple) or not self.history or any(not isinstance(x, PurchaseState) for x in self.history):
            raise ValidationError("history must contain PurchaseState values")
        if self.history[0] is not PurchaseState.DRAFT or self.history[-1] is not self.state:
            raise ValidationError("history must start at draft and end at current state")
        allowed = {
            PurchaseState.DRAFT: {PurchaseState.SUBMITTED, PurchaseState.CANCELLED},
            PurchaseState.SUBMITTED: {PurchaseState.APPROVED, PurchaseState.CANCELLED},
            PurchaseState.APPROVED: {PurchaseState.ORDERED, PurchaseState.CANCELLED},
            PurchaseState.ORDERED: {PurchaseState.PARTIALLY_RECEIVED, PurchaseState.RECEIVED},
            PurchaseState.PARTIALLY_RECEIVED: {PurchaseState.RECEIVED},
            PurchaseState.RECEIVED: set(), PurchaseState.CANCELLED: set(),
        }
        for previous, current in zip(self.history, self.history[1:]):
            if current is previous:
                if current is not PurchaseState.PARTIALLY_RECEIVED:
                    raise ValidationError(f"duplicate Purchase history state: {current.value}")
                continue
            if current not in allowed[previous]:
                raise ValidationError(f"invalid Purchase transition from {previous.value} to {current.value}")
        if any(not isinstance(event, ReceiptEvent) for event in self.receipts):
            raise ValidationError("receipts must contain ReceiptEvent values")
        receipt_ids = [event.id for event in self.receipts]
        if len(receipt_ids) != len(set(receipt_ids)):
            raise ValidationError("receipt event identifiers must be unique")
        line_ids = set(ids)
        if any(event.line_id not in line_ids for event in self.receipts):
            raise ValidationError("receipt event references an unknown line")
        totals = {line.id: 0 for line in self.lines}
        for event in self.receipts:
            totals[event.line_id] += event.quantity_units
        if any(totals[line.id] != line.received_quantity_units for line in self.lines):
            raise ValidationError("receipt history disagrees with line received quantities")
        receiving_history_events = sum(
            state in {PurchaseState.PARTIALLY_RECEIVED, PurchaseState.RECEIVED}
            for state in self.history[1:]
        )
        if receiving_history_events != len(self.receipts):
            raise ValidationError("receipt history event count disagrees with Purchase history")
        received_quantities = [line.received_quantity_units for line in self.lines]
        if any(received_quantities) and self.state not in {PurchaseState.PARTIALLY_RECEIVED, PurchaseState.RECEIVED}:
            raise ValidationError("received quantities require a receiving state")
        if self.state is PurchaseState.PARTIALLY_RECEIVED:
            if not any(received_quantities):
                raise ValidationError("partially received Purchase must have received quantities")
            if all(line.received_quantity_units == line.quantity_units for line in self.lines):
                raise ValidationError("fully received quantities require the received state")
        if self.state is PurchaseState.RECEIVED and any(line.received_quantity_units != line.quantity_units for line in self.lines):
            raise ValidationError("received Purchase must have all quantities received")

    def _transition(self, target, allowed):
        if target is self.state:
            return self
        if self.state not in allowed:
            raise ValidationError(f"invalid Purchase transition from {self.state.value} to {target.value}")
        return replace(self, state=target, history=self.history + (target,))

    def submit(self):
        return self._transition(PurchaseState.SUBMITTED, {PurchaseState.DRAFT})

    def approve(self):
        return self._transition(PurchaseState.APPROVED, {PurchaseState.SUBMITTED})

    def mark_ordered(self):
        return self._transition(PurchaseState.ORDERED, {PurchaseState.APPROVED})

    def cancel(self):
        return self._transition(PurchaseState.CANCELLED, {PurchaseState.DRAFT, PurchaseState.SUBMITTED, PurchaseState.APPROVED})

    def receive(self, line_id: UUID, quantity_units: int):
        if self.state not in {PurchaseState.ORDERED, PurchaseState.PARTIALLY_RECEIVED}:
            raise ValidationError("items can be received only after ordering")
        if type(quantity_units) is not int or quantity_units <= 0:
            raise ValidationError("receipt quantity must be a positive integer")
        found = False
        updated = []
        for line in self.lines:
            if line.id == line_id:
                found = True
                if line.received_quantity_units + quantity_units > line.quantity_units:
                    raise ValidationError("receipt exceeds outstanding ordered quantity")
                updated.append(replace(line, received_quantity_units=line.received_quantity_units + quantity_units))
            else:
                updated.append(line)
        if not found:
            raise ValidationError("receipt line does not belong to this Purchase")
        event = ReceiptEvent(line_id=line_id, quantity_units=quantity_units)
        complete = all(line.received_quantity_units == line.quantity_units for line in updated)
        target = PurchaseState.RECEIVED if complete else PurchaseState.PARTIALLY_RECEIVED
        # Each receipt is a versioned domain change, even when the state remains partial.
        history = self.history + (target,)
        return replace(self, lines=tuple(updated), state=target, history=history, receipts=self.receipts + (event,))
