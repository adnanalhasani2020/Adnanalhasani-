import sqlite3
import tempfile
import uuid
from pathlib import Path

import pytest

from agent_core.integrity import DurableOperationAuthority, IdentityResolutionAuthority
from agent_core.persistence import apply_migrations, connect_database


EXPECTED_DOMAIN_TABLES = {
    "persons","identifiers","access_accounts","authenticators","devices","sessions",
    "activities","memberships","role_assignments","products","services","offerings",
    "inventory_positions","sales","invoices","financial_accounts","loans","obligations",
    "payments","settlements","financial_transactions","ledger_entries","instruments",
    "instrument_usages","family_relationships","delegations","authorization_grants",
    "agents","authority_policies","agent_actions","approvals","conversations",
    "participants","messages","patient_contexts","encounters","clinical_records",
    "results_reports","prescriptions","pending_operations","conflicts",
    "durable_operation_records","audit_records","provenance_records","domain_history",
    "workflows","workflow_events",
}
NOW = "2026-10-08T00:00:00Z"


def _tables(db):
    return {
        row[0]
        for row in db.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
        )
    }


def _person(db, state="active"):
    value = str(uuid.uuid4())
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (value, state, NOW, NOW),
    )
    return value


def _financial_fixture(db):
    creditor, debtor = _person(db), _person(db)
    account = str(uuid.uuid4())
    obligation = str(uuid.uuid4())
    payment = str(uuid.uuid4())
    settlement = str(uuid.uuid4())
    db.execute(
        "INSERT INTO financial_accounts(financial_account_id,person_id,account_type,state,created_at,updated_at)"
        " VALUES(?,?,?,?,?,?)",
        (account, creditor, "personal", "active", NOW, NOW),
    )
    db.execute(
        "INSERT INTO obligations(obligation_id,creditor_person_id,debtor_person_id,amount_minor,currency_code,state,created_at,updated_at)"
        " VALUES(?,?,?,?,?,?,?,?)",
        (obligation, creditor, debtor, 100, "YER", "open", NOW, NOW),
    )
    db.execute(
        "INSERT INTO payments(payment_id,obligation_id,payer_person_id,payee_person_id,amount_minor,currency_code,state,created_at,updated_at)"
        " VALUES(?,?,?,?,?,?,?,?,?)",
        (payment, obligation, debtor, creditor, 100, "YER", "completed", NOW, NOW),
    )
    db.execute(
        "INSERT INTO settlements(settlement_id,payment_id,obligation_id,state,created_at,updated_at)"
        " VALUES(?,?,?,?,?,?)",
        (settlement, payment, obligation, "settled", NOW, NOW),
    )
    return account, obligation, payment, settlement


def test_clean_install_has_exactly_47_domain_tables_plus_migration_bookkeeping():
    db = connect_database()
    assert _tables(db) == EXPECTED_DOMAIN_TABLES | {"schema_migrations"}
    assert len(EXPECTED_DOMAIN_TABLES) == 47
    assert db.execute("PRAGMA foreign_keys").fetchone()[0] == 1
    assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    versions = [r[0] for r in db.execute("SELECT version FROM schema_migrations ORDER BY version")]
    assert versions == [\n        "0001_initial_relational_schema",\n        "0002_prevent_person_canonical_cycles",\n        "0003_enforce_sale_offering_activity_consistency",\n    ]


def test_existing_db_migration_preserves_data_and_is_idempotent(tmp_path):
    path = tmp_path / "existing.sqlite"
    db = connect_database(path)
    person = _person(db)
    db.commit()
    db.close()

    reopened = connect_database(path)
    assert reopened.execute("SELECT state FROM persons WHERE person_id=?", (person,)).fetchone()[0] == "active"
    before = reopened.execute("SELECT COUNT(*) FROM persons").fetchone()[0]
    assert apply_migrations(reopened) == []
    assert reopened.execute("SELECT COUNT(*) FROM persons").fetchone()[0] == before
    assert reopened.execute("SELECT COUNT(*) FROM schema_migrations").fetchone()[0] == 3


