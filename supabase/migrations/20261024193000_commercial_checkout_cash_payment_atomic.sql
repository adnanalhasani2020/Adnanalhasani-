-- Ensure every confirmed cash checkout has exactly one successful payment row.
-- Payment insertion runs in a private SECURITY DEFINER function so client RLS does not
-- need a direct INSERT policy on the protected payments table.
CREATE OR REPLACE FUNCTION commercial_private.commercial_checkout_sale_with_cash_payment(
  p_offering_id uuid,
  p_location_id uuid,
  p_quantity integer,
  p_idempotency_key text
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog', 'public', 'commercial_private'
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_org_id uuid;
  v_result jsonb;
  v_sale_id uuid;
  v_total bigint;
  v_currency text;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'authentication_required' USING ERRCODE = '42501';
  END IF;

  -- Preserve idempotent retries even if an offering or organization was later deactivated.
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_sales s
    WHERE s.user_id = v_uid AND s.idempotency_key = p_idempotency_key
  ) THEN
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
  END IF;

  -- The existing trusted checkout validates membership, stock, quantity and idempotency.
  v_result := commercial_private.commercial_checkout_sale(
    p_offering_id, p_location_id, p_quantity, p_idempotency_key
  );

  v_sale_id := (v_result->>'sale_id')::uuid;
  v_total := (v_result->>'total_minor')::bigint;
  v_org_id := (v_result->>'organization_id')::uuid;
  v_currency := v_result->>'currency_code';

  IF v_sale_id IS NULL OR v_total IS NULL OR v_total < 0
     OR v_org_id IS NULL OR v_currency IS NULL THEN
    RAISE EXCEPTION 'checkout_result_missing_payment_fields' USING ERRCODE = '22023';
  END IF;

  IF v_total > 0 THEN
    INSERT INTO public.commercial_payments (
      organization_id, sale_id, provider, idempotency_key, amount_minor,
      currency_code, status, payment_method_type
    ) VALUES (
      v_org_id, v_sale_id, 'ruh_cash_checkout', 'sale:' || v_sale_id::text || ':cash',
      v_total, v_currency, 'succeeded', 'cash'
    )
    ON CONFLICT (organization_id, idempotency_key) DO NOTHING;
  END IF;

  RETURN v_result || jsonb_build_object(
    'payment_recorded', v_total > 0,
    'payment_status', CASE WHEN v_total = 0 THEN 'paid' ELSE 'paid' END
  );
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_checkout_sale(
  p_offering_id uuid,
  p_location_id uuid,
  p_quantity integer,
  p_idempotency_key text
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path TO 'pg_catalog', 'public', 'commercial_private'
AS $function$
BEGIN
  IF auth.uid() IS NULL THEN
    RAISE EXCEPTION 'authentication_required' USING ERRCODE = '42501';
  END IF;
  RETURN commercial_private.commercial_checkout_sale_with_cash_payment(
    p_offering_id, p_location_id, p_quantity, p_idempotency_key
  );
END;
$function$;

REVOKE ALL ON FUNCTION commercial_private.commercial_checkout_sale_with_cash_payment(uuid, uuid, integer, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION commercial_private.commercial_checkout_sale_with_cash_payment(uuid, uuid, integer, text) TO authenticated;
REVOKE ALL ON FUNCTION public.commercial_checkout_sale(uuid, uuid, integer, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_checkout_sale(uuid, uuid, integer, text) TO authenticated;
