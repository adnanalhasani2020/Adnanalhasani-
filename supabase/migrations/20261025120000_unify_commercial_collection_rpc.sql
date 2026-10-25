-- Keep the legacy collection RPC as a compatibility shim, but route it through
-- the canonical customer-payment RPC so every accepted collection posts GL atomically.
CREATE OR REPLACE FUNCTION commercial_private.commercial_record_sale_collection(
  p_sale_id uuid, p_amount_minor bigint, p_idempotency_key text
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog', 'public', 'commercial_private'
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_org uuid;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501';
  END IF;

  SELECT s.organization_id INTO v_org
  FROM public.commercial_sales s
  WHERE s.id = p_sale_id AND s.status = 'completed';
  IF NOT FOUND OR v_org IS NULL THEN
    RAISE EXCEPTION 'sale_not_found' USING ERRCODE='P0002';
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id = v_org AND m.user_id = v_uid AND m.state = 'active'
  ) THEN
    RAISE EXCEPTION 'organization_access_denied' USING ERRCODE='42501';
  END IF;

  -- Let a previously committed request replay through the canonical idempotency
  -- check before evaluating later return/refund state changes.
  IF EXISTS (
    SELECT 1 FROM public.commercial_payments p
    WHERE p.organization_id = v_org AND p.idempotency_key = p_idempotency_key
  ) THEN
    RETURN public.commercial_record_customer_payment(
      p_sale_id, p_amount_minor, 'cash', p_idempotency_key
    );
  END IF;

  -- Preserve the legacy endpoint's conservative return/refund reconciliation gate
  -- for new collections.
  IF EXISTS (
    SELECT 1 FROM public.commercial_payments p
    WHERE p.sale_id = p_sale_id AND p.status IN ('refunded','partially_refunded')
  ) OR EXISTS (
    SELECT 1 FROM public.commercial_sales_returns r
    WHERE r.sale_id = p_sale_id AND r.status NOT IN ('rejected','cancelled')
  ) THEN
    RAISE EXCEPTION 'sale_return_reconciliation_required' USING ERRCODE='22023';
  END IF;

  -- This RPC validates manager privileges, outstanding balance, idempotency and
  -- invokes commercial_gl_post_payment before the transaction can commit.
  RETURN public.commercial_record_customer_payment(
    p_sale_id, p_amount_minor, 'cash', p_idempotency_key
  );
END;
$function$;

REVOKE ALL ON FUNCTION commercial_private.commercial_record_sale_collection(uuid,bigint,text)
  FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION commercial_private.commercial_record_sale_collection(uuid,bigint,text)
  TO authenticated;
