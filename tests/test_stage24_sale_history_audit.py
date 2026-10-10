"""Integration tests for read-only Sale/Domain History integrity checks."""
import uuid
import pytest

from agent_core.persistence import connect_database
from agent_core.sale_history_audit import SaleHistoryIntegrityAuditor
from agent_core.shared import ValidationError

NOW = "2026-10-10T12:00:00Z"
def uid(): return str(uuid.uuid4())

def sale_fixture(db, version=2):
    person, activity, product, offering, sale = (uid() for _ in range(5))
    for sql, params in [
        ("INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",(person,"active",NOW,NOW)),
        ("INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) VALUES(?,?,?,?,?)",(activity,person,"active",NOW,NOW)),
        ("INSERT INTO products(product_id,state,created_at,updated_at) VALUES(?,?,?,?)",(product,"active",NOW,NOW)),
        ("INSERT INTO offerings(offering_id,product_id,activity_id,state,effective_from,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",(offering,product,activity,"active",NOW,NOW,NOW)),
        ("INSERT INTO sales(sale_id,offering_id,activity_id,state,occurred_at,created_at,updated_at,version_no) VALUES(?,?,?,?,?,?,?,?)",(sale,offering,activity,"confirmed",NOW,NOW,NOW,version)),
    ]:
        db.execute(sql, params)
    db.commit()
    return sale

def history(db, sale, prior, current):
    db.execute("INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,historical_at,actor_context_ref,prior_version_ref,current_version_ref,change_payload_ref,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
               (uid(),"Commerce",sale,"state_change",NOW,"actor",prior,current,"state:confirmed",NOW))

def test_audit_recognizes_complete_version_chain():
    db = connect_database()
    sale = sale_fixture(db)
    history(db,sale,None,"v1")
    history(db,sale,"v1","v2")
    db.commit()
    result = SaleHistoryIntegrityAuditor().inspect(db,sale)
    assert result.status=="consistent"
    assert result.history_record_count==result.sale_version==2
    assert result.findings==()
    db.close()

def test_audit_distinguishes_missing_history_from_broken_chain_without_repair():
    db = connect_database()
    sale = sale_fixture(db)
    auditor = SaleHistoryIntegrityAuditor()
    missing = auditor.inspect(db,sale)
    assert missing.status=="incomplete" and "sale_history_missing" in missing.findings
    history(db,sale,None,"v1")
    history(db,sale,"wrong-prior","v2")
    db.commit()
    before = db.execute("SELECT prior_version_ref,current_version_ref FROM domain_history WHERE target_ref=? ORDER BY rowid",(sale,)).fetchall()
    result = auditor.inspect(db,sale)
    assert result.status=="inconsistent"
    assert "history_version_chain_break:2" in result.findings
    assert db.execute("SELECT prior_version_ref,current_version_ref FROM domain_history WHERE target_ref=? ORDER BY rowid",(sale,)).fetchall()==before
    db.close()

def test_audit_rejects_invalid_or_missing_sale():
    db = connect_database()
    auditor = SaleHistoryIntegrityAuditor()
    with pytest.raises(ValidationError,match="valid UUID"):
        auditor.inspect(db,"not-a-uuid")
    with pytest.raises(ValidationError,match="does not exist"):
        auditor.inspect(db,uid())
    db.close()
