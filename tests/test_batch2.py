from datetime import datetime
from uuid import uuid4
import pytest
from agent_core.shared import ValidationError
from agent_core.domain_inventory import Product,Offering,InventoryPosition,Availability,Service,AvailabilityState
from agent_core.domain_commerce import Sale,Invoice,SaleState
from agent_core.domain_activities import Activity
from agent_core.domain_communication import Conversation,Message,ChannelContext,ConversationState,MessageState

def test_inventory_boundaries():
    p=Product("P"); o=Offering(p.id, uuid4()); ip=InventoryPosition(o.id, uuid4(), scope_key="default"); av=Availability(o.id,AvailabilityState.COMPUTED)
    assert p.id!=o.id and o.id!=ip.id and av.offering_id==o.id
    assert not hasattr(o,"inventory_state") and not hasattr(av,"inventory_position_id")
def test_inventory_rejects_bad_refs_and_blank_product():
    with pytest.raises(ValidationError): Product(" ")
    with pytest.raises(ValidationError): Offering("bad", uuid4())
    with pytest.raises(ValidationError): InventoryPosition("bad", uuid4(), scope_key="default")
def test_inventory_lifecycle():
    p=Product("P"); p.activate(); o=Offering(p.id, uuid4()); o.activate(); ip=InventoryPosition(o.id, uuid4(), scope_key="default"); ip.make_unavailable()
    assert ip.state.value=="closed"
def test_discovery_result_is_not_inventory_truth():
    p=Product("P"); o=Offering(p.id, uuid4()); av=Availability(o.id,AvailabilityState.COMPUTED)
    assert not hasattr(av,"state_source") and not hasattr(av,"inventory_position_id")
def test_service_is_separate():
    assert Service("S").id!=Product("P").id
def test_sale_boundaries_and_activity_context():
    p=Product("P"); o=Offering(p.id, uuid4()); a=Activity("A"); s=Sale(o.id,a.id); inv=Invoice(s.id)
    assert o.id!=s.id and s.id!=inv.id and not hasattr(s,"payment_id") and not hasattr(s,"financial_transaction_id")
def test_sale_lifecycle():
    p=Product("P"); o=Offering(p.id, uuid4()); s=Sale(o.id,Activity("A").id); s.confirm(); s.complete()
    assert s.state==SaleState.COMPLETED
def test_sale_completion_requires_confirmation():
    p=Product("P"); o=Offering(p.id, uuid4()); s=Sale(o.id,Activity("A").id)
    with pytest.raises(ValidationError): s.complete()
def test_invoice_is_not_payment_or_settlement():
    i=Invoice(Sale(Offering(Product("P").id).id,Activity("A").id).id)
    assert not hasattr(i,"payment_id") and not hasattr(i,"settlement_id")
def test_communication_boundaries():
    c=Conversation(); m=Message(c.id,"hello"); ch=ChannelContext("channel")
    assert c.id!=m.id and ch.channel_type!="authorization"
    assert not hasattr(c,"authorization") and not hasattr(m,"authorization")
def test_message_requires_conversation_and_content():
    with pytest.raises(ValidationError): Message("bad","x")
    with pytest.raises(ValidationError): Message(Conversation().id," ")
def test_communication_lifecycle():
    c=Conversation(); m=Message(c.id,"hello"); m.send(); c.close()
    assert m.state==MessageState.SENT and c.state==ConversationState.CLOSED
def test_channel_context_is_not_conversation():
    ch=ChannelContext("voice"); c=Conversation()
    assert not hasattr(ch,"conversation_id") and ch is not c
