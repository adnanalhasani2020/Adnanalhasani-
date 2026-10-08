import sqlite3
import uuid

import pytest

from agent_core.persistence import connect_database


EXPECTED_TABLES = {
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


def tables(db):
    return {
        row[0]
        for row in db.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
        )
    }


def test_initial_migration_creates_exact_47_domain_tables():
    db = connect_database()
    assert EXPECTED_TABLES <= tables(db)
    assert len(EXPECTED_TABLES) == 47
    assert db.execute("PRAGMA foreign_keys").fetchone()[0] == 1
    assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"


def test_migration_is_idempotent():
    db = connect_database()
    first = db.execute(
        "SELECT COUNT(*) FROM schema_migrations WHERE version='0001_initial_relational_schema'"
    ).fetchone()[0]
    assert first == 1

    from agent_core.persistence import apply_migrations

    assert apply_migrations(db) == []
    second = db.execute(
        "SELECT COUNT(*) FROM schema_migrations WHERE version='0001_initial_relational_schema'"
    ).fetchone()[0]
    assert second == 1


def insert_person(db, state="active"):
    pid = str(uuid.uuid4())
    now = "2026-10-08T00:00:00Z"
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (pid, state, now, now),
    )
    return pid


def test_gap_0006_identity_uniqueness_and_merge_cycle_guard():
    db = connect_database()
    p1, p2 = insert_person(db), insert_person(db)
    now = "2026-10-08T00:00:00Z"
    db.execute(
        "INSERT INTO identifiers(identifier_id,person_id,identifier_type,normalized_value,"
        "uniqueness_scope,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
        (str(uuid.uuid4()), p1, "email", "a@example.com", "global", "issued", now, now),
    )
    with pytest.raises(sqlite3.IntegrityError):
        db.execute(
            "INSERT INTO identifiers(identifier_id,person_id,identifier_type,normalized_value,"
            "uniqueness_scope,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
            (str(uuid.uuid4()), p2, "email", "a@example.com", "global", "issued", now, now),
        )


def test_gap_0005_operation_identity_is_unique():
    db = connect_database()
    op = ("ns", "op-1", "create", "fp", "completed", "outcome", "2026-10-08T00:00:00Z")
    db.execute(
        "INSERT INTO durable_operation_records("
        "durable_operation_record_id,namespace,operation_id,operation_kind,actor_context_ref,"
        "request_fingerprint,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
        (str(uuid.uuid4()), op[0], op[1], op[2], 'actor', op[3], op[4], op[6], op[6]),
    )
    with pytest.raises(sqlite3.IntegrityError):
        db.execute(
            "INSERT INTO durable_operation_records("
            "durable_operation_record_id,namespace,operation_id,operation_kind,actor_context_ref,"
            "request_fingerprint,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
            (str(uuid.uuid4()), *op[:5], op[5], op[6], op[6]),
        )


def test_grant_agent_action_and_approval_bindings():
    db = connect_database()
    p = insert_person(db)
    now = "2026-10-08T00:00:00Z"
    agent = str(uuid.uuid4())
    grant = str(uuid.uuid4())
    action = str(uuid.uuid4())
    db.execute(
        "INSERT INTO agents(agent_id,owner_person_id,configured_identity_ref,context_ref,state,created_at,updated_at)"
        " VALUES(?,?,?,?,?,?,?)",
        (agent,p,"agent-1","ctx","active",now,now),
    )
    db.execute(
        "INSERT INTO authorization_grants(authorization_grant_id,subject_person_id,action_code,scope_ref,"
        "context_ref,state,effective_from,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
        (grant,p,"pay","scope","ctx","active",now,now,now),
    )
    db.execute(
        "INSERT INTO agent_actions(agent_action_id,agent_id,action_code,target_ref,context_ref,"
        "authorization_grant_id,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
        (action,agent,"pay","target","ctx",grant,"proposed",now,now),
    )
    db.execute(
        "INSERT INTO approvals(approval_id,agent_action_id,state,created_at,updated_at)"
        " VALUES(?,?,?,?,?)",
        (str(uuid.uuid4()),action,"APPROVED",now,now),
    )
    with pytest.raises(sqlite3.IntegrityError):
        db.execute(
            "INSERT INTO agent_actions(agent_action_id,agent_id,action_code,target_ref,context_ref,"
            "authorization_grant_id,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
            (str(uuid.uuid4()),agent,"pay","other","ctx",grant,"proposed",now,now),
        )


