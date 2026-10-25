from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261026010000_commercial_complete_purchase_workflow.sql"
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudRepository.kt"
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudPurchasesScreen.kt"
ORDER_DIALOG = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/PurchaseOrderEntryDialog.kt"
RECEIPT_DIALOG = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/PurchaseReceiptEntryDialog.kt"


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
    assert "v_qty<>trunc(v_qty)" in receipt


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


def test_android_purchase_workflow_is_connected_to_server_rpcs():
    repository = REPOSITORY.read_text(encoding="utf-8")
    screen = SCREEN.read_text(encoding="utf-8")
    order_dialog = ORDER_DIALOG.read_text(encoding="utf-8")
    receipt_dialog = RECEIPT_DIALOG.read_text(encoding="utf-8")
    for marker in (
        "/rest/v1/rpc/commercial_create_purchase_order",
        "/rest/v1/rpc/commercial_transition_purchase_order",
        "/rest/v1/rpc/commercial_receive_purchase_order",
        "/rest/v1/rpc/commercial_record_supplier_payment",
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
        "PurchaseOrderEntryDialog(",
        "PurchaseReceiptEntryDialog(",
    ):
        assert marker in screen
    assert "createCommercialCloudPurchaseOrder(" in order_dialog
    assert "receiveCommercialCloudPurchaseOrder(" in receipt_dialog
    assert "UUID.randomUUID()" in order_dialog
    assert "UUID.randomUUID()" in receipt_dialog
