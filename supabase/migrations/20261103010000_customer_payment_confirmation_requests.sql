-- Customer-submitted payment claims. A claim is not a payment and has no
-- accounting effect until an authorized merchant approves it.
CREATE TABLE IF NOT EXISTS public.commercial_customer_payment_requests (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
  customer_id uuid NOT NULL REFERENCES public.commercial_customers(id) ON DELETE RESTRICT,
  sale_id uuid NOT NULL REFERENCES public.commercial_sales(id) ON DELETE RESTRICT,
  submitted_by uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
  amount_minor bigint NOT NULL CHECK (amount_minor > 0),
  currency_code text NOT NULL CHECK (currency_code ~ '^[A-Z]{3}$'),
  customer_note text NOT NULL DEFAULT '' CHECK (length(customer_note) <= 1000),
  status text NOT NULL DEFAULT 'pending'
    CHECK (status IN ('pending','change_requested','rejected','approved')),
  review_note text NOT NULL DEFAULT '' CHECK (length(review_note) <= 1000),
  reviewed_by uuid REFERENCES auth.users(id) ON DELETE RESTRICT,
  reviewed_at timestamptz,
  payment_id uuid REFERENCES public.commercial_payments(id) ON DELETE RESTRICT,
  idempotency_key text NOT NULL CHECK (length(idempotency_key) BETWEEN 8 AND 200),
  last_action_key text NOT NULL CHECK (length(last_action_key) BETWEEN 8 AND 200),
  version integer NOT NULL DEFAULT 1 CHECK (version > 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT commercial_customer_payment_request_approved_payment_check
    CHECK ((status = 'approved') = (payment_id IS NOT NULL))
);

CREATE UNIQUE INDEX IF NOT EXISTS commercial_customer_payment_requests_idempotency_uidx
  ON public.commercial_customer_payment_requests(submitted_by, idempotency_key);
CREATE INDEX IF NOT EXISTS commercial_customer_payment_requests_org_status_created_idx
  ON public.commercial_customer_payment_requests(organization_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS commercial_customer_payment_requests_customer_created_idx
  ON public.commercial_customer_payment_requests(customer_id, created_at DESC);
CREATE INDEX IF NOT EXISTS commercial_customer_payment_requests_sale_status_idx
  ON public.commercial_customer_payment_requests(sale_id, status);

CREATE TABLE IF NOT EXISTS public.commercial_customer_payment_request_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  request_id uuid NOT NULL REFERENCES public.commercial_customer_payment_requests(id) ON DELETE RESTRICT,
  organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
  actor_user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
  event_type text NOT NULL CHECK (event_type IN ('submitted','resubmitted','change_requested','rejected','approved')),
  previous_amount_minor bigint,
  new_amount_minor bigint,
  note text NOT NULL DEFAULT '' CHECK (length(note) <= 1000),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS commercial_customer_payment_request_events_request_created_idx
  ON public.commercial_customer_payment_request_events(request_id, created_at, id);

ALTER TABLE public.commercial_customer_payment_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.commercial_customer_payment_request_events ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.commercial_customer_payment_requests FROM PUBLIC, anon, authenticated;
REVOKE ALL ON TABLE public.commercial_customer_payment_request_events FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.commercial_customer_portal_list_outstanding_sales(p_organization_id uuid)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_customer_id uuid;
  v_result jsonb;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  PERFORM public.commercial_customer_portal_link_verified_email();
  SELECT c.id INTO v_customer_id
  FROM public.commercial_customers c
  JOIN public.commercial_customer_party_accounts cpa
    ON cpa.customer_id=c.id AND cpa.organization_id=c.organization_id
  JOIN public.commercial_party_accounts a
    ON a.id=cpa.party_account_id AND a.organization_id=cpa.organization_id
   AND a.account_type IN ('business','organization') AND a.status='active'
  JOIN public.commercial_organizations o
    ON o.id=c.organization_id AND o.state='active' AND o.deleted_at IS NULL
  WHERE c.organization_id=p_organization_id AND c.auth_user_id=v_uid AND c.state='active'
  LIMIT 1;
  IF v_customer_id IS NULL THEN
    RAISE EXCEPTION 'customer_not_linked_to_organization' USING ERRCODE='42501';
  END IF;

  SELECT coalesce(jsonb_agg(jsonb_build_object(
    'sale_id',q.id,'invoice_number',q.receipt_number,'created_at',q.created_at,
    'total_minor',q.total_minor,'paid_minor',q.paid_minor,'refunded_minor',q.refunded_minor,
    'credited_minor',q.credited_minor,'due_minor',q.due_minor,'currency_code',q.currency_code
  ) ORDER BY q.created_at DESC,q.id), '[]'::jsonb)
  INTO v_result
  FROM (
    SELECT s.id,s.receipt_number,s.created_at,s.total_minor,s.currency_code,
      coalesce((SELECT sum(p.amount_minor)::bigint FROM public.commercial_payments p
        WHERE p.sale_id=s.id AND p.organization_id=s.organization_id
          AND p.status IN ('succeeded','partially_refunded','refunded')),0)::bigint AS paid_minor,
      coalesce((SELECT sum(r.approved_refund_minor)::bigint FROM public.commercial_sales_returns r
        WHERE r.sale_id=s.id AND r.organization_id=s.organization_id AND r.refund_status='refunded'),0)::bigint AS refunded_minor,
      coalesce((SELECT sum(r.approved_refund_minor)::bigint FROM public.commercial_sales_returns r
        WHERE r.sale_id=s.id AND r.organization_id=s.organization_id AND r.status='completed'),0)::bigint AS credited_minor,
      greatest(s.total_minor::numeric
        - greatest(coalesce((SELECT sum(p.amount_minor)::numeric FROM public.commercial_payments p
            WHERE p.sale_id=s.id AND p.organization_id=s.organization_id
              AND p.status IN ('succeeded','partially_refunded','refunded')),0)
          - coalesce((SELECT sum(r.approved_refund_minor)::numeric FROM public.commercial_sales_returns r
            WHERE r.sale_id=s.id AND r.organization_id=s.organization_id AND r.refund_status='refunded'),0),0)
        - coalesce((SELECT sum(r.approved_refund_minor)::numeric FROM public.commercial_sales_returns r
            WHERE r.sale_id=s.id AND r.organization_id=s.organization_id AND r.status='completed'),0),0)::bigint AS due_minor
    FROM public.commercial_sales s
    WHERE s.organization_id=p_organization_id AND s.customer_id=v_customer_id AND s.status='completed'
  ) q
  WHERE q.due_minor > 0;
  RETURN v_result;
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_customer_portal_list_my_payment_requests(p_organization_id uuid DEFAULT NULL)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE v_uid uuid := auth.uid(); v_result jsonb;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  PERFORM public.commercial_customer_portal_link_verified_email();
  SELECT coalesce(jsonb_agg(jsonb_build_object(
    'id',r.id,'organization_id',r.organization_id,'organization_name',o.name,
    'sale_id',r.sale_id,'invoice_number',s.receipt_number,'amount_minor',r.amount_minor,
    'currency_code',r.currency_code,'customer_note',r.customer_note,'status',r.status,
    'review_note',r.review_note,'payment_id',r.payment_id,'version',r.version,
    'created_at',r.created_at,'updated_at',r.updated_at,'reviewed_at',r.reviewed_at
  ) ORDER BY r.created_at DESC,r.id), '[]'::jsonb)
  INTO v_result
  FROM public.commercial_customer_payment_requests r
  JOIN public.commercial_customers c
    ON c.id=r.customer_id AND c.organization_id=r.organization_id
   AND c.auth_user_id=v_uid AND c.state='active'
  JOIN public.commercial_organizations o ON o.id=r.organization_id
  JOIN public.commercial_sales s ON s.id=r.sale_id AND s.organization_id=r.organization_id
  WHERE r.submitted_by=v_uid
    AND (p_organization_id IS NULL OR r.organization_id=p_organization_id);
  RETURN v_result;
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_customer_portal_submit_payment_request(
  p_sale_id uuid,p_amount_minor bigint,p_customer_note text,p_idempotency_key text
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_sale public.commercial_sales%ROWTYPE;
  v_existing public.commercial_customer_payment_requests%ROWTYPE;
  v_due bigint;
  v_reserved numeric;
  v_request public.commercial_customer_payment_requests%ROWTYPE;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  PERFORM public.commercial_customer_portal_link_verified_email();
  IF p_sale_id IS NULL OR p_amount_minor IS NULL OR p_amount_minor<=0
     OR p_customer_note IS NULL OR length(p_customer_note)>1000
     OR p_idempotency_key IS NULL OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200 THEN
    RAISE EXCEPTION 'invalid_customer_payment_request' USING ERRCODE='22023';
  END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended(v_uid::text||':'||btrim(p_idempotency_key),0));
  SELECT * INTO v_existing FROM public.commercial_customer_payment_requests
  WHERE submitted_by=v_uid AND idempotency_key=btrim(p_idempotency_key) FOR UPDATE;
  IF FOUND THEN
    IF v_existing.sale_id<>p_sale_id OR v_existing.amount_minor<>p_amount_minor
       OR v_existing.customer_note<>btrim(p_customer_note) THEN
      RAISE EXCEPTION 'idempotency_key_reused_with_different_request' USING ERRCODE='23505';
    END IF;
    RETURN jsonb_build_object('request_id',v_existing.id,'status',v_existing.status,'replayed',true);
  END IF;

  SELECT s.* INTO v_sale
  FROM public.commercial_sales s
  JOIN public.commercial_customers c
    ON c.id=s.customer_id AND c.organization_id=s.organization_id
   AND c.auth_user_id=v_uid AND c.state='active'
  JOIN public.commercial_customer_party_accounts cpa
    ON cpa.customer_id=c.id AND cpa.organization_id=c.organization_id
  JOIN public.commercial_party_accounts a
    ON a.id=cpa.party_account_id AND a.organization_id=cpa.organization_id
   AND a.account_type IN ('business','organization') AND a.status='active'
  JOIN public.commercial_organizations o
    ON o.id=s.organization_id AND o.state='active' AND o.deleted_at IS NULL
  WHERE s.id=p_sale_id AND s.status='completed'
  FOR UPDATE OF s;
  IF NOT FOUND THEN RAISE EXCEPTION 'customer_sale_not_found' USING ERRCODE='42501'; END IF;

  SELECT greatest(v_sale.total_minor::numeric
      - greatest(coalesce((SELECT sum(p.amount_minor)::numeric FROM public.commercial_payments p
          WHERE p.sale_id=v_sale.id AND p.organization_id=v_sale.organization_id
            AND p.status IN ('succeeded','partially_refunded','refunded')),0)
        - coalesce((SELECT sum(r.approved_refund_minor)::numeric FROM public.commercial_sales_returns r
          WHERE r.sale_id=v_sale.id AND r.organization_id=v_sale.organization_id AND r.refund_status='refunded'),0),0)
      - coalesce((SELECT sum(r.approved_refund_minor)::numeric FROM public.commercial_sales_returns r
          WHERE r.sale_id=v_sale.id AND r.organization_id=v_sale.organization_id AND r.status='completed'),0),0)::bigint
    INTO v_due;
  SELECT coalesce(sum(amount_minor),0) INTO v_reserved
  FROM public.commercial_customer_payment_requests
  WHERE sale_id=v_sale.id AND status='pending';
  IF p_amount_minor::numeric > v_due::numeric-v_reserved THEN
    RAISE EXCEPTION 'payment_request_exceeds_unreserved_due' USING ERRCODE='22023';
  END IF;

  INSERT INTO public.commercial_customer_payment_requests(
    organization_id,customer_id,sale_id,submitted_by,amount_minor,currency_code,
    customer_note,idempotency_key,last_action_key
  ) VALUES (
    v_sale.organization_id,v_sale.customer_id,v_sale.id,v_uid,p_amount_minor,
    v_sale.currency_code,btrim(p_customer_note),btrim(p_idempotency_key),btrim(p_idempotency_key)
  ) RETURNING * INTO v_request;

  INSERT INTO public.commercial_customer_payment_request_events(
    request_id,organization_id,actor_user_id,event_type,new_amount_minor,note
  ) VALUES(v_request.id,v_request.organization_id,v_uid,'submitted',v_request.amount_minor,v_request.customer_note);

  INSERT INTO public.commercial_user_notifications(
    user_id,organization_id,notification_type,title,body,entity_type,entity_id,priority,metadata
  )
  SELECT m.user_id,v_request.organization_id,'customer_payment_request',
    'طلب سند قبض جديد','طلب العميل دفعة بقيمة '||v_request.amount_minor||' '||v_request.currency_code,
    'customer_payment_request',v_request.id,'high',
    jsonb_build_object('request_id',v_request.id,'sale_id',v_sale.id,'customer_id',v_sale.customer_id)
  FROM public.commercial_organization_members m
  WHERE m.organization_id=v_request.organization_id AND m.state='active'
    AND m.role IN ('owner','admin','manager');
  RETURN jsonb_build_object('request_id',v_request.id,'status',v_request.status,'replayed',false);
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_customer_portal_update_payment_request(
  p_request_id uuid,p_amount_minor bigint,p_customer_note text,p_idempotency_key text
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_request public.commercial_customer_payment_requests%ROWTYPE;
  v_sale public.commercial_sales%ROWTYPE;
  v_due bigint;
  v_reserved numeric;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_request_id IS NULL OR p_amount_minor IS NULL OR p_amount_minor<=0
     OR p_customer_note IS NULL OR length(p_customer_note)>1000
     OR p_idempotency_key IS NULL OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200 THEN
    RAISE EXCEPTION 'invalid_customer_payment_request_update' USING ERRCODE='22023';
  END IF;
  SELECT * INTO v_request FROM public.commercial_customer_payment_requests
  WHERE id=p_request_id AND submitted_by=v_uid FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'customer_payment_request_not_found' USING ERRCODE='P0002'; END IF;
  IF v_request.last_action_key=btrim(p_idempotency_key) THEN
    IF v_request.amount_minor<>p_amount_minor OR v_request.customer_note<>btrim(p_customer_note) THEN
      RAISE EXCEPTION 'idempotency_key_reused_with_different_request' USING ERRCODE='23505';
    END IF;
    RETURN jsonb_build_object('request_id',v_request.id,'status',v_request.status,'replayed',true);
  END IF;
  IF v_request.status<>'change_requested' THEN
    RAISE EXCEPTION 'payment_request_not_open_for_changes' USING ERRCODE='55000';
  END IF;
  SELECT s.* INTO v_sale FROM public.commercial_sales s
  JOIN public.commercial_customers c
    ON c.id=s.customer_id AND c.organization_id=s.organization_id
   AND c.auth_user_id=v_uid AND c.state='active'
  WHERE s.id=v_request.sale_id AND s.organization_id=v_request.organization_id
  FOR UPDATE OF s;
  IF NOT FOUND THEN RAISE EXCEPTION 'customer_sale_not_found' USING ERRCODE='42501'; END IF;
  SELECT greatest(v_sale.total_minor::numeric
      - greatest(coalesce((SELECT sum(p.amount_minor)::numeric FROM public.commercial_payments p
          WHERE p.sale_id=v_sale.id AND p.organization_id=v_sale.organization_id
            AND p.status IN ('succeeded','partially_refunded','refunded')),0)
        - coalesce((SELECT sum(r.approved_refund_minor)::numeric FROM public.commercial_sales_returns r
          WHERE r.sale_id=v_sale.id AND r.organization_id=v_sale.organization_id AND r.refund_status='refunded'),0),0)
      - coalesce((SELECT sum(r.approved_refund_minor)::numeric FROM public.commercial_sales_returns r
          WHERE r.sale_id=v_sale.id AND r.organization_id=v_sale.organization_id AND r.status='completed'),0),0)::bigint
    INTO v_due;
  SELECT coalesce(sum(amount_minor),0) INTO v_reserved
  FROM public.commercial_customer_payment_requests
  WHERE sale_id=v_sale.id AND status='pending' AND id<>v_request.id;
  IF p_amount_minor::numeric > v_due::numeric-v_reserved THEN
    RAISE EXCEPTION 'payment_request_exceeds_unreserved_due' USING ERRCODE='22023';
  END IF;

  UPDATE public.commercial_customer_payment_requests
  SET amount_minor=p_amount_minor,customer_note=btrim(p_customer_note),status='pending',
      review_note='',reviewed_by=NULL,reviewed_at=NULL,last_action_key=btrim(p_idempotency_key),
      version=version+1,updated_at=now()
  WHERE id=v_request.id RETURNING * INTO v_request;
  INSERT INTO public.commercial_customer_payment_request_events(
    request_id,organization_id,actor_user_id,event_type,previous_amount_minor,new_amount_minor,note
  ) VALUES(v_request.id,v_request.organization_id,v_uid,'resubmitted',v_request.amount_minor,p_amount_minor,v_request.customer_note);

  INSERT INTO public.commercial_user_notifications(
    user_id,organization_id,notification_type,title,body,entity_type,entity_id,priority,metadata
  )
  SELECT m.user_id,v_request.organization_id,'customer_payment_request',
    'تم تعديل طلب سند قبض','أعاد العميل إرسال طلب دفعة بقيمة '||v_request.amount_minor||' '||v_request.currency_code,
    'customer_payment_request',v_request.id,'high',jsonb_build_object('request_id',v_request.id,'sale_id',v_sale.id)
  FROM public.commercial_organization_members m
  WHERE m.organization_id=v_request.organization_id AND m.state='active'
    AND m.role IN ('owner','admin','manager');
  RETURN jsonb_build_object('request_id',v_request.id,'status',v_request.status,'replayed',false);
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_merchant_list_customer_payment_requests(
  p_organization_id uuid,p_status text DEFAULT 'pending',p_limit integer DEFAULT 50
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE v_uid uuid:=auth.uid(); v_result jsonb;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_organization_id IS NULL OR p_status NOT IN ('pending','change_requested','rejected','approved','all')
     OR p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 100 THEN
    RAISE EXCEPTION 'invalid_payment_request_filter' USING ERRCODE='22023';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id=p_organization_id AND m.user_id=v_uid AND m.state='active'
      AND m.role IN ('owner','admin','manager')) THEN
    RAISE EXCEPTION 'accounting_permission_required' USING ERRCODE='42501';
  END IF;
  SELECT coalesce(jsonb_agg(jsonb_build_object(
    'id',q.id,'customer_id',q.customer_id,'customer_name',q.customer_name,
    'sale_id',q.sale_id,'invoice_number',q.receipt_number,'amount_minor',q.amount_minor,
    'currency_code',q.currency_code,'customer_note',q.customer_note,'status',q.status,
    'review_note',q.review_note,'version',q.version,'created_at',q.created_at
  ) ORDER BY q.created_at,q.id),'[]'::jsonb)
  INTO v_result
  FROM (
    SELECT r.*,c.display_name customer_name,s.receipt_number
    FROM public.commercial_customer_payment_requests r
    JOIN public.commercial_customers c ON c.id=r.customer_id AND c.organization_id=r.organization_id
    JOIN public.commercial_sales s ON s.id=r.sale_id AND s.organization_id=r.organization_id
    WHERE r.organization_id=p_organization_id
      AND (p_status='all' OR r.status=p_status)
    ORDER BY r.created_at,r.id LIMIT p_limit
  ) q;
  RETURN v_result;
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_merchant_review_customer_payment_request(
  p_request_id uuid,p_decision text,p_confirmed_amount_minor bigint,
  p_review_note text,p_payment_method_type text,p_idempotency_key text
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE
  v_uid uuid:=auth.uid();
  v_request public.commercial_customer_payment_requests%ROWTYPE;
  v_payment jsonb;
  v_customer_uid uuid;
  v_event text;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_request_id IS NULL OR p_decision NOT IN ('approve','reject','request_changes')
     OR p_review_note IS NULL OR length(p_review_note)>1000
     OR p_idempotency_key IS NULL OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200
     OR (p_decision='approve' AND (p_confirmed_amount_minor IS NULL OR p_confirmed_amount_minor<=0))
     OR (p_decision<>'approve' AND length(btrim(p_review_note))=0)
     OR (p_payment_method_type IS NULL OR p_payment_method_type NOT IN ('cash','bank_transfer','card','other')) THEN
    RAISE EXCEPTION 'invalid_payment_request_review' USING ERRCODE='22023';
  END IF;
  SELECT * INTO v_request FROM public.commercial_customer_payment_requests
  WHERE id=p_request_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'customer_payment_request_not_found' USING ERRCODE='P0002'; END IF;
  IF NOT EXISTS(SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id=v_request.organization_id AND m.user_id=v_uid AND m.state='active'
      AND m.role IN ('owner','admin','manager')) THEN
    RAISE EXCEPTION 'accounting_permission_required' USING ERRCODE='42501';
  END IF;
  IF v_request.last_action_key=btrim(p_idempotency_key) THEN
    RETURN jsonb_build_object('request_id',v_request.id,'status',v_request.status,
      'payment_id',v_request.payment_id,'replayed',true);
  END IF;
  IF v_request.status<>'pending' THEN
    RAISE EXCEPTION 'payment_request_not_pending' USING ERRCODE='55000';
  END IF;

  IF p_decision='approve' THEN
    IF p_confirmed_amount_minor<>v_request.amount_minor THEN
      RAISE EXCEPTION 'confirmed_amount_does_not_match_customer_claim' USING ERRCODE='22023';
    END IF;
    -- This is the only path that creates a financial payment and journal posting.
    v_payment:=public.commercial_record_customer_payment(
      v_request.sale_id,v_request.amount_minor,p_payment_method_type,
      'customer-request:'||v_request.id::text||':'||v_request.version::text
    );
    UPDATE public.commercial_customer_payment_requests
    SET status='approved',payment_id=(v_payment->>'payment_id')::uuid,
        review_note=btrim(p_review_note),reviewed_by=v_uid,reviewed_at=now(),
        last_action_key=btrim(p_idempotency_key),updated_at=now()
    WHERE id=v_request.id RETURNING * INTO v_request;
    v_event:='approved';
  ELSIF p_decision='reject' THEN
    UPDATE public.commercial_customer_payment_requests
    SET status='rejected',review_note=btrim(p_review_note),reviewed_by=v_uid,
        reviewed_at=now(),last_action_key=btrim(p_idempotency_key),updated_at=now()
    WHERE id=v_request.id RETURNING * INTO v_request;
    v_event:='rejected';
  ELSE
    UPDATE public.commercial_customer_payment_requests
    SET status='change_requested',review_note=btrim(p_review_note),reviewed_by=v_uid,
        reviewed_at=now(),last_action_key=btrim(p_idempotency_key),updated_at=now()
    WHERE id=v_request.id RETURNING * INTO v_request;
    v_event:='change_requested';
  END IF;

  INSERT INTO public.commercial_customer_payment_request_events(
    request_id,organization_id,actor_user_id,event_type,previous_amount_minor,new_amount_minor,note
  ) VALUES(v_request.id,v_request.organization_id,v_uid,v_event,v_request.amount_minor,
      CASE WHEN p_decision='approve' THEN p_confirmed_amount_minor ELSE NULL END,btrim(p_review_note));

  SELECT c.auth_user_id INTO v_customer_uid
  FROM public.commercial_customers c
  WHERE c.id=v_request.customer_id AND c.organization_id=v_request.organization_id AND c.state='active';
  IF v_customer_uid IS NOT NULL THEN
    INSERT INTO public.commercial_user_notifications(
      user_id,organization_id,notification_type,title,body,entity_type,entity_id,priority,metadata
    ) VALUES(
      v_customer_uid,v_request.organization_id,'customer_payment_request_'||v_event,
      CASE v_event WHEN 'approved' THEN 'تم اعتماد سند القبض'
        WHEN 'rejected' THEN 'تم رفض طلب سند القبض' ELSE 'طلب التاجر تعديل سند القبض' END,
      CASE v_event WHEN 'approved' THEN 'اعتمد التاجر الدفعة، وتم إنشاء سند القبض.'
        WHEN 'rejected' THEN 'رفض التاجر الطلب: '||btrim(p_review_note)
        ELSE 'طلب التاجر تعديل الطلب: '||btrim(p_review_note) END,
      'customer_payment_request',v_request.id,
      CASE WHEN v_event='approved' THEN 'normal' ELSE 'high' END,
      jsonb_build_object('request_id',v_request.id,'status',v_request.status,'payment_id',v_request.payment_id)
    );
  END IF;
  RETURN jsonb_build_object('request_id',v_request.id,'status',v_request.status,
    'payment_id',v_request.payment_id,'payment',v_payment,'replayed',false);
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_customer_portal_list_outstanding_sales(uuid) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.commercial_customer_portal_list_my_payment_requests(uuid) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.commercial_customer_portal_submit_payment_request(uuid,bigint,text,text) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.commercial_customer_portal_update_payment_request(uuid,bigint,text,text) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.commercial_merchant_list_customer_payment_requests(uuid,text,integer) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.commercial_merchant_review_customer_payment_request(uuid,text,bigint,text,text,text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_customer_portal_list_outstanding_sales(uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_customer_portal_list_my_payment_requests(uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_customer_portal_submit_payment_request(uuid,bigint,text,text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_customer_portal_update_payment_request(uuid,bigint,text,text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_merchant_list_customer_payment_requests(uuid,text,integer) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_merchant_review_customer_payment_request(uuid,text,bigint,text,text,text) TO authenticated;