def test_constraints_are_enforced_at_runtime():
    db = connect_database()
    person = _person(db)

    with pytest.raises(sqlite3.IntegrityError):
        db.execute(
            "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
            (person, "active", NOW, NOW),
        )

    with pytest.raises(sqlite3.IntegrityError):
        db.execute(
            "INSERT INTO identifiers(identifier_id,person_id,identifier_type,normalized_value,uniqueness_scope,state,created_at,updated_at)"
            " VALUES(?,?,?,?,?,?,?,?)",
            (str(uuid.uuid4()), "missing", "email", "a@example.com", "global", "issued", NOW, NOW),
        )

    with pytest.raises(sqlite3.IntegrityError):
        db.execute(
            "INSERT INTO loans(loan_id,lender_person_id,borrower_person_id,state,principal_minor,currency_code,created_at,updated_at)"
            " VALUES(?,?,?,?,?,?,?,?)",
            (str(uuid.uuid4()), person, person, "active", 1, "YER", NOW, NOW),
        )

    with pytest.raises(sqlite3.IntegrityError):
        db.execute(
            "INSERT INTO financial_accounts(financial_account_id,person_id,account_type,state,created_at,updated_at)"
            " VALUES(?,?,?,?,?,?)",
            (str(uuid.uuid4()), person, "bad", "invalid", NOW, NOW),
        )


def test_gap_0005_replay_conflict_and_restart_are_persistent(tmp_path):
    path = str(tmp_path / "operations.sqlite")
    first = DurableOperationAuthority(path)
    request = {"amount": "100", "settlement": "s-1"}
    calls = []

    def effect():
        calls.append("ran")
        return {"status": "completed", "reference": "tx-1"}

    assert first.execute(namespace="finance", operation_id="op-1", operation_kind="recognize", request=request, effect=effect) == {"status": "completed", "reference": "tx-1"}
    assert first.execute(namespace="finance", operation_id="op-1", operation_kind="recognize", request=request, effect=effect) == {"status": "completed", "reference": "tx-1"}
    assert calls == ["ran"]

    with pytest.raises(Exception, match="Operation identity conflict"):
        first.execute(
            namespace="finance",
            operation_id="op-1",
            operation_kind="recognize",
            request={"amount": "101", "settlement": "s-1"},
            effect=effect,
        )

    reopened = DurableOperationAuthority(path)
    assert reopened.get("finance", "op-1").request_fingerprint == first.get("finance", "op-1").request_fingerprint
    assert reopened.execute(namespace="finance", operation_id="op-1", operation_kind="recognize", request=request, effect=lambda: {"unexpected": True}) == {"status": "completed", "reference": "tx-1"}


def test_gap_0006_identity_uniqueness_canonical_resolution_cycle_guard_and_history():
    db = connect_database()
    p1, p2, p3 = _person(db), _person(db), _person(db)
    db.execute(
        "INSERT INTO identifiers(identifier_id,person_id,identifier_type,normalized_value,uniqueness_scope,state,created_at,updated_at)"
        " VALUES(?,?,?,?,?,?,?,?)",
        (str(uuid.uuid4()), p1, "email", "merge@example.com", "global", "issued", NOW, NOW),
    )
    with pytest.raises(sqlite3.IntegrityError):
        db.execute(
            "INSERT INTO identifiers(identifier_id,person_id,identifier_type,normalized_value,uniqueness_scope,state,created_at,updated_at)"
            " VALUES(?,?,?,?,?,?,?,?)",
            (str(uuid.uuid4()), p2, "email", "merge@example.com", "global", "issued", NOW, NOW),
        )

    db.execute("UPDATE persons SET canonical_person_id=? WHERE person_id=?", (p1, p2))
    db.execute("UPDATE persons SET canonical_person_id=? WHERE person_id=?", (p2, p3))
    with pytest.raises(sqlite3.IntegrityError, match="canonical person merge cycle"):
        db.execute("UPDATE persons SET canonical_person_id=? WHERE person_id=?", (p3, p1))

    history = str(uuid.uuid4())
    db.execute(
        "INSERT INTO domain_history(domain_history_id,owner_domain,target_ref,change_type,historical_at,actor_context_ref,created_at)"
        " VALUES(?,?,?,?,?,?,?)",
        (history, "identity", p2, "merged", NOW, "test", NOW),
    )
    assert db.execute("SELECT target_ref FROM domain_history WHERE domain_history_id=?", (history,)).fetchone()[0] == p2
    assert db.execute("SELECT canonical_person_id FROM persons WHERE person_id=?", (p2,)).fetchone()[0] == p1

    runtime = IdentityResolutionAuthority()
    ids = [uuid.UUID(p1), uuid.UUID(p2), uuid.UUID(p3)]
    for identity in ids:
        runtime.register_candidate(identity, "email", "merge@example.com")
    canonical = runtime.deduplicate("email", "merge@example.com")
    assert runtime.resolve(ids[1]) == canonical
    assert runtime.historical_reference(ids[1]) == canonical


