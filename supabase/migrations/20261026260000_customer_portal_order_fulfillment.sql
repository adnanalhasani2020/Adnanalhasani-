-- Explicit merchant-side fulfillment for customer portal product orders.
-- A portal order remains a request until an authorized organization member runs this RPC.
-- Checkout, inventory decrement, payment/receivable posting, sale creation and order transition
-- commit or roll back together in one database transaction.
CREATE TABLE IF NOT EXISTS public.commercial_customer_order_fulfillments (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
  order_id uuid NOT NULL REFERENCES public.commercial_customer_orders(id) ON DELETE RESTRICT,
  sale_id uuid NOT NULL REFERENCES public.commercial_sales(id) ON DELETE RESTRICT,
  location_id uuid NOT NULL REFERENCES public.commercial_organization_locations(id) ON DELETE RESTRICT,
  idempotency_key text NOT NULL CHECK (length(btrim(idempotency_key)) BETWEEN 8 AND 200),
  request_fingerprint text NOT NULL,
  result jsonb NOT NULL,
  fulfilled_by uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
  fulfilled_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT commercial_customer_order_fulfillments_order_uq UNIQUE (order_id),
  CONSTRAINT commercial_customer_order_fulfillments_sale_uq UNIQUE (sale_id),
  CONSTRAINT commercial_customer_order_fulfillments_org_key_uq UNIQUE (organization_id, idempotency_key)
);

ALTER TABLE public.commercial_customer_order_fulfillments ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS commercial_customer_order_fulfillments_org_read
  ON public.commercial_customer_order_fulfillments;
CREATE POLICY commercial_customer_order_fulfillments_org_read
  ON public.commercial_customer_order_fulfillments FOR SELECT TO authenticated
  USING (EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id = commercial_customer_order_fulfillments.organization_id
      AND m.user_id = auth.uid() AND m.state = 'active'
  ));
REVOKE ALL ON public.commercial_customer_order_fulfillments FROM PUBLIC, anon, authenticated;
GRANT SELECT ON public.commercial_customer_order_fulfillments TO authenticated;

