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


def test_invoice_issue_rejects_issue_timestamp_before_sale_creation_without_writes():
    db = connect_database()
    actor, _activity, sale, grant = fixture(db)
    invoice_id = uid()

    with pytest.raises(ValidationError, match="cannot precede Sale creation timestamp"):
        InvoiceApplication().create_invoice(
            db, sale_id=sale, actor_context_ref=actor,
            authorization_grant_id=grant, issuer_ref=actor,
            invoice_number="INV-BEFORE-SALE", invoice_id=invoice_id,
            issue_at="2026-10-10T11:59:59Z", now=STAMP,
        )

    assert db.execute(
        "SELECT COUNT(*) FROM invoices WHERE invoice_id=?", (invoice_id,)
    ).fetchone()[0] == 0
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=? AND change_type='invoice_state_transition'",
        (invoice_id,),
    ).fetchone()[0] == 0
    db.close()


def test_conflicting_persisted_sale_states_require_explicit_reconciliation():
    from agent_core.domain_commerce import SaleState

    with pytest.raises(ValidationError, match="Sale state and lifecycle_state disagree"):
        SaleState.from_persisted("completed", "cancelled")


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


def test_invoice_issue_preserves_caller_owned_transaction():
    db = connect_database()
    actor, activity, sale, grant = fixture(db)
    caller_person = uid()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
        (caller_person, STAMP, STAMP),
    )
    assert db.in_transaction

    InvoiceApplication().create_invoice(
        db, sale_id=sale, actor_context_ref=actor, authorization_grant_id=grant,
        issuer_ref=actor, invoice_number="INV-CALLER-TX", now=STAMP,
    )

    # The application releases only its savepoint; it must not commit caller work.
    assert db.in_transaction
    db.rollback()
    assert db.execute(
        "SELECT COUNT(*) FROM persons WHERE person_id=?", (caller_person,)
    ).fetchone()[0] == 0
    assert db.execute(
        "SELECT COUNT(*) FROM invoices WHERE invoice_number='INV-CALLER-TX'"
    ).fetchone()[0] == 0
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE change_type='invoice_state_transition'"
    ).fetchone()[0] == 0
    db.close()


def test_invoice_issue_failure_preserves_caller_owned_transaction():
    db = connect_database()
    actor, activity, sale, grant = fixture(db)
    caller_person = uid()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
        (caller_person, STAMP, STAMP),
    )
    db.execute(
        "CREATE TRIGGER fail_invoice_history BEFORE INSERT ON domain_history "
        "WHEN NEW.change_type='invoice_state_transition' "
        "BEGIN SELECT RAISE(ABORT, 'history unavailable'); END"
    )
    assert db.in_transaction

    with pytest.raises(sqlite3.IntegrityError, match="history unavailable"):
        InvoiceApplication().create_invoice(
            db, sale_id=sale, actor_context_ref=actor, authorization_grant_id=grant,
            issuer_ref=actor, invoice_number="INV-CALLER-TX-FAIL", now=STAMP,
        )

    # Failure rolls back only the invoice savepoint; the caller still owns its transaction.
    assert db.in_transaction
    assert db.execute(
        "SELECT COUNT(*) FROM invoices WHERE invoice_number='INV-CALLER-TX-FAIL'"
    ).fetchone()[0] == 0
    assert db.execute(
        "SELECT COUNT(*) FROM persons WHERE person_id=?", (caller_person,)
    ).fetchone()[0] == 1
    db.rollback()
    assert db.execute(
        "SELECT COUNT(*) FROM persons WHERE person_id=?", (caller_person,)
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

@pytest.mark.parametrize("bad_now", ["", 0, False])
def test_invoice_rejects_explicit_falsey_creation_timestamp_without_writes(bad_now):
    db = connect_database()
    actor, _activity, sale, grant = fixture(db)
    app = InvoiceApplication()
    invoice_id = uid()
    with pytest.raises(ValidationError, match="Invoice creation timestamp"):
        app.create_invoice(
            db, sale_id=sale, actor_context_ref=actor,
            authorization_grant_id=grant, issuer_ref=actor,
            invoice_number="INV-BAD-TIME", invoice_id=invoice_id, now=bad_now,
        )
    assert db.execute("SELECT COUNT(*) FROM invoices WHERE invoice_id=?", (invoice_id,)).fetchone()[0] == 0
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=?", (invoice_id,)
    ).fetchone()[0] == 0
    db.close()


