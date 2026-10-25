-- Normalize checkout response on first execution and idempotent replay.
-- The sale + sale_items remain the canonical operational invoice projection;
-- this does not claim tax-invoice compliance or create a second document ledger.
CREATE OR REPLACE FUNCTION public.commercial_checkout_sale_multi(
  p_lines jsonb,
  p_location_id uuid,
  p_idempotency_key text,
  p_customer_id uuid DEFAULT NULL,
  p_payment_mode text DEFAULT 'cash',
  p_initial_payment_minor bigint DEFAULT NULL
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path TO 'pg_catalog', 'public', 'commercial_private'
AS $function$
DECLARE
  v_result jsonb;
  v_sale uuid;
  v_total bigint;
  v_paid bigint;
  v_lines jsonb;
BEGIN
  IF auth.uid() IS NULL THEN
    RAISE EXCEPTION 'authentication_required' USING ERRCODE = '42501';
  END IF;

  v_result := commercial_private.commercial_checkout_sale_multi(
    p_lines,
    p_location_id,
    p_idempotency_key,
    p_customer_id,
    p_payment_mode,
    p_initial_payment_minor
  );

  v_sale := (v_result->>'sale_id')::uuid;
  IF v_sale IS NULL THEN
    RAISE EXCEPTION 'checkout_result_missing_sale_id' USING ERRCODE = '22023';
  END IF;

  SELECT s.total_minor
    INTO v_total
  FROM public.commercial_sales s
  WHERE s.id = v_sale
    AND s.user_id = auth.uid();

  IF NOT FOUND THEN
    RAISE EXCEPTION 'checkout_sale_not_visible' USING ERRCODE = '42501';
  END IF;

  SELECT coalesce(sum(p.amount_minor), 0)::bigint
    INTO v_paid
  FROM public.commercial_payments p
  WHERE p.sale_id = v_sale
    AND p.organization_id = (v_result->>'organization_id')::uuid
    AND p.status = 'succeeded';

  SELECT coalesce(
    jsonb_agg(
      jsonb_build_object(
        'offering_id', i.offering_id,
        'product_name', i.product_name_snapshot,
        'quantity', i.quantity,
        'unit_price_minor', i.unit_price_minor,
        'line_total_minor', i.line_total_minor
      )
      ORDER BY i.created_at, i.id
    ),
    '[]'::jsonb
  )
  INTO v_lines
  FROM public.commercial_sale_items i
  WHERE i.sale_id = v_sale;

  RETURN v_result || jsonb_build_object(
    'total_minor', v_total,
    'paid_minor', v_paid,
    'outstanding_minor', greatest(v_total - v_paid, 0),
    'payment_recorded', v_paid > 0,
    'lines', v_lines
  );
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_checkout_sale_multi(jsonb,uuid,text,uuid,text,bigint) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_checkout_sale_multi(jsonb,uuid,text,uuid,text,bigint) TO authenticated;
