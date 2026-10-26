from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261025222803_commercial_complete_purchase_workflow.sql"
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudRepository.kt"
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudPurchasesScreen.kt"
ORDER_DIALOG = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/PurchaseOrderEntryDialog.kt"
RECEIPT_DIALOG = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/PurchaseReceiptEntryDialog.kt"
PAYMENT_DIALOG = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/SupplierPaymentEntryDialog.kt"
MAPPING_DIALOG = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/PurchaseLineOfferingLinkDialog.kt"
LINE_LINK_MIGRATION = ROOT / "supabase/migrations/20261025223738_commercial_purchase_line_offering_link.sql"
OFFERING_GUARD = ROOT / "supabase/migrations/20261025223900_commercial_purchase_offering_validity_guard.sql"
OBLIGATION_INTEGRITY = ROOT / "supabase/migrations/20261025223549_commercial_supplier_obligation_integrity.sql"
PAYMENT_EVIDENCE = ROOT / "supabase/migrations/20261025223401_commercial_supplier_payment_evidence_uniqueness.sql"
GLOBAL_PAYMENT_EVIDENCE = ROOT / "supabase/migrations/20261025224128_commercial_supplier_payment_evidence_global_unique.sql"
FRACTIONAL_RECEIVING = ROOT / "supabase/migrations/20261025230817_commercial_fractional_purchase_receiving.sql"
ACCOUNTING_ROUNDING = ROOT / "supabase/migrations/20261026080000_commercial_purchase_fractional_accrual_rounding.sql"
INTEGER_RECEIVING = ROOT / "supabase/migrations/20261026250000_commercial_integer_purchase_receiving.sql"


def test_purchase_order_lifecycle_is_scoped_and_idempotent():
    sql = MIGRATION.read_text(encoding="utf-8")
    for marker in (
        "commercial_create_purchase_order",
        "commercial_transition_purchase_order",
        "commercial_receive_purchase_order",
        "purchase_idempotency_key_reused_with_different_request",
        "purchase_receipt_key_reused_with_different_request",
        "purchase_order_cancel_after_receipt_forbidden",
        "purchase_receipt_exceeds_remaining_quantity",
        "purchase_line_missing_inventory_offering",
        "commercial_organization_members",
        "commercial_audit_events",
    ):
        assert marker in sql


def test_purchase_receipt_posts_inventory_and_order_quantities_atomically():
    sql = MIGRATION.read_text(encoding="utf-8")
    receipt = sql.split("CREATE OR REPLACE FUNCTION commercial_private.commercial_receive_purchase_order", 1)[1]
    receipt = receipt.split("$function$;", 1)[0]
    for marker in (
        "public.commercial_receive_inventory_stock",
        "public.commercial_inventory_balances",
        "public.commercial_purchase_order_items SET quantity_received=quantity_received+v_qty",
        "public.commercial_purchase_receipt_items",
        "purchase_order.received",
        "pg_advisory_xact_lock",
    ):
        assert marker in receipt
    # This is the historical integer-only implementation. A later migration replaces it
    # with a numeric implementation; also verify the effective implementation.
    assert "v_qty<>trunc(v_qty)" in receipt
    effective_sql = INTEGER_RECEIVING.read_text(encoding="utf-8")
    effective_receipt = effective_sql.split(
        "CREATE OR REPLACE FUNCTION commercial_private.commercial_receive_purchase_order", 1
    )[1].split("$function$;", 1)[0]
    assert "v_qty<>trunc(v_qty)" in effective_receipt
    assert "purchase_receipt_requires_positive_integer_quantity" in effective_receipt
    assert "quantity_received=quantity_received+v_qty" in effective_receipt


def test_supplier_invoice_creates_payable_and_balanced_posted_journal():
    sql = MIGRATION.read_text(encoding="utf-8")
    trigger = sql.split("CREATE OR REPLACE FUNCTION commercial_private.commercial_post_supplier_invoice_financials()", 1)[1]
    trigger = trigger.split("$function$;", 1)[0]
    for marker in (
        "commercial_ensure_supplier_ledger_account",
        "'inventory_asset'",
        "'accounts_payable'",
        "commercial_ensure_supplier_party_account",
        "commercial_journal_entries",
        "commercial_journal_lines",
        "commercial_supplier_obligations",
        "status='posted'",
        "NEW.total_minor",
    ):
        assert marker in trigger
    assert "commercial_record_supplier_invoice" not in trigger


