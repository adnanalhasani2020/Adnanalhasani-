from dataclasses import dataclass,field
from datetime import datetime, timezone
from enum import Enum
from typing import Optional
from uuid import UUID
from agent_core.shared import ValidationError,new_id

class ProductState(str,Enum):
    DRAFT="draft"; ACTIVE="active"; RETIRED="retired"

class ServiceState(str,Enum):
    DEFINED="defined"; ACTIVE="active"; INACTIVE="inactive"; RETIRED="retired"

class OfferingState(str,Enum):
    DRAFT="draft"; ACTIVE="active"; ENDED="ended"; WITHDRAWN="withdrawn"

class InventoryPositionState(str,Enum):
    OBSERVED="observed"; EFFECTIVE="effective"; CLOSED="closed"

class AvailabilityState(str,Enum):
    COMPUTED="computed"; STALE="stale"; INVALID="invalid"

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
    def retire(self): self.state=ServiceState.RETIRED

@dataclass
class Offering:
    product_id:UUID
    activity_id:UUID
    service_id:Optional[UUID]=None
    id:UUID=field(default_factory=new_id)
    state:OfferingState=OfferingState.DRAFT
    effective_from:Optional[datetime]=None
    effective_to:Optional[datetime]=None
    def __post_init__(self):
        if not isinstance(self.product_id,UUID): raise ValidationError("Offering must reference Product")
        if not isinstance(self.activity_id,UUID): raise ValidationError("Offering must reference Activity")
        if self.service_id is not None and not isinstance(self.service_id,UUID): raise ValidationError("Offering.service_id must be a UUID")
        if self.effective_from is not None and not isinstance(self.effective_from,datetime): raise ValidationError("Offering.effective_from must be datetime")
        if self.effective_to is not None and not isinstance(self.effective_to,datetime): raise ValidationError("Offering.effective_to must be datetime")
        if self.effective_from is not None and self.effective_to is not None:
            starts = self.effective_from
            ends = self.effective_to
            if starts.tzinfo is not None and starts.utcoffset() is not None and ends.tzinfo is not None and ends.utcoffset() is not None:
                starts = starts.astimezone(timezone.utc)
                ends = ends.astimezone(timezone.utc)
            if ends < starts:
                raise ValidationError("Offering.effective_to cannot precede effective_from")
    def activate(self): self.state=OfferingState.ACTIVE
    def end(self): self.state=OfferingState.ENDED
    def withdraw(self): self.state=OfferingState.WITHDRAWN
    def retire(self): self.withdraw()

@dataclass
class InventoryPosition:
    offering_id:Optional[UUID]
    activity_id:UUID
    product_id:Optional[UUID]=None
    location_ref:Optional[str]=None
    scope_key:str=""
    quantity_minor:Optional[int]=None
    observed_at:datetime=field(default_factory=lambda: datetime.now(timezone.utc))
    effective_from:Optional[datetime]=None
    effective_to:Optional[datetime]=None
    id:UUID=field(default_factory=new_id)
    state:InventoryPositionState=InventoryPositionState.OBSERVED
    def __post_init__(self):
        if self.offering_id is not None and not isinstance(self.offering_id,UUID): raise ValidationError("Inventory Position offering_id must be a UUID")
        if not isinstance(self.activity_id,UUID): raise ValidationError("Inventory Position must reference Activity")
        if self.product_id is not None and not isinstance(self.product_id,UUID): raise ValidationError("Inventory Position product_id must be a UUID")
        if not isinstance(self.scope_key,str) or not self.scope_key.strip(): raise ValidationError("Inventory Position.scope_key is required")
        if not isinstance(self.observed_at,datetime): raise ValidationError("Inventory Position.observed_at must be datetime")
        if self.observed_at.tzinfo is None or self.observed_at.utcoffset() is None:
            raise ValidationError("Inventory Position.observed_at must be timezone-aware")
        if self.effective_from is not None and not isinstance(self.effective_from,datetime): raise ValidationError("Inventory Position.effective_from must be datetime")
        if self.effective_from is not None and (self.effective_from.tzinfo is None or self.effective_from.utcoffset() is None):
            raise ValidationError("Inventory Position.effective_from must be timezone-aware")
        if self.effective_to is not None and not isinstance(self.effective_to,datetime): raise ValidationError("Inventory Position.effective_to must be datetime")
        if self.effective_to is not None and (self.effective_to.tzinfo is None or self.effective_to.utcoffset() is None):
            raise ValidationError("Inventory Position.effective_to must be timezone-aware")
        if self.effective_from is not None and self.effective_to is not None:
            starts = self.effective_from.astimezone(timezone.utc)
            ends = self.effective_to.astimezone(timezone.utc)
            if ends < starts:
                raise ValidationError("Inventory Position.effective_to cannot precede effective_from")
    def make_effective(self):
        if self.state is not InventoryPositionState.OBSERVED:
            raise ValidationError("Only an observed Inventory Position can become effective")
        self.state=InventoryPositionState.EFFECTIVE
    def close(self):
        if self.state is not InventoryPositionState.EFFECTIVE:
            raise ValidationError("Only an effective Inventory Position can be closed")
        self.state=InventoryPositionState.CLOSED
    def make_unavailable(self): self.close()
    def retire(self): self.close()

@dataclass(frozen=True)
class Availability:
    offering_id:UUID
    state:AvailabilityState
    valid_at:datetime
    freshness_at:Optional[datetime]=None
    location_ref:Optional[str]=None
    provenance_ref:Optional[str]=None
    def __post_init__(self):
        if not isinstance(self.offering_id,UUID): raise ValidationError("Availability must reference Offering")
        if not isinstance(self.valid_at,datetime): raise ValidationError("Availability.valid_at must be datetime")
        if self.freshness_at is not None and not isinstance(self.freshness_at,datetime): raise ValidationError("Availability.freshness_at must be datetime")

def discovery_result_is_positive(availability:Availability)->bool:
    """Discovery boundary only: consumes a derived Availability result; no proximity/search/ranking calculation."""
    if not isinstance(availability,Availability): raise ValidationError("Discovery requires Availability")
    return availability.state is AvailabilityState.COMPUTED
