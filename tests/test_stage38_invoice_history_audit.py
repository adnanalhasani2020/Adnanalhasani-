"""Integration tests for read-only Invoice/Domain History integrity checks."""
import uuid

import pytest

from agent_core.invoice_application import InvoiceApplication
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

STAMP = "2026-10-10T12:00:00Z"


def uid():
    return str(uuid.uuid4())


def setup_invoice(db):
    actor, activity, product, offering, sale, grant = [uid() for _ in range(6)]
    db.execute("INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
               (actor, "active", STAMP, STAMP))
    db.execute("INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) VALUES(?,?,?,?,?)",
               (activity, actor, "active", STAMP, STAMP))
    db.execute("INSERT INTO products(product_id,state,created_at,updated_at) VALUES(?,?,?,?)",
               (product, "active", STAMP, STAMP))
    db.execute("INSERT INTO offerings(offering_id,product_id,activity_id,state,effective_from,created_at,updated_at) "
               "VALUES(?,?,?,'active',?,?,?)",
               (offering, product, activity, "2026-10-01T00:00:00Z", STAMP, STAMP))
    db.execute("INSERT INTO sales(sale_id,offering_id,activity_id,state,occurred_at,created_at,updated_at,version_no) "
               "VALUES(?,?,?,'initiated',?,?,?,1)",
               (sale, offering, activity, STAMP, STAMP, STAMP))
    db.execute("INSERT INTO authorization_grants(authorization_grant_id,subject_person_id,action_code,scope_ref,"
               "context_ref,state,effective_from,created_at,updated_at) VALUES(?,?,?,?,?,'active',?,?,?)",
               (grant, actor, "issue_invoice", sale, activity, "2026-10-01T00:00:00Z", STAMP, STAMP))
    db.commit()
    issued = InvoiceApplication().create_invoice(
        db, sale_id=sale, actor_context_ref=actor, authorization_grant_id=grant,
        issuer_ref=actor, invoice_number="INV-AUDIT", now=STAMP,
    )
    return actor, activity, sale, grant, issued


def test_invoice_audit_accepts_complete_issued_history():
    from agent_core.invoice_history_audit import InvoiceHistoryIntegrityAuditor

    db = connect_database()
    *_, issued = setup_invoice(db)
    result = InvoiceHistoryIntegrityAuditor().inspect(db, str(issued.invoice.id))
    assert result.status == "consistent"
    assert result.invoice_version == result.history_record_count == 1
    assert result.findings == ()
    db.close()


def test_invoice_audit_detects_tampered_version_and_final_state():
    from agent_core.invoice_history_audit import InvoiceHistoryIntegrityAuditor

    db = connect_database()
    *_, issued = setup_invoice(db)
    invoice_id = str(issued.invoice.id)
    db.execute(
        "UPDATE domain_history SET current_version_ref=? WHERE target_ref=?",
        (f"invoice-state:{invoice_id}:v9", invoice_id),
    )
    db.execute("UPDATE invoices SET state='void',version_no=2 WHERE invoice_id=?", (invoice_id,))
    db.commit()

    result = InvoiceHistoryIntegrityAuditor().inspect(db, invoice_id)
    assert result.status == "inconsistent"
    assert "history_current_version_mismatch:1" in result.findings
    assert "invoice_history_shorter_than_invoice_version" in result.findings
    assert "history_final_state_mismatch" not in result.findings
    db.close()


def test_invoice_audit_detects_missing_history_without_repair():
    from agent_core.invoice_history_audit import InvoiceHistoryIntegrityAuditor

    db = connect_database()
    *_, issued = setup_invoice(db)
    invoice_id = str(issued.invoice.id)
    db.execute("DELETE FROM domain_history WHERE target_ref=? AND change_type='invoice_state_transition'",
               (invoice_id,))
    db.commit()
    result = InvoiceHistoryIntegrityAuditor().inspect(db, invoice_id)
    assert result.status == "incomplete"
    assert "invoice_history_missing" in result.findings
    assert db.execute("SELECT COUNT(*) FROM domain_history WHERE target_ref=?", (invoice_id,)).fetchone()[0] == 0
    db.close()


def test_invoice_audit_rejects_invalid_or_missing_invoice():
    from agent_core.invoice_history_audit import InvoiceHistoryIntegrityAuditor

    db = connect_database()
    auditor = InvoiceHistoryIntegrityAuditor()
    with pytest.raises(ValidationError, match="valid UUID"):
        auditor.inspect(db, "not-a-uuid")
    with pytest.raises(ValidationError, match="does not exist"):
        auditor.inspect(db, uid())
    db.close()
