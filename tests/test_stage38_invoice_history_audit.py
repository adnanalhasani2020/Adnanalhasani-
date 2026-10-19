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



def test_invoice_audit_detects_broken_prior_reference():
    from agent_core.invoice_history_audit import InvoiceHistoryIntegrityAuditor

    db = connect_database()
    *_, issued = setup_invoice(db)
    invoice_id = str(issued.invoice.id)
    db.execute(
        "UPDATE domain_history SET prior_version_ref=? WHERE target_ref=? AND change_type='invoice_state_transition'",
        (f"invoice-state:{invoice_id}:v9", invoice_id),
    )
    db.commit()

    result = InvoiceHistoryIntegrityAuditor().inspect(db, invoice_id)
    assert result.status == "inconsistent"
    assert "history_version_chain_break:1" in result.findings
    db.close()


def test_invoice_audit_detects_invalid_state_transition():
    from agent_core.invoice_history_audit import InvoiceHistoryIntegrityAuditor

    db = connect_database()
    actor, activity, sale, grant, issued = setup_invoice(db)
    invoice_id = str(issued.invoice.id)
    db.execute(
        "UPDATE authorization_grants SET action_code='void_invoice' WHERE authorization_grant_id=?",
        (grant,),
    )
    db.commit()
    voided = InvoiceApplication().void_invoice(
        db, invoice_id, actor_context_ref=actor, authorization_grant_id=grant,
        now="2026-10-10T12:01:00Z", expected_version=1,
    )
    assert voided.invoice.state.value == "void"
    db.execute(
        "UPDATE domain_history SET change_payload_ref='state:issued' "
        "WHERE target_ref=? AND change_type='invoice_state_transition' "
        "AND current_version_ref=?",
        (invoice_id, f"invoice-state:{invoice_id}:v2"),
    )
    db.commit()

    result = InvoiceHistoryIntegrityAuditor().inspect(db, invoice_id)
    assert result.status == "inconsistent"
    assert "history_state_transition_invalid:2" in result.findings
    db.close()


def test_invoice_audit_detects_history_exceeding_persisted_version():
    from agent_core.invoice_history_audit import InvoiceHistoryIntegrityAuditor

    db = connect_database()
    *_, issued = setup_invoice(db)
    invoice_id = str(issued.invoice.id)
    db.execute(
        "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,historical_at,"
        "actor_context_ref,prior_version_ref,current_version_ref,change_payload_ref,created_at) "
        "VALUES(?,?,?,?,?,?,?,?,?,?)",
        (uid(), "commerce", invoice_id, "invoice_state_transition", STAMP, uid(),
         f"invoice-state:{invoice_id}:v1", f"invoice-state:{invoice_id}:v2", "state:void", STAMP),
    )
    db.commit()

    result = InvoiceHistoryIntegrityAuditor().inspect(db, invoice_id)
    assert result.status == "inconsistent"
    assert "invoice_history_exceeds_invoice_version" in result.findings
    db.close()


def test_invoice_audit_detects_final_state_mismatch_at_matching_version():
    from agent_core.invoice_history_audit import InvoiceHistoryIntegrityAuditor

    db = connect_database()
    *_, issued = setup_invoice(db)
    invoice_id = str(issued.invoice.id)
    db.execute("UPDATE invoices SET state='void' WHERE invoice_id=?", (invoice_id,))
    db.commit()

    result = InvoiceHistoryIntegrityAuditor().inspect(db, invoice_id)
    assert result.status == "inconsistent"
    assert "history_final_state_mismatch" in result.findings
    db.close()


def test_invoice_audit_detects_missing_history_actor_context():
    from agent_core.invoice_history_audit import InvoiceHistoryIntegrityAuditor

    db = connect_database()
    *_, issued = setup_invoice(db)
    invoice_id = str(issued.invoice.id)
    db.execute(
        "UPDATE domain_history SET actor_context_ref=' ' WHERE target_ref=? "
        "AND change_type='invoice_state_transition'",
        (invoice_id,),
    )
    db.commit()

    result = InvoiceHistoryIntegrityAuditor().inspect(db, invoice_id)
    assert result.status == "inconsistent"
    assert "history_actor_context_missing:1" in result.findings
    db.close()


