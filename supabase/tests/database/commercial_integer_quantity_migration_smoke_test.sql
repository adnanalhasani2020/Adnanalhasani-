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

-- Exercise the security-definer helpers as an authenticated caller with a simulated JWT subject.
INSERT INTO auth.users(id) VALUES ('11111111-1111-4111-8111-111111111111');
INSERT INTO public.commercial_sales(id,user_id,idempotency_key)
VALUES ('22222222-2222-4222-8222-222222222222','11111111-1111-4111-8111-111111111111','base-checkout-key');

SELECT set_config('request.jwt.claim.sub','11111111-1111-4111-8111-111111111111',false);
SET ROLE authenticated;
DO $auth_test$
DECLARE
  v_fingerprint text;
  v_base_key text;
  v_sale_id uuid;
  v_count integer;
  v_rejected boolean := false;
BEGIN
  PERFORM commercial_private.commercial_record_sale_unit_idempotency(
    'caller-request-key','request-fingerprint','base-checkout-key',
    '22222222-2222-4222-8222-222222222222'
  );
  SELECT request_fingerprint,base_idempotency_key,sale_id
    INTO v_fingerprint,v_base_key,v_sale_id
  FROM commercial_private.commercial_get_sale_unit_idempotency('caller-request-key');
  IF v_fingerprint IS DISTINCT FROM 'request-fingerprint'
     OR v_base_key IS DISTINCT FROM 'base-checkout-key'
     OR v_sale_id IS DISTINCT FROM '22222222-2222-4222-8222-222222222222'::uuid THEN
    RAISE EXCEPTION 'authenticated idempotency helper round-trip failed';
  END IF;

  BEGIN
    PERFORM 1 FROM public.commercial_sale_unit_idempotency;
    RAISE EXCEPTION 'authenticated client read idempotency table directly';
  EXCEPTION WHEN insufficient_privilege THEN
    v_rejected := true;
  END;
  IF NOT v_rejected THEN RAISE EXCEPTION 'direct idempotency table access was not blocked'; END IF;

  v_rejected := false;
  BEGIN
    PERFORM commercial_private.commercial_record_sale_unit_idempotency(
      'forged-request-key','forged-fingerprint','not-the-sale-key',
      '22222222-2222-4222-8222-222222222222'
    );
    RAISE EXCEPTION 'idempotency helper accepted a mismatched sale key';
  EXCEPTION WHEN insufficient_privilege THEN
    v_rejected := true;
  END;
  IF NOT v_rejected THEN RAISE EXCEPTION 'idempotency helper did not bind sale ownership and base key'; END IF;

  PERFORM set_config('request.jwt.claim.sub','33333333-3333-4333-8333-333333333333',true);
  SELECT count(*) INTO v_count
  FROM commercial_private.commercial_get_sale_unit_idempotency('caller-request-key');
  IF v_count<>0 THEN RAISE EXCEPTION 'idempotency helper leaked another user mapping'; END IF;
END;
$auth_test$;
RESET ROLE;
