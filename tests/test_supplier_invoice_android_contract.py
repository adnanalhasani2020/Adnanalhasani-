from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def test_supplier_invoice_has_an_android_entry_point_and_cloud_rpc():
    screen = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudPurchasesScreen.kt").read_text()
    dialog = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/SupplierInvoiceEntryDialog.kt").read_text()
    repository = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudRepository.kt").read_text()

    assert 'Text("تسجيل فاتورة المورد")' in screen
    assert "SupplierInvoiceEntryDialog(" in screen
    assert "recordCommercialCloudSupplierInvoice(" in dialog
    assert "getOrCreateCommercialSupplierInvoiceIdempotencyKey(context, order.id)" in dialog
    assert "clearCommercialSupplierInvoiceIdempotencyKey(context, order.id, key)" in dialog
    assert "UUID.randomUUID()" not in dialog
    assert "commercial_supplier_invoice_pending_v1" in repository
    assert "supplier_invoices:commercial_supplier_invoices(id,invoice_number,subtotal_minor,tax_minor,freight_minor,total_minor,currency_code,recorded_at,state,obligation:commercial_supplier_obligations(" in repository
    assert "supplierInvoices = supplierInvoices" in repository
    assert "فواتير المورد المسجلة" in screen
    assert "/rest/v1/rpc/commercial_record_supplier_invoice" in repository


def test_supplier_invoice_purchase_item_ids_are_projected_for_rpc_lines():
    repository = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudRepository.kt").read_text()

    # The dialog sends each selected line ID to the RPC; omitting id from the nested
    # PostgREST select silently produced empty IDs and every save failed server-side.
    assert "items:commercial_purchase_order_items(id,line_number,item_description,quantity_ordered,quantity_received,unit_cost_minor,offering_id,source_selling_unit_code,source_selling_unit_name,source_selling_quantity,source_quantity_in_stock_units)" in repository
    assert 'id = item.optString("id")' in repository


def test_supplier_invoice_rpc_is_atomic_scoped_and_idempotent():
    migration = (ROOT / "supabase/migrations/20261025215237_commercial_supplier_invoice_capture.sql").read_text()

    for marker in (
        "CREATE TABLE IF NOT EXISTS public.commercial_supplier_invoices",
        "CREATE TABLE IF NOT EXISTS public.commercial_supplier_invoice_items",
        "commercial_private.commercial_record_supplier_invoice",
        "auth.uid()",
        "commercial_organization_members",
        "pg_advisory_xact_lock",
        "idempotency_key_reused_with_different_request",
        "supplier_invoice_quantity_exceeds_uninvoiced_received",
        "v_status NOT IN ('approved','partially_received','received','closed')",
        "commercial_audit_events",
        "GRANT EXECUTE ON FUNCTION public.commercial_record_supplier_invoice",
    ):
        assert marker in migration

    # Invoice capture is evidence only: no implicit stock, payment, obligation, or GL writes.
    assert "INSERT INTO public.commercial_stock_movements" not in migration
    assert "INSERT INTO public.commercial_payments" not in migration
    assert "INSERT INTO public.commercial_journal_entries" not in migration


def test_supplier_invoice_rounding_allocation_reconciles_stored_lines():
    migration = (ROOT / "supabase/migrations/20261025221631_commercial_supplier_invoice_largest_remainder_rounding.sql").read_text()

    # Round the exact subtotal once, then use deterministic largest-remainder
    # allocation so line-level bigint values add back to the header subtotal.
    assert "v_subtotal := round(v_subtotal);" in migration
    assert "ORDER BY value DESC, ordinality" in migration
    assert "v_line_totals[v_line_index] := v_line_totals[v_line_index] + 1;" in migration
    assert "SUM(stored lines) = subtotal_minor" in migration
    assert "supplier_invoice_rounding_allocation_failed" in migration
    assert "sum(line_total_minor)" in migration


def test_supplier_invoice_minor_unit_rounding_is_consistent_per_line():
    migration = (ROOT / "supabase/migrations/20261025221026_commercial_supplier_invoice_minor_unit_rounding.sql").read_text()

    # Round each line before adding it to the subtotal so the header total matches
    # the persisted integer-minor-unit line totals for fractional quantities.
    assert migration.count("v_line_total := round(v_qty * v_price);") == 2
    assert "v_subtotal := v_subtotal + v_line_total;" in migration


def test_invoice_transfer_purchase_order_projection_preserves_source_units():
    repository = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudRepository.kt").read_text(encoding="utf-8")
    screen = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudPurchasesScreen.kt").read_text(encoding="utf-8")
    for field in ("source_selling_unit_code", "source_selling_unit_name", "source_selling_quantity", "source_quantity_in_stock_units"):
        assert field in repository
    assert "sourceSellingUnitName" in repository
    assert "وحدة فاتورة المصدر:" in screen