@pytest.mark.parametrize("bad_issue_at", ["", 0, False])
def test_invoice_rejects_explicit_falsey_issue_timestamp_without_writes(bad_issue_at):
    db = connect_database()
    actor, _activity, sale, grant = fixture(db)
    app = InvoiceApplication()
    invoice_id = uid()
    with pytest.raises(ValidationError, match="Invoice issue timestamp"):
        app.create_invoice(
            db, sale_id=sale, actor_context_ref=actor,
            authorization_grant_id=grant, issuer_ref=actor,
            invoice_number="INV-BAD-ISSUE-TIME", invoice_id=invoice_id,
            issue_at=bad_issue_at, now=STAMP,
        )
    assert db.execute("SELECT COUNT(*) FROM invoices WHERE invoice_id=?", (invoice_id,)).fetchone()[0] == 0
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=?", (invoice_id,)
    ).fetchone()[0] == 0
    db.close()

def test_invoice_rejects_due_timestamp_before_issue_timestamp_without_writes():
    db = connect_database()
    actor, _activity, sale, grant = fixture(db)
    invoice_id = uid()
    with pytest.raises(ValidationError, match="due timestamp cannot precede issue timestamp"):
        InvoiceApplication().create_invoice(
            db, sale_id=sale, actor_context_ref=actor,
            authorization_grant_id=grant, issuer_ref=actor,
            invoice_number="INV-DUE-BEFORE-ISSUE", invoice_id=invoice_id,
            issue_at="2026-10-10T12:00:00Z",
            due_at="2026-10-10T11:59:59Z",
            now=STAMP,
        )
    assert db.execute(
        "SELECT COUNT(*) FROM invoices WHERE invoice_id=?", (invoice_id,)
    ).fetchone()[0] == 0
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=?", (invoice_id,)
    ).fetchone()[0] == 0
    db.close()


def test_invoice_due_timestamp_accepts_same_instant_as_issue_with_different_offset():
    db = connect_database()
    actor, _activity, sale, grant = fixture(db)
    issued = InvoiceApplication().create_invoice(
        db, sale_id=sale, actor_context_ref=actor,
        authorization_grant_id=grant, issuer_ref=actor,
        invoice_number="INV-DUE-EQUAL-ISSUE",
        issue_at="2026-10-10T12:00:00Z",
        due_at="2026-10-10T15:00:00+03:00",
        now=STAMP,
    )
    assert issued.issue_at == "2026-10-10T12:00:00Z"
    assert issued.due_at == "2026-10-10T12:00:00Z"
    db.close()

def test_invoice_due_timestamp_fraction_after_issue_in_same_second_is_valid():
    db = connect_database()
    actor, _activity, sale, grant = fixture(db)
    issued = InvoiceApplication().create_invoice(
        db, sale_id=sale, actor_context_ref=actor,
        authorization_grant_id=grant, issuer_ref=actor,
        invoice_number="INV-DUE-FRACTION-AFTER",
        issue_at="2026-10-10T12:00:00Z",
        due_at="2026-10-10T12:00:00.000001Z",
        now=STAMP,
    )
    assert issued.issue_at == "2026-10-10T12:00:00Z"
    assert issued.due_at == "2026-10-10T12:00:00.000001Z"
    db.close()

def test_invoice_rejects_issue_timestamp_after_creation_without_writes():
    db = connect_database()
    actor, _activity, sale, grant = fixture(db)
    invoice_id = uid()
    with pytest.raises(ValidationError, match="issue timestamp cannot follow creation timestamp"):
        InvoiceApplication().create_invoice(
            db, sale_id=sale, actor_context_ref=actor,
            authorization_grant_id=grant, issuer_ref=actor,
            invoice_number="INV-ISSUED-IN-FUTURE", invoice_id=invoice_id,
            issue_at="2026-10-10T12:00:00.000001Z",
            now="2026-10-10T12:00:00Z",
        )
    assert db.execute(
        "SELECT COUNT(*) FROM invoices WHERE invoice_id=?", (invoice_id,)
    ).fetchone()[0] == 0
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=?", (invoice_id,)
    ).fetchone()[0] == 0
    db.close()

