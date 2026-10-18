import sqlite3
import uuid

import pytest

from agent_core.hospital_finance_integration_settings_application import (
    HospitalFinanceIntegrationSettingsApplication,
)
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError


NOW = "2026-10-18T12:00:00Z"


def _id():
    return str(uuid.uuid4())


def _person(db):
    ident = _id()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (ident, "active", NOW, NOW),
    )
    return ident


def _fixture(db):
    owner = _person(db)
    proposer = _person(db)
    approver = _person(db)
    activity = _id()
    db.execute(
        "INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at) "
        "VALUES(?,?,?,?,?)", (activity, owner, "active", NOW, NOW)
    )
    account = _id()
    db.execute(
        "INSERT INTO financial_accounts(financial_account_id,person_id,account_type,state,created_at,updated_at) "
        "VALUES(?,?,?,?,?,?)", (account, owner, "personal", "active", NOW, NOW)
    )
    return activity, owner, proposer, approver, account


def test_settings_require_distinct_active_approver_and_preserve_versioned_mapping(tmp_path):
    db = connect_database(tmp_path / "hospital-finance-settings.sqlite")
    activity, owner, proposer, approver, account = _fixture(db)
    app = HospitalFinanceIntegrationSettingsApplication()

    proposal = app.propose(
        db, activity_id=activity, hospital_scope_ref="clinic-main",
        finance_person_id=owner, financial_account_id=account,
        proposed_by_person_id=proposer, payment_trigger="fully_settled_existing_finance_payment",
        now=NOW,
    )
    assert proposal.status == "pending_approval"
    assert app.get_active(db, activity_id=activity, hospital_scope_ref="clinic-main") is None
    with pytest.raises(ValidationError, match="cannot approve their own"):
        app.approve(db, settings_id=proposal.settings_id, approved_by_person_id=proposer, now=NOW)

    active = app.approve(db, settings_id=proposal.settings_id, approved_by_person_id=approver, now=NOW)
    assert active.status == "active"
    assert active.approved_by_person_id.hex == uuid.UUID(approver).hex
    assert app.get_active(db, activity_id=activity, hospital_scope_ref="clinic-main").settings_id == active.settings_id
    db.close()


def test_settings_fail_closed_for_partial_payment_policy_and_account_owner_mismatch(tmp_path):
    db = connect_database(tmp_path / "hospital-finance-settings-invalid.sqlite")
    activity, owner, proposer, approver, account = _fixture(db)
    app = HospitalFinanceIntegrationSettingsApplication()
    other_person = _person(db)

    with pytest.raises(ValidationError, match="belong to the selected"):
        app.propose(
            db, activity_id=activity, hospital_scope_ref="clinic-main",
            finance_person_id=other_person, financial_account_id=account,
            proposed_by_person_id=proposer, now=NOW,
        )
    with pytest.raises(ValidationError, match="partial payments remain blocked"):
        app.propose(
            db, activity_id=activity, hospital_scope_ref="clinic-main",
            finance_person_id=owner, financial_account_id=account,
            proposed_by_person_id=proposer, partial_payment_policy="allow",
            now=NOW,
        )
    db.close()


def test_settings_require_active_activity_when_proposed_and_approved(tmp_path):
    db = connect_database(tmp_path / "hospital-finance-settings-inactive-activity.sqlite")
    activity, owner, proposer, approver, account = _fixture(db)
    app = HospitalFinanceIntegrationSettingsApplication()

    db.execute("UPDATE activities SET state='suspended' WHERE activity_id=?", (activity,))
    with pytest.raises(ValidationError, match="must be active to propose"):
        app.propose(
            db, activity_id=activity, hospital_scope_ref="clinic-main",
            finance_person_id=owner, financial_account_id=account,
            proposed_by_person_id=proposer, now=NOW,
        )

    db.execute("UPDATE activities SET state='active' WHERE activity_id=?", (activity,))
    proposal = app.propose(
        db, activity_id=activity, hospital_scope_ref="clinic-main",
        finance_person_id=owner, financial_account_id=account,
        proposed_by_person_id=proposer, now=NOW,
    )
    with pytest.raises(
        sqlite3.IntegrityError,
        match="Pause or reject Hospital Finance settings before deactivating the Finance Activity",
    ):
        db.execute("UPDATE activities SET state='suspended' WHERE activity_id=?", (activity,))
    assert db.execute("SELECT state FROM activities WHERE activity_id=?", (activity,)).fetchone()[0] == "active"
    assert app.get_by_id(db, settings_id=proposal.settings_id).status == "pending_approval"
    db.close()


