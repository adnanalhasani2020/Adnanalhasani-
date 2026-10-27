-- Guard return-adjusted collections against malformed credits and currency mismatches.
-- Preserve the hardened idempotent payment RPC and the existing single GL posting trigger.
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

  -- Reject malformed return credits instead of silently undercounting them.
  IF EXISTS (
    SELECT 1 FROM public.commercial_sales_returns sr
    WHERE sr.sale_id = v_sale.id
      AND sr.organization_id = v_sale.organization_id
      AND sr.status = 'completed'
      AND (sr.approved_refund_minor IS NULL OR sr.currency_code IS DISTINCT FROM v_sale.currency_code)
  ) THEN
    RAISE EXCEPTION 'completed_return_credit_missing_or_currency_mismatch'
      USING ERRCODE = '55000';
  END IF;
  IF EXISTS (
    SELECT 1
    FROM public.commercial_return_refund_postings rp
    JOIN public.commercial_sales_returns sr ON sr.id = rp.return_id
    WHERE sr.sale_id = v_sale.id
      AND sr.organization_id = v_sale.organization_id
      AND (rp.currency_code IS DISTINCT FROM v_sale.currency_code
        OR sr.currency_code IS DISTINCT FROM v_sale.currency_code)
  ) THEN
    RAISE EXCEPTION 'return_refund_currency_mismatch'
      USING ERRCODE = '55000';
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