def test_invoice_issue_authorization_runs_inside_atomic_write_transaction(monkeypatch):
    from agent_core.application import SaleApplication

    db = connect_database()
    actor, _activity, sale, grant = fixture(db)
    original = SaleApplication._authorize
    observed = {"inside_transaction": False}

    def checked_authorize(self, connection, *args, **kwargs):
        observed["inside_transaction"] = connection.in_transaction
        assert connection.in_transaction
        return original(self, connection, *args, **kwargs)

    monkeypatch.setattr(SaleApplication, "_authorize", checked_authorize)
    InvoiceApplication().create_invoice(
        db, sale_id=sale, actor_context_ref=actor,
        authorization_grant_id=grant, issuer_ref=actor,
        invoice_number="INV-AUTH-ATOMIC", now=STAMP,
    )
    assert observed["inside_transaction"] is True
    db.close()

def test_invoice_sale_offering_activity_mismatch_is_rejected_by_database_guard_without_writes():
    db = connect_database()
    actor, activity, sale, grant = fixture(db)
    other_activity = uid()
    db.execute(
        "INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) "
        "VALUES(?,?,'active',?,?)",
        (other_activity, actor, STAMP, STAMP),
    )
    db.commit()
    with pytest.raises(sqlite3.IntegrityError, match="offering activity update would break sale activity context"):
        db.execute(
            "UPDATE offerings SET activity_id=? WHERE offering_id=("
            "SELECT offering_id FROM sales WHERE sale_id=?)",
            (other_activity, sale),
        )
    db.rollback()
    invoice_id = uid()
    issued = InvoiceApplication().create_invoice(
        db, sale_id=sale, actor_context_ref=actor,
        authorization_grant_id=grant, issuer_ref=actor,
        invoice_number="INV-CONTEXT-GUARD", invoice_id=invoice_id, now=STAMP,
    )
    assert str(issued.invoice.id) == invoice_id
    assert db.execute(
        "SELECT COUNT(*) FROM invoices WHERE invoice_id=?", (invoice_id,)
    ).fetchone()[0] == 1
    db.close()
def test_invoice_default_issue_and_creation_timestamps_share_one_instant():
    db = connect_database()
    actor, _activity, sale, grant = fixture(db)
    issued = InvoiceApplication().create_invoice(
        db, sale_id=sale, actor_context_ref=actor,
        authorization_grant_id=grant, issuer_ref=actor,
        invoice_number="INV-DEFAULT-TIMESTAMPS",
    )
    row = db.execute(
        "SELECT created_at, issue_at FROM invoices WHERE invoice_id=?",
        (str(issued.invoice.id),),
    ).fetchone()
    assert row[0] == row[1]
    db.close()


def test_invoice_void_rejects_timestamp_before_creation_without_mutation():
    db = connect_database()
    actor, activity, sale, issue_grant = fixture(db)
    app = InvoiceApplication()
    issued = app.create_invoice(
        db, sale_id=sale, actor_context_ref=actor,
        authorization_grant_id=issue_grant, issuer_ref=actor,
        invoice_number="INV-VOID-BEFORE-CREATION", now=STAMP,
    )
    void_grant = uid()
    db.execute(
        "INSERT INTO authorization_grants(authorization_grant_id,subject_person_id,action_code,scope_ref,"
        "context_ref,state,effective_from,created_at,updated_at) VALUES(?,?,?,?,?,'active',?,?,?)",
        (void_grant, actor, "void_invoice", sale, activity, "2026-10-01T00:00:00Z", STAMP, STAMP),
    )
    db.commit()
    with pytest.raises(ValidationError, match="void timestamp cannot precede creation timestamp"):
        app.void_invoice(
            db, issued.invoice.id, actor_context_ref=actor,
            authorization_grant_id=void_grant, expected_version=1,
            now="2026-10-10T11:59:59Z",
        )
    persisted = app.get_invoice(db, issued.invoice.id)
    assert persisted.invoice.state.value == "issued"
    assert persisted.version_no == 1
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=? AND change_type='invoice_state_transition'",
        (str(issued.invoice.id),),
    ).fetchone()[0] == 1
    db.close()