def test_gap_0003_and_gap_0001_exact_bindings_and_duplicates_are_rejected():
    db = connect_database()
    person = _person(db)
    agent, grant, action = [str(uuid.uuid4()) for _ in range(3)]
    db.execute(
        "INSERT INTO agents(agent_id,owner_person_id,configured_identity_ref,context_ref,state,created_at,updated_at)"
        " VALUES(?,?,?,?,?,?,?)",
        (agent, person, "agent-1", "ctx", "active", NOW, NOW),
    )
    db.execute(
        "INSERT INTO authorization_grants(authorization_grant_id,subject_person_id,action_code,scope_ref,context_ref,state,effective_from,created_at,updated_at)"
        " VALUES(?,?,?,?,?,?,?,?,?)",
        (grant, person, "pay", "scope", "ctx", "active", NOW, NOW, NOW),
    )
    db.execute(
        "INSERT INTO agent_actions(agent_action_id,agent_id,action_code,target_ref,context_ref,authorization_grant_id,state,created_at,updated_at)"
        " VALUES(?,?,?,?,?,?,?,?,?)",
        (action, agent, "pay", "target", "ctx", grant, "proposed", NOW, NOW),
    )
    with pytest.raises(sqlite3.IntegrityError):
        db.execute(
            "INSERT INTO agent_actions(agent_action_id,agent_id,action_code,target_ref,context_ref,authorization_grant_id,state,created_at,updated_at)"
            " VALUES(?,?,?,?,?,?,?,?,?)",
            (str(uuid.uuid4()), agent, "pay", "other", "ctx", grant, "proposed", NOW, NOW),
        )
    with pytest.raises(sqlite3.IntegrityError):
        db.execute(
            "INSERT INTO approvals(approval_id,agent_action_id,state,created_at,updated_at)"
            " VALUES(?,?,?,?,?)",
            (str(uuid.uuid4()), "missing-action", "APPROVED", NOW, NOW),
        )
    approval = str(uuid.uuid4())
    db.execute(
        "INSERT INTO approvals(approval_id,agent_action_id,state,created_at,updated_at)"
        " VALUES(?,?,?,?,?)",
        (approval, action, "APPROVED", NOW, NOW),
    )
    with pytest.raises(sqlite3.IntegrityError):
        db.execute(
            "INSERT INTO approvals(approval_id,agent_action_id,state,created_at,updated_at)"
            " VALUES(?,?,?,?,?)",
            (str(uuid.uuid4()), action, "APPROVED", NOW, NOW),
        )