CREATE OR REPLACE FUNCTION public.commercial_gl_post_payment(p_payment_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog', 'public', 'commercial_private'
AS $function$
DECLARE
  p public.commercial_payments%ROWTYPE;
  s public.commercial_sales%ROWTYPE;
  m record;
  v_party uuid;
  v_entry uuid;
  v_paid numeric;
  v_refunded numeric;
  v_credited numeric;
  v_outstanding numeric;
  v_actor uuid;
  v_receipt_mapping_key text;
BEGIN
  IF auth.uid() IS NULL AND pg_trigger_depth()=0 THEN
    RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501';
  END IF;
  SELECT * INTO p FROM public.commercial_payments WHERE id=p_payment_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'payment_not_found' USING ERRCODE='P0002'; END IF;
  IF p.status<>'succeeded' OR p.sale_id IS NULL THEN
    RAISE EXCEPTION 'succeeded_payment_with_sale_required' USING ERRCODE='55000';
  END IF;
  SELECT * INTO s FROM public.commercial_sales WHERE id=p.sale_id FOR UPDATE;
  IF NOT FOUND OR s.organization_id IS DISTINCT FROM p.organization_id OR s.currency_code<>p.currency_code THEN
    RAISE EXCEPTION 'payment_sale_scope_or_currency_mismatch' USING ERRCODE='55000';
  END IF;
  IF pg_trigger_depth()=0 AND NOT EXISTS(
    SELECT 1 FROM public.commercial_organization_members om
    WHERE om.organization_id=p.organization_id AND om.user_id=auth.uid()
      AND om.state='active' AND om.role IN ('owner','admin','manager')
  ) THEN
    RAISE EXCEPTION 'accounting_permission_required' USING ERRCODE='42501';
  END IF;
  IF s.payment_status NOT IN ('pending','partially_paid') THEN
    RAISE EXCEPTION 'payment_not_applicable_to_paid_or_failed_sale' USING ERRCODE='55000';
  END IF;
  IF s.customer_id IS NULL THEN RAISE EXCEPTION 'payment_sale_customer_required' USING ERRCODE='55000'; END IF;

  v_receipt_mapping_key:=CASE p.payment_method_type
    WHEN 'cash' THEN 'cash'
    WHEN 'bank_transfer' THEN 'bank'
    WHEN 'card' THEN 'card_clearing'
    WHEN 'other' THEN 'other_receipts'
    ELSE NULL END;
  v_party:=commercial_private.commercial_customer_party_account(s.organization_id,s.customer_id,s.currency_code);
  SELECT max(ledger_account_id) FILTER(WHERE mapping_key=v_receipt_mapping_key) receipt_account,
         max(ledger_account_id) FILTER(WHERE mapping_key='accounts_receivable') ar
    INTO m FROM public.commercial_gl_account_mappings WHERE organization_id=p.organization_id;
  IF m.receipt_account IS NULL OR m.ar IS NULL THEN
    RAISE EXCEPTION 'receipt_and_ar_mappings_required' USING ERRCODE='55000';
  END IF;

  -- Completed return credits and executed refunds must use the invoice currency.
  IF EXISTS (
    SELECT 1 FROM public.commercial_sales_returns sr
    WHERE sr.sale_id = s.id
      AND sr.organization_id = s.organization_id
      AND sr.status = 'completed'
      AND (sr.approved_refund_minor IS NULL OR sr.currency_code IS DISTINCT FROM s.currency_code)
  ) THEN
    RAISE EXCEPTION 'completed_return_credit_missing_or_currency_mismatch'
      USING ERRCODE='55000';
  END IF;
  IF EXISTS (
    SELECT 1
    FROM public.commercial_return_refund_postings rp
    JOIN public.commercial_sales_returns sr ON sr.id=rp.return_id
    WHERE sr.sale_id=s.id AND sr.organization_id=s.organization_id
      AND (rp.currency_code IS DISTINCT FROM s.currency_code
        OR sr.currency_code IS DISTINCT FROM s.currency_code)
  ) THEN
    RAISE EXCEPTION 'return_refund_currency_mismatch'
      USING ERRCODE='55000';
  END IF;

  SELECT COALESCE(sum(pay.amount_minor),0) INTO v_paid
  FROM public.commercial_payments pay
  WHERE pay.sale_id=s.id
    AND pay.organization_id=s.organization_id
    AND pay.status IN ('succeeded','partially_refunded','refunded')
    AND pay.id<>p.id;

  SELECT COALESCE(sum(rp.amount_minor),0) INTO v_refunded
  FROM public.commercial_return_refund_postings rp
  JOIN public.commercial_sales_returns sr ON sr.id=rp.return_id
  WHERE sr.sale_id=s.id AND sr.organization_id=s.organization_id;

  SELECT COALESCE(sum(sr.approved_refund_minor),0) INTO v_credited
  FROM public.commercial_sales_returns sr
  WHERE sr.sale_id=s.id AND sr.organization_id=s.organization_id
    AND sr.status='completed' AND sr.approved_refund_minor IS NOT NULL;

  v_outstanding:=GREATEST(
    s.total_minor-GREATEST(v_paid-v_refunded,0)-v_credited,
    0
  );
  IF p.amount_minor>v_outstanding THEN
    RAISE EXCEPTION 'payment_exceeds_outstanding_after_returns' USING ERRCODE='22023';
  END IF;

  v_actor:=COALESCE(auth.uid(),s.user_id);
  v_entry:=commercial_private.commercial_gl_post_entry(
    p.organization_id,'payment:'||p.id,'payment',p.id,
    'Customer collection for receipt '||s.receipt_number,p.currency_code,
    jsonb_build_array(
      jsonb_build_object('ledger_account_id',m.receipt_account,'debit_minor',p.amount_minor,'credit_minor',0,
        'description',CASE v_receipt_mapping_key WHEN 'cash' THEN 'Customer cash collection'
          WHEN 'bank' THEN 'Customer bank collection' WHEN 'card_clearing' THEN 'Card collection clearing'
          ELSE 'Other receipt clearing' END),
      jsonb_build_object('ledger_account_id',m.ar,'debit_minor',0,'credit_minor',p.amount_minor,
        'description','Reduce customer receivable','party_account_id',v_party)
    ),v_actor,NULL
  );
  UPDATE public.commercial_sales
  SET payment_status=CASE WHEN GREATEST(v_outstanding-p.amount_minor,0)=0
    THEN 'paid' ELSE 'partially_paid' END,
    updated_at=now()
  WHERE id=s.id;
  RETURN jsonb_build_object('payment_id',p.id,'journal_entry_id',v_entry,'posted',true,
    'receipt_mapping_key',v_receipt_mapping_key);
END;
$function$;
