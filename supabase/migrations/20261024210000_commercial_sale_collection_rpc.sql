-- Record an additional cash collection against an existing outstanding cloud sale.
-- Returns are conservatively gated until their refund amounts are reconciled to payment rows.
CREATE OR REPLACE FUNCTION commercial_private.commercial_record_sale_collection(
  p_sale_id uuid, p_amount_minor bigint, p_idempotency_key text
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog', 'public', 'commercial_private'
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_org uuid;
  v_total bigint;
  v_currency text;
  v_status text;
  v_payment_status text;
  v_paid numeric;
  v_payment_id uuid;
  v_existing_sale uuid;
  v_existing_amount bigint;
  v_existing_provider text;
  v_existing_status text;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_sale_id IS NULL OR p_amount_minor IS NULL OR p_amount_minor <= 0
     OR p_idempotency_key IS NULL OR length(btrim(p_idempotency_key)) < 8
     OR length(btrim(p_idempotency_key)) > 200 THEN
    RAISE EXCEPTION 'invalid_collection_request' USING ERRCODE='22023';
  END IF;

  SELECT s.organization_id INTO v_org FROM public.commercial_sales s WHERE s.id=p_sale_id;
  IF NOT FOUND OR v_org IS NULL THEN RAISE EXCEPTION 'sale_not_found' USING ERRCODE='P0002'; END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_organizations o
    WHERE o.id=v_org AND o.state='active' AND o.deleted_at IS NULL
  ) THEN RAISE EXCEPTION 'organization_inactive' USING ERRCODE='42501'; END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id=v_org AND m.user_id=v_uid AND m.state='active'
  ) THEN RAISE EXCEPTION 'organization_access_denied' USING ERRCODE='42501'; END IF;

  PERFORM pg_advisory_xact_lock(hashtext(v_org::text), hashtext(p_idempotency_key));

  SELECT p.sale_id, p.amount_minor, p.id, p.provider, p.status
    INTO v_existing_sale, v_existing_amount, v_payment_id, v_existing_provider, v_existing_status
  FROM public.commercial_payments p
  WHERE p.organization_id=v_org AND p.idempotency_key=p_idempotency_key;
  IF FOUND THEN
    IF v_existing_sale IS DISTINCT FROM p_sale_id OR v_existing_amount IS DISTINCT FROM p_amount_minor
       OR v_existing_provider IS DISTINCT FROM 'ruh_cash_collection' OR v_existing_status IS DISTINCT FROM 'succeeded' THEN
      RAISE EXCEPTION 'idempotency_key_reused_with_different_request' USING ERRCODE='23505';
    END IF;
    RETURN jsonb_build_object('sale_id',p_sale_id,'payment_id',v_payment_id,
      'amount_minor',p_amount_minor,'state','succeeded','idempotent_replay',true);
  END IF;

  SELECT s.total_minor,s.currency_code,s.status,s.payment_status
    INTO v_total,v_currency,v_status,v_payment_status
  FROM public.commercial_sales s
  WHERE s.id=p_sale_id AND s.organization_id=v_org
  FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'sale_not_found' USING ERRCODE='P0002'; END IF;
  IF v_status <> 'completed' THEN RAISE EXCEPTION 'sale_not_collectible' USING ERRCODE='22023'; END IF;
  IF v_payment_status='refunded' OR EXISTS (
    SELECT 1 FROM public.commercial_payments p
    WHERE p.sale_id=p_sale_id AND p.status IN ('refunded','partially_refunded')
  ) THEN RAISE EXCEPTION 'sale_refund_reconciliation_required' USING ERRCODE='22023'; END IF;
  IF EXISTS (
    SELECT 1 FROM public.commercial_sales_returns r
    WHERE r.sale_id=p_sale_id AND r.status NOT IN ('rejected','cancelled')
  ) THEN RAISE EXCEPTION 'sale_return_reconciliation_required' USING ERRCODE='22023'; END IF;

  SELECT coalesce(sum(p.amount_minor),0) INTO v_paid
  FROM public.commercial_payments p
  WHERE p.sale_id=p_sale_id AND p.status='succeeded';
  IF v_total IS NULL OR v_total <= 0 OR v_paid >= v_total THEN
    RAISE EXCEPTION 'sale_has_no_outstanding_balance' USING ERRCODE='22023';
  END IF;
  IF p_amount_minor::numeric > v_total::numeric-v_paid THEN
    RAISE EXCEPTION 'collection_exceeds_outstanding_balance' USING ERRCODE='22023';
  END IF;

  INSERT INTO public.commercial_payments(
    organization_id,sale_id,provider,idempotency_key,amount_minor,currency_code,status,payment_method_type
  ) VALUES (
    v_org,p_sale_id,'ruh_cash_collection',p_idempotency_key,p_amount_minor,v_currency,'succeeded','cash'
  ) RETURNING id INTO v_payment_id;

  UPDATE public.commercial_sales
  SET payment_status=CASE WHEN v_paid+p_amount_minor=v_total THEN 'paid' ELSE 'partially_paid' END
  WHERE id=p_sale_id AND organization_id=v_org;

  INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
  VALUES(v_org,v_uid,'sale.payment_collected','sale',p_sale_id::text,
    jsonb_build_object('payment_id',v_payment_id,'amount_minor',p_amount_minor,
      'paid_total_minor',(v_paid+p_amount_minor)::bigint,'total_minor',v_total,'currency_code',v_currency));

  RETURN jsonb_build_object('sale_id',p_sale_id,'payment_id',v_payment_id,'amount_minor',p_amount_minor,
    'paid_total_minor',(v_paid+p_amount_minor)::bigint,'outstanding_minor',(v_total-v_paid-p_amount_minor)::bigint,
    'payment_status',CASE WHEN v_paid+p_amount_minor=v_total THEN 'paid' ELSE 'partially_paid' END,
    'state','succeeded','idempotent_replay',false);
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_record_sale_collection(
  p_sale_id uuid, p_amount_minor bigint, p_idempotency_key text
) RETURNS jsonb
LANGUAGE plpgsql SECURITY INVOKER
SET search_path TO 'pg_catalog', 'public', 'commercial_private'
AS $function$
BEGIN
  IF auth.uid() IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  RETURN commercial_private.commercial_record_sale_collection(p_sale_id,p_amount_minor,p_idempotency_key);
END;
$function$;

REVOKE ALL ON FUNCTION commercial_private.commercial_record_sale_collection(uuid,bigint,text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION commercial_private.commercial_record_sale_collection(uuid,bigint,text) TO authenticated;
REVOKE ALL ON FUNCTION public.commercial_record_sale_collection(uuid,bigint,text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_record_sale_collection(uuid,bigint,text) TO authenticated;
