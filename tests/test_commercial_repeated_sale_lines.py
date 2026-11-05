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

    def test_same_offering_keeps_different_integer_selling_units_as_separate_lines(self):
        quantity_math = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialQuantityMath.kt").read_text(encoding="utf-8")
        self.assertIn('CommercialCloudCheckoutLine(key.first, quantity, key.second)', quantity_math)
        self.assertIn('.sortedWith(compareBy({ it.key.first }, { it.key.second }))', quantity_math)

    def test_aggregate_quantity_is_positive_bounded_and_whole(self):
        source = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialQuantityMath.kt").read_text(encoding="utf-8")
        self.assertIn('quantity <= BigDecimal("100000")', source)
        self.assertIn("quantity.stripTrailingZeros().scale() <= 0", source)
        self.assertIn('require(total <= BigDecimal("100000"))', source)

    def test_quantity_parser_rejects_fractional_values(self):
        quantity_math = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialQuantityMath.kt").read_text(encoding="utf-8")
        self.assertIn('Regex("^[0-9]{1,6}$")', quantity_math)
        self.assertNotIn(r"(\\.[0-9]{1,3})?", quantity_math)

    def test_return_quantity_is_whole_and_uses_saved_unit_snapshot(self):
        migration = (ROOT / "supabase/migrations/20261105123000_commercial_integer_quantity_enforcement.sql").read_text(encoding="utf-8")
        return_rpc = migration.split("CREATE OR REPLACE FUNCTION public.commercial_request_sale_return(", 1)[1].split("$function$;", 1)[0]
        self.assertIn("v_item->>'quantity'", return_rpc)
        self.assertIn("v_qty := (v_item->>'quantity')::numeric * v_factor", return_rpc)
        self.assertIn("v_qty <> trunc(v_qty)", return_rpc)
        self.assertIn("v_sale_item.selling_unit_code IS DISTINCT FROM v_unit_code", return_rpc)
        self.assertIn("v_sale_item.quantity_in_stock_units::numeric / v_sale_item.selling_quantity", return_rpc)
        self.assertNotIn("FROM public.commercial_offering_sell_units", return_rpc)

    def test_sale_rpc_accepts_only_integer_selling_quantities(self):
        migration = (ROOT / "supabase/migrations/20261105123000_commercial_integer_quantity_enforcement.sql").read_text(encoding="utf-8")
        self.assertIn("coalesce(v_line->>'quantity','') !~", migration)
        self.assertIn("v_base_qty := (v_line->>'quantity')::numeric * v_unit.quantity_in_stock", migration)
        self.assertIn("v_base_qty <> trunc(v_base_qty)", migration)
        self.assertIn("'quantity',v_base_qty::bigint", migration)


if __name__ == "__main__":
    unittest.main()
