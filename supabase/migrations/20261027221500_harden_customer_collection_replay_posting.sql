-- Revalidate customer collections against approved return credits and executed refunds.
-- Keep the payment row and its journal posting in the same transaction.
CREATE OR REPLACE FUNCTION commercial_private.commercial_record_customer_payment(
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
  v_existing public.commercial_payments%ROWTYPE;
  v_posting jsonb;
  v_paid_minor bigint := 0;
  v_refunded_minor bigint := 0;
  v_credited_minor bigint := 0;
  v_outstanding_minor bigint := 0;
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
    IF NOT EXISTS (
      SELECT 1 FROM public.commercial_organization_members m
      WHERE m.organization_id = v_existing.organization_id
        AND m.user_id = v_uid AND m.state = 'active'
        AND m.role IN ('owner','admin','manager')
    ) THEN
      RAISE EXCEPTION 'accounting_permission_required' USING ERRCODE = '42501';
    END IF;
    IF v_existing.sale_id IS DISTINCT FROM p_sale_id
       OR v_existing.amount_minor IS DISTINCT FROM p_amount_minor
       OR v_existing.payment_method_type IS DISTINCT FROM p_payment_method_type THEN
      RAISE EXCEPTION 'idempotency_key_reused_with_different_request' USING ERRCODE = '23505';
    END IF;
    IF v_existing.status <> 'succeeded' THEN
      RAISE EXCEPTION 'existing_payment_not_succeeded' USING ERRCODE = '55000';
    END IF;
    SELECT jsonb_build_object('journal_entry_id', e.id, 'posted', true)
      INTO v_posting
    FROM public.commercial_journal_entries e
    WHERE e.organization_id = v_existing.organization_id
      AND e.source_type = 'payment'
      AND e.source_id = v_existing.id
      AND e.status = 'posted'
    ORDER BY e.created_at DESC
    LIMIT 1;
    IF v_posting IS NULL THEN
      RAISE EXCEPTION 'customer_payment_journal_not_posted' USING ERRCODE = '55000';
    END IF;
    RETURN jsonb_build_object('payment_id', v_existing.id, 'sale_id', v_existing.sale_id,
      'amount_minor', v_existing.amount_minor, 'currency_code', v_existing.currency_code,
      'status', v_existing.status, 'posting', v_posting, 'idempotent_replay', true);
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

  -- A finalized approved return is a credit note even if its cash refund is
  -- still pending. Executed refunds reduce net payments, but must not be counted
  -- as a second credit. This server-side guard prevents over-collection even if
  -- the client has stale receivable data.
  SELECT COALESCE(sum(p.amount_minor),0) INTO v_paid_minor
  FROM public.commercial_payments p
  WHERE p.sale_id = v_sale.id
    AND p.organization_id = v_sale.organization_id
    AND p.status IN ('succeeded','partially_refunded','refunded');

  SELECT COALESCE(sum(rp.amount_minor),0) INTO v_refunded_minor
  FROM public.commercial_return_refund_postings rp
  JOIN public.commercial_sales_returns sr ON sr.id = rp.return_id
  WHERE sr.sale_id = v_sale.id
    AND sr.organization_id = v_sale.organization_id;

  SELECT COALESCE(sum(sr.approved_refund_minor),0) INTO v_credited_minor
  FROM public.commercial_sales_returns sr
  WHERE sr.sale_id = v_sale.id
    AND sr.organization_id = v_sale.organization_id
    AND sr.status = 'completed'
    AND sr.approved_refund_minor IS NOT NULL;

  v_outstanding_minor := GREATEST(
    v_sale.total_minor - GREATEST(v_paid_minor - v_refunded_minor, 0) - v_credited_minor,
    0
  );
  IF p_amount_minor > v_outstanding_minor THEN
    RAISE EXCEPTION 'collection_exceeds_outstanding_balance_after_returns'
      USING ERRCODE = '22023';
  END IF;

  INSERT INTO public.commercial_payments(
    organization_id, sale_id, provider, provider_payment_id, idempotency_key,
    amount_minor, currency_code, status, payment_method_type
  ) VALUES (
    v_sale.organization_id, v_sale.id, 'manual', 'manual:' || p_idempotency_key, p_idempotency_key,
    p_amount_minor, v_sale.currency_code, 'succeeded', p_payment_method_type
  )
  RETURNING * INTO v_payment;

  -- The AFTER INSERT trigger commercial_payment_gl_post invokes the guarded
  -- GL routine in this same transaction. Do not call it a second time here:
  -- the trigger is the single posting path and updates payment_status.
  SELECT jsonb_build_object('journal_entry_id', e.id, 'posted', true)
  INTO v_posting
  FROM public.commercial_journal_entries e
  WHERE e.organization_id = v_payment.organization_id
    AND e.source_type = 'payment'
    AND e.source_id = v_payment.id
    AND e.status = 'posted'
  ORDER BY e.created_at DESC
  LIMIT 1;

  IF v_posting IS NULL THEN
    RAISE EXCEPTION 'customer_payment_journal_not_posted' USING ERRCODE = '55000';
  END IF;

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

REVOKE ALL ON FUNCTION commercial_private.commercial_record_customer_payment(uuid, bigint, text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION commercial_private.commercial_record_customer_payment(uuid, bigint, text, text) TO authenticated;
