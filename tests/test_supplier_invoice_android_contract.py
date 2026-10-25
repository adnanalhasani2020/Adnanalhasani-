from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def test_supplier_invoice_has_an_android_entry_point_and_cloud_rpc():
    screen = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudPurchasesScreen.kt").read_text()
    dialog = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/SupplierInvoiceEntryDialog.kt").read_text()
    repository = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudRepository.kt").read_text()

    assert 'Text("تسجيل فاتورة المورد")' in screen
    assert "SupplierInvoiceEntryDialog(" in screen
    assert "recordCommercialCloudSupplierInvoice(" in dialog
    assert "/rest/v1/rpc/commercial_record_supplier_invoice" in repository


def test_supplier_invoice_rpc_is_atomic_scoped_and_idempotent():
    migration = (ROOT / "supabase/migrations/20261026090000_commercial_supplier_invoice_capture.sql").read_text()

    for marker in (
        "CREATE TABLE IF NOT EXISTS public.commercial_supplier_invoices",
        "CREATE TABLE IF NOT EXISTS public.commercial_supplier_invoice_items",
        "commercial_private.commercial_record_supplier_invoice",
        "auth.uid()",
        "commercial_organization_members",
        "pg_advisory_xact_lock",
        "idempotency_key_reused_with_different_request",
        "supplier_invoice_quantity_exceeds_uninvoiced_received",
        "commercial_audit_events",
        "GRANT EXECUTE ON FUNCTION public.commercial_record_supplier_invoice",
    ):
        assert marker in migration

    # Invoice capture is evidence only: no implicit stock, payment, obligation, or GL writes.
    assert "INSERT INTO public.commercial_stock_movements" not in migration
    assert "INSERT INTO public.commercial_payments" not in migration
    assert "INSERT INTO public.commercial_journal_entries" not in migration
