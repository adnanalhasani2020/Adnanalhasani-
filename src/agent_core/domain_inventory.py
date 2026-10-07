from dataclasses import dataclass,field
from enum import Enum
from uuid import UUID
from agent_core.shared import ValidationError,new_id
class ProductState(str,Enum): DRAFT="draft"; ACTIVE="active"; INACTIVE="inactive"; RETIRED="retired"
class OfferingState(str,Enum): DRAFT="draft"; ACTIVE="active"; INACTIVE="inactive"; RETIRED="retired"
class InventoryPositionState(str,Enum): AVAILABLE="available"; UNAVAILABLE="unavailable"; RETIRED="retired"
class AvailabilityState(str,Enum): AVAILABLE="available"; UNAVAILABLE="unavailable"
class ServiceState(str,Enum): DEFINED="defined"; ACTIVE="active"; INACTIVE="inactive"
@dataclass
class Product:
    name:str; id:UUID=field(default_factory=new_id); state:ProductState=ProductState.DRAFT
    def __post_init__(self):
        if not isinstance(self.name,str) or not self.name.strip(): raise ValidationError("Product.name is required")
        self.name=self.name.strip()
    def activate(self): self.state=ProductState.ACTIVE
    def retire(self): self.state=ProductState.RETIRED
@dataclass
class Service:
    name:str; id:UUID=field(default_factory=new_id); state:ServiceState=ServiceState.DEFINED
    def __post_init__(self):
        if not isinstance(self.name,str) or not self.name.strip(): raise ValidationError("Service.name is required")
        self.name=self.name.strip()
    def activate(self): self.state=ServiceState.ACTIVE
@dataclass
class Offering:
    product_id:UUID; id:UUID=field(default_factory=new_id); state:OfferingState=OfferingState.DRAFT
    def __post_init__(self):
        if not isinstance(self.product_id,UUID): raise ValidationError("Offering must reference Product")
    def activate(self): self.state=OfferingState.ACTIVE
    def retire(self): self.state=OfferingState.RETIRED
@dataclass
class InventoryPosition:
    offering_id:UUID; id:UUID=field(default_factory=new_id); state:InventoryPositionState=InventoryPositionState.AVAILABLE
    def __post_init__(self):
        if not isinstance(self.offering_id,UUID): raise ValidationError("Inventory Position must reference Offering")
    def make_unavailable(self): self.state=InventoryPositionState.UNAVAILABLE
    def retire(self): self.state=InventoryPositionState.RETIRED
@dataclass(frozen=True)
class Availability:
    offering_id:UUID; state:AvailabilityState
    def __post_init__(self):
        if not isinstance(self.offering_id,UUID): raise ValidationError("Availability must reference Offering")