def test_invoice_rejects_duplicate_number_for_same_issuer_without_writes():
    db = connect_database()
    actor, _activity, sale, grant = fixture(db)
    app = InvoiceApplication()
    first = app.create_invoice(
        db, sale_id=sale, actor_context_ref=actor,
        authorization_grant_id=grant, issuer_ref=actor,
        invoice_number="INV-DUPLICATE", now=STAMP,
    )
    second_id = uid()
    with pytest.raises(ValidationError, match="Invoice number already exists for this issuer"):
        app.create_invoice(
            db, sale_id=sale, actor_context_ref=actor,
            authorization_grant_id=grant, issuer_ref=actor,
            invoice_number=" INV-DUPLICATE ", invoice_id=second_id, now=STAMP,
        )
    assert db.execute(
        "SELECT COUNT(*) FROM invoices WHERE issuer_ref=? AND invoice_number='INV-DUPLICATE'",
        (actor,),
    ).fetchone()[0] == 1
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=?",
        (second_id,),
    ).fetchone()[0] == 0
    assert app.get_invoice(db, first.invoice.id).invoice.state.value == "issued"
    db.close()


def test_invoice_rejects_obligation_already_linked_to_another_invoice_without_writes():
    db = connect_database()
    actor, activity, sale, grant = fixture(db)
    app = InvoiceApplication()
    first = app.create_invoice(
        db, sale_id=sale, actor_context_ref=actor,
        authorization_grant_id=grant, issuer_ref=actor,
        invoice_number="INV-SOURCE-OWNER", now=STAMP,
    )
    debtor = uid()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
        (debtor, STAMP, STAMP),
    )
    obligation_id = uid()
    db.execute(
        "INSERT INTO obligations(obligation_id,creditor_person_id,debtor_person_id,source_invoice_id,"
        "amount_minor,currency_code,state,created_at,updated_at,version_no) "
        "VALUES(?,?,?,?,1000,'YER','proposed',?,?,1)",
        (obligation_id, actor, debtor, str(first.invoice.id), STAMP, STAMP),
    )
    db.commit()
    second_id = uid()
    with pytest.raises(ValidationError, match="already linked to an Invoice"):
        app.create_invoice(
            db, sale_id=sale, actor_context_ref=actor,
            authorization_grant_id=grant, issuer_ref=actor,
            invoice_number="INV-SOURCE-SECOND", invoice_id=second_id,
            obligation_id=obligation_id, now=STAMP,
        )
    assert db.execute(
        "SELECT COUNT(*) FROM invoices WHERE invoice_id=?", (second_id,)
    ).fetchone()[0] == 0
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=?", (second_id,)
    ).fetchone()[0] == 0
    assert db.execute(
        "SELECT source_invoice_id FROM obligations WHERE obligation_id=?", (obligation_id,)
    ).fetchone()[0] == str(first.invoice.id)
    db.close()


def test_invoice_rejects_reusing_an_obligation_already_linked_by_invoice_foreign_key():
    db = connect_database()
    actor, _activity, sale, grant = fixture(db)
    debtor = uid()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
        (debtor, STAMP, STAMP),
    )
    obligation_id = uid()
    db.execute(
        "INSERT INTO obligations(obligation_id,creditor_person_id,debtor_person_id,amount_minor,"
        "currency_code,state,created_at,updated_at,version_no) "
        "VALUES(?,?,?,1000,'YER','proposed',?,?,1)",
        (obligation_id, actor, debtor, STAMP, STAMP),
    )
    db.commit()
    app = InvoiceApplication()
    first = app.create_invoice(
        db, sale_id=sale, actor_context_ref=actor,
        authorization_grant_id=grant, issuer_ref=actor,
        invoice_number="INV-OBLIGATION-OWNER-1", obligation_id=obligation_id, now=STAMP,
    )
    second_id = uid()
    with pytest.raises(ValidationError, match="Obligation is already linked to an Invoice"):
        app.create_invoice(
            db, sale_id=sale, actor_context_ref=actor,
            authorization_grant_id=grant, issuer_ref=actor,
            invoice_number="INV-OBLIGATION-OWNER-2", invoice_id=second_id,
            obligation_id=obligation_id, now=STAMP,
        )
    assert db.execute(
        "SELECT invoice_id FROM invoices WHERE obligation_id=?", (obligation_id,)
    ).fetchone()[0] == str(first.invoice.id)
    assert db.execute(
        "SELECT COUNT(*) FROM invoices WHERE invoice_id=?", (second_id,)
    ).fetchone()[0] == 0
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=?", (second_id,)
    ).fetchone()[0] == 0
    db.close()