def test_supplier_payments_reduce_outstanding_balance_and_post_journal():
    sql = MIGRATION.read_text(encoding="utf-8")
    payment = sql.split("CREATE OR REPLACE FUNCTION commercial_private.commercial_record_supplier_payment", 1)[1]
    payment = payment.split("$function$;", 1)[0]
    for marker in (
        "supplier_payment_exceeds_outstanding_balance",
        "supplier_payment_cash_account_not_configured_for_currency",
        "commercial_journal_entries",
        "commercial_journal_lines",
        "commercial_supplier_payments",
        "amount_paid_minor=v_paid",
        "state=CASE WHEN v_paid=amount_due_minor THEN 'paid' ELSE 'partially_paid' END",
        "idempotent_replay",
    ):
        assert marker in payment


def test_supplier_obligations_and_payment_evidence_are_consistent():
    integrity = OBLIGATION_INTEGRITY.read_text(encoding="utf-8")
    evidence = PAYMENT_EVIDENCE.read_text(encoding="utf-8")
    global_evidence = GLOBAL_PAYMENT_EVIDENCE.read_text(encoding="utf-8")
    assert "commercial_supplier_obligation_state_consistency_ck" in integrity
    assert "commercial_supplier_payment_obligation_identity_fk" in integrity
    assert "amount_paid_minor = amount_due_minor" in integrity
    assert "commercial_supplier_payments_evidence_uq" in evidence
    assert "lower(btrim(evidence_reference))" in evidence
    assert "DROP INDEX IF EXISTS public.commercial_supplier_payments_evidence_uq" in global_evidence
    assert "ON public.commercial_supplier_payments (organization_id, lower(btrim(evidence_reference)))" in global_evidence


def test_legacy_purchase_lines_require_explicit_inventory_mapping_before_receipt():
    sql = LINE_LINK_MIGRATION.read_text(encoding="utf-8")
    assert "commercial_link_purchase_order_item_offering" in sql
    assert "purchase_line_offering_link_after_receipt_or_invoice_forbidden" in sql
    assert "purchase_offering_currency_or_scope_mismatch" in sql
    assert "reapproval_required" in sql
    mapping_dialog = MAPPING_DIALOG.read_text(encoding="utf-8")
    offering_guard = OFFERING_GUARD.read_text(encoding="utf-8")
    assert "linkCommercialCloudPurchaseOrderItemOffering(" in mapping_dialog
    assert "ربط بنود المخزون" in mapping_dialog
    assert "commercial_guard_purchase_order_item_offering" in offering_guard
    assert "o.valid_from<=now()" in offering_guard
    assert "o.valid_to>=now()" in offering_guard


def test_android_purchase_workflow_is_connected_to_server_rpcs():
    repository = REPOSITORY.read_text(encoding="utf-8")
    screen = SCREEN.read_text(encoding="utf-8")
    order_dialog = ORDER_DIALOG.read_text(encoding="utf-8")
    receipt_dialog = RECEIPT_DIALOG.read_text(encoding="utf-8")
    payment_dialog = PAYMENT_DIALOG.read_text(encoding="utf-8")
    for marker in (
        "/rest/v1/rpc/commercial_create_purchase_order",
        "/rest/v1/rpc/commercial_transition_purchase_order",
        "/rest/v1/rpc/commercial_receive_purchase_order",
        "/rest/v1/rpc/commercial_record_supplier_payment",
        "/rest/v1/rpc/commercial_link_purchase_order_item_offering",
        "commercial_purchase_order_items(id,line_number,item_description,quantity_ordered,quantity_received,unit_cost_minor,offering_id)",
    ):
        assert marker in repository
    for marker in (
        "إنشاء أمر شراء جديد",
        "إرسال للاعتماد",
        "اعتماد الأمر",
        "إلغاء الأمر",
        "استلام البضاعة",
        "تسجيل فاتورة المورد",
        "تسجيل دفعة للمورد",
        "ربط عناصر المخزون للبنود القديمة",
        "التحقق من الاستلام المعلق",
        "PurchaseOrderEntryDialog(",
        "PurchaseReceiptEntryDialog(",
    ):
        assert marker in screen
    assert "createCommercialCloudPurchaseOrder(" in order_dialog
    assert "receiveCommercialCloudPurchaseOrder(" in receipt_dialog
    assert "recordCommercialCloudSupplierPayment(" in payment_dialog
    assert 'commercialStableIdempotencyKey("supplier-payment"' in payment_dialog
    assert 'commercialStableIdempotencyKey("purchase-create"' in order_dialog
    assert "getOrCreateCommercialPurchaseReceiptIdempotencyKey" in receipt_dialog
    assert "resolvePendingCommercialPurchaseReceipt" in repository


