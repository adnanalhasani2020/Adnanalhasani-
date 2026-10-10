"""Integration tests for non-destructive Product legacy-name auditing."""
import uuid
from agent_core.persistence import connect_database
from agent_core.product_legacy_audit import ProductLegacyNameAuditor

NOW="2026-10-10T12:00:00Z"
def uid(): return str(uuid.uuid4())

def test_audit_reports_missing_names_and_preserves_offering_sale_references():
    db=connect_database()
    person,activity,legacy_product,known_product=uid(),uid(),uid(),uid()
    offering1,offering2,sale1,sale2=uid(),uid(),uid(),uid()
    db.execute("INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
               (person,"active",NOW,NOW))
    db.execute("INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) VALUES(?,?,?,?,?)",
               (activity,person,"active",NOW,NOW))
    db.execute("INSERT INTO products(product_id,name,state,created_at,updated_at) VALUES(?,?,?,?,?)",
               (legacy_product,None,"retired",NOW,NOW))
    db.execute("INSERT INTO products(product_id,name,state,created_at,updated_at) VALUES(?,?,?,?,?)",
               (known_product,"Known product","active",NOW,NOW))
    for oid in (offering1,offering2):
        db.execute("INSERT INTO offerings(offering_id,product_id,activity_id,state,effective_from,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                   (oid,legacy_product,activity,"ended",NOW,NOW,NOW))
    db.execute("INSERT INTO sales(sale_id,offering_id,activity_id,state,occurred_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
               (sale1,offering1,activity,"completed",NOW,NOW,NOW))
    db.execute("INSERT INTO sales(sale_id,offering_id,activity_id,state,occurred_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
               (sale2,offering2,activity,"cancelled",NOW,NOW,NOW))
    db.commit()

    before_offerings=db.execute("SELECT offering_id,product_id,state FROM offerings ORDER BY offering_id").fetchall()
    before_sales=db.execute("SELECT sale_id,offering_id,state FROM sales ORDER BY sale_id").fetchall()
    audit=ProductLegacyNameAuditor().inspect(db)
    assert audit.affected_product_count==1
    assert audit.referenced_product_count==1
    gap=audit.gaps[0]
    assert gap.product_id==legacy_product
    assert gap.product_state=="retired"
    assert set(gap.offering_ids)=={offering1,offering2}
    assert set(gap.sale_ids)=={sale1,sale2}
    assert gap.offering_count==2 and gap.sale_count==2
    assert db.execute("SELECT offering_id,product_id,state FROM offerings ORDER BY offering_id").fetchall()==before_offerings
    assert db.execute("SELECT sale_id,offering_id,state FROM sales ORDER BY sale_id").fetchall()==before_sales
    db.close()

def test_audit_includes_blank_names_but_excludes_named_products():
    db=connect_database()
    person,activity,blank,named=uid(),uid(),uid(),uid()
    db.execute("INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
               (person,"active",NOW,NOW))
    db.execute("INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) VALUES(?,?,?,?,?)",
               (activity,person,"active",NOW,NOW))
    db.execute("INSERT INTO products(product_id,name,state,created_at,updated_at) VALUES(?,?,?,?,?)",
               (blank,"   ","draft",NOW,NOW))
    db.execute("INSERT INTO products(product_id,name,state,created_at,updated_at) VALUES(?,?,?,?,?)",
               (named,"Lamp","active",NOW,NOW))
    db.commit()
    result=ProductLegacyNameAuditor().inspect(db)
    assert [gap.product_id for gap in result.gaps]==[blank]
    assert result.gaps[0].offering_ids==() and result.gaps[0].sale_ids==()
    db.close()