CREATE OR REPLACE FUNCTION public.commercial_customer_portal_fulfill_order(
  p_organization_id uuid,
  p_order_id uuid,
  p_location_id uuid,
  p_idempotency_key text
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_order public.commercial_customer_orders%ROWTYPE;
  v_existing public.commercial_customer_order_fulfillments%ROWTYPE;
  v_customer_id uuid;
  v_credit_limit_minor bigint;
  v_posted_balance numeric := 0;
  v_other_open_credit numeric := 0;
  v_payment_mode text;
  v_lines jsonb;
  v_activity_count integer;
  v_fingerprint text;
  v_checkout jsonb;
  v_sale_id uuid;
  v_payment_status text;
  v_result jsonb;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501';
  END IF;
  IF p_organization_id IS NULL OR p_order_id IS NULL OR p_location_id IS NULL
     OR p_idempotency_key IS NULL OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200 THEN
    RAISE EXCEPTION 'invalid_customer_order_fulfillment_request' USING ERRCODE='22023';
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id = p_organization_id AND m.user_id = v_uid
      AND m.state = 'active' AND m.role IN ('owner','admin','manager')
  ) THEN
    RAISE EXCEPTION 'customer_order_fulfillment_permission_required' USING ERRCODE='42501';
  END IF;

  SELECT * INTO v_order
  FROM public.commercial_customer_orders o
  WHERE o.id = p_order_id AND o.organization_id = p_organization_id
  FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'customer_order_not_found' USING ERRCODE='P0002';
  END IF;

  v_fingerprint := md5(jsonb_build_object(
    'organization_id', p_organization_id,
    'order_id', p_order_id,
    'location_id', p_location_id
  )::text);

  SELECT * INTO v_existing
  FROM public.commercial_customer_order_fulfillments f
  WHERE f.order_id = p_order_id;
  IF FOUND THEN
    IF v_existing.request_fingerprint IS DISTINCT FROM v_fingerprint
       OR v_existing.idempotency_key IS DISTINCT FROM btrim(p_idempotency_key) THEN
      RAISE EXCEPTION 'customer_order_already_fulfilled_with_different_request' USING ERRCODE='23505';
    END IF;
    RETURN v_existing.result || jsonb_build_object('idempotent_replay', true);
  END IF;

  -- Only normal product purchase requests can become inventory-backed sales through this RPC.
  -- Service requests and bookings require their own operational fulfillment flow.
  IF v_order.order_type IS DISTINCT FROM 'purchase' THEN
    RAISE EXCEPTION 'customer_order_type_requires_specialized_fulfillment' USING ERRCODE='22023';
  END IF;
  IF v_order.status IS DISTINCT FROM 'pending'
     AND v_order.status IS DISTINCT FROM 'confirmed'
     AND v_order.status IS DISTINCT FROM 'in_progress' THEN
    RAISE EXCEPTION 'customer_order_status_not_fulfillable' USING ERRCODE='22023';
  END IF;
  IF v_order.payment_status IS DISTINCT FROM 'unpaid'
     AND v_order.payment_status IS DISTINCT FROM 'credit' THEN
    RAISE EXCEPTION 'customer_order_payment_state_not_fulfillable' USING ERRCODE='22023';
  END IF;

  SELECT c.id INTO v_customer_id
  FROM public.commercial_customer_party_accounts cpa
  JOIN public.commercial_customers c
    ON c.id = cpa.customer_id AND c.organization_id = cpa.organization_id
  WHERE cpa.organization_id = p_organization_id
    AND cpa.party_account_id = v_order.buyer_account_id
    AND c.state = 'active'
  LIMIT 1;
  IF v_customer_id IS NULL THEN
    RAISE EXCEPTION 'customer_order_has_no_active_customer_record' USING ERRCODE='55000';
  END IF;

  SELECT coalesce(jsonb_agg(jsonb_build_object(
    'offering_id', oi.offering_id,
    'quantity', oi.quantity::bigint
  ) ORDER BY oi.offering_id), '[]'::jsonb),
  count(DISTINCT a.id)
  INTO v_lines, v_activity_count
  FROM public.commercial_customer_order_items oi
  JOIN public.commercial_offerings ofr ON ofr.id = oi.offering_id
  JOIN public.commercial_activities a ON a.id = ofr.activity_id
  WHERE oi.order_id = v_order.id
    AND oi.item_type = 'product'
    AND a.organization_id = p_organization_id;

  IF jsonb_array_length(v_lines) = 0
     OR (SELECT count(*) FROM public.commercial_customer_order_items oi WHERE oi.order_id = v_order.id)
        <> jsonb_array_length(v_lines) THEN
    RAISE EXCEPTION 'customer_order_contains_non_product_or_invalid_lines' USING ERRCODE='22023';
  END IF;
  IF EXISTS (
    SELECT 1 FROM public.commercial_customer_order_items oi
    WHERE oi.order_id = v_order.id
      AND (oi.quantity <= 0 OR oi.quantity <> trunc(oi.quantity)
        OR oi.quantity > 100000 OR oi.unit_price_minor < 0)
  ) THEN
    RAISE EXCEPTION 'customer_order_quantity_not_supported_by_inventory_checkout' USING ERRCODE='22023';
  END IF;
  IF v_activity_count <> 1 THEN
    RAISE EXCEPTION 'customer_order_spans_multiple_activities' USING ERRCODE='22023';
  END IF;
  IF EXISTS (
    SELECT 1
    FROM public.commercial_customer_order_items oi
    JOIN public.commercial_offerings ofr ON ofr.id = oi.offering_id
    WHERE oi.order_id = v_order.id
      AND (ofr.state IS DISTINCT FROM 'active'
        OR ofr.valid_from > now()
        OR (ofr.valid_to IS NOT NULL AND ofr.valid_to <= now())
        OR ofr.price_minor IS DISTINCT FROM oi.unit_price_minor
        OR upper(ofr.currency_code) IS DISTINCT FROM upper(v_order.currency_code))
  ) THEN
    RAISE EXCEPTION 'customer_order_price_or_offering_changed_requote_required' USING ERRCODE='22023';
  END IF;

  v_payment_mode := CASE WHEN v_order.payment_status = 'credit' THEN 'credit' ELSE 'cash' END;
  IF v_payment_mode = 'credit' THEN
    -- Recheck the credit ceiling at fulfillment time; exposure may have changed since order creation.
    PERFORM pg_advisory_xact_lock(hashtextextended(v_order.buyer_account_id::text || ':credit-limit', 0));
    SELECT credit_limit_minor INTO v_credit_limit_minor
    FROM public.commercial_party_accounts
    WHERE id = v_order.buyer_account_id AND organization_id = p_organization_id
      AND status = 'active' AND allow_credit IS TRUE
    FOR UPDATE;
    IF NOT FOUND THEN
      RAISE EXCEPTION 'customer_credit_not_authorized_at_fulfillment' USING ERRCODE='42501';
    END IF;
    SELECT coalesce(sum(jl.debit_minor::numeric - jl.credit_minor::numeric), 0)
    INTO v_posted_balance
    FROM public.commercial_journal_lines jl
    JOIN public.commercial_journal_entries je
      ON je.id = jl.journal_entry_id AND je.organization_id = jl.organization_id
    WHERE jl.organization_id = p_organization_id
      AND jl.party_account_id = v_order.buyer_account_id
      AND je.status = 'posted';
    SELECT coalesce(sum(o.total_minor::numeric), 0)
    INTO v_other_open_credit
    FROM public.commercial_customer_orders o
    WHERE o.organization_id = p_organization_id
      AND o.buyer_account_id = v_order.buyer_account_id
      AND o.id <> v_order.id
      AND o.payment_status = 'credit'
      AND o.status IN ('pending','confirmed','in_progress');
    IF v_credit_limit_minor IS NOT NULL
       AND greatest(v_posted_balance, 0) + v_other_open_credit + v_order.total_minor > v_credit_limit_minor THEN
      RAISE EXCEPTION 'customer_credit_limit_exceeded_at_fulfillment' USING ERRCODE='22023';
    END IF;
  END IF;
  -- The existing checkout RPC owns stock locks, sale rows, inventory movements, GL posting
  -- and payment/receivable accounting. Its failure aborts this whole transaction.
  v_checkout := public.commercial_checkout_sale_multi(
    v_lines,
    p_location_id,
    'customer-order:' || v_order.id::text,
    v_customer_id,
    v_payment_mode,
    CASE WHEN v_payment_mode = 'credit' THEN 0::bigint ELSE NULL::bigint END
  );
  v_sale_id := nullif(v_checkout->>'sale_id','')::uuid;
  IF v_sale_id IS NULL OR v_checkout->>'state' IS DISTINCT FROM 'completed' THEN
    RAISE EXCEPTION 'customer_order_checkout_did_not_complete' USING ERRCODE='55000';
  END IF;
  IF (v_checkout->>'total_minor')::bigint IS DISTINCT FROM v_order.total_minor THEN
    RAISE EXCEPTION 'customer_order_total_mismatch_checkout_rolled_back' USING ERRCODE='55000';
  END IF;

  v_payment_status := CASE WHEN v_payment_mode = 'credit' THEN 'credit' ELSE 'paid' END;
  UPDATE public.commercial_customer_orders
  SET status = 'fulfilled',
      payment_status = v_payment_status,
      updated_at = now()
  WHERE id = v_order.id AND organization_id = p_organization_id;

  v_result := jsonb_build_object(
    'order_id', v_order.id,
    'order_number', v_order.order_number,
    'sale_id', v_sale_id,
    'receipt_number', v_checkout->'receipt_number',
    'status', 'fulfilled',
    'payment_status', v_payment_status,
    'total_minor', v_order.total_minor,
    'currency_code', v_order.currency_code,
    'paid_minor', v_checkout->'paid_minor',
    'outstanding_minor', v_checkout->'outstanding_minor',
    'journal_entry_id', v_checkout->'journal_entry_id',
    'location_id', p_location_id,
    'idempotent_replay', false
  );

  INSERT INTO public.commercial_customer_order_fulfillments(
    organization_id, order_id, sale_id, location_id, idempotency_key,
    request_fingerprint, result, fulfilled_by
  ) VALUES (
    p_organization_id, v_order.id, v_sale_id, p_location_id, btrim(p_idempotency_key),
    v_fingerprint, v_result, v_uid
  );

  INSERT INTO public.commercial_audit_events(
    organization_id, actor_user_id, action, entity_type, entity_id, metadata
  ) VALUES (
    p_organization_id, v_uid, 'customer_order.fulfilled', 'customer_order', v_order.id::text,
    jsonb_build_object('sale_id', v_sale_id, 'location_id', p_location_id,
      'payment_status', v_payment_status, 'total_minor', v_order.total_minor,
      'currency_code', v_order.currency_code)
  );

  RETURN v_result;
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_customer_portal_fulfill_order(uuid,uuid,uuid,text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_customer_portal_fulfill_order(uuid,uuid,uuid,text) TO authenticated;
