from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FOUNDATION = ROOT / "supabase/migrations/20261028010000_commercial_personal_account_delegation_foundation.sql"
DELEGATED_ORDERS = ROOT / "supabase/migrations/20261028011000_commercial_delegated_customer_orders.sql"
REPORTING = ROOT / "supabase/migrations/20261028012000_commercial_delegated_spend_reporting.sql"
PERSONAL_ORGANIZATIONS = ROOT / "supabase/migrations/20261028015000_commercial_personal_account_organizations.sql"


def test_delegation_storage_is_private_and_scoped():
    sql = FOUNDATION.read_text(encoding="utf-8")
    assert "ALTER TABLE public.commercial_account_delegations ENABLE ROW LEVEL SECURITY" in sql
    assert "REVOKE ALL ON TABLE public.commercial_account_delegations FROM anon, authenticated" in sql
    assert "pending_merchant_approval" in sql
    assert "commercial_organization_members" in sql
    assert "organization_manager_required" in sql
    assert "activity_not_in_organization" in sql
    assert "spend_limit_minor" in sql
    assert "spend_currency_code" in sql


def test_account_lookup_does_not_return_private_identity_data():
    sql = FOUNDATION.read_text(encoding="utf-8")
    function = sql.split("CREATE OR REPLACE FUNCTION public.commercial_account_lookup_personal_account", 1)[1]
    function = function.split("$function$;", 1)[0]
    assert "'account_number', a.account_number" in function
    assert "'display_name', a.display_name" in function
    assert "'email'," not in function.lower()
    assert "'phone'," not in function.lower()
    assert "owner_identity" not in function.lower()


def test_delegated_orders_bind_parent_billing_child_actor_and_enforce_budget():
    sql = DELEGATED_ORDERS.read_text(encoding="utf-8")
    assert "commercial_customer_portal_create_delegated_order" in sql
    assert "c.auth_user_id = v_parent_uid" in sql
    assert "delegated_purchase_permission_required" in sql
    assert "delegated_booking_permission_required" in sql
    assert "delegated_spend_limit_exceeded" in sql
    assert "v_delegation.spent_minor::numeric + v_subtotal::numeric" in sql
    assert "delegated_access_id,principal_account_id,acting_account_id" in sql
    assert "SET spent_minor=spent_minor + v_subtotal" in sql
    assert "commercial_customer_portal_create_delegated_booking" in sql
    assert "commercial_customer_portal_create_delegated_order(" in sql
    assert "PERFORM public.commercial_customer_portal_link_verified_email()" not in sql


def test_delegated_report_requires_parent_child_permission_or_merchant_manager():
    sql = REPORTING.read_text(encoding="utf-8")
    assert "commercial_account_delegated_spend_report" in sql
    assert "delegated_report_permission_required" in sql
    assert "v_uid=v_parent_owner OR v_is_manager" in sql
    assert "v_row.permissions->>'view_reports'" in sql
    assert "o.delegated_access_id=v_row.id" in sql
    assert "o.principal_account_id=v_row.parent_account_id" in sql
    assert "o.acting_account_id=v_row.child_account_id" in sql


def test_delegation_functions_are_not_callable_anonymously():
    sql = FOUNDATION.read_text(encoding="utf-8")
    for name in (
        "commercial_account_lookup_personal_account",
        "commercial_account_create_delegation",
        "commercial_account_review_delegation",
        "commercial_account_update_delegation",
        "commercial_account_revoke_delegation",
        "commercial_account_list_delegations",
    ):
        assert f"REVOKE ALL ON FUNCTION public.{name}" in sql
        assert f"GRANT EXECUTE ON FUNCTION public.{name}" in sql


def test_android_portal_routes_delegated_actions_to_guarded_rpcs():
    repository = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudRepository.kt").read_text(encoding="utf-8")
    portal = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CustomerPortalScreen.kt").read_text(encoding="utf-8")
    activity = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/MainActivity.kt").read_text(encoding="utf-8")
    screen = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/PersonalAccountsScreen.kt").read_text(encoding="utf-8")
    assert "commercial_account_list_my_active_delegations" in repository
    assert "commercial_account_create_delegation" in screen
    assert "commercial_account_review_delegation" in screen
    assert "commercial_account_update_delegation" in screen
    assert "commercial_account_delegated_spend_report" in screen
    assert "commercial_customer_portal_create_delegated_order" in repository
    assert "commercial_customer_portal_create_delegated_booking" in repository
    assert "commercial_customer_portal_create_delegated_multi_service_booking" in repository
    assert "PersonalAccountsScreen" in activity
    assert "onPersonalAccounts" in activity


def test_personal_account_owns_multiple_organizations_and_activities():
    sql = PERSONAL_ORGANIZATIONS.read_text(encoding="utf-8")
    screen = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/PersonalAccountsScreen.kt").read_text(encoding="utf-8")
    assert "commercial_personal_account_organizations" in sql
    assert "trg_commercial_auto_link_new_organization_to_personal_account" in sql
    assert "trg_commercial_auto_link_personal_account_to_owned_organizations" in sql
    assert "commercial_account_list_my_organizations" in sql
    assert "commercial_account_link_my_organization" in sql
    assert "مؤسساتي وأنشطتي" in screen
    assert "ربط بحسابي الرئيسي" in screen
