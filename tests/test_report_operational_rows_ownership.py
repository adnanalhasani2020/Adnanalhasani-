from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial"
SCREEN = ANDROID / "ReportsCenterScreen.kt"
ROWS = ANDROID / "ReportOperationalRows.kt"


def test_payment_return_and_expense_rows_have_a_dedicated_owner():
    screen = SCREEN.read_text(encoding="utf-8")
    rows = ROWS.read_text(encoding="utf-8")

    assert "buildOperationalReportRows(" in screen
    for marker in (
        '"التحصيل حسب طريقة الدفع" ->',
        '"الدفعات السحابية الناجحة" ->',
        '"التحصيلات المسجلة محليًا" ->',
        '"مرتجعات المبيعات وحالة التسوية" ->',
        '"سندات القبض والصرف" ->',
        '"ملخص المصروفات حسب التصنيف وطريقة الصرف" ->',
        '"المصروفات التشغيلية" ->',
    ):
        assert marker in rows
        assert marker not in screen
    assert "row.status.equals(\"succeeded\", true)" in rows
    assert "row.refundStatus" in rows
    assert "canceledAt.isBlank()" in rows
    assert "Math.addExact" in rows