def test_purchase_receiving_rejects_fractions_at_android_and_database_boundaries():
    fractional_migration = FRACTIONAL_RECEIVING.read_text(encoding="utf-8")
    integer_migration = INTEGER_RECEIVING.read_text(encoding="utf-8")
    repository = REPOSITORY.read_text(encoding="utf-8")
    order_dialog = ORDER_DIALOG.read_text(encoding="utf-8")
    receipt_dialog = RECEIPT_DIALOG.read_text(encoding="utf-8")

    # Keep numeric storage for compatibility with existing fractional records, but reject
    # any new fractional receipt at both the purchase RPC and inventory receipt boundary.
    for marker in ("numeric(18,6)", "numeric(20,6)", "quantity_delta TYPE numeric(20,6)"):
        assert marker in fractional_migration
    receipt_rpc = integer_migration.split(
        "CREATE OR REPLACE FUNCTION commercial_private.commercial_receive_purchase_order", 1
    )[1].split("$function$;", 1)[0]
    inventory_rpc = integer_migration.split(
        "CREATE OR REPLACE FUNCTION public.commercial_receive_inventory_stock", 1
    )[1].split("$function$;", 1)[0]
    assert "v_qty<>trunc(v_qty)" in receipt_rpc
    assert "p_quantity<>trunc(p_quantity)" in inventory_rpc
    assert "purchase_receipt_requires_positive_integer_quantity" in receipt_rpc
    assert "invalid_inventory_receipt_integer_quantity" in inventory_rpc

    receipt_repository = repository.split("internal fun receiveCommercialCloudPurchaseOrder", 1)[1]
    receipt_repository = receipt_repository.split("internal fun createCommercialCloudPurchaseOrder", 1)[0]
    assert "quantity.stripTrailingZeros().scale() <= 0" in receipt_repository
    assert "كمية الاستلام يجب أن تكون عددًا صحيحًا موجبًا دون كسور." in receipt_repository
    assert "quantity.stripTrailingZeros().scale() > 0" in receipt_dialog
    assert "كميات صحيحة موجبة بدون كسور" in receipt_dialog

    # Fractional purchase order/invoice quantities remain a separate policy; only receiving
    # is restricted here, as requested.
    assert "quantity.stripTrailingZeros().scale() <= 6" in order_dialog
    invoice_repository = repository.split("internal fun recordCommercialCloudSupplierInvoice", 1)[1]
    invoice_repository = invoice_repository.split("private fun commercialPurchaseReceiptPendingPreferences", 1)[0]
    assert "quantity.stripTrailingZeros().scale() <= 6" in invoice_repository

def test_purchase_order_rpc_accepts_fractional_quantities_with_storage_precision():
    migration = (ROOT / "supabase/migrations/20261026114729_commercial_purchase_order_fractional_quantities.sql").read_text(encoding="utf-8")
    function = migration.split("CREATE OR REPLACE FUNCTION commercial_private.commercial_create_purchase_order", 1)[1]
    assert "scale(v_qty)>6" in function
    assert "v_qty>999999999999.999999" in function
    assert "v_qty<>trunc(v_qty)" not in function
    for marker in (
        "purchase_management_permission_required",
        "purchase_idempotency_key_reused_with_different_request",
        "purchase_offering_currency_or_scope_mismatch",
        "commercial_audit_events",
    ):
        assert marker in function


def test_fractional_accrual_rounding_migration_is_present_and_replaces_accounting_triggers():
    sql = ACCOUNTING_ROUNDING.read_text(encoding="utf-8")
    assert "CREATE OR REPLACE FUNCTION commercial_private.commercial_post_purchase_receipt_financials()" in sql
    assert "CREATE OR REPLACE FUNCTION commercial_private.commercial_post_supplier_invoice_financials()" in sql
    assert "CREATE CONSTRAINT TRIGGER commercial_purchase_receipt_financials_after_insert" in sql
    assert "CREATE CONSTRAINT TRIGGER commercial_supplier_invoice_financials_after_insert" in sql
    assert "round((prior.quantity_before + ri.quantity::numeric) * ri.unit_cost_minor::numeric)" in sql
    assert "round((prior.quantity_before + si.quantity::numeric) * poi.unit_cost_minor::numeric)" in sql
    assert "pr.accounting_sequence < NEW.accounting_sequence" in sql
    assert "prior_invoice.accounting_sequence < NEW.accounting_sequence" in sql
    assert "commercial_purchase_receipts_accounting_sequence_uq" in sql
    assert "commercial_supplier_invoices_accounting_sequence_uq" in sql
