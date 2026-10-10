"""Integration tests for the Commerce/Inventory context projection."""
import uuid
import pytest
from agent_core.commerce_inventory_read import CommerceInventoryContextReader
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

NOW = "2026-10-10T12:00:00Z"
def uid(): return str(uuid.uuid4())

def setup_graph(db, name="Reusable Bottle", with_service=True):
    person, activity, product, offering, service = uid(), uid(), uid(), uid(), uid()
    db.execute("INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
               (person,"active",NOW,NOW))
    db.execute("INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) VALUES(?,?,?,?,?)",
               (activity,person,"active",NOW,NOW))
    db.execute("INSERT INTO products(product_id,name,state,created_at,updated_at) VALUES(?,?,?,?,?)",
               (product,name,"active",NOW,NOW))
    if with_service:
        db.execute("INSERT INTO services(service_id,state,created_at,updated_at) VALUES(?,?,?,?)",
                   (service,"active",NOW,NOW))
    db.execute("INSERT INTO offerings(offering_id,product_id,service_id,activity_id,state,effective_from,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
               (offering,product,service if with_service else None,activity,"active",NOW,NOW,NOW))
    db.commit()
    return activity,product,offering,service if with_service else None

def test_joined_context_includes_all_inventory_states_and_unknown_quantity():
    db=connect_database()
    activity,product,offering,service=setup_graph(db)
    positions=[(uid(),"observed",8,"2026-10-10T10:00:00Z"),
               (uid(),"effective",None,"2026-10-10T11:00:00Z"),
               (uid(),"closed",2,"2026-10-10T09:00:00Z")]
    for pid,state,qty,observed in positions:
        db.execute("INSERT INTO inventory_positions(inventory_position_id,activity_id,product_id,offering_id,scope_key,state,quantity_minor,observed_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                   (pid,activity,product,offering,"store-A",state,qty,observed,NOW,NOW))
    db.commit()
    result=CommerceInventoryContextReader().read(db,offering)
    assert (result.product_id,result.product_name,result.activity_id)==(product,"Reusable Bottle",activity)
    assert (result.service_id,result.service_state)==(service,"active")
    assert {p.state for p in result.inventory_positions}=={"observed","effective","closed"}
    assert next(p for p in result.inventory_positions if p.state=="effective").quantity_minor is None
    assert result.integrity_issues==()
    db.close()

def test_legacy_missing_product_name_is_explicit_not_fabricated():
    db=connect_database()
    _,product,offering,_=setup_graph(db,name="")
    result=CommerceInventoryContextReader().read(db,offering)
    assert result.product_id==product and result.product_name is None
    assert "product_name_missing" in result.integrity_issues
    assert db.execute("SELECT name FROM products WHERE product_id=?",(product,)).fetchone()[0]==""
    db.close()

def test_offering_without_inventory_is_empty_not_availability():
    db=connect_database()
    _,_,offering,_=setup_graph(db,with_service=False)
    result=CommerceInventoryContextReader().read(db,offering)
    assert result.inventory_positions==() and result.service_id is None
    assert not hasattr(result,"availability")
    db.close()

def test_invalid_or_missing_offering_is_rejected():
    db=connect_database()
    reader=CommerceInventoryContextReader()
    with pytest.raises(ValidationError,match="valid UUID"): reader.read(db,"bad-id")
    with pytest.raises(ValidationError,match="does not exist"): reader.read(db,uid())
    db.close()
