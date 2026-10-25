from decimal import Decimal
import unittest

from agent_core.commercial_document_math import (
    Discount, TaxRule, calculate_document_totals, to_base_quantity,
    validate_base_quantity_precision,
)


class CommercialDocumentMathTests(unittest.TestCase):
    def test_piece_carton_and_kilogram_conversions(self):
        self.assertEqual(to_base_quantity(Decimal("2"), Decimal("12")), Decimal("24"))
        self.assertEqual(to_base_quantity("1.25", "1000"), Decimal("1250"))
        self.assertEqual(to_base_quantity("0.75", "1"), Decimal("0.75"))

    def test_base_unit_precision_rejects_silent_rounding(self):
        self.assertEqual(validate_base_quantity_precision("1.250", 3), Decimal("1.250"))
        self.assertEqual(validate_base_quantity_precision("12", 0), Decimal("12"))
        for quantity, places in (("1.001", 2), ("0.1", 0), (0, 3), ("NaN", 3), ("Infinity", 3)):
            with self.subTest(quantity=quantity, places=places), self.assertRaises(ValueError):
                validate_base_quantity_precision(quantity, places)
        for places in (-1, 13, True, 1.5):
            with self.subTest(places=places), self.assertRaises(ValueError):
                validate_base_quantity_precision("1", places)

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

    def test_zero_and_full_discount_and_total_overflow_boundaries(self):
        maximum = (1 << 63) - 1
        zero = calculate_document_totals(line_amounts_minor=[0])
        self.assertEqual((zero.subtotal_minor, zero.discount_minor, zero.tax_minor, zero.total_minor), (0, 0, 0, 0))

        fully_discounted = calculate_document_totals(
            line_amounts_minor=[maximum],
            discount=Discount("percent", Decimal("100")),
        )
        self.assertEqual(fully_discounted.total_minor, 0)

        with self.assertRaisesRegex(ValueError, "document total exceeds"):
            calculate_document_totals(
                line_amounts_minor=[maximum],
                tax_rules=(TaxRule("VAT", Decimal("1")),),
            )

    def test_duplicate_active_tax_codes_are_rejected_case_insensitively(self):
        with self.assertRaisesRegex(ValueError, "tax rule codes must be unique"):
            calculate_document_totals(
                line_amounts_minor=[1000],
                tax_rules=(TaxRule("VAT", Decimal("5")), TaxRule(" vat ", Decimal("5"))),
            )
        # Disabled historical/configured rules do not affect the current document.
        totals = calculate_document_totals(
            line_amounts_minor=[1000],
            tax_rules=(TaxRule("VAT", Decimal("5")), TaxRule("vat", Decimal("5"), enabled=False)),
        )
        self.assertEqual(totals.tax_minor, 50)

    def test_unrepresentable_currency_amount_fails_with_value_error(self):
        with self.assertRaises(ValueError):
            from agent_core.commercial_document_math import rounded_minor
            rounded_minor(Decimal("1E1000"))

    def test_rejects_negative_and_non_integer_minor_amounts(self):
        for amount in (-1, 1.5, True):
            with self.subTest(amount=amount), self.assertRaises(ValueError):
                calculate_document_totals(line_amounts_minor=[amount])


if __name__ == "__main__":
    unittest.main()
