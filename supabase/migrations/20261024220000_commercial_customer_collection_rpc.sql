-- Record a customer collection and post its GL entry in one transaction.
-- The RPC is intentionally limited to finance-capable organization roles.
CREATE OR REPLACE FUNCTION public.commercial_record_customer_payment(
  p_sale_id uuid,
  p_amount_minor bigint,
  p_payment_method_type text,
  p_idempotency_key text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog', 'public', 'commercial_private'
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_sale public.commercial_sales%ROWTYPE;
  v_payment public.commercial_payments%ROWTYPE;
  v_fingerprint text;
  v_existing public.commercial_payments%ROWTYPE;
  v_posting jsonb;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'authentication_required' USING ERRCODE = '42501';
  END IF;
  IF p_sale_id IS NULL
     OR p_amount_minor IS NULL OR p_amount_minor <= 0
     OR p_payment_method_type IS NULL
     OR p_payment_method_type NOT IN ('cash','bank_transfer','card','other')
     OR p_idempotency_key IS NULL
     OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200 THEN
    RAISE EXCEPTION 'invalid_customer_payment_request' USING ERRCODE = '22023';
  END IF;

  PERFORM pg_advisory_xact_lock(hashtext(v_uid::text), hashtext(p_idempotency_key));

  SELECT * INTO v_existing
  FROM public.commercial_payments
  WHERE idempotency_key = p_idempotency_key
    AND organization_id IN (
      SELECT m.organization_id
      FROM public.commercial_organization_members m
      WHERE m.user_id = v_uid AND m.state = 'active'
    )
  FOR UPDATE;

  IF FOUND THEN
    v_fingerprint := md5(concat_ws('|', v_existing.sale_id::text,
      v_existing.amount_minor::text, v_existing.currency_code,
      v_existing.payment_method_type));
    IF v_existing.sale_id IS DISTINCT FROM p_sale_id
       OR v_existing.amount_minor IS DISTINCT FROM p_amount_minor
       OR v_existing.payment_method_type IS DISTINCT FROM p_payment_method_type
       OR v_existing.provider_payment_id IS DISTINCT FROM v_fingerprint THEN
      RAISE EXCEPTION 'idempotency_key_reused_with_different_request' USING ERRCODE = '23505';
    END IF;
    IF v_existing.status <> 'succeeded' THEN
      RAISE EXCEPTION 'existing_payment_not_succeeded' USING ERRCODE = '55000';
    END IF;
    RETURN jsonb_build_object('payment_id', v_existing.id, 'sale_id', v_existing.sale_id,
      'amount_minor', v_existing.amount_minor, 'status', v_existing.status, 'idempotent_replay', true);
  END IF;

  SELECT * INTO v_sale
  FROM public.commercial_sales
  WHERE id = p_sale_id
  FOR UPDATE;

  IF NOT FOUND OR v_sale.status <> 'completed' THEN
    RAISE EXCEPTION 'completed_sale_required' USING ERRCODE = 'P0002';
  END IF;
  IF v_sale.organization_id IS NULL OR v_sale.customer_id IS NULL
     OR v_sale.payment_status NOT IN ('pending','partially_paid') THEN
    RAISE EXCEPTION 'outstanding_customer_sale_required' USING ERRCODE = '55000';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id = v_sale.organization_id
      AND m.user_id = v_uid AND m.state = 'active'
      AND m.role IN ('owner','admin','manager')
  ) THEN
    RAISE EXCEPTION 'accounting_permission_required' USING ERRCODE = '42501';
  END IF;

  v_fingerprint := md5(concat_ws('|', p_sale_id::text, p_amount_minor::text,
    v_sale.currency_code, p_payment_method_type));

  INSERT INTO public.commercial_payments(
    organization_id, sale_id, provider, provider_payment_id, idempotency_key,
    amount_minor, currency_code, status, payment_method_type
  ) VALUES (
    v_sale.organization_id, v_sale.id, 'manual', v_fingerprint, p_idempotency_key,
    p_amount_minor, v_sale.currency_code, 'succeeded', p_payment_method_type
  )
  RETURNING * INTO v_payment;

  -- This guarded posting validates remaining receivable capacity and writes
  -- the balanced journal entry before updating the sale payment status.
  v_posting := public.commercial_gl_post_payment(v_payment.id);

  RETURN jsonb_build_object(
    'payment_id', v_payment.id,
    'sale_id', v_sale.id,
    'amount_minor', v_payment.amount_minor,
    'currency_code', v_payment.currency_code,
    'status', v_payment.status,
    'posting', v_posting,
    'idempotent_replay', false
  );
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_record_customer_payment(uuid, bigint, text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_record_customer_payment(uuid, bigint, text, text) TO authenticated;
