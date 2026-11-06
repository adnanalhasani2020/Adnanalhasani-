from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261106124452_inventory_batch_expiry_and_rotation.sql"
ALLOCATION = ROOT / "supabase/migrations/20261106124500_inventory_batch_fefo_allocation.sql"
PURCHASE_REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/PurchaseRepository.kt"
RECEIPT_DIALOG = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/PurchaseReceiptEntryDialog.kt"
INVENTORY_REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/InventoryRepository.kt"
STOCK_SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudStockAlertsScreen.kt"


def test_purchase_receiving_persists_physical_batch_metadata_atomically():
    sql = MIGRATION.read_text(encoding="utf-8")
    assert "ADD COLUMN IF NOT EXISTS batch_number text" in sql
    assert "ADD COLUMN IF NOT EXISTS expires_on date" in sql
    assert "commercial_sync_receipt_batch_metadata" in sql
    assert "commercial_list_inventory_batches" in sql
    assert "GRANT EXECUTE ON FUNCTION public.commercial_list_inventory_batches" in sql
    assert "inventory_receipt_id=NEW.inventory_receipt_id" in sql
    assert "v_batch_number:=nullif(btrim(v_line->>'batch_number'),'')" in sql
    assert "v_expires_on:=nullif(btrim(v_line->>'expires_on'),'')::date" in sql
    assert "inventory_receipt_id,batch_number,expires_on" in sql


def test_rotation_policy_is_separate_from_inventory_valuation_policy():
    sql = MIGRATION.read_text(encoding="utf-8")
    allocation = ALLOCATION.read_text(encoding="utf-8")
    assert "commercial_inventory_rotation_policies" in sql
    assert "rotation_method text NOT NULL DEFAULT 'fefo'" in sql
    assert "p_rotation_method NOT IN ('fifo','fefo')" in sql
    assert "inventory.rotation_policy.updated" in sql
    assert "v_rotation:=coalesce(v_rotation,'fefo')" in allocation
    assert "CASE WHEN v_rotation='fefo' THEN l.expires_on END ASC NULLS LAST" in allocation
    assert "ADD COLUMN IF NOT EXISTS rotation_method text" in allocation
    assert "costing_method,rotation_method,quantity" in allocation


def test_sales_never_consume_expired_or_non_sellable_layers():
    allocation = ALLOCATION.read_text(encoding="utf-8")
    assert "l.stock_condition='sellable'" in allocation
    assert "(l.expires_on IS NULL OR l.expires_on>=current_date)" in allocation
    assert "Insufficient verified, sellable, non-expired batch quantity" in allocation


def test_return_cost_restoration_keeps_original_cost_layer_linkage():
    # The existing return engine restores quantities by sale_cost_allocation_id
    # and cost_layer_id. The batch enhancement deliberately reuses that layer.
    sql = (ROOT / "supabase/migrations/20261031100000_inventory_cost_return_cancel_reversals.sql").read_text(encoding="utf-8")
    assert "v_alloc.cost_layer_id" in sql
    assert "SET quantity_remaining=quantity_remaining+v_take" in sql
    assert "sale_cost_allocation_id" in sql


def test_android_purchase_and_stock_screens_expose_batch_workflow():
    purchase = PURCHASE_REPOSITORY.read_text(encoding="utf-8")
    dialog = RECEIPT_DIALOG.read_text(encoding="utf-8")
    inventory = INVENTORY_REPOSITORY.read_text(encoding="utf-8")
    screen = STOCK_SCREEN.read_text(encoding="utf-8")
    assert "batch_number" in purchase and "expires_on" in purchase
    assert "رقم التشغيلة / الدفعة" in dialog
    assert "تاريخ الصلاحية YYYY-MM-DD" in dialog
    assert "loadCommercialInventoryRotationPolicy" in inventory
    assert "commercial_set_inventory_rotation_policy" in inventory
    assert "loadCommercialCloudInventoryBatches" in inventory
    assert "commercial_list_inventory_batches" in inventory
    assert "sellableByOffering" in inventory
    assert "stock_condition" in inventory
    assert "دفعات المخزون الفعلية" in screen
    assert "FEFO" in screen and "FIFO" in screen
    assert "batchLoadError" in screen
    assert "تعذر تحميل بيانات الدفعات" in screen
