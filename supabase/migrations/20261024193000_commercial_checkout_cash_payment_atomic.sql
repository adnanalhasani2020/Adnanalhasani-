-- Ensure every confirmed cash checkout has exactly one successful payment row.
-- The insert and stock/sale writes run in the same PostgreSQL transaction.
-- Credit sales and GL posting remain gated until their complete workflows are available.
CREATE OR REPLACE FUNCTION public.commercial_checkout_sale(
  p_offering_id uuid,
  p_location_id uuid,
  p_quantity integer,
  p_idempotency_key text
) RETURNS jsonb
LANGUAGE plpgsql
SET search_path TO 'pg_catalog', 'public', 'commercial_private'
AS $function$
DECLARE
  v_org_id uuid;
  v_result jsonb;
  v_sale_id uuid;
  v_total bigint;
  v_currency text;
BEGIN
  IF auth.uid() IS NULL
     OR p_offering_id IS NULL OR p_location_id IS NULL
     OR p_quantity IS NULL OR p_quantity < 1 OR p_quantity > 100000
     OR p_idempotency_key IS NULL
     OR length(btrim(p_idempotency_key)) < 8 OR length(btrim(p_idempotency_key)) > 200 THEN
    RETURN commercial_private.commercial_checkout_sale(
      p_offering_id, p_location_id, p_quantity, p_idempotency_key
    );
  END IF;

  -- Keep the original idempotent retry path: a retry must not create a second sale.
  IF EXISTS (
    SELECT 1 FROM public.commercial_sales s
    WHERE s.user_id = auth.uid() AND s.idempotency_key = p_idempotency_key
  ) THEN
    v_result := commercial_private.commercial_checkout_sale(
      p_offering_id, p_location_id, p_quantity, p_idempotency_key
    );
  ELSE
    SELECT a.organization_id INTO v_org_id
    FROM public.commercial_offerings o
    JOIN public.commercial_activities a ON a.id = o.activity_id
    WHERE o.id = p_offering_id
      AND o.state = 'active'
      AND o.valid_from <= now()
      AND (o.valid_to IS NULL OR o.valid_to > now())
      AND a.state = 'active'
      AND a.deleted_at IS NULL;

    IF NOT FOUND OR v_org_id IS NULL THEN
      RAISE EXCEPTION 'offering_not_available' USING ERRCODE = 'P0002';
    END IF;
    IF NOT EXISTS (
      SELECT 1 FROM public.commercial_organizations o
      WHERE o.id = v_org_id AND o.state = 'active' AND o.deleted_at IS NULL
    ) THEN
      RAISE EXCEPTION 'organization_inactive' USING ERRCODE = '42501';
    END IF;

    v_result := commercial_private.commercial_checkout_sale(
      p_offering_id, p_location_id, p_quantity, p_idempotency_key
    );
  END IF;

  v_sale_id := (v_result->>'sale_id')::uuid;
  v_total := (v_result->>'total_minor')::bigint;
  v_org_id := (v_result->>'organization_id')::uuid;
  v_currency := v_result->>'currency_code';

  IF v_sale_id IS NULL OR v_total IS NULL OR v_total <= 0
     OR v_org_id IS NULL OR v_currency IS NULL THEN
    RAISE EXCEPTION 'checkout_result_missing_payment_fields' USING ERRCODE = '22023';
  END IF;

  INSERT INTO public.commercial_payments (
    organization_id, sale_id, provider, idempotency_key, amount_minor,
    currency_code, status, payment_method_type
  ) VALUES (
    v_org_id, v_sale_id, 'ruh_cash_checkout', 'sale:' || v_sale_id::text || ':cash',
    v_total, v_currency, 'succeeded', 'cash'
  )
  ON CONFLICT (organization_id, idempotency_key) DO NOTHING;

  RETURN v_result || jsonb_build_object('payment_recorded', true, 'payment_status', 'paid');
END;
$function$;
