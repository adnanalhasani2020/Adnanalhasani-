from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudRepository.kt"


class CommercialRepeatedSaleLineTests(unittest.TestCase):
    def test_duplicate_offerings_are_aggregated_before_key_and_checkout(self):
        source = REPOSITORY.read_text(encoding="utf-8")
        self.assertIn("internal fun aggregateCommercialCheckoutLines", source)
        self.assertIn("Math.addExact(totals[line.offeringId] ?: 0, line.quantity)", source)
        self.assertGreaterEqual(source.count("val normalizedLines = aggregateCommercialCheckoutLines(lines)"), 2)
        self.assertNotIn('require(lines.map { it.offeringId }.distinct().size == lines.size)', source)

    def test_aggregate_quantity_is_bounded_and_positive(self):
        source = REPOSITORY.read_text(encoding="utf-8")
        self.assertIn("line.quantity in 1..100000", source)
        self.assertIn("require(total <= 100000)", source)


if __name__ == "__main__":
    unittest.main()
