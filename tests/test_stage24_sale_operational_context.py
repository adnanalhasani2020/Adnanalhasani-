"Integration tests for a joined operational Sale context."""
import uuid
import pytest

from agent_core.persistence import connect_database
from agent_core.sale_operational_read import SaleOperationalContextReader
from agent_core.shared import ValidationError

NOW = "2026-10-10T12:00:00Z"
def uid(): return str(uuid.uuid4())

def fixture(db, product_name="Lamp"):
    person, activity, product, offering, sale = (uid() for _ in range(5))
    db.execute("INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",(person,"active",NOW,NOW))
    db.execute("INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) VALUES(?,?,?,?,?)",(activity,person,"active",NOW,NOW))
    db.execute("INSERT INTO products(product_id,name,state,created_at,updated_at) VALUES(?,?,?,?,?)",(product,product_name,"active",NOW,NOW))
    db.execute("INSERT INTO offerings(offering_id,product_id,activity_id,state,effective_from,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",(offering,product,activity,"active",NOW,NOW,NOW))
    db.execute("INSERT INTO sales(sale_id,offering_id,activity_id,state,occurred_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",(sale,offering,activity,"confirmed",NOW,NOW,NOW))
    db.commit()
    return activity, product, offering, sale

def test_sale_context_joins_persisted_sale_offering_product_and_inventory_facts():
    db=connect_database()
    activity,product,offering,sale=fixture(db)
    db.execute("INSERT INTO inventory_positions(inventory_position_id,activity_id,product_id,offering_id,scope_key,state,quantity_minor,observed_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
               (uid(),activity,product,offering,"shop-A","effective",None,NOW,NOW,NOW))
    db.commit()
    result=SaleOperationalContextReader().read(db,sale)
    assert (result.sale_id,result.sale_state,result.sale_version)==(sale,"confirmed",1)
    assert result.offering_context.product_name=="Lamp"
    assert result.offering_context.inventory_positions[0].quantity_minor is None
    assert result.integrity_issues==()
    assert not hasattr(result,"is_sellable")
    db.close()

def test_sale_context_preserves_unknown_legacy_product_name_and_reports_it():
    db=connect_database()
    _,_,_,sale=fixture(db,product_name=None)
    result=SaleOperationalContextReader().read(db,sale)
    assert result.offering_context.product_name is None
    assert "product_name_missing" in result.integrity_issues
    assert db.execute("SELECT name FROM products WHERE product_id=?",(result.offering_context.product_id,)).fetchone()[0] is None
    db.close()

def test_invalid_or_missing_sale_is_rejected():
    db=connect_database()
    reader=SaleOperationalContextReader()
    with pytest.raises(ValidationError,match="valid UUID"): reader.read(db,"bad-id")
    with pytest.raises(ValidationError,match="does not exist"): reader.read(db,uid())
    db.close()


def test_sale_operational_context_read_does_not_mutate_inventory_or_database():
    db = connect_database()
    activity, product, offering, sale = fixture(db)
    position_id = uid()
    db.execute(
        "INSERT INTO inventory_positions(inventory_position_id,activity_id,product_id,offering_id,scope_key,state,quantity_minor,observed_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
        (position_id, activity, product, offering, "shop-A", "effective", 12, NOW, NOW, NOW),
    )
    db.commit()

    before_changes = db.total_changes
    before_position = db.execute(
        "SELECT quantity_minor,state FROM inventory_positions WHERE inventory_position_id=?",
        (position_id,),
    ).fetchone()

    result = SaleOperationalContextReader().read(db, sale)

    after_position = db.execute(
        "SELECT quantity_minor,state FROM inventory_positions WHERE inventory_position_id=?",
        (position_id,),
    ).fetchone()
    assert result.offering_context.inventory_positions[0].quantity_minor == 12
    assert db.total_changes == before_changes
    assert after_position == before_position
    db.close()

def test_sale_activity_mismatch_is_rejected_without_mutating_persisted_sale():
    db = connect_database()
    activity, _, _, sale = fixture(db)
    owner_person_id = db.execute(
        "SELECT owner_person_id FROM activities WHERE activity_id=?",
        (activity,),
    ).fetchone()[0]
    other_activity = uid()
    db.execute(
        "INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) VALUES(?,?,?,?,?)",
        (other_activity, owner_person_id, "active", NOW, NOW),
    )
    db.commit()

    before = db.execute(
        "SELECT activity_id, offering_id FROM sales WHERE sale_id=?",
        (sale,),
    ).fetchone()
    with pytest.raises(Exception, match="sale activity must match offering activity"):
        db.execute(
            "UPDATE sales SET activity_id=? WHERE sale_id=?",
            (other_activity, sale),
        )
    after = db.execute(
        "SELECT activity_id, offering_id FROM sales WHERE sale_id=?",
        (sale,),
    ).fetchone()

    assert after == before
    result = SaleOperationalContextReader().read(db, sale)
    assert result.sale_activity_id == activity
    assert result.offering_context.activity_id == activity
    assert "sale_offering_activity_mismatch" not in result.integrity_issues
    db.close()


def test_sale_operational_context_reports_corrupted_persisted_state_and_version():
    db = connect_database()
    _, _, _, sale = fixture(db)
    # Simulate legacy/manual database corruption that bypassed table CHECK constraints.
    db.execute("PRAGMA ignore_check_constraints = ON")
    db.execute("UPDATE sales SET state='corrupted-state', version_no=0 WHERE sale_id=?", (sale,))
    db.commit()

    result = SaleOperationalContextReader().read(db, sale)

    assert result.sale_state == "corrupted-state"
    assert result.sale_version == 0
    assert "sale_state_invalid" in result.integrity_issues
    assert "sale_version_invalid" in result.integrity_issues
    db.close()