def test_database_rejects_settings_for_inactive_activity(tmp_path):
    db = connect_database(tmp_path / "hospital-finance-settings-db-guard.sqlite")
    activity, owner, proposer, approver, account = _fixture(db)
    db.execute("UPDATE activities SET state='suspended' WHERE activity_id=?", (activity,))

    with pytest.raises(sqlite3.IntegrityError, match="require an active Finance Activity"):
        db.execute(
            "INSERT INTO hospital_finance_integration_settings("
            "settings_id,activity_id,hospital_scope_ref,finance_person_id,financial_account_id,"
            "invoice_policy,payment_trigger,partial_payment_policy,currency_policy,status,"
            "version_no,proposed_by_person_id,proposed_at,created_at) "
            "VALUES(?,?,?,?,?,?,?,?,?,'pending_approval',?,?,?,?)",
            (
                _id(), activity, "clinic-main", owner, account,
                "operational_only", "manual_review_only", "reject_until_supported",
                "require_exact_match", 1, proposer, NOW, NOW,
            ),
        )
    db.close()


@pytest.mark.parametrize("settings_status", ["pending_approval", "active"])
def test_database_prevents_deactivating_referenced_finance_mapping_rows(tmp_path, settings_status):
    db = connect_database(tmp_path / f"finance-reference-state-{settings_status}.sqlite")
    activity, owner, proposer, approver, account = _fixture(db)
    app = HospitalFinanceIntegrationSettingsApplication()
    proposal = app.propose(
        db, activity_id=activity, hospital_scope_ref="clinic-main",
        finance_person_id=owner, financial_account_id=account,
        proposed_by_person_id=proposer, now=NOW,
    )
    if settings_status == "active":
        app.approve(db, settings_id=proposal.settings_id, approved_by_person_id=approver, now=NOW)

    blocked_changes = [
        (
            "UPDATE activities SET state='suspended' WHERE activity_id=?",
            (activity,),
            "Pause or reject Hospital Finance settings before deactivating the Finance Activity",
            "SELECT state FROM activities WHERE activity_id=?",
            (activity,),
        ),
        (
            "UPDATE persons SET state='deactivated' WHERE person_id=?",
            (owner,),
            "Pause or reject Hospital Finance settings before deactivating the mapped Finance Person",
            "SELECT state FROM persons WHERE person_id=?",
            (owner,),
        ),
        (
            "UPDATE financial_accounts SET state='closed' WHERE financial_account_id=?",
            (account,),
            "Pause or reject Hospital Finance settings before closing the mapped Financial Account",
            "SELECT state FROM financial_accounts WHERE financial_account_id=?",
            (account,),
        ),
    ]
    for update_sql, update_args, message, read_sql, read_args in blocked_changes:
        with pytest.raises(sqlite3.IntegrityError, match=message):
            db.execute(update_sql, update_args)
        assert db.execute(read_sql, read_args).fetchone()[0] == "active"

    assert app.get_by_id(db, settings_id=proposal.settings_id).status == settings_status
    db.close()