def test_invoice_rejects_obligation_with_different_creditor_without_writes():
    db = connect_database()
    actor, _activity, sale, grant = fixture(db)
    other_creditor = uid()
    debtor = uid()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
        (other_creditor, STAMP, STAMP),
    )
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
        (debtor, STAMP, STAMP),
    )
    obligation_id = uid()
    db.execute(
        "INSERT INTO obligations(obligation_id,creditor_person_id,debtor_person_id,amount_minor,"
        "currency_code,state,created_at,updated_at,version_no) "
        "VALUES(?,?,?,1000,'YER','proposed',?,?,1)",
        (obligation_id, other_creditor, debtor, STAMP, STAMP),
    )
    db.commit()
    invoice_id = uid()
    with pytest.raises(ValidationError, match="issuer must match the linked Obligation creditor"):
        InvoiceApplication().create_invoice(
            db, sale_id=sale, actor_context_ref=actor,
            authorization_grant_id=grant, issuer_ref=actor,
            invoice_number="INV-WRONG-CREDITOR", invoice_id=invoice_id,
            obligation_id=obligation_id, now=STAMP,
        )
    assert db.execute(
        "SELECT COUNT(*) FROM invoices WHERE invoice_id=?", (invoice_id,)
    ).fetchone()[0] == 0
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=?", (invoice_id,)
    ).fetchone()[0] == 0
    assert db.execute(
        "SELECT creditor_person_id FROM obligations WHERE obligation_id=?", (obligation_id,)
    ).fetchone()[0] == other_creditor
    db.close()



@pytest.mark.parametrize("link_kind", ["obligation", "payment"])
def test_invoice_void_refuses_financial_links_without_reconciliation(link_kind):
    db = connect_database()
    actor, activity, sale, grant = fixture(db)
    app = InvoiceApplication()
    issued = app.create_invoice(
        db, sale_id=sale, actor_context_ref=actor, authorization_grant_id=grant,
        issuer_ref=actor, invoice_number=f"INV-LINK-{link_kind}", now=STAMP,
    )
    invoice_id = str(issued.invoice.id)
    linked_person = uid()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
        (linked_person, STAMP, STAMP),
    )

    if link_kind == "obligation":
        obligation_id = uid()
        db.execute(
            "INSERT INTO obligations(obligation_id,creditor_person_id,debtor_person_id,source_invoice_id,"
            "loan_id,amount_minor,currency_code,state,due_at,created_at,updated_at,version_no) "
            "VALUES(?,?,?, ?,NULL,100,'SAR','open',NULL,?,?,1)",
            (obligation_id, actor, linked_person, invoice_id, STAMP, STAMP),
        )
        db.execute(
            "UPDATE invoices SET obligation_id=? WHERE invoice_id=?",
            (obligation_id, invoice_id),
        )
        expected_message = "linked Obligation requires explicit reconciliation"
    else:
        db.execute(
            "INSERT INTO payments(payment_id,obligation_id,invoice_id,payer_person_id,payee_person_id,"
            "amount_minor,currency_code,state,provider_ref,external_payment_ref,created_at,updated_at,version_no) "
            "VALUES(?,NULL,?,?,?,100,'SAR','initiated',NULL,NULL,?,?,1)",
            (uid(), invoice_id, linked_person, actor, STAMP, STAMP),
        )
        expected_message = "linked Payment history requires explicit reconciliation"

    db.execute(
        "UPDATE authorization_grants SET action_code='void_invoice' WHERE authorization_grant_id=?",
        (grant,),
    )
    db.commit()

    with pytest.raises(ValidationError, match=expected_message):
        app.void_invoice(
            db, invoice_id, actor_context_ref=actor, authorization_grant_id=grant,
            expected_version=1, now="2026-10-10T12:01:00Z",
        )

    persisted = app.get_invoice(db, invoice_id)
    assert persisted.invoice.state.value == "issued"
    assert persisted.version_no == 1
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=? AND change_type='invoice_state_transition'",
        (invoice_id,),
    ).fetchone()[0] == 1
    db.close()


