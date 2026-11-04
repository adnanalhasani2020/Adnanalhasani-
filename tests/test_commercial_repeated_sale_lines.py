from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/SalesRepository.kt"


class CommercialRepeatedSaleLineTests(unittest.TestCase):
    def test_duplicate_offerings_are_aggregated_before_key_and_checkout(self):
        source = REPOSITORY.read_text(encoding="utf-8")
        quantity_math = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialQuantityMath.kt").read_text(encoding="utf-8")
        self.assertIn("internal fun aggregateCommercialCheckoutLines", quantity_math)
        self.assertIn("val totals = linkedMapOf<Pair<String, String>, BigDecimal>()", quantity_math)
        self.assertIn("val key = line.offeringId to line.unitCode", quantity_math)
        self.assertIn("(totals[key] ?: BigDecimal.ZERO).add(quantity)", quantity_math)
        self.assertGreaterEqual(source.count("val normalizedLines = aggregateCommercialCheckoutLines(lines)"), 2)
        self.assertNotIn("لا يمكن تكرار الصنف بوحدتي بيع مختلفتين", source)

    def test_same_offering_keeps_different_selling_units_as_separate_lines(self):
        # One carton (12 stock pieces) and two individual pieces must remain distinct
        # so the server applies each unit's own conversion factor: 12 + 2 = 14.
        quantity_math = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialQuantityMath.kt").read_text(encoding="utf-8")
        self.assertIn('CommercialCloudCheckoutLine(key.first, quantity, key.second)', quantity_math)
        self.assertIn('.sortedWith(compareBy({ it.key.first }, { it.key.second }))', quantity_math)

    def test_aggregate_quantity_is_bounded_and_positive(self):
        source = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialQuantityMath.kt").read_text(encoding="utf-8")
        self.assertIn("quantity <= BigDecimal(\"100000\")", source)
        self.assertIn("quantity.scale() <= 3", source)
        self.assertIn('require(total <= BigDecimal("100000"))', source)

    def test_fractional_quantities_are_preserved_and_bounded(self):
        source = REPOSITORY.read_text(encoding="utf-8")
        quantity_math = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialQuantityMath.kt").read_text(encoding="utf-8")
        self.assertIn("internal fun parseCommercialCheckoutQuantity", quantity_math)
        self.assertIn("line.quantity.stripTrailingZeros().toPlainString()", source)
        self.assertIn("quantity.scale() <= 3", quantity_math)
        self.assertIn("BigDecimal(ascii)", quantity_math)

    def test_fractional_return_quantity_uses_the_same_exact_base_conversion(self):
        migration = (ROOT / "supabase/migrations/20261103020100_commercial_fractional_sell_quantity_returns.sql").read_text(encoding="utf-8")
        self.assertIn(r"(\.[0-9]{1,3})?", migration)
        self.assertIn("v_qty := (v_item->>'quantity')::numeric * v_factor", migration)
        self.assertIn("v_qty <> trunc(v_qty)", migration)
        self.assertIn("fractional_base_return_quantity_not_supported", migration)
        self.assertIn("'quantity',v_qty::bigint", migration)

    def test_fractional_selling_quantity_must_convert_to_whole_base_stock(self):
        migration = (ROOT / "supabase/migrations/20261103020000_commercial_fractional_sell_quantity_checkout.sql").read_text(encoding="utf-8")
        self.assertIn(r"(\.[0-9]{1,3})?", migration)
        self.assertIn("v_base_qty := (v_line->>'quantity')::numeric * v_unit.quantity_in_stock", migration)
        self.assertIn("v_base_qty <> trunc(v_base_qty)", migration)
        self.assertIn("fractional_base_stock_quantity_not_supported", migration)
        self.assertIn("'quantity',v_base_qty::bigint", migration)


if __name__ == "__main__":
    unittest.main()
