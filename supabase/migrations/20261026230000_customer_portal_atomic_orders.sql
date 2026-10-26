-- Customer portal foundation: atomic, tenant-scoped customer orders.
-- Prices and totals are calculated server-side; callers never submit trusted totals.
CREATE OR REPLACE FUNCTION public.commercial_customer_portal_create_order(
  p_organization_id uuid,
  p_lines jsonb,
  p_payment_mode text,
  p_customer_note text DEFAULT NULL,
  p_reserved_from timestamptz DEFAULT NULL,
  p_reserved_until timestamptz DEFAULT NULL,
  p_idempotency_key text DEFAULT NULL
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_account_id uuid;
  v_currency text;
  v_order_type text := 'purchase';
  v_subtotal bigint := 0;
  v_total bigint := 0;
  v_line jsonb;
  v_offering record;
  v_qty numeric;
  v_line_total bigint;
  v_order_id uuid;
  v_existing public.commercial_customer_orders%ROWTYPE;
  v_fingerprint text;
  v_key text := nullif(btrim(p_idempotency_key), '');
  v_count integer := 0;
  v_allow_credit boolean := false;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_organization_id IS NULL THEN RAISE EXCEPTION 'organization_required' USING ERRCODE='22023'; END IF;
  IF p_payment_mode NOT IN ('cash','credit') THEN RAISE EXCEPTION 'invalid_payment_mode' USING ERRCODE='22023'; END IF;
  IF v_key IS NULL OR length(v_key) < 8 OR length(v_key) > 200 THEN RAISE EXCEPTION 'invalid_idempotency_key' USING ERRCODE='22023'; END IF;
  IF p_lines IS NULL OR jsonb_typeof(p_lines) <> 'array' OR jsonb_array_length(p_lines) < 1 OR jsonb_array_length(p_lines) > 100 THEN
    RAISE EXCEPTION 'invalid_order_lines' USING ERRCODE='22023';
  END IF;
  IF p_customer_note IS NOT NULL AND length(p_customer_note) > 1000 THEN RAISE EXCEPTION 'customer_note_too_long' USING ERRCODE='22023'; END IF;
  IF p_reserved_until IS NOT NULL AND (p_reserved_from IS NULL OR p_reserved_until <= p_reserved_from) THEN
    RAISE EXCEPTION 'invalid_reservation_window' USING ERRCODE='22023';
  END IF;

  SELECT a.id INTO v_account_id
  FROM public.commercial_party_accounts a
  WHERE a.owner_user_id = v_uid AND a.status = 'active'
    AND a.account_type = 'personal'
  ORDER BY a.created_at LIMIT 1;
  IF v_account_id IS NULL THEN RAISE EXCEPTION 'active_customer_account_required' USING ERRCODE='42501'; END IF;

  PERFORM 1 FROM public.commercial_organizations o
  WHERE o.id = p_organization_id AND o.state = 'active' AND o.deleted_at IS NULL;
  IF NOT FOUND THEN RAISE EXCEPTION 'organization_not_available' USING ERRCODE='22023'; END IF;

  IF p_payment_mode = 'credit' THEN
    SELECT EXISTS (
      SELECT 1 FROM public.commercial_customer_party_accounts cpa
      JOIN public.commercial_customers c ON c.id = cpa.customer_id
      JOIN public.commercial_party_accounts a ON a.id = cpa.party_account_id
      WHERE cpa.organization_id = p_organization_id
        AND c.organization_id = p_organization_id
        AND a.id = v_account_id AND a.status = 'active' AND a.allow_credit
        AND c.state = 'active'
    ) INTO v_allow_credit;
    IF NOT v_allow_credit THEN RAISE EXCEPTION 'customer_credit_not_authorized' USING ERRCODE='42501'; END IF;
  END IF;

  -- A stable fingerprint binds retries to the exact request. Existing keys replay only the same payload.
  v_fingerprint := md5(jsonb_build_object(
    'organization_id',p_organization_id,'lines',p_lines,'payment_mode',p_payment_mode,
    'note',coalesce(p_customer_note,''),'reserved_from',p_reserved_from,'reserved_until',p_reserved_until
  )::text);
  PERFORM pg_advisory_xact_lock(hashtextextended(v_uid::text || ':' || v_key, 0));
  SELECT * INTO v_existing FROM public.commercial_customer_orders
  WHERE created_by = v_uid AND idempotency_key = v_key;
  IF FOUND THEN
    IF v_existing.organization_id <> p_organization_id OR
       coalesce(v_existing.customer_note,'') <> coalesce(p_customer_note,'') OR
       v_existing.reserved_from IS DISTINCT FROM p_reserved_from OR
       v_existing.reserved_until IS DISTINCT FROM p_reserved_until OR
       v_existing.buyer_account_id <> v_account_id OR
       v_existing.payment_status <> CASE WHEN p_payment_mode='credit' THEN 'credit' ELSE 'unpaid' END THEN
      RAISE EXCEPTION 'idempotency_key_reused_with_different_request' USING ERRCODE='22023';
    END IF;
    RETURN jsonb_build_object('order_id',v_existing.id,'order_number',v_existing.order_number,
      'status',v_existing.status,'total_minor',v_existing.total_minor,
      'currency_code',v_existing.currency_code,'replayed',true);
  END IF;

  -- Validate each line against an active offer belonging to the selected organization.
  FOR v_line IN SELECT value FROM jsonb_array_elements(p_lines)
  LOOP
    IF jsonb_typeof(v_line) <> 'object' OR nullif(v_line->>'offering_id','') IS NULL
       OR nullif(v_line->>'quantity','') IS NULL THEN
      RAISE EXCEPTION 'invalid_order_line' USING ERRCODE='22023';
    END IF;
    BEGIN
      v_qty := (v_line->>'quantity')::numeric;
    EXCEPTION WHEN OTHERS THEN RAISE EXCEPTION 'invalid_order_quantity' USING ERRCODE='22023'; END;
    IF v_qty <= 0 OR v_qty > 1000000 OR v_qty <> round(v_qty, 3) THEN
      RAISE EXCEPTION 'invalid_order_quantity' USING ERRCODE='22023';
    END IF;
    SELECT ofr.id, ofr.price_minor, ofr.currency_code, p.name, a.id AS activity_id,
           a.activity_type
      INTO v_offering
    FROM public.commercial_offerings ofr
    JOIN public.commercial_activities a ON a.id=ofr.activity_id
    JOIN public.commercial_products p ON p.id=ofr.product_id
    WHERE ofr.id=(v_line->>'offering_id')::uuid
      AND ofr.state='active' AND ofr.valid_from <= now()
      AND (ofr.valid_to IS NULL OR ofr.valid_to > now())
      AND a.organization_id=p_organization_id AND a.state='active' AND a.deleted_at IS NULL
      AND p.state='active';
    IF NOT FOUND THEN RAISE EXCEPTION 'offering_not_available_for_organization' USING ERRCODE='22023'; END IF;
    IF v_currency IS NULL THEN v_currency := v_offering.currency_code;
    ELSIF v_currency <> v_offering.currency_code THEN RAISE EXCEPTION 'mixed_order_currencies_not_supported' USING ERRCODE='22023'; END IF;
    v_line_total := round(v_qty * v_offering.price_minor)::bigint;
    IF v_line_total < 0 OR v_subtotal > 9000000000000000000 - v_line_total THEN
      RAISE EXCEPTION 'order_total_overflow' USING ERRCODE='22003';
    END IF;
    v_subtotal := v_subtotal + v_line_total;
    v_count := v_count + 1;
    IF v_offering.activity_type = 'service' THEN v_order_type := 'service'; END IF;
  END LOOP;

  -- Avoid ambiguous duplicate lines: each offering appears once per order.
  IF (SELECT count(DISTINCT value->>'offering_id') FROM jsonb_array_elements(p_lines)) <> v_count THEN
    RAISE EXCEPTION 'duplicate_order_offering' USING ERRCODE='22023';
  END IF;
  v_total := v_subtotal;

  INSERT INTO public.commercial_customer_orders(
    organization_id,buyer_account_id,created_by,order_type,status,payment_status,currency_code,
    subtotal_minor,discount_minor,tax_minor,total_minor,reserved_from,reserved_until,customer_note,idempotency_key
  ) VALUES (
    p_organization_id,v_account_id,v_uid,v_order_type,'pending',
    CASE WHEN p_payment_mode='credit' THEN 'credit' ELSE 'unpaid' END,
    v_currency,v_subtotal,0,0,v_total,p_reserved_from,p_reserved_until,
    nullif(btrim(p_customer_note),''),v_key
  ) RETURNING id INTO v_order_id;

  FOR v_line IN SELECT value FROM jsonb_array_elements(p_lines)
  LOOP
    SELECT ofr.price_minor, ofr.currency_code, p.name
      INTO v_offering
    FROM public.commercial_offerings ofr
    JOIN public.commercial_activities a ON a.id=ofr.activity_id
    JOIN public.commercial_products p ON p.id=ofr.product_id
    WHERE ofr.id=(v_line->>'offering_id')::uuid
      AND a.organization_id=p_organization_id AND a.state='active'
      AND ofr.state='active' AND ofr.valid_from <= now()
      AND (ofr.valid_to IS NULL OR ofr.valid_to > now()) AND p.state='active'
    FOR SHARE OF ofr;
    v_qty := (v_line->>'quantity')::numeric;
    v_line_total := round(v_qty * v_offering.price_minor)::bigint;
    INSERT INTO public.commercial_customer_order_items(
      order_id,offering_id,item_type,item_name_snapshot,quantity,unit_price_minor,line_total_minor
    ) VALUES (
      v_order_id,(v_line->>'offering_id')::uuid,
      CASE WHEN EXISTS (SELECT 1 FROM public.commercial_activities a JOIN public.commercial_offerings x ON x.activity_id=a.id
        WHERE x.id=(v_line->>'offering_id')::uuid AND a.activity_type='service') THEN 'service' ELSE 'product' END,
      v_offering.name,v_qty,v_offering.price_minor,v_line_total
    );
  END LOOP;

  RETURN (SELECT jsonb_build_object('order_id',o.id,'order_number',o.order_number,'status',o.status,
    'payment_status',o.payment_status,'total_minor',o.total_minor,'currency_code',o.currency_code,'replayed',false)
    FROM public.commercial_customer_orders o WHERE o.id=v_order_id);
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_customer_portal_create_order(uuid,jsonb,text,text,timestamptz,timestamptz,text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_customer_portal_create_order(uuid,jsonb,text,text,timestamptz,timestamptz,text) TO authenticated;

CREATE INDEX IF NOT EXISTS commercial_customer_orders_created_by_idempotency_idx
ON public.commercial_customer_orders(created_by,idempotency_key);

CREATE INDEX IF NOT EXISTS commercial_customer_party_accounts_customer_id_idx
ON public.commercial_customer_party_accounts(customer_id);

CREATE INDEX IF NOT EXISTS commercial_customer_orders_buyer_recent_idx
ON public.commercial_customer_orders(buyer_account_id,created_at DESC);

CREATE INDEX IF NOT EXISTS commercial_customer_orders_org_recent_idx
ON public.commercial_customer_orders(organization_id,created_at DESC);
