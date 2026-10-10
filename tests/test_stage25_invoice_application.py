"""Integration tests for persisted Invoice issuance and voiding."""
from datetime import datetime, timezone
import sqlite3
import uuid

import pytest

from agent_core.invoice_application import InvoiceApplication
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

STAMP = "2026-10-10T12:00:00Z"


def uid():
    return str(uuid.uuid4())


def fixture(db):
    actor, activity, product, offering, sale, grant = [uid() for _ in range(6)]
    db.execute("INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
               (actor, "active", STAMP, STAMP))
    db.execute("INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) VALUES(?,?,?,?,?)",
               (activity, actor, "active", STAMP, STAMP))
    db.execute("INSERT INTO products(product_id,state,created_at,updated_at) VALUES(?,?,?,?)",
               (product, "active", STAMP, STAMP))
    db.execute(
        "INSERT INTO offerings(offering_id,product_id,activity_id,state,effective_from,created_at,updated_at) "
        "VALUES(?,?,?,'active',?,?,?)",
        (offering, product, activity, "2026-10-01T00:00:00Z", STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO sales(sale_id,offering_id,activity_id,state,occurred_at,created_at,updated_at,version_no) "
        "VALUES(?,?,?,'initiated',?,?,?,1)",
        (sale, offering, activity, STAMP, STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO authorization_grants(authorization_grant_id,subject_person_id,action_code,scope_ref,"
        "context_ref,state,effective_from,created_at,updated_at) VALUES(?,?,?,?,?,'active',?,?,?)",
        (grant, actor, "issue_invoice", sale, activity, "2026-10-01T00:00:00Z", STAMP, STAMP),
    )
    db.commit()
    return actor, activity, sale, grant


def test_invoice_issue_and_void_persist_history_and_use_separate_grants(tmp_path):
    path = tmp_path / "invoice.sqlite"
    db = connect_database(path)
    actor, activity, sale, issue_grant = fixture(db)
    app = InvoiceApplication()
    issued = app.create_invoice(
        db, sale_id=sale, actor_context_ref=actor, authorization_grant_id=issue_grant,
        issuer_ref=actor, invoice_number="INV-001", now=STAMP,
    )
    assert issued.invoice.state.value == "issued"
    assert issued.version_no == 1
    assert db.execute(
        "SELECT change_payload_ref FROM domain_history WHERE target_ref=? AND change_type='invoice_state_transition'",
        (str(issued.invoice.id),),
    ).fetchall() == [("state:issued",)]

    # Issue permission cannot authorize voiding.
    with pytest.raises(ValidationError, match="does not permit action void_invoice"):
        app.void_invoice(
            db, issued.invoice.id, actor_context_ref=actor,
            authorization_grant_id=issue_grant, expected_version=1, now=STAMP,
        )

    void_grant = uid()
    db.execute(
        "INSERT INTO authorization_grants(authorization_grant_id,subject_person_id,action_code,scope_ref,"
        "context_ref,state,effective_from,created_at,updated_at) VALUES(?,?,?,?,?,'active',?,?,?)",
        (void_grant, actor, "void_invoice", sale, activity, "2026-10-01T00:00:00Z", STAMP, STAMP),
    )
    db.commit()
    voided = app.void_invoice(
        db, issued.invoice.id, actor_context_ref=actor,
        authorization_grant_id=void_grant, expected_version=1, now=STAMP,
    )
    assert voided.invoice.state.value == "void"
    assert voided.version_no == 2
    assert app.get_invoice(db, issued.invoice.id).invoice.state.value == "void"
    history = db.execute(
        "SELECT prior_version_ref,current_version_ref,change_payload_ref FROM domain_history "
        "WHERE target_ref=? AND change_type='invoice_state_transition' ORDER BY rowid",
        (str(issued.invoice.id),),
    ).fetchall()
    assert history == [
        (None, f"invoice-state:{issued.invoice.id}:v1", "state:issued"),
        (f"invoice-state:{issued.invoice.id}:v1", f"invoice-state:{issued.invoice.id}:v2", "state:void"),
    ]
    db.close()


def test_invoice_issue_rejects_missing_sale_or_wrong_grant_scope_without_write():
    db = connect_database()
    actor, activity, sale, grant = fixture(db)
    app = InvoiceApplication()
    with pytest.raises(ValidationError, match="existing Sale"):
        app.create_invoice(
            db, sale_id=uid(), actor_context_ref=actor, authorization_grant_id=grant,
            issuer_ref=actor, invoice_number="INV-X", now=STAMP,
        )
    wrong_scope = uid()
    db.execute(
        "INSERT INTO authorization_grants(authorization_grant_id,subject_person_id,action_code,scope_ref,"
        "context_ref,state,effective_from,created_at,updated_at) VALUES(?,?,?,?,?,'active',?,?,?)",
        (wrong_scope, actor, "issue_invoice", uid(), activity, "2026-10-01T00:00:00Z", STAMP, STAMP),
    )
    db.commit()
    with pytest.raises(ValidationError, match="scope does not match Sale"):
        app.create_invoice(
            db, sale_id=sale, actor_context_ref=actor, authorization_grant_id=wrong_scope,
            issuer_ref=actor, invoice_number="INV-Y", now=STAMP,
        )
    assert db.execute("SELECT COUNT(*) FROM invoices").fetchone()[0] == 0
    db.close()


def test_invoice_storage_failure_rolls_back_invoice_and_history():
    db = connect_database()
    actor, activity, sale, grant = fixture(db)
    db.execute(
        "CREATE TRIGGER fail_invoice_history BEFORE INSERT ON domain_history "
        "WHEN NEW.change_type='invoice_state_transition' BEGIN SELECT RAISE(ABORT, 'history unavailable'); END"
    )
    db.commit()
    with pytest.raises(sqlite3.IntegrityError, match="history unavailable"):
        InvoiceApplication().create_invoice(
            db, sale_id=sale, actor_context_ref=actor, authorization_grant_id=grant,
            issuer_ref=actor, invoice_number="INV-FAIL", now=STAMP,
        )
    assert db.execute("SELECT COUNT(*) FROM invoices").fetchone()[0] == 0
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE change_type='invoice_state_transition'"
    ).fetchone()[0] == 0
    db.close()


def test_invoice_void_rejects_stale_version_without_mutation():
    db = connect_database()
    actor, activity, sale, grant = fixture(db)
    app = InvoiceApplication()
    issued = app.create_invoice(
        db, sale_id=sale, actor_context_ref=actor, authorization_grant_id=grant,
        issuer_ref=actor, invoice_number="INV-STALE", now=STAMP,
    )
    void_grant = uid()
    db.execute(
        "INSERT INTO authorization_grants(authorization_grant_id,subject_person_id,action_code,scope_ref,"
        "context_ref,state,effective_from,created_at,updated_at) VALUES(?,?,?,?,?,'active',?,?,?)",
        (void_grant, actor, "void_invoice", sale, activity, "2026-10-01T00:00:00Z", STAMP, STAMP),
    )
    db.commit()
    with pytest.raises(ValidationError, match="version conflict"):
        app.void_invoice(
            db, issued.invoice.id, actor_context_ref=actor,
            authorization_grant_id=void_grant, expected_version=9, now=STAMP,
        )
    assert app.get_invoice(db, issued.invoice.id).invoice.state.value == "issued"
    assert app.get_invoice(db, issued.invoice.id).version_no == 1
    db.close()
