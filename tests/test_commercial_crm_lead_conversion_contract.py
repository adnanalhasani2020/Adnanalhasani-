from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261106150000_commercial_crm_lead_conversion.sql"
FIXTURE = ROOT / "supabase/tests/fixtures/commercial_crm_leads_schema_fixture.sql"
SMOKE = ROOT / "supabase/tests/database/commercial_crm_lead_conversion_smoke_test.sql"
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCrmRepository.kt"
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCrmScreen.kt"


def test_lead_conversion_is_authenticated_tenant_scoped_and_audited():
    sql = MIGRATION.read_text(encoding="utf-8")
    assert "SECURITY DEFINER SET search_path = ''" in sql
    assert "commercial_crm_context_role(v_lead.organization_id,v_lead.activity_id)" in sql
    assert "v_role='viewer'" in sql
    assert "v_role='staff' AND v_lead.assigned_to<>v_uid AND v_lead.created_by<>v_uid" in sql
    assert "REVOKE ALL ON TABLE public.commercial_crm_lead_conversions FROM PUBLIC,anon,authenticated" in sql
    assert "GRANT EXECUTE ON FUNCTION public.commercial_crm_lead_convert(uuid,integer,text) TO authenticated" in sql
    assert "commercial_audit_events" in sql


def test_lead_conversion_prevents_duplicates_and_is_idempotent():
    sql = MIGRATION.read_text(encoding="utf-8")
    assert "UNIQUE(lead_id)" in sql
    assert "commercial_crm_lead_conversions_idempotency_uq" in sql
    assert "crm_customer_match_ambiguous" in sql
    assert "lower(btrim(v_lead.email))" in sql
    assert "regexp_replace(v_lead.phone" in sql
    assert "crm-lead:" in sql
    assert "request_fingerprint" in sql
    assert "FOR UPDATE" in sql
    assert "'converted_customer_id',c.customer_id" in sql
    assert "'converted_at',c.created_at" in sql


def test_android_repository_and_screen_expose_conversion_action():
    repository = REPOSITORY.read_text(encoding="utf-8")
    screen = SCREEN.read_text(encoding="utf-8")
    assert '"/rest/v1/rpc/commercial_crm_lead_convert"' in repository
    assert '"p_expected_version"' in repository
    assert '"p_idempotency_key"' in repository
    assert "convertCommercialCrmLead(context, lead, pendingConversionKey)" in screen
    assert "تحويل إلى عميل" in screen
    assert "دون إنشاء سجل مكرر" in screen
    assert "lead.convertedCustomerId.isBlank()" in screen
    assert "تم التحويل إلى عميل" in screen
    assert "alreadyConverted ->" in screen


def test_postgres_smoke_covers_roles_and_reuse():
    smoke = SMOKE.read_text(encoding="utf-8")
    for marker in (
        "new_customer_conversion_failed",
        "conversion_idempotent_replay_failed",
        "already_converted_lead_created_duplicate",
        "existing_customer_was_not_reused",
        "ambiguous_customer_match_was_accepted",
        "staff_converted_another_users_lead",
        "viewer_converted_lead",
        "outsider_converted_lead",
        "conversion_rpc_grants_invalid",
    ):
        assert marker in smoke


def test_fixture_contains_the_customer_fields_used_by_conversion():
    fixture = FIXTURE.read_text(encoding="utf-8")
    for field in ("email text", "phone text", "external_reference text", "created_by uuid", "state text"):
        assert field in fixture
