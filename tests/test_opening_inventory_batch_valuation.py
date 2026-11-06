from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261106131420_opening_inventory_batch_valuation.sql"
CONDITION_MIGRATION = ROOT / "supabase/migrations/20261106134449_opening_inventory_batch_condition.sql"
SMOKE = ROOT / "supabase/tests/database/commercial_inventory_batch_fefo_smoke_test.sql"
DIALOG = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialOpeningInventoryBatchValuationDialog.kt"
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudStockAlertsScreen.kt"
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/InventoryRepository.kt"


def test_opening_valuation_requires_exact_uncovered_stock_and_manager_scope():
    sql = MIGRATION.read_text(encoding="utf-8")
    assert "m.role IN ('owner','admin','manager')" in sql
    assert "FOR UPDATE" in sql
    assert "opening_batches_must_cover_exact_unvalued_quantity" in sql
    assert "v_covered_quantity>v_balance.quantity_on_hand" in sql
    assert "v_cost bigint" in sql


def test_opening_valuation_preserves_lot_costs_and_expiry():
    sql = MIGRATION.read_text(encoding="utf-8")
    assert "source_type,source_ref,received_at" in sql
    assert "batch_number,expires_on,stock_condition" in sql
    assert "source_unit_cost_minor,unit_cost_minor" in sql
    assert "CASE WHEN v_expires_on IS NOT NULL AND v_expires_on<current_date THEN 'expired' ELSE 'sellable' END" in sql
    assert "inventory.opening_valuation.recorded" in sql


def test_opening_valuation_is_idempotent_and_never_fabricates_cost():
    sql = MIGRATION.read_text(encoding="utf-8")
    assert "commercial_inventory_opening_valuation_requests" in sql
    assert "request_fingerprint" in sql
    assert "opening_inventory_idempotency_key_reused" in sql
    assert "opening_batches_must_cover_exact_unvalued_quantity" in sql
    assert "GRANT EXECUTE ON FUNCTION public.commercial_record_opening_inventory_batches" in sql


def test_smoke_covers_multiple_lots_average_cost_and_retries():
    smoke = SMOKE.read_text(encoding="utf-8")
    assert "OPEN-LOT-A" in smoke and "OPEN-LOT-B" in smoke
    assert "opening_weighted_average_cost_mismatch" in smoke
    assert "opening_valuation_retry_not_idempotent" in smoke
    assert "opening_valuation_allowed_partial_coverage" in smoke


def test_android_exposes_manager_opening_batch_valuation_and_exact_coverage():
    repository = REPOSITORY.read_text(encoding="utf-8")
    dialog = DIALOG.read_text(encoding="utf-8")
    screen = SCREEN.read_text(encoding="utf-8")
    assert "commercial_record_opening_inventory_batches" in repository
    assert "unitCostMinor" in repository
    assert "CommercialOpeningInventoryBatchValuationDialog" in screen
    assert "تسجيل دفعات وتكلفة الرصيد الافتتاحي" in screen
    assert "يجب أن يساوي مجموع الدفعات الكمية غير المغطاة تماماً" in dialog
    assert "إضافة دفعة أخرى" in dialog


def test_opening_valuation_displays_the_offering_currency_and_minor_unit_convention():
    repository = REPOSITORY.read_text(encoding="utf-8")
    dialog = DIALOG.read_text(encoding="utf-8")
    assert "currencyCode = offering.optString(\"currency_code\")" in repository
    assert "currencyCode = selected.currencyCode" in SCREEN.read_text(encoding="utf-8")
    assert "العملة: $currencyCode" in dialog
    assert "الوحدة النقدية الصغرى" in dialog


def test_opening_valuation_preserves_inspection_condition_and_blocks_expired_lots():
    sql = CONDITION_MIGRATION.read_text(encoding="utf-8")
    smoke = SMOKE.read_text(encoding="utf-8")
    assert "v_stock_condition NOT IN ('sellable','quarantine','damaged','expired')" in sql
    assert "ELSE v_stock_condition END" in sql
    assert "'OPEN-LOT-B','expires_on','2027-06-01','stock_condition','quarantine'" in smoke
    assert "'OPEN-LOT-C','expires_on','2027-08-01','stock_condition','damaged'" in smoke
    assert "opening_batch_condition_not_preserved_or_expiry_not_enforced" in smoke


def test_android_opening_batch_dialog_requires_visible_condition_selection():
    dialog = DIALOG.read_text(encoding="utf-8")
    repository = REPOSITORY.read_text(encoding="utf-8")
    assert 'row.stockCondition == "sellable"' in dialog
    assert 'row.stockCondition = "quarantine"' in dialog
    assert 'row.stockCondition = "damaged"' in dialog
    assert "الدفعة المحجورة أو التالفة لا تدخل في الصرف" in dialog
    assert '.put("stock_condition", condition)' in repository