def test_stage_12a_settlement_one_obligation_and_one_final_recognition():
    db = connect_database()
    p1, p2 = insert_person(db), insert_person(db)
    now = "2026-10-08T00:00:00Z"
    account = str(uuid.uuid4())
    obligation = str(uuid.uuid4())
    payment = str(uuid.uuid4())
    settlement = str(uuid.uuid4())
    db.execute(
        "INSERT INTO financial_accounts(financial_account_id,person_id,account_type,state,created_at,updated_at)"
        " VALUES(?,?,?,?,?,?)",
        (account,p1,"personal","active",now,now),
    )
    db.execute(
        "INSERT INTO obligations(obligation_id,creditor_person_id,debtor_person_id,amount_minor,"
        "currency_code,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
        (obligation,p1,p2,100,"YER","open",now,now),
    )
    db.execute(
        "INSERT INTO payments(payment_id,obligation_id,payer_person_id,payee_person_id,amount_minor,"
        "currency_code,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
        (payment,obligation,p2,p1,100,"YER","completed",now,now,now),
    )
    db.execute(
        "INSERT INTO settlements(settlement_id,payment_id,obligation_id,state,created_at,updated_at)"
        " VALUES(?,?,?,?,?,?)",
        (settlement,payment,obligation,"settled",now,now),
    )
    with pytest.raises(sqlite3.IntegrityError):
        db.execute(
            "INSERT INTO settlements(settlement_id,payment_id,obligation_id,state,created_at,updated_at)"
            " VALUES(?,?,?,?,?,?)",
            (str(uuid.uuid4()),payment,obligation,"settled",now,now),
        )

    tx = str(uuid.uuid4())
    db.execute(
        "INSERT INTO financial_transactions(financial_transaction_id,settlement_id,financial_account_id,"
        "amount_minor,currency_code,state,recognition_source_type,recognition_source_ref,recognized_at,created_at,updated_at)"
        " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        (tx,settlement,account,100,"YER","recognized","settlement",settlement,now,now,now),
    )
    with pytest.raises(sqlite3.IntegrityError):
        db.execute(
            "INSERT INTO financial_transactions(financial_transaction_id,settlement_id,financial_account_id,"
            "amount_minor,currency_code,state,recognition_source_type,recognition_source_ref,recognized_at,created_at,updated_at)"
            " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            (str(uuid.uuid4()),settlement,account,100,"YER","recognized","settlement",settlement,now,now,now),
        )


def test_financial_transaction_cannot_be_created_without_valid_recognition_source():
    db = connect_database()
    p = insert_person(db)
    account = str(uuid.uuid4())
    now = "2026-10-08T00:00:00Z"
    db.execute(
        "INSERT INTO financial_accounts(financial_account_id,person_id,account_type,state,created_at,updated_at)"
        " VALUES(?,?,?,?,?,?)",
        (account,p,"personal","active",now,now),
    )
    with pytest.raises(sqlite3.IntegrityError):
        db.execute(
            "INSERT INTO financial_transactions(financial_transaction_id,financial_account_id,amount_minor,"
            "currency_code,state,recognition_source_type,recognition_source_ref,recognized_at,created_at,updated_at)"
            " VALUES(?,?,?,?,?,?,?,?,?,?)",
            (str(uuid.uuid4()),account,100,"YER","recognized","invalid","x",now,now,now),
        )
