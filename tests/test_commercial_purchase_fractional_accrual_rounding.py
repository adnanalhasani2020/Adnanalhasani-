from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261026080000_commercial_purchase_fractional_accrual_rounding.sql"


def test_receipt_accounting_rounds_cumulative_line_value_not_each_partial_receipt():
    sql = MIGRATION.read_text(encoding="utf-8")
    receipt = sql.split(
        "CREATE OR REPLACE FUNCTION commercial_private.commercial_post_purchase_receipt_financials()",
        1,
    )[1].split("$function$;", 1)[0]
    assert "round((prior.quantity_before + ri.quantity::numeric) * ri.unit_cost_minor::numeric)" in receipt
    assert "round(prior.quantity_before * ri.unit_cost_minor::numeric)" in receipt
    assert "(pr.received_at,pr.id) < (NEW.received_at,NEW.id)" in receipt
    assert "DEFERRABLE INITIALLY DEFERRED" in sql


def test_supplier_invoice_expected_cost_uses_cumulative_partial_invoice_delta():
    sql = MIGRATION.read_text(encoding="utf-8")
    invoice = sql.split(
        "CREATE OR REPLACE FUNCTION commercial_private.commercial_post_supplier_invoice_financials()",
        1,
    )[1].split("$function$;", 1)[0]
    assert "round((prior.quantity_before + si.quantity::numeric) * poi.unit_cost_minor::numeric)" in invoice
    assert "round(prior.quantity_before * poi.unit_cost_minor::numeric)" in invoice
    assert "(prior_invoice.recorded_at,prior_invoice.id) < (NEW.recorded_at,NEW.id)" in invoice
    assert "prior_invoice.state='recorded'" in invoice


def test_zero_value_supplier_invoice_is_balanced_through_price_variance_instead_of_rejected():
    sql = MIGRATION.read_text(encoding="utf-8")
    invoice = sql.split(
        "CREATE OR REPLACE FUNCTION commercial_private.commercial_post_supplier_invoice_financials()",
        1,
    )[1].split("$function$;", 1)[0]
    assert "v_price_variance:=v_invoice_subtotal-v_expected_subtotal" in invoice
    assert "IF v_price_variance<0 THEN" in invoice
    assert "supplier_invoice_zero_total_conflicts_with_received_cost" not in invoice
    assert "IF v_expected_subtotal>0 OR v_total>0 THEN" in invoice
    assert "CASE WHEN NEW.total_minor=0 THEN 'paid' ELSE 'open' END" in invoice


def test_partial_rounding_examples_match_postgresql_numeric_rounding_policy():
    # PostgreSQL round(numeric) rounds halves away from zero; values here are nonnegative.
    from decimal import Decimal, ROUND_HALF_UP

    def rounded(value):
        return int(Decimal(value).quantize(Decimal("1"), rounding=ROUND_HALF_UP))

    def delta(prior_qty, added_qty, unit_cost):
        before = Decimal(prior_qty) * Decimal(unit_cost)
        after = (Decimal(prior_qty) + Decimal(added_qty)) * Decimal(unit_cost)
        return rounded(after) - rounded(before)

    # Two half-unit receipts at one minor unit must value to one minor unit total, not two.
    assert delta("0", "0.5", "1") + delta("0.5", "0.5", "1") == 1
    # Splitting invoice quantity must not round the same cumulative expected cost twice.
    assert delta("0", "0.5", "1") + delta("0.5", "0.5", "1") == 1
    # A later quarter-unit tranche gets only the remaining cumulative rounding delta.
    assert delta("0", "0.25", "1") + delta("0.25", "0.25", "1") == 1
