-- Executed against a disposable PostgreSQL 17 service in GitHub Actions.
-- This is a structural migration smoke test, not a replacement for authenticated full-schema integration tests.
DO $test$
DECLARE
  v_rejected boolean;
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conrelid='public.commercial_inventory_balances'::regclass
      AND conname='commercial_inventory_balances_quantity_on_hand_whole_qty_ck'
  ) THEN RAISE EXCEPTION 'missing inventory whole-quantity constraint'; END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conrelid='public.commercial_sale_items'::regclass
      AND conname='commercial_sale_items_selling_quantity_whole_qty_ck'
  ) THEN RAISE EXCEPTION 'missing sale selling-quantity constraint'; END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conrelid='public.commercial_purchase_order_items'::regclass
      AND conname='commercial_purchase_order_items_quantity_ordered_whole_qty_ck'
  ) THEN RAISE EXCEPTION 'missing purchase ordered-quantity constraint'; END IF;
  IF EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conrelid='public.commercial_global_units'::regclass
      AND conname='commercial_global_units_factor_to_base_whole_qty_ck'
  ) THEN RAISE EXCEPTION 'global conversion factor was incorrectly constrained'; END IF;
  IF NOT (SELECT relrowsecurity FROM pg_class WHERE oid='public.commercial_sale_unit_idempotency'::regclass) THEN
    RAISE EXCEPTION 'sell-unit idempotency table must have RLS enabled';
  END IF;
  IF has_table_privilege('authenticated','public.commercial_sale_unit_idempotency','SELECT') THEN
    RAISE EXCEPTION 'authenticated clients must not read idempotency mapping table directly';
  END IF;
  IF to_regprocedure('commercial_private.commercial_get_sale_unit_idempotency(text)') IS NULL
     OR to_regprocedure('commercial_private.commercial_record_sale_unit_idempotency(text,text,text,uuid)') IS NULL THEN
    RAISE EXCEPTION 'trusted idempotency RPC helpers are missing';
  END IF;

  v_rejected := false;
  BEGIN
    INSERT INTO public.commercial_inventory_balances(quantity_on_hand) VALUES (1.5);
  EXCEPTION WHEN check_violation THEN v_rejected := true;
  END;
  IF NOT v_rejected THEN RAISE EXCEPTION 'fractional inventory quantity was accepted'; END IF;

  v_rejected := false;
  BEGIN
    INSERT INTO public.commercial_inventory_balances(quantity_on_hand) VALUES ('NaN'::numeric);
  EXCEPTION WHEN check_violation THEN v_rejected := true;
  END;
  IF NOT v_rejected THEN RAISE EXCEPTION 'NaN inventory quantity was accepted'; END IF;

  v_rejected := false;
  BEGIN
    INSERT INTO public.commercial_inventory_balances(quantity_on_hand) VALUES ('Infinity'::numeric);
  EXCEPTION WHEN check_violation THEN v_rejected := true;
  END;
  IF NOT v_rejected THEN RAISE EXCEPTION 'infinite inventory quantity was accepted'; END IF;

  -- Physical measurement conversion factors remain fractional by design.
  INSERT INTO public.commercial_global_units(factor_to_base) VALUES (0.01);
END;
$test$;