def test_invoice_audit_detects_non_chronological_history():
    from agent_core.invoice_history_audit import InvoiceHistoryIntegrityAuditor

    db = connect_database()
    actor, _activity, _sale, grant, issued = setup_invoice(db)
    invoice_id = str(issued.invoice.id)
    db.execute(
        "UPDATE authorization_grants SET action_code='void_invoice' WHERE authorization_grant_id=?",
        (grant,),
    )
    db.commit()
    InvoiceApplication().void_invoice(
        db, invoice_id, actor_context_ref=actor, authorization_grant_id=grant,
        now="2026-10-10T12:01:00Z", expected_version=1,
    )
    db.execute(
        "UPDATE domain_history SET historical_at='2026-10-10T12:02:00Z' "
        "WHERE target_ref=? AND change_type='invoice_state_transition' "
        "AND current_version_ref=?",
        (invoice_id, f"invoice-state:{invoice_id}:v1"),
    )
    db.commit()

    result = InvoiceHistoryIntegrityAuditor().inspect(db, invoice_id)
    assert result.status == "inconsistent"
    assert "history_timestamps_not_chronological:2" in result.findings
    db.close()


def test_invoice_audit_detects_history_timestamp_before_invoice_creation():
    from agent_core.invoice_history_audit import InvoiceHistoryIntegrityAuditor

    db = connect_database()
    *_, issued = setup_invoice(db)
    invoice_id = str(issued.invoice.id)
    db.execute(
        "UPDATE domain_history SET historical_at='2026-10-10T11:59:00Z' "
        "WHERE target_ref=? AND change_type='invoice_state_transition'",
        (invoice_id,),
    )
    db.commit()

    result = InvoiceHistoryIntegrityAuditor().inspect(db, invoice_id)
    assert result.status == "inconsistent"
    assert "history_timestamp_before_invoice_creation:1" in result.findings
    db.close()


def test_invoice_reader_rejects_persisted_due_timestamp_before_issue():
    db = connect_database()
    *_, issued = setup_invoice(db)
    invoice_id = str(issued.invoice.id)
    db.execute(
        "UPDATE invoices SET due_at='2026-10-10T11:59:59Z' WHERE invoice_id=?",
        (invoice_id,),
    )
    db.commit()

    with pytest.raises(ValidationError, match="due timestamp precedes issue timestamp"):
        InvoiceApplication().get_invoice(db, invoice_id)
    db.close()


def test_invoice_reader_rejects_sale_offering_activity_drift():
    db = connect_database()
    actor, activity, sale, _grant, issued = setup_invoice(db)
    other_activity = uid()
    db.execute(
        "INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) "
        "VALUES(?,?,'active',?,?)",
        (other_activity, actor, STAMP, STAMP),
    )
    db.execute("UPDATE sales SET activity_id=? WHERE sale_id=?", (other_activity, sale))
    db.commit()

    with pytest.raises(ValidationError, match="Sale Activity does not match its Offering Activity"):
        InvoiceApplication().get_invoice(db, str(issued.invoice.id))
    db.close()


def test_invoice_reader_rejects_issue_timestamp_before_sale_creation():
    db = connect_database()
    *_, issued = setup_invoice(db)
    invoice_id = str(issued.invoice.id)
    db.execute(
        "UPDATE invoices SET issue_at='2026-10-10T11:59:59Z' WHERE invoice_id=?",
        (invoice_id,),
    )
    db.commit()

    with pytest.raises(ValidationError, match="issue timestamp precedes Sale creation timestamp"):
        InvoiceApplication().get_invoice(db, invoice_id)
    db.close()