def _sale_grant(db, *, actor, activity, scope, action):
    # The invoice fixture creates a bare Sale row; lifecycle tests must seed the
    # same v1 history evidence required by SaleApplication before transitioning it.
    has_initial_history = db.execute(
        "SELECT 1 FROM domain_history WHERE owner_domain='commerce' AND target_ref=? "
        "AND change_type='sale_state_transition' LIMIT 1",
        (scope,),
    ).fetchone()
    if has_initial_history is None:
        db.execute(
            "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,historical_at,"
            "actor_context_ref,prior_version_ref,current_version_ref,change_payload_ref,created_at) "
            "VALUES(?,?,?,?,?,?,?,?,?,?)",
            (uid(), "commerce", scope, "sale_state_transition", STAMP, actor, None,
             f"sale-state:{scope}:v1", "state:initiated", STAMP),
        )
    grant = uid()
    db.execute(
        "INSERT INTO authorization_grants(authorization_grant_id,subject_person_id,action_code,scope_ref,"
        "context_ref,state,effective_from,created_at,updated_at) VALUES(?,?,?,?,?,'active',?,?,?)",
        (grant, actor, action, scope, activity, "2026-10-01T00:00:00Z", STAMP, STAMP),
    )
    db.commit()
    return grant


def test_invoice_issue_rejects_cancelled_sale_without_writes():
    from agent_core.application import SaleApplication

    db = connect_database()
    actor, activity, sale, issue_grant = fixture(db)
    cancel_grant = _sale_grant(
        db, actor=actor, activity=activity, scope=sale, action="cancel_sale"
    )
    SaleApplication().cancel_sale(
        db, sale, actor, cancel_grant, now=datetime(2026, 10, 10, 12, 0, tzinfo=timezone.utc),
    )

    invoice_id = uid()
    with pytest.raises(ValidationError, match="cancelled or returned Sale"):
        InvoiceApplication().create_invoice(
            db, sale_id=sale, actor_context_ref=actor,
            authorization_grant_id=issue_grant, issuer_ref=actor,
            invoice_number="INV-CANCELLED-SALE", invoice_id=invoice_id, now=datetime(2026, 10, 10, 12, 0, tzinfo=timezone.utc),
        )

    assert db.execute(
        "SELECT COUNT(*) FROM invoices WHERE invoice_id=?", (invoice_id,)
    ).fetchone()[0] == 0
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=? AND change_type='invoice_state_transition'",
        (invoice_id,),
    ).fetchone()[0] == 0
    db.close()


@pytest.mark.parametrize("action", ["cancel", "return"])
def test_sale_cancel_or_return_requires_reconciliation_of_issued_invoice(action):
    from agent_core.application import SaleApplication

    db = connect_database()
    actor, activity, sale, issue_grant = fixture(db)
    invoice = InvoiceApplication().create_invoice(
        db, sale_id=sale, actor_context_ref=actor,
        authorization_grant_id=issue_grant, issuer_ref=actor,
        invoice_number=f"INV-BLOCK-{action.upper()}", now=datetime(2026, 10, 10, 12, 0, tzinfo=timezone.utc),
    )
    sales = SaleApplication()

    if action == "return":
        confirm_grant = _sale_grant(
            db, actor=actor, activity=activity, scope=sale, action="confirm_sale"
        )
        fulfill_grant = _sale_grant(
            db, actor=actor, activity=activity, scope=sale, action="fulfill_sale"
        )
        sales.confirm_sale(db, sale, actor, confirm_grant, now=datetime(2026, 10, 10, 12, 0, tzinfo=timezone.utc))
        sales.fulfill_sale(db, sale, actor, fulfill_grant, now=datetime(2026, 10, 10, 12, 0, tzinfo=timezone.utc))

    transition_grant = _sale_grant(
        db, actor=actor, activity=activity, scope=sale,
        action="cancel_sale" if action == "cancel" else "return_sale",
    )
    before_sale = db.execute(
        "SELECT state,version_no FROM sales WHERE sale_id=?", (sale,)
    ).fetchone()
    before_history = db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=? AND change_type='sale_state_transition'",
        (sale,),
    ).fetchone()[0]

    with pytest.raises(ValidationError, match="requires explicit Invoice reconciliation"):
        if action == "cancel":
            sales.cancel_sale(db, sale, actor, transition_grant, now=datetime(2026, 10, 10, 12, 0, tzinfo=timezone.utc))
        else:
            sales.return_sale(db, sale, actor, transition_grant, now=datetime(2026, 10, 10, 12, 0, tzinfo=timezone.utc))

    assert db.execute(
        "SELECT state,version_no FROM sales WHERE sale_id=?", (sale,)
    ).fetchone() == before_sale
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=? AND change_type='sale_state_transition'",
        (sale,),
    ).fetchone()[0] == before_history
    assert InvoiceApplication().get_invoice(db, invoice.invoice.id).invoice.state.value == "issued"
    assert db.execute(
        "SELECT COUNT(*) FROM inventory_movements WHERE source_type='sale_return' AND source_ref=?",
        (sale,),
    ).fetchone()[0] == 0
    db.close()


