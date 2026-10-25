from decimal import Decimal
import unittest

from agent_core.commercial_document_math import (
    Discount, TaxRule, calculate_document_totals, to_base_quantity,
)


class CommercialDocumentMathTests(unittest.TestCase):
    def test_piece_carton_and_kilogram_conversions(self):
        self.assertEqual(to_base_quantity(Decimal("2"), Decimal("12")), Decimal("24"))
        self.assertEqual(to_base_quantity("1.25", "1000"), Decimal("1250"))
        self.assertEqual(to_base_quantity("0.75", "1"), Decimal("0.75"))

    def test_conversion_rejects_nonpositive_and_nonfinite_inputs(self):
        for quantity, factor in ((0, 12), (-1, 12), (1, 0), (1, -1), ("NaN", 1), (1, "Infinity")):
            with self.subTest(quantity=quantity, factor=factor):
                with self.assertRaises(ValueError):
                    to_base_quantity(quantity, factor)

    def test_percentage_discount_is_applied_before_exclusive_tax(self):
        totals = calculate_document_totals(
            line_amounts_minor=[10000, 5000],
            discount=Discount("percent", Decimal("10")),
            tax_rules=(TaxRule("VAT", Decimal("15")),),
        )
        self.assertEqual((totals.subtotal_minor, totals.discount_minor), (15000, 1500))
        self.assertEqual((totals.taxable_minor, totals.tax_minor, totals.total_minor), (13500, 2025, 15525))

    def test_fixed_discount_and_tax_inclusive_amount(self):
        totals = calculate_document_totals(
            line_amounts_minor=[11000],
            discount=Discount("fixed", Decimal("1000")),
            tax_rules=(TaxRule("VAT", Decimal("10"), inclusive=True),),
        )
        self.assertEqual(totals.discount_minor, 1000)
        self.assertEqual(totals.taxable_minor, 10000)
        self.assertEqual(totals.tax_minor, 909)
        self.assertEqual(totals.total_minor, 10000)

    def test_rounding_is_half_up_and_disabled_tax_is_ignored(self):
        totals = calculate_document_totals(
            line_amounts_minor=[1],
            tax_rules=(TaxRule("small", Decimal("50")), TaxRule("disabled", Decimal("100"), enabled=False)),
        )
        self.assertEqual(totals.tax_minor, 1)
        self.assertEqual(totals.total_minor, 2)

    def test_invalid_discount_and_tax_configuration_fails_closed(self):
        with self.assertRaises(ValueError):
            calculate_document_totals(line_amounts_minor=[100], discount=Discount("fixed", Decimal("101")))
        for rate in (Decimal("-1"), Decimal("100.1")):
            with self.subTest(rate=rate), self.assertRaises(ValueError):
                TaxRule("VAT", rate)
        with self.assertRaises(ValueError):
            calculate_document_totals(
                line_amounts_minor=[100],
                tax_rules=(TaxRule("inclusive", Decimal("5"), inclusive=True),
                           TaxRule("exclusive", Decimal("5"), inclusive=False)),
            )

    def test_rejects_negative_and_non_integer_minor_amounts(self):
        for amount in (-1, 1.5, True):
            with self.subTest(amount=amount), self.assertRaises(ValueError):
                calculate_document_totals(line_amounts_minor=[amount])


if __name__ == "__main__":
    unittest.main()
