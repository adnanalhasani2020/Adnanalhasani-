from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261103010000_customer_payment_confirmation_requests.sql"
SQL = MIGRATION.read_text(encoding="utf-8")


def function_body(name: str, next_marker: str) -> str:
    start = SQL.index(f"CREATE OR REPLACE FUNCTION public.{name}(")
    end = SQL.index(next_marker, start)
    return SQL[start:end]


def test_payment_claim_tables_are_private_and_audited():
    assert "CREATE TABLE IF NOT EXISTS public.commercial_customer_payment_requests" in SQL
    assert "CREATE TABLE IF NOT EXISTS public.commercial_customer_payment_request_events" in SQL
    assert "ALTER TABLE public.commercial_customer_payment_requests ENABLE ROW LEVEL SECURITY" in SQL
    assert "ALTER TABLE public.commercial_customer_payment_request_events ENABLE ROW LEVEL SECURITY" in SQL
    assert "REVOKE ALL ON TABLE public.commercial_customer_payment_requests FROM PUBLIC, anon, authenticated" in SQL
    assert "REVOKE ALL ON TABLE public.commercial_customer_payment_request_events FROM PUBLIC, anon, authenticated" in SQL
    assert "commercial_customer_payment_request_approved_payment_check" in SQL
    assert "idempotency_fingerprint text NOT NULL" in SQL
    assert "last_action_fingerprint text NOT NULL" in SQL
    assert "md5(concat_ws" in SQL
    assert "'due_minor',q.due_minor" in SQL
    assert "last_action_fingerprint=md5" in SQL
    assert "CHECK (status IN ('pending','change_requested','rejected','approved'))" in SQL


def test_customer_can_only_claim_their_own_completed_sale_and_claim_does_not_post():
    body = function_body(
        "commercial_customer_portal_submit_payment_request",
        "CREATE OR REPLACE FUNCTION public.commercial_customer_portal_update_payment_request",
    )
    assert "auth.uid()" in body
    assert "c.auth_user_id=v_uid" in body
    assert "s.id=p_sale_id AND s.status='completed'" in body
    assert "payment_request_exceeds_unreserved_due" in body
    assert "pg_advisory_xact_lock" in body
    assert "commercial_record_customer_payment" not in body
    assert "INSERT INTO public.commercial_customer_payment_request_events" in body


def test_customer_can_resubmit_only_after_merchant_requested_changes():
    body = function_body(
        "commercial_customer_portal_update_payment_request",
        "CREATE OR REPLACE FUNCTION public.commercial_merchant_list_customer_payment_requests",
    )
    assert "v_request.status<>'change_requested'" in body
    assert "status='pending'" in body
    assert "version=version+1" in body
    assert "v_previous_amount" in body
    assert "'resubmitted'" in body


def test_merchant_approval_is_the_only_financial_posting_path():
    body = function_body(
        "commercial_merchant_review_customer_payment_request",
        "REVOKE ALL ON FUNCTION public.commercial_customer_portal_list_outstanding_sales",
    )
    assert "m.role IN ('owner','admin','manager')" in body
    assert "p_confirmed_amount_minor<>v_request.amount_minor" in body
    assert "confirmed_amount_does_not_match_customer_claim" in body
    assert "public.commercial_record_customer_payment(" in body
    assert "SET status='approved',payment_id=" in body
    assert "'customer-request:'||v_request.id::text||':'||v_request.version::text" in body
    assert "v_request.status<>'pending'" in body
    assert "event_type" in body


def test_customer_and_merchant_notifications_are_sent_for_state_changes():
    assert "INSERT INTO public.commercial_user_notifications" in SQL
    assert "'customer_payment_request'" in SQL
    assert "'customer_payment_request_'||v_event" in SQL
    assert "commercial_organization_members m" in SQL
    assert "c.auth_user_id=v_uid" in SQL


def test_rpc_permissions_are_explicitly_scoped_to_authenticated_users():
    for signature in (
        "commercial_customer_portal_list_outstanding_sales(uuid)",
        "commercial_customer_portal_list_my_payment_requests(uuid)",
        "commercial_customer_portal_submit_payment_request(uuid,bigint,text,text)",
        "commercial_customer_portal_update_payment_request(uuid,bigint,text,text)",
        "commercial_merchant_list_customer_payment_requests(uuid,text,integer)",
        "commercial_merchant_review_customer_payment_request(uuid,text,bigint,text,text,text)",
    ):
        assert f"REVOKE ALL ON FUNCTION public.{signature} FROM PUBLIC, anon, authenticated" in SQL
        assert f"GRANT EXECUTE ON FUNCTION public.{signature} TO authenticated" in SQL


def test_each_payment_request_transition_notifies_the_other_party():
    submit = function_body(
        "commercial_customer_portal_submit_payment_request",
        "CREATE OR REPLACE FUNCTION public.commercial_customer_portal_update_payment_request",
    )
    update = function_body(
        "commercial_customer_portal_update_payment_request",
        "CREATE OR REPLACE FUNCTION public.commercial_merchant_list_customer_payment_requests",
    )
    review = function_body(
        "commercial_merchant_review_customer_payment_request",
        "REVOKE ALL ON FUNCTION public.commercial_customer_portal_list_outstanding_sales",
    )
    assert "'طلب سند قبض جديد'" in submit
    assert "INSERT INTO public.commercial_user_notifications" in submit
    assert "'تم تعديل طلب سند قبض'" in update
    assert "INSERT INTO public.commercial_user_notifications" in update
    assert "v_customer_uid" in review
    assert "'تم اعتماد سند القبض'" in review
    assert "'تم رفض طلب سند القبض'" in review
    assert "'طلب التاجر تعديل سند القبض'" in review
    assert "INSERT INTO public.commercial_user_notifications" in review
