from dataclasses import dataclass,field
from enum import Enum
from uuid import UUID
from agent_core.shared import ValidationError,new_id
class SaleState(str,Enum): INITIATED="initiated"; CONFIRMED="confirmed"; CANCELLED="cancelled"; COMPLETED="completed"
class InvoiceState(str,Enum): ISSUED="issued"; VOID="void"; SETTLED="settled"
@dataclass
class Sale:
    offering_id:UUID; activity_id:UUID; id:UUID=field(default_factory=new_id); state:SaleState=SaleState.INITIATED
    def __post_init__(self):
        if not isinstance(self.offering_id,UUID): raise ValidationError("Sale must reference Offering")
        if not isinstance(self.activity_id,UUID): raise ValidationError("Sale must reference Activity context")
    def confirm(self): self.state=SaleState.CONFIRMED
    def cancel(self): self.state=SaleState.CANCELLED
    def complete(self):
        if self.state!=SaleState.CONFIRMED: raise ValidationError("only confirmed Sale can complete")
        self.state=SaleState.COMPLETED
@dataclass
class Invoice:
    sale_id:UUID; id:UUID=field(default_factory=new_id); state:InvoiceState=InvoiceState.ISSUED
    def __post_init__(self):
        if not isinstance(self.sale_id,UUID): raise ValidationError("Invoice must reference Sale")
    def void(self): self.state=InvoiceState.VOID
