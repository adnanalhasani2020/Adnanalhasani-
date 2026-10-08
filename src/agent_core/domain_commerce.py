from dataclasses import dataclass,field
from enum import Enum
from uuid import UUID
from typing import Tuple
from agent_core.shared import ValidationError,new_id

class SaleState(str,Enum):
    INITIATED="initiated"; CONFIRMED="confirmed"; COMPLETED="completed"; CANCELLED="cancelled"; RETURNED="returned"

class InvoiceState(str,Enum):
    ISSUED="issued"; VOID="void"; SETTLED="settled"

@dataclass
class Sale:
    offering_id:UUID
    activity_id:UUID
    id:UUID=field(default_factory=new_id)
    state:SaleState=SaleState.INITIATED
    history:Tuple[SaleState,...]=field(default_factory=lambda:(SaleState.INITIATED,))
    def __post_init__(self):
        if not isinstance(self.offering_id,UUID): raise ValidationError("Sale must reference Offering")
        if not isinstance(self.activity_id,UUID): raise ValidationError("Sale must reference Activity context")
        if not self.history or self.history[-1] != self.state: raise ValidationError("Sale history must end at current state")
    def _transition(self,state:SaleState,allowed:Tuple[SaleState,...]):
        if self.state is state: return
        if self.state not in allowed: raise ValidationError(f"invalid Sale transition from {self.state.value} to {state.value}")
        self.state=state
        self.history=self.history+(state,)
    def confirm(self): self._transition(SaleState.CONFIRMED,(SaleState.INITIATED,))
    def cancel(self): self._transition(SaleState.CANCELLED,(SaleState.INITIATED,SaleState.CONFIRMED))
    def complete(self): self._transition(SaleState.COMPLETED,(SaleState.CONFIRMED,))
    def return_sale(self): self._transition(SaleState.RETURNED,(SaleState.COMPLETED,))

@dataclass
class Invoice:
    sale_id:UUID; id:UUID=field(default_factory=new_id); state:InvoiceState=InvoiceState.ISSUED
    def __post_init__(self):
        if not isinstance(self.sale_id,UUID): raise ValidationError("Invoice must reference Sale")
    def void(self): self.state=InvoiceState.VOID
