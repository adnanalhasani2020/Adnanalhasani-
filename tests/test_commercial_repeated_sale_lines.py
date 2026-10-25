from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudRepository.kt"


class CommercialRepeatedSaleLineTests(unittest.TestCase):
    def test_duplicate_offerings_are_aggregated_before_key_and_checkout(self):
        source = REPOSITORY.read_text(encoding="utf-8")
        self.assertIn("internal fun aggregateCommercialCheckoutLines", source)
        self.assertIn("val totals = linkedMapOf<Pair<String, String>, Int>()", source)
        self.assertIn("val key = line.offeringId to line.unitCode", source)
        self.assertIn("Math.addExact(totals[key] ?: 0, line.quantity)", source)
        self.assertGreaterEqual(source.count("val normalizedLines = aggregateCommercialCheckoutLines(lines)"), 2)
        self.assertNotIn("لا يمكن تكرار الصنف بوحدتي بيع مختلفتين", source)

    def test_same_offering_keeps_different_selling_units_as_separate_lines(self):
        # One carton (12 stock pieces) and two individual pieces must remain distinct
        # so the server applies each unit's own conversion factor: 12 + 2 = 14.
        source = REPOSITORY.read_text(encoding="utf-8")
        self.assertIn('CommercialCloudCheckoutLine(key.first, quantity, key.second)', source)
        self.assertIn('.sortedWith(compareBy({ it.key.first }, { it.key.second }))', source)

    def test_aggregate_quantity_is_bounded_and_positive(self):
        source = REPOSITORY.read_text(encoding="utf-8")
        self.assertIn("line.quantity in 1..100000", source)
        self.assertIn("require(total <= 100000)", source)


if __name__ == "__main__":
    unittest.main()
