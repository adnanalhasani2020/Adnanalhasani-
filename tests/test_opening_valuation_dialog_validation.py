from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DIALOG = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialOpeningInventoryBatchValuationDialog.kt"


def test_invalid_opening_batch_drafts_remain_editable_before_submission():
    source = DIALOG.read_text(encoding="utf-8")
    guard = source.index("if (!attempted && !canSubmit)")
    saving = source.index("saving = true", guard)
    attempted = source.index("attempted = true", guard)
    assert guard < saving < attempted
    assert "val validationError = drafts.mapIndexedNotNull" in source
    assert "LocalDate.parse(row.expiresOn)" in source
    assert "enabled = !saving && (attempted || canSubmit)" in source
    assert "Text(validationError" in source
