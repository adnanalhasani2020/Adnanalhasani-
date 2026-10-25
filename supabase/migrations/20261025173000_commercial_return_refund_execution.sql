-- Record an explicitly authorized, manually executed customer refund.
-- This does not call a bank/provider; the actor must provide the actual payout evidence.
CREATE TABLE IF NOT EXISTS public.commercial_return_refund_postings (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
  return_id uuid NOT NULL UNIQUE REFERENCES public.commercial_sales_returns(id) ON DELETE RESTRICT,
  idempotency_key text NOT NULL CHECK (length(btrim(idempotency_key)) BETWEEN 8 AND 200),
  request_fingerprint text NOT NULL CHECK (length(request_fingerprint) = 32),
  amount_minor bigint NOT NULL CHECK (amount_minor > 0),
  currency_code text NOT NULL CHECK (currency_code ~ '^[A-Z]{3}$'),
  method text NOT NULL CHECK (method IN ('cash', 'bank_transfer', 'provider')),
  evidence_reference text NOT NULL CHECK (length(btrim(evidence_reference)) BETWEEN 3 AND 200),
  executed_by uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (organization_id, idempotency_key)
);

ALTER TABLE public.commercial_return_refund_postings ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.commercial_return_refund_postings FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.commercial_execute_sale_return_refund(
  p_return_id uuid,
  p_idempotency_key text,
  p_method text,
  p_evidence_reference text
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog', 'public', 'commercial_private'
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  r public.commercial_sales_returns%ROWTYPE;
  s public.commercial_sales%ROWTYPE;
  v_amount bigint;
  v_paid bigint;
  v_prior_refunds bigint;
  v_fingerprint text;
  v_existing public.commercial_return_refund_postings%ROWTYPE;
  v_journal jsonb;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501';
  END IF;
  IF p_idempotency_key IS NULL OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200 THEN
    RAISE EXCEPTION 'invalid_idempotency_key' USING ERRCODE='22023';
  END IF;
  IF p_method IS NULL OR p_method NOT IN ('cash','bank_transfer','provider') THEN
    RAISE EXCEPTION 'invalid_refund_method' USING ERRCODE='22023';
  END IF;
  IF p_evidence_reference IS NULL OR length(btrim(p_evidence_reference)) NOT BETWEEN 3 AND 200 THEN
    RAISE EXCEPTION 'refund_evidence_required' USING ERRCODE='22023';
  END IF;

  SELECT * INTO r FROM public.commercial_sales_returns WHERE id=p_return_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'return_not_found' USING ERRCODE='P0002'; END IF;
  SELECT * INTO s FROM public.commercial_sales WHERE id=r.sale_id FOR UPDATE;
  IF NOT FOUND OR s.organization_id IS DISTINCT FROM r.organization_id THEN
    RAISE EXCEPTION 'return_sale_scope_mismatch' USING ERRCODE='55000';
  END IF;
  IF r.organization_id IS NULL OR NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id=r.organization_id AND m.user_id=v_uid
      AND m.state='active' AND m.role IN ('owner','admin','manager')
  ) THEN
    RAISE EXCEPTION 'refund_permission_required' USING ERRCODE='42501';
  END IF;

  v_fingerprint := md5(concat_ws('|',p_return_id::text,lower(btrim(p_method)),btrim(p_evidence_reference)));
  SELECT * INTO v_existing FROM public.commercial_return_refund_postings
  WHERE organization_id=r.organization_id AND idempotency_key=p_idempotency_key;
  IF FOUND THEN
    IF v_existing.return_id<>p_return_id OR v_existing.request_fingerprint<>v_fingerprint THEN
      RAISE EXCEPTION 'idempotency_key_reused_with_different_request' USING ERRCODE='22023';
    END IF;
    RETURN jsonb_build_object('return_id',p_return_id,'refund_posting_id',v_existing.id,
      'amount_minor',v_existing.amount_minor,'currency_code',v_existing.currency_code,
      'status','refunded','idempotent_replay',true);
  END IF;

  IF r.status<>'completed' OR r.approved_refund_minor IS NULL THEN
    RAISE EXCEPTION 'completed_approved_return_required' USING ERRCODE='55000';
  END IF;
  IF r.approved_refund_minor<=0 THEN
    RAISE EXCEPTION 'no_refund_amount_approved' USING ERRCODE='22023';
  END IF;
  IF r.refund_status NOT IN ('pending','failed') THEN
    RAISE EXCEPTION 'refund_not_executable_in_current_state' USING ERRCODE='55000';
  END IF;
  IF r.restock_status NOT IN ('restocked','not_required') THEN
    RAISE EXCEPTION 'return_inspection_and_restock_must_finish_first' USING ERRCODE='55000';
  END IF;

  v_amount:=r.approved_refund_minor;
  SELECT COALESCE(sum(p.amount_minor),0) INTO v_paid
  FROM public.commercial_payments p
  WHERE p.sale_id=s.id AND p.organization_id=s.organization_id AND p.status IN ('succeeded','partially_refunded','refunded');
  SELECT COALESCE(sum(rp.amount_minor),0) INTO v_prior_refunds
  FROM public.commercial_return_refund_postings rp
  JOIN public.commercial_sales_returns rr ON rr.id=rp.return_id
  WHERE rr.sale_id=s.id;
  IF v_prior_refunds + v_amount > v_paid THEN
    RAISE EXCEPTION 'refund_exceeds_confirmed_payments' USING ERRCODE='22023';
  END IF;

  INSERT INTO public.commercial_return_refund_postings(
    organization_id,return_id,idempotency_key,request_fingerprint,amount_minor,
    currency_code,method,evidence_reference,executed_by
  ) VALUES (
    r.organization_id,r.id,btrim(p_idempotency_key),v_fingerprint,v_amount,
    r.currency_code,p_method,btrim(p_evidence_reference),v_uid
  ) RETURNING * INTO v_existing;

  UPDATE public.commercial_sales_returns
  SET refund_status='refunded',updated_at=now()
  WHERE id=r.id;

  -- The existing accounting-owned routine validates configured GL mappings and
  -- posts the return entry. Any posting failure rolls back this entire RPC.
  v_journal:=public.commercial_gl_post_sale_return(r.id);

  INSERT INTO public.commercial_audit_events(
    organization_id,actor_user_id,action,entity_type,entity_id,metadata
  ) VALUES (
    r.organization_id,v_uid,'sale_return.refund_executed','sale_return',r.id::text,
    jsonb_build_object('refund_posting_id',v_existing.id,'amount_minor',v_amount,
      'currency_code',r.currency_code,'method',p_method,'evidence_reference',btrim(p_evidence_reference),
      'journal',v_journal)
  );

  RETURN jsonb_build_object('return_id',r.id,'refund_posting_id',v_existing.id,
    'amount_minor',v_amount,'currency_code',r.currency_code,'status','refunded',
    'journal',v_journal,'idempotent_replay',false);
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_execute_sale_return_refund(uuid,text,text,text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_execute_sale_return_refund(uuid,text,text,text) TO authenticated;
