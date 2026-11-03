from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
QUANTITY_MATH = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialQuantityMath.kt"
SALE_COMPOSER = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudSaleComposerScreen.kt"
RETURNS_SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudReturnsScreen.kt"


def test_fractional_cloud_quantity_gate_matches_unmigrated_production_rpc():
    math = QUANTITY_MATH.read_text(encoding="utf-8")
    assert "COMMERCIAL_CLOUD_FRACTIONAL_QUANTITY_RPC_READY = false" in math
    assert "20261103020000" in math
    assert "20261103020100" in math


def test_sale_composer_blocks_fractional_quantity_before_cloud_rpc():
    screen = SALE_COMPOSER.read_text(encoding="utf-8")
    assert "hasFractionalSellingQuantity" in screen
    assert "fractionalSellingQuantityBlocked" in screen
    assert "&& !fractionalSellingQuantityBlocked" in screen
    assert "قاعدة الإنتاج الحالية لا تقبل كميات البيع العشرية" in screen
    assert screen.index("fractionalSellingQuantityBlocked") < screen.index("checkoutCommercialCloudSaleMulti(")


def test_return_screen_blocks_fractional_quantity_before_cloud_rpc():
    screen = RETURNS_SCREEN.read_text(encoding="utf-8")
    assert "hasFractionalReturnQuantity" in screen
    assert "fractionalReturnQuantityBlocked" in screen
    assert "!fractionalReturnQuantityBlocked" in screen
    assert "قاعدة الإنتاج الحالية لا تقبل كميات مرتجعة عشرية" in screen
    assert screen.index("fractionalReturnQuantityBlocked") < screen.index("requestCommercialCloudSaleReturn(")
