from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261106170000_commercial_support_tickets.sql"
SMOKE = ROOT / "supabase/tests/database/commercial_support_tickets_smoke_test.sql"
WORKFLOW = ROOT / ".github/workflows/commercial-support-tickets-smoke.yml"


def test_support_tickets_are_tenant_scoped_and_rpc_only():
    sql = MIGRATION.read_text(encoding="utf-8")
    assert "commercial_support_context_role" in sql
    assert "SECURITY DEFINER SET search_path = ''" in sql
    assert "auth.uid()" in sql
    assert "ENABLE ROW LEVEL SECURITY" in sql
    assert "REVOKE ALL ON TABLE public.commercial_support_tickets FROM PUBLIC,anon,authenticated" in sql
    assert "REVOKE ALL ON TABLE public.commercial_support_ticket_messages FROM PUBLIC,anon,authenticated" in sql
    assert "commercial_audit_events" in sql


def test_support_ticket_role_and_assignment_rules_are_enforced():
    sql = MIGRATION.read_text(encoding="utf-8")
    assert "support_write_permission_required" in sql
    assert "support_staff_cannot_assign_other_users" in sql
    assert "support_ticket_update_forbidden" in sql
    assert "support_staff_cannot_reassign_or_close_ticket" in sql
    assert "IS DISTINCT FROM v_uid" in sql
    assert "support_customer_not_in_organization" in sql
    assert "support_assignee_not_active_member" in sql


def test_support_ticket_writes_are_idempotent_and_versioned():
    sql = MIGRATION.read_text(encoding="utf-8")
    assert "support_ticket_idempotency_conflict" in sql
    assert "support_message_idempotency_conflict" in sql
    assert "support_ticket_version_conflict" in sql
    assert "pg_advisory_xact_lock" in sql
    assert "version=version+1" in sql
    assert "support_ticket_closed" in sql


def test_support_ticket_smoke_covers_roles_and_lifecycle():
    smoke = SMOKE.read_text(encoding="utf-8")
    for marker in (
        "support_ticket_create_failed",
        "support_ticket_idempotency_replay_failed",
        "support_ticket_idempotency_conflict_not_detected",
        "cross_org_customer_ticket_was_allowed",
        "assigned_staff_ticket_list_failed",
        "support_message_replay_failed",
        "staff_ticket_status_update_failed",
        "staff_closed_ticket",
        "viewer_ticket_read_failed",
        "manager_ticket_update_failed",
        "closed_ticket_accepted_new_message",
        "cross_tenant_ticket_list_was_allowed",
        "support_ticket_rpc_grants_invalid",
    ):
        assert marker in smoke
    assert "commercial_support_tickets" in WORKFLOW.read_text(encoding="utf-8")

REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialSupportRepository.kt"
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialSupportScreen.kt"
STORE = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/StoreApp.kt"
MORE = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/LocalOperationalScreens.kt"


def test_android_support_ticket_screen_is_wired_to_cloud_rpc_and_navigation():
    repository = REPOSITORY.read_text(encoding="utf-8")
    screen = SCREEN.read_text(encoding="utf-8")
    store = STORE.read_text(encoding="utf-8")
    more = MORE.read_text(encoding="utf-8")
    assert "commercial_support_ticket_list" in repository
    assert "commercial_support_ticket_create" in repository
    assert "commercial_support_ticket_update" in repository
    assert "commercial_support_ticket_message_create" in repository
    assert "commercial_support_ticket_message_list" in repository
    assert "loadCommercialSupportMembers" in repository
    assert "تذاكر الدعم" in screen
    assert "الملاحظات الداخلية" in screen
    assert "commercialSupportTicketsPage" in store
    assert "onSupportTickets" in more