def test_stage_12a_settlement_finance_truth_reversal_correction_and_derived_balance():
    db = connect_database()
    account, obligation, payment, settlement = _financial_fixture(db)

    with pytest.raises(sqlite3.IntegrityError):
        db.execute(
            "INSERT INTO settlements(settlement_id,payment_id,obligation_id,state,created_at,updated_at)"
            " VALUES(?,?,?,?,?,?)",
            (str(uuid.uuid4()), payment, obligation, "settled", NOW, NOW),
        )

    original = str(uuid.uuid4())
    db.execute(
        "INSERT INTO financial_transactions(financial_transaction_id,settlement_id,financial_account_id,amount_minor,currency_code,state,recognition_source_type,recognition_source_ref,recognized_at,created_at,updated_at)"
        " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        (original, settlement, account, 100, "YER", "recognized", "settlement", settlement, NOW, NOW, NOW),
    )
    with pytest.raises(sqlite3.IntegrityError):
        db.execute(
            "INSERT INTO financial_transactions(financial_transaction_id,settlement_id,financial_account_id,amount_minor,currency_code,state,recognition_source_type,recognition_source_ref,recognized_at,created_at,updated_at)"
            " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            (str(uuid.uuid4()), settlement, account, 100, "YER", "recognized", "settlement", settlement, NOW, NOW, NOW),
        )

    reversal = str(uuid.uuid4())
    correction = str(uuid.uuid4())
    db.execute(
        "INSERT INTO financial_transactions(financial_transaction_id,settlement_id,financial_account_id,amount_minor,currency_code,state,recognition_source_type,recognition_source_ref,recognized_at,created_at,updated_at)"
        " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        (reversal, None, account, -100, "YER", "recognized", "reversal", original, NOW, NOW, NOW),
    )
    db.execute(
        "INSERT INTO financial_transactions(financial_transaction_id,settlement_id,financial_account_id,amount_minor,currency_code,state,recognition_source_type,recognition_source_ref,recognized_at,created_at,updated_at)"
        " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        (correction, None, account, 20, "YER", "recognized", "correction", original, NOW, NOW, NOW),
    )
    db.execute(
        "INSERT INTO ledger_entries(ledger_entry_id,financial_transaction_id,financial_account_id,entry_sequence,amount_minor,currency_code,state,posted_at,created_at,updated_at)"
        " VALUES(?,?,?,?,?,?,?,?,?,?)",
        (str(uuid.uuid4()), original, account, 1, 100, "YER", "posted", NOW, NOW, NOW),
    )
    db.execute(
        "INSERT INTO ledger_entries(ledger_entry_id,financial_transaction_id,financial_account_id,entry_sequence,amount_minor,currency_code,state,posted_at,created_at,updated_at)"
        " VALUES(?,?,?,?,?,?,?,?,?,?)",
        (str(uuid.uuid4()), reversal, account, 1, -100, "YER", "posted", NOW, NOW, NOW),
    )
    db.execute(
        "INSERT INTO ledger_entries(ledger_entry_id,financial_transaction_id,financial_account_id,entry_sequence,amount_minor,currency_code,state,posted_at,created_at,updated_at)"
        " VALUES(?,?,?,?,?,?,?,?,?,?)",
        (str(uuid.uuid4()), correction, account, 1, 20, "YER", "posted", NOW, NOW, NOW),
    )
    assert db.execute("SELECT COUNT(*) FROM financial_transactions WHERE financial_transaction_id IN (?,?,?)", (original,reversal,correction)).fetchone()[0] == 3
    assert not db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='balances'").fetchone()


def test_transaction_failure_rolls_back_dml_and_preserves_prior_state():
    db = connect_database()
    person = _person(db)
    db.commit()
    before = db.execute("SELECT COUNT(*) FROM persons").fetchone()[0]
    with pytest.raises(sqlite3.IntegrityError):
        with db:
            db.execute("INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)", (str(uuid.uuid4()),"active",NOW,NOW))
            db.execute("INSERT INTO access_accounts(access_account_id,person_id,state,created_at,updated_at) VALUES(?,?,?,?,?)", (str(uuid.uuid4()),"missing","active",NOW,NOW))
    assert db.execute("SELECT COUNT(*) FROM persons").fetchone()[0] == before
    assert db.execute("SELECT person_id FROM persons WHERE person_id=?", (person,)).fetchone()[0] == person


def test_optimistic_version_guard_is_usable_and_stale_update_is_rejected_by_row_count():
    db = connect_database()
    person = _person(db)
    first = db.execute(
        "UPDATE persons SET state='suspended', version_no=version_no+1 WHERE person_id=? AND version_no=?",
        (person, 1),
    )
    assert first.rowcount == 1
    stale = db.execute(
        "UPDATE persons SET state='active', version_no=version_no+1 WHERE person_id=? AND version_no=?",
        (person, 1),
    )
    assert stale.rowcount == 0
    assert db.execute("SELECT version_no,state FROM persons WHERE person_id=?", (person,)).fetchone() == (2, "suspended")


def test_no_destructive_cascade_on_protected_domains():
    db = connect_database()
    protected = {"financial_transactions","ledger_entries","authorization_grants","agent_actions","approvals","audit_records","domain_history"}
    for table in protected:
        sql = db.execute("SELECT sql FROM sqlite_master WHERE type='table' AND name=?", (table,)).fetchone()[0]
        assert "ON DELETE CASCADE" not in sql.upper()
    foreign_keys = db.execute("PRAGMA foreign_key_list(financial_transactions)").fetchall()
    assert all((row[6] or "").upper() != "CASCADE" for row in foreign_keys)