def test_database_preserves_immutable_settings_snapshots_and_approval_history(tmp_path):
    db = connect_database(tmp_path / "finance-settings-immutable-history.sqlite")
    activity, owner, proposer, approver, account = _fixture(db)
    app = HospitalFinanceIntegrationSettingsApplication()
    proposal = app.propose(
        db, activity_id=activity, hospital_scope_ref="clinic-main",
        finance_person_id=owner, financial_account_id=account,
        proposed_by_person_id=proposer, now=NOW,
    )

    with pytest.raises(sqlite3.IntegrityError, match="snapshots are immutable"):
        db.execute(
            "UPDATE hospital_finance_integration_settings SET invoice_policy=? WHERE settings_id=?",
            ("finance_obligation_review_required", str(proposal.settings_id)),
        )
    with pytest.raises(sqlite3.IntegrityError):
        db.execute(
            "UPDATE hospital_finance_integration_settings SET status='active' WHERE settings_id=?",
            (str(proposal.settings_id),),
        )
    assert app.get_by_id(db, settings_id=proposal.settings_id).status == "pending_approval"

    active = app.approve(
        db, settings_id=proposal.settings_id, approved_by_person_id=approver, now=NOW
    )
    with pytest.raises(sqlite3.IntegrityError, match="approval history is immutable"):
        db.execute(
            "UPDATE hospital_finance_integration_settings SET approval_note='rewritten' WHERE settings_id=?",
            (str(active.settings_id),),
        )
    with pytest.raises(sqlite3.IntegrityError, match="Invalid Hospital Finance settings status transition"):
        db.execute(
            "UPDATE hospital_finance_integration_settings SET status='rejected' WHERE settings_id=?",
            (str(active.settings_id),),
        )
    assert app.get_by_id(db, settings_id=active.settings_id).status == "active"
    db.close()


def test_settings_proposal_and_approval_write_atomic_append_only_audit_events(tmp_path):
    db = connect_database(tmp_path / "hospital-finance-settings-audit.sqlite")
    activity, owner, proposer, approver, account = _fixture(db)
    app = HospitalFinanceIntegrationSettingsApplication()

    proposal = app.propose(
        db, activity_id=activity, hospital_scope_ref="clinic-main",
        finance_person_id=owner, financial_account_id=account,
        proposed_by_person_id=proposer, now=NOW,
    )
    audit = db.execute(
        "SELECT event_type,actor_context_ref,target_ref,result_status,operation_ref "
        "FROM audit_records WHERE target_ref=? ORDER BY occurred_at,audit_record_id",
        (str(proposal.settings_id),),
    ).fetchall()
    assert audit == [
        ("hospital_finance.settings.proposed", proposer, str(proposal.settings_id),
         "pending_approval", str(proposal.settings_id))
    ]

    active = app.approve(
        db, settings_id=proposal.settings_id, approved_by_person_id=approver, now=NOW,
        note="Approved for this activity",
    )
    audit = db.execute(
        "SELECT event_type,actor_context_ref,target_ref,result_status,operation_ref "
        "FROM audit_records WHERE target_ref=? ORDER BY occurred_at,audit_record_id",
        (str(active.settings_id),),
    ).fetchall()
    assert [row[0] for row in audit] == [
        "hospital_finance.settings.proposed", "hospital_finance.settings.approved"
    ]
    assert audit[1] == (
        "hospital_finance.settings.approved", approver, str(active.settings_id),
        "active", str(active.settings_id)
    )
    with pytest.raises(sqlite3.IntegrityError, match="audit_records are append-only"):
        db.execute(
            "DELETE FROM audit_records WHERE target_ref=?",
            (str(active.settings_id),),
        )
    db.close()


def test_settings_proposal_rolls_back_when_required_audit_write_fails(tmp_path):
    db = connect_database(tmp_path / "hospital-finance-settings-audit-failure.sqlite")
    activity, owner, proposer, approver, account = _fixture(db)
    app = HospitalFinanceIntegrationSettingsApplication()
    db.execute(
        "CREATE TRIGGER fail_hospital_finance_proposal_audit "
        "BEFORE INSERT ON audit_records "
        "WHEN NEW.event_type='hospital_finance.settings.proposed' "
        "BEGIN SELECT RAISE(ABORT, 'simulated audit persistence failure'); END"
    )

    with pytest.raises(sqlite3.IntegrityError, match="simulated audit persistence failure"):
        app.propose(
            db, activity_id=activity, hospital_scope_ref="clinic-main",
            finance_person_id=owner, financial_account_id=account,
            proposed_by_person_id=proposer, now=NOW,
        )

    assert db.execute(
        "SELECT COUNT(*) FROM hospital_finance_integration_settings"
    ).fetchone()[0] == 0
    assert db.execute(
        "SELECT COUNT(*) FROM audit_records WHERE event_type='hospital_finance.settings.proposed'"
    ).fetchone()[0] == 0
    db.close()