@pytest.mark.parametrize("obligation_state", ["satisfied", "cancelled"])
def test_invoice_issue_rejects_terminal_obligation_without_writes(obligation_state):
    db = connect_database()
    actor, _activity, sale, grant = fixture(db)
    debtor = uid()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,'active',?,?)",
        (debtor, STAMP, STAMP),
    )
    obligation_id = uid()
    db.execute(
        "INSERT INTO obligations(obligation_id,creditor_person_id,debtor_person_id,amount_minor,"
        "currency_code,state,created_at,updated_at,version_no) VALUES(?,?,?,1000,'YER',?,?,?,1)",
        (obligation_id, actor, debtor, obligation_state, STAMP, STAMP),
    )
    db.commit()

    invoice_id = uid()
    with pytest.raises(ValidationError, match="satisfied or cancelled Obligation"):
        InvoiceApplication().create_invoice(
            db, sale_id=sale, actor_context_ref=actor,
            authorization_grant_id=grant, issuer_ref=actor,
            invoice_number=f"INV-TERMINAL-{obligation_state.upper()}",
            invoice_id=invoice_id, obligation_id=obligation_id, now=datetime(2026, 10, 10, 12, 0, tzinfo=timezone.utc),
        )

    assert db.execute(
        "SELECT COUNT(*) FROM invoices WHERE invoice_id=?", (invoice_id,)
    ).fetchone()[0] == 0
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=? AND change_type='invoice_state_transition'",
        (invoice_id,),
    ).fetchone()[0] == 0
    assert db.execute(
        "SELECT source_invoice_id,state FROM obligations WHERE obligation_id=?", (obligation_id,)
    ).fetchone() == (None, obligation_state)
    db.close()



def test_invoice_void_rejects_sale_offering_activity_mismatch_without_writes():
    db = connect_database()
    actor, activity, sale, issue_grant = fixture(db)
    app = InvoiceApplication()
    issued = app.create_invoice(
        db, sale_id=sale, actor_context_ref=actor,
        authorization_grant_id=issue_grant, issuer_ref=actor,
        invoice_number="INV-VOID-CONTEXT", now=STAMP,
    )

    void_grant = uid()
    db.execute(
        "INSERT INTO authorization_grants(authorization_grant_id,subject_person_id,action_code,scope_ref,"
        "context_ref,state,effective_from,created_at,updated_at) VALUES(?,?,?,?,?,'active',?,?,?)",
        (void_grant, actor, "void_invoice", sale, activity, "2026-10-01T00:00:00Z", STAMP, STAMP),
    )
    other_activity = uid()
    db.execute(
        "INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) "
        "VALUES(?,?,'active',?,?)",
        (other_activity, actor, STAMP, STAMP),
    )
    # Deliberately bypass the database invariant trigger to model a legacy or
    # externally-corrupted database; the application guard must still fail closed.
    db.execute("DROP TRIGGER offering_activity_preserves_sales_context")
    db.execute("UPDATE offerings SET activity_id=? WHERE offering_id=(SELECT offering_id FROM sales WHERE sale_id=?)",
               (other_activity, sale))
    db.commit()

    with pytest.raises(ValidationError, match="Sale Activity does not match its Offering Activity"):
        app.void_invoice(
            db, issued.invoice.id, actor_context_ref=actor,
            authorization_grant_id=void_grant, expected_version=1, now=STAMP,
        )

    assert db.execute(
        "SELECT state,version_no FROM invoices WHERE invoice_id=?",
        (str(issued.invoice.id),),
    ).fetchone() == ("issued", 1)
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=? AND change_type='invoice_state_transition'",
        (str(issued.invoice.id),),
    ).fetchone()[0] == 1
    db.close()



