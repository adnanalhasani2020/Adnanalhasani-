-- Multi-service booking invoice: one booking, one order, many immutable priced lines.
-- Server-side price, tenant, permission, capacity and idempotency checks are authoritative.
CREATE OR REPLACE FUNCTION public.commercial_customer_portal_create_multi_service_booking(
  p_organization_id uuid,
  p_lines jsonb,
  p_resource_id uuid,
  p_starts_at timestamptz,
  p_ends_at timestamptz,
  p_payment_mode text,
  p_customer_note text DEFAULT NULL,
  p_idempotency_key text DEFAULT NULL
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_account_id uuid;
  v_account_currency text;
  v_resource public.commercial_booking_resources%ROWTYPE;
  v_existing public.commercial_bookings%ROWTYPE;
  v_existing_order public.commercial_customer_orders%ROWTYPE;
  v_order jsonb;
  v_order_id uuid;
  v_order_total bigint;
  v_key text := nullif(btrim(p_idempotency_key), '');
  v_line jsonb;
  v_offering record;
  v_qty numeric;
  v_count integer := 0;
  v_match_count integer := 0;
  v_requested_capacity numeric := 0;
  v_used_capacity numeric := 0;
  v_currency text;
  v_result jsonb;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501';
  END IF;
  PERFORM public.commercial_customer_portal_link_verified_email();

  IF p_organization_id IS NULL OR p_resource_id IS NULL THEN
    RAISE EXCEPTION 'booking_fields_required' USING ERRCODE='22023';
  END IF;
  IF p_starts_at IS NULL OR p_ends_at IS NULL OR p_starts_at <= now() OR p_ends_at <= p_starts_at THEN
    RAISE EXCEPTION 'invalid_reservation_window' USING ERRCODE='22023';
  END IF;
  IF p_payment_mode IS NULL OR p_payment_mode NOT IN ('cash','credit') THEN
    RAISE EXCEPTION 'invalid_payment_mode' USING ERRCODE='22023';
  END IF;
  IF v_key IS NULL OR length(v_key) < 8 OR length(v_key) > 200 THEN
    RAISE EXCEPTION 'invalid_idempotency_key' USING ERRCODE='22023';
  END IF;
  IF p_customer_note IS NOT NULL AND length(p_customer_note) > 1000 THEN
    RAISE EXCEPTION 'customer_note_too_long' USING ERRCODE='22023';
  END IF;
  IF p_lines IS NULL OR jsonb_typeof(p_lines) <> 'array'
     OR jsonb_array_length(p_lines) < 1 OR jsonb_array_length(p_lines) > 50 THEN
    RAISE EXCEPTION 'invalid_booking_lines' USING ERRCODE='22023';
  END IF;

  -- Validate the complete submitted line set before looking up/replaying a booking.
  FOR v_line IN SELECT value FROM jsonb_array_elements(p_lines)
  LOOP
    IF jsonb_typeof(v_line) <> 'object'
       OR nullif(v_line->>'offering_id','') IS NULL
       OR nullif(v_line->>'quantity','') IS NULL
       OR (v_line->>'offering_id') !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
      RAISE EXCEPTION 'invalid_booking_line' USING ERRCODE='22023';
    END IF;
    BEGIN
      v_qty := (v_line->>'quantity')::numeric;
    EXCEPTION WHEN invalid_text_representation THEN
      RAISE EXCEPTION 'invalid_booking_quantity' USING ERRCODE='22023';
    END;
    IF v_qty <= 0 OR v_qty > 100000 OR v_qty <> round(v_qty, 3) THEN
      RAISE EXCEPTION 'invalid_booking_quantity' USING ERRCODE='22023';
    END IF;
    v_count := v_count + 1;
  END LOOP;
  IF (SELECT count(DISTINCT value->>'offering_id') FROM jsonb_array_elements(p_lines)) <> v_count THEN
    RAISE EXCEPTION 'duplicate_booking_offering' USING ERRCODE='22023';
  END IF;

  SELECT a.id, a.currency_code
    INTO v_account_id, v_account_currency
  FROM public.commercial_customer_party_accounts cpa
  JOIN public.commercial_customers c
    ON c.id=cpa.customer_id AND c.organization_id=cpa.organization_id
   AND c.state='active' AND c.auth_user_id=v_uid
  JOIN public.commercial_party_accounts a
    ON a.id=cpa.party_account_id AND a.organization_id=cpa.organization_id
   AND a.account_type IN ('business','organization') AND a.status='active'
  JOIN public.commercial_organizations o
    ON o.id=cpa.organization_id AND o.state='active' AND o.deleted_at IS NULL
  WHERE cpa.organization_id=p_organization_id
  LIMIT 1;
  IF v_account_id IS NULL THEN
    RAISE EXCEPTION 'customer_not_linked_to_organization' USING ERRCODE='42501';
  END IF;
  IF p_payment_mode='credit' AND NOT EXISTS (
    SELECT 1 FROM public.commercial_party_accounts a
    WHERE a.id=v_account_id AND a.organization_id=p_organization_id
      AND a.status='active' AND a.allow_credit
  ) THEN
    RAISE EXCEPTION 'customer_credit_not_authorized' USING ERRCODE='42501';
  END IF;

  -- Serialize retries for this account/key; unique constraints remain the final protection.
  PERFORM pg_advisory_xact_lock(hashtextextended(v_account_id::text || ':' || v_key, 0));

  SELECT * INTO v_existing
  FROM public.commercial_bookings b
  WHERE b.customer_account_id=v_account_id AND b.idempotency_key=v_key
  FOR UPDATE;
  IF FOUND THEN
    IF v_existing.organization_id <> p_organization_id
       OR v_existing.resource_id <> p_resource_id
       OR v_existing.starts_at <> p_starts_at
       OR v_existing.ends_at <> p_ends_at
       OR coalesce(v_existing.notes,'') <> coalesce(nullif(btrim(p_customer_note),''),'') THEN
      RAISE EXCEPTION 'idempotency_key_reused_with_different_request' USING ERRCODE='22023';
    END IF;

    SELECT * INTO v_existing_order
    FROM public.commercial_customer_orders o
    WHERE o.id=v_existing.order_id AND o.organization_id=p_organization_id
      AND o.buyer_account_id=v_account_id
      AND o.payment_status=CASE WHEN p_payment_mode='credit' THEN 'credit' ELSE 'unpaid' END;
    IF NOT FOUND THEN
      RAISE EXCEPTION 'idempotency_key_reused_with_different_request' USING ERRCODE='22023';
    END IF;

    SELECT count(*) INTO v_match_count
    FROM public.commercial_customer_order_items oi
    JOIN jsonb_array_elements(p_lines) j(value)
      ON oi.offering_id=(j.value->>'offering_id')::uuid
     AND oi.quantity=(j.value->>'quantity')::numeric
    WHERE oi.order_id=v_existing_order.id AND oi.item_type='service';
    IF v_match_count <> jsonb_array_length(p_lines)
       OR (SELECT count(*) FROM public.commercial_customer_order_items oi WHERE oi.order_id=v_existing_order.id)
          <> jsonb_array_length(p_lines)
       OR v_existing_order.reserved_from IS DISTINCT FROM p_starts_at
       OR v_existing_order.reserved_until IS DISTINCT FROM p_ends_at THEN
      RAISE EXCEPTION 'idempotency_key_reused_with_different_request' USING ERRCODE='22023';
    END IF;

    RETURN jsonb_build_object(
      'booking_id',v_existing.id,'booking_number',v_existing.booking_number,
      'order_id',v_existing.order_id,'order_number',v_existing_order.order_number,
      'status',v_existing.status,'price_minor',v_existing.price_minor,
      'currency_code',v_existing.currency_code,'replayed',true,
      'items',(SELECT coalesce(jsonb_agg(jsonb_build_object(
        'offering_id',oi.offering_id,'name',oi.item_name_snapshot,'quantity',oi.quantity,
        'unit_price_minor',oi.unit_price_minor,'line_total_minor',oi.line_total_minor
      ) ORDER BY oi.created_at,oi.id),'[]'::jsonb)
      FROM public.commercial_customer_order_items oi WHERE oi.order_id=v_existing_order.id)
    );
  END IF;

  -- Lock the capacity row while validating all requested services and the shared resource.
  SELECT * INTO v_resource
  FROM public.commercial_booking_resources r
  WHERE r.id=p_resource_id AND r.organization_id=p_organization_id AND r.status='active'
  FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'booking_resource_not_available' USING ERRCODE='22023'; END IF;

  FOR v_line IN SELECT value FROM jsonb_array_elements(p_lines)
  LOOP
    SELECT f.id, f.price_minor, f.currency_code, a.activity_type
      INTO v_offering
    FROM public.commercial_offerings f
    JOIN public.commercial_activities a ON a.id=f.activity_id
    JOIN public.commercial_products p ON p.id=f.product_id
    WHERE f.id=(v_line->>'offering_id')::uuid
      AND f.state='active' AND f.valid_from<=now() AND (f.valid_to IS NULL OR f.valid_to>now())
      AND a.organization_id=p_organization_id AND a.activity_type='service'
      AND a.state='active' AND a.deleted_at IS NULL AND p.state='active'
    FOR SHARE OF f,a,p;
    IF NOT FOUND THEN RAISE EXCEPTION 'booking_service_not_available' USING ERRCODE='22023'; END IF;
    IF v_offering.currency_code <> v_account_currency THEN
      RAISE EXCEPTION 'booking_currency_mismatch' USING ERRCODE='22023';
    END IF;
    IF v_currency IS NULL THEN v_currency := v_offering.currency_code;
    ELSIF v_currency <> v_offering.currency_code THEN
      RAISE EXCEPTION 'mixed_order_currencies_not_supported' USING ERRCODE='22023';
    END IF;
  END LOOP;

  -- Capacity is consumed by requested service units, not merely by booking rows.
  -- Legacy/other bookings without service order lines conservatively consume one unit.
  SELECT coalesce(sum((j.value->>'quantity')::numeric), 0)
    INTO v_requested_capacity
  FROM jsonb_array_elements(p_lines) AS j(value);

  SELECT coalesce(sum(active_booking.consumed_units), 0)
    INTO v_used_capacity
  FROM (
    SELECT b.id,
           CASE
             WHEN count(oi.id) FILTER (WHERE oi.item_type='service') > 0
               THEN sum(oi.quantity) FILTER (WHERE oi.item_type='service')
             ELSE 1::numeric
           END AS consumed_units
    FROM public.commercial_bookings b
    LEFT JOIN public.commercial_customer_order_items oi ON oi.order_id=b.order_id
    WHERE b.organization_id=p_organization_id AND b.resource_id=p_resource_id
      AND b.status IN ('requested','confirmed','checked_in')
      AND b.starts_at<p_ends_at AND b.ends_at>p_starts_at
    GROUP BY b.id
  ) AS active_booking;

  IF v_used_capacity + v_requested_capacity > v_resource.capacity THEN
    RAISE EXCEPTION 'booking_resource_capacity_exceeded' USING ERRCODE='22023';
  END IF;

  v_order := public.commercial_customer_portal_create_order(
    p_organization_id,p_lines,p_payment_mode,p_customer_note,p_starts_at,p_ends_at,v_key
  );
  v_order_id := (v_order->>'order_id')::uuid;
  v_order_total := (v_order->>'total_minor')::bigint;

  -- Defense in depth: booking orders may contain service lines only.
  IF EXISTS (
    SELECT 1 FROM public.commercial_customer_order_items oi
    WHERE oi.order_id=v_order_id AND oi.item_type IS DISTINCT FROM 'service'
  ) OR (SELECT count(*) FROM public.commercial_customer_order_items oi WHERE oi.order_id=v_order_id)
       <> jsonb_array_length(p_lines) THEN
    RAISE EXCEPTION 'booking_order_contains_invalid_lines' USING ERRCODE='22023';
  END IF;

  INSERT INTO public.commercial_bookings(
    organization_id,resource_id,customer_account_id,order_id,status,starts_at,ends_at,
    currency_code,price_minor,paid_minor,created_by,notes,idempotency_key
  ) VALUES (
    p_organization_id,p_resource_id,v_account_id,v_order_id,'requested',p_starts_at,p_ends_at,
    v_currency,v_order_total,0,v_uid,nullif(btrim(p_customer_note),''),v_key
  ) RETURNING id,booking_number INTO v_existing.id,v_existing.booking_number;

  SELECT coalesce(jsonb_agg(jsonb_build_object(
    'offering_id',oi.offering_id,'name',oi.item_name_snapshot,'quantity',oi.quantity,
    'unit_price_minor',oi.unit_price_minor,'line_total_minor',oi.line_total_minor
  ) ORDER BY oi.created_at,oi.id),'[]'::jsonb)
  INTO v_result
  FROM public.commercial_customer_order_items oi WHERE oi.order_id=v_order_id;

  RETURN jsonb_build_object(
    'booking_id',v_existing.id,'booking_number',v_existing.booking_number,
    'order_id',v_order_id,'order_number',v_order->>'order_number',
    'status','requested','price_minor',v_order_total,'currency_code',v_currency,
    'replayed',coalesce((v_order->>'replayed')::boolean,false),'items',v_result
  );
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_customer_portal_create_multi_service_booking(uuid,jsonb,uuid,timestamptz,timestamptz,text,text,text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_customer_portal_create_multi_service_booking(uuid,jsonb,uuid,timestamptz,timestamptz,text,text,text) TO authenticated;

COMMENT ON FUNCTION public.commercial_customer_portal_create_multi_service_booking(uuid,jsonb,uuid,timestamptz,timestamptz,text,text,text)
IS 'Creates one capacity-checked booking linked to a single order containing multiple priced service lines; retries are idempotent.';
