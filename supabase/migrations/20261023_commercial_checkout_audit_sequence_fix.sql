-- Atomic, tenant-authorized checkout. No service-role token is used by the app.
CREATE OR REPLACE FUNCTION public.commercial_checkout_sale(
  p_offering_id uuid,
  p_location_id uuid,
  p_quantity integer,
  p_idempotency_key text
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog', 'public'
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_fingerprint text;
  v_sale_id uuid;
  v_receipt_number bigint;
  v_activity_id uuid;
  v_org_id uuid;
  v_currency text;
  v_price bigint;
  v_product_name text;
  v_created_at timestamptz;
  v_on_hand numeric;
  v_reserved numeric;
  v_total numeric;
  v_existing_fingerprint text;
  v_existing_quantity integer;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'authentication_required' USING ERRCODE = '42501';
  END IF;
  IF p_offering_id IS NULL OR p_location_id IS NULL THEN
    RAISE EXCEPTION 'offering_and_location_required' USING ERRCODE = '22023';
  END IF;
  IF p_quantity IS NULL OR p_quantity < 1 OR p_quantity > 100000 THEN
    RAISE EXCEPTION 'invalid_quantity' USING ERRCODE = '22023';
  END IF;
  IF p_idempotency_key IS NULL OR length(btrim(p_idempotency_key)) < 8
     OR length(btrim(p_idempotency_key)) > 200 THEN
    RAISE EXCEPTION 'invalid_idempotency_key' USING ERRCODE = '22023';
  END IF;

  v_fingerprint := md5(concat_ws('|', p_offering_id::text, p_location_id::text, p_quantity::text));
  PERFORM pg_advisory_xact_lock(hashtext(v_uid::text), hashtext(p_idempotency_key));

  SELECT s.id, s.receipt_number, s.activity_id, s.organization_id, s.currency_code,
         s.total_minor, s.created_at, s.request_fingerprint,
         si.quantity, si.unit_price_minor, si.product_name_snapshot
    INTO v_sale_id, v_receipt_number, v_activity_id, v_org_id, v_currency,
         v_total, v_created_at, v_existing_fingerprint,
         v_existing_quantity, v_price, v_product_name
  FROM public.commercial_sales s
  JOIN public.commercial_sale_items si ON si.sale_id = s.id
  WHERE s.user_id = v_uid AND s.idempotency_key = p_idempotency_key
  LIMIT 1;

  IF FOUND THEN
    IF v_existing_fingerprint IS DISTINCT FROM v_fingerprint THEN
      RAISE EXCEPTION 'idempotency_key_reused_with_different_request' USING ERRCODE = '23505';
    END IF;
    RETURN jsonb_build_object(
      'sale_id', v_sale_id, 'receipt_number', v_receipt_number,
      'activity_id', v_activity_id, 'organization_id', v_org_id,
      'offering_id', p_offering_id, 'location_id', p_location_id,
      'product_name', v_product_name, 'quantity', v_existing_quantity,
      'unit_price_minor', v_price, 'total_minor', v_total,
      'currency_code', v_currency, 'occurred_at', v_created_at,
      'state', 'completed', 'idempotent_replay', true
    );
  END IF;

  SELECT o.activity_id, a.organization_id, o.currency_code, o.price_minor, p.name
    INTO v_activity_id, v_org_id, v_currency, v_price, v_product_name
  FROM public.commercial_offerings o
  JOIN public.commercial_products p ON p.id = o.product_id
  JOIN public.commercial_activities a ON a.id = o.activity_id
  WHERE o.id = p_offering_id
    AND o.state = 'active'
    AND o.valid_from <= now()
    AND (o.valid_to IS NULL OR o.valid_to > now())
    AND p.state = 'active'
    AND a.state = 'active'
    AND a.deleted_at IS NULL
  FOR SHARE OF o, p, a;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'offering_not_available' USING ERRCODE = 'P0002';
  END IF;
  IF v_org_id IS NULL THEN
    RAISE EXCEPTION 'organization_inventory_required' USING ERRCODE = '22023';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id = v_org_id AND m.user_id = v_uid
      AND m.state = 'active'
  ) THEN
    RAISE EXCEPTION 'organization_access_denied' USING ERRCODE = '42501';
  END IF;

  SELECT b.quantity_on_hand, b.quantity_reserved
    INTO v_on_hand, v_reserved
  FROM public.commercial_inventory_balances b
  JOIN public.commercial_organization_locations l
    ON l.id = b.location_id AND l.organization_id = b.organization_id
  WHERE b.organization_id = v_org_id
    AND b.location_id = p_location_id
    AND b.offering_id = p_offering_id
    AND l.state = 'active'
  FOR UPDATE OF b;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'inventory_location_not_found' USING ERRCODE = 'P0002';
  END IF;
  IF v_on_hand - v_reserved < p_quantity THEN
    RAISE EXCEPTION 'insufficient_available_stock' USING ERRCODE = '22023';
  END IF;

  v_total := v_price::numeric * p_quantity::numeric;
  IF v_total > 9223372036854775807 THEN
    RAISE EXCEPTION 'sale_total_overflow' USING ERRCODE = '22003';
  END IF;

  INSERT INTO public.commercial_sales (
    receipt_number, user_id, activity_id, organization_id,
    idempotency_key, request_fingerprint, status, currency_code,
    total_minor, payment_status, channel
  ) VALUES (
    nextval('public.commercial_sales_receipt_number_seq'::regclass),
    v_uid, v_activity_id, v_org_id, p_idempotency_key, v_fingerprint,
    'completed', v_currency, v_total::bigint, 'paid', 'in_person'
  )
  RETURNING id, receipt_number, created_at
    INTO v_sale_id, v_receipt_number, v_created_at;

  INSERT INTO public.commercial_sale_items (
    sale_id, offering_id, product_name_snapshot, quantity,
    unit_price_minor, line_total_minor
  ) VALUES (
    v_sale_id, p_offering_id, v_product_name, p_quantity,
    v_price, v_total::bigint
  );

  UPDATE public.commercial_inventory_balances
     SET quantity_on_hand = quantity_on_hand - p_quantity,
         updated_at = now()
   WHERE organization_id = v_org_id
     AND location_id = p_location_id
     AND offering_id = p_offering_id;

  INSERT INTO public.commercial_stock_movements (
    offering_id, sale_id, movement_type, quantity_delta, source_ref, created_by
  ) VALUES (
    p_offering_id, v_sale_id, 'sale', -p_quantity,
    'sale:' || v_sale_id::text || ':offering:' || p_offering_id::text || ':location:' || p_location_id::text,
    v_uid
  );

  INSERT INTO public.commercial_audit_events (
    id, organization_id, actor_user_id, action, entity_type, entity_id, metadata
  ) VALUES (
    nextval('public.commercial_audit_events_id_seq'::regclass),
    v_org_id, v_uid, 'sale.completed', 'sale', v_sale_id::text,
    jsonb_build_object('receipt_number', v_receipt_number, 'offering_id', p_offering_id,
                       'location_id', p_location_id, 'quantity', p_quantity,
                       'total_minor', v_total::bigint, 'currency_code', v_currency)
  );

  RETURN jsonb_build_object(
    'sale_id', v_sale_id, 'receipt_number', v_receipt_number,
    'activity_id', v_activity_id, 'organization_id', v_org_id,
    'offering_id', p_offering_id, 'location_id', p_location_id,
    'product_name', v_product_name, 'quantity', p_quantity,
    'unit_price_minor', v_price, 'total_minor', v_total::bigint,
    'currency_code', v_currency, 'occurred_at', v_created_at,
    'state', 'completed', 'idempotent_replay', false
  );
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_checkout_sale(uuid, uuid, integer, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.commercial_checkout_sale(uuid, uuid, integer, text) FROM anon;
GRANT EXECUTE ON FUNCTION public.commercial_checkout_sale(uuid, uuid, integer, text) TO authenticated;