def test_invoice_void_rejects_timestamp_before_current_update_without_writes():
    db = connect_database()
    actor, activity, sale, issue_grant = fixture(db)
    app = InvoiceApplication()
    issued = app.create_invoice(
        db, sale_id=sale, actor_context_ref=actor, authorization_grant_id=issue_grant,
        issuer_ref=actor, invoice_number="INV-VOID-OLD-TIME", now=STAMP,
    )
    void_grant = uid()
    db.execute(
        "INSERT INTO authorization_grants(authorization_grant_id,subject_person_id,action_code,scope_ref,"
        "context_ref,state,effective_from,created_at,updated_at) VALUES(?,?,?,?,?,'active',?,?,?)",
        (void_grant, actor, "void_invoice", sale, activity, "2026-10-01T00:00:00Z", STAMP, STAMP),
    )
    db.commit()
    # Move the persisted lifecycle forward without changing the invoice's creation time.
    db.execute(
        "UPDATE invoices SET updated_at='2026-10-10T12:01:00Z' WHERE invoice_id=?",
        (str(issued.invoice.id),),
    )
    db.commit()
    before = db.execute(
        "SELECT state,updated_at,version_no FROM invoices WHERE invoice_id=?",
        (str(issued.invoice.id),),
    ).fetchone()
    history_before = db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=? AND change_type='invoice_state_transition'",
        (str(issued.invoice.id),),
    ).fetchone()[0]

    with pytest.raises(ValidationError, match="cannot precede the current update timestamp"):
        app.void_invoice(
            db, issued.invoice.id, actor_context_ref=actor,
            authorization_grant_id=void_grant, expected_version=1,
            now="2026-10-10T12:00:30Z",
        )

    assert db.execute(
        "SELECT state,updated_at,version_no FROM invoices WHERE invoice_id=?",
        (str(issued.invoice.id),),
    ).fetchone() == before
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=? AND change_type='invoice_state_transition'",
        (str(issued.invoice.id),),
    ).fetchone()[0] == history_before
    db.close()



def test_invoice_issue_rejects_second_active_invoice_for_same_sale_without_writes():
    db = connect_database()
    actor, _activity, sale, grant = fixture(db)
    app = InvoiceApplication()
    first = app.create_invoice(
        db, sale_id=sale, actor_context_ref=actor, authorization_grant_id=grant,
        issuer_ref=actor, invoice_number="INV-SALE-ONE", now=STAMP,
    )
    second_id = uid()

    with pytest.raises(ValidationError, match="Sale already has an issued Invoice"):
        app.create_invoice(
            db, sale_id=sale, actor_context_ref=actor, authorization_grant_id=grant,
            issuer_ref=actor, invoice_number="INV-SALE-TWO", invoice_id=second_id, now=STAMP,
        )

    assert db.execute("SELECT COUNT(*) FROM invoices WHERE sale_id=?", (sale,)).fetchone()[0] == 1
    assert db.execute(
        "SELECT COUNT(*) FROM domain_history WHERE target_ref=?", (second_id,)
    ).fetchone()[0] == 0
    assert app.get_invoice(db, first.invoice.id).invoice.state.value == "issued"
    db.close()


def test_database_guard_rejects_second_issued_invoice_for_sale():
    db = connect_database()
    actor, _activity, sale, grant = fixture(db)
    app = InvoiceApplication()
    first = app.create_invoice(
        db, sale_id=sale, actor_context_ref=actor, authorization_grant_id=grant,
        issuer_ref=actor, invoice_number="INV-DB-GUARD-ONE", now=STAMP,
    )
    with pytest.raises(sqlite3.IntegrityError, match="Sale already has an issued Invoice"):
        db.execute(
            """INSERT INTO invoices(
                   invoice_id,sale_id,obligation_id,state,issuer_ref,invoice_number,
                   issue_at,due_at,created_at,updated_at,version_no
               ) VALUES(?,?,NULL,'issued',?,?,?,NULL,?,?,1)""",
            (uid(), sale, actor, "INV-DB-GUARD-TWO", STAMP, STAMP, STAMP),
        )
    assert db.execute("SELECT COUNT(*) FROM invoices WHERE sale_id=? AND state='issued'", (sale,)).fetchone()[0] == 1
    assert app.get_invoice(db, first.invoice.id).invoice.state.value == "issued"
    db.close()
