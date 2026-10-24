CREATE OR REPLACE FUNCTION commercial_private.commercial_create_inventory_item(
  p_organization_id uuid,
  p_activity_id uuid,
  p_location_id uuid,
  p_name text,
  p_unit_name text,
  p_price_minor bigint,
  p_currency_code text,
  p_initial_quantity integer,
  p_reorder_point numeric DEFAULT 0
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog', 'public'
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_product_id uuid;
  v_offering_id uuid;
  v_existing_id uuid;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE = '42501'; END IF;
  IF p_organization_id IS NULL OR p_activity_id IS NULL OR p_location_id IS NULL THEN
    RAISE EXCEPTION 'organization_activity_and_location_required' USING ERRCODE = '22023';
  END IF;
  IF p_name IS NULL OR length(btrim(p_name)) NOT BETWEEN 1 AND 180
     OR p_unit_name IS NULL OR length(btrim(p_unit_name)) NOT BETWEEN 1 AND 40 THEN
    RAISE EXCEPTION 'invalid_product_name_or_unit' USING ERRCODE = '22023';
  END IF;
  IF p_price_minor IS NULL OR p_price_minor < 0 THEN RAISE EXCEPTION 'invalid_price' USING ERRCODE = '22023'; END IF;
  IF p_currency_code IS NULL OR p_currency_code !~ '^[A-Z]{3}$' THEN RAISE EXCEPTION 'invalid_currency_code' USING ERRCODE = '22023'; END IF;
  IF p_initial_quantity IS NULL OR p_initial_quantity < 0 OR p_initial_quantity > 100000000
     OR p_reorder_point IS NULL OR p_reorder_point < 0 THEN
    RAISE EXCEPTION 'invalid_inventory_quantity' USING ERRCODE = '22023';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id = p_organization_id AND m.user_id = v_uid
      AND m.state = 'active' AND m.role IN ('owner','admin','manager')
  ) THEN RAISE EXCEPTION 'organization_write_access_denied' USING ERRCODE = '42501'; END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_activities a
    JOIN public.commercial_organizations o ON o.id = a.organization_id
    WHERE a.id = p_activity_id AND a.organization_id = p_organization_id
      AND a.state = 'active' AND a.deleted_at IS NULL
      AND o.state = 'active' AND o.deleted_at IS NULL
  ) THEN RAISE EXCEPTION 'activity_not_in_active_organization' USING ERRCODE = '42501'; END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_locations l
    WHERE l.id = p_location_id AND l.organization_id = p_organization_id AND l.state = 'active'
  ) THEN RAISE EXCEPTION 'location_not_in_organization' USING ERRCODE = '42501'; END IF;

  PERFORM pg_advisory_xact_lock(hashtext(p_activity_id::text),
    hashtext(lower(btrim(p_name)) || '|' || p_location_id::text || '|' || upper(p_currency_code)));
  SELECT o.id INTO v_existing_id
  FROM public.commercial_offerings o JOIN public.commercial_products p ON p.id = o.product_id
  WHERE o.activity_id = p_activity_id AND o.location_ref = p_location_id::text
    AND o.state = 'active' AND lower(btrim(p.name)) = lower(btrim(p_name))
    AND lower(btrim(p.unit_name)) = lower(btrim(p_unit_name))
  LIMIT 1;
  IF FOUND THEN RAISE EXCEPTION 'duplicate_active_inventory_item' USING ERRCODE = '23505'; END IF;

  INSERT INTO public.commercial_products(name, unit_name, state)
  VALUES (btrim(p_name), btrim(p_unit_name), 'active') RETURNING id INTO v_product_id;
  INSERT INTO public.commercial_offerings(activity_id, product_id, price_minor, currency_code, scope_key, location_ref, state, valid_from)
  VALUES (p_activity_id, v_product_id, p_price_minor, upper(p_currency_code), 'retail', p_location_id::text, 'active', now())
  RETURNING id INTO v_offering_id;
  INSERT INTO public.commercial_inventory_balances(organization_id, location_id, offering_id, quantity_on_hand, quantity_reserved, reorder_point)
  VALUES (p_organization_id, p_location_id, v_offering_id, p_initial_quantity, 0, p_reorder_point);
  IF p_initial_quantity > 0 THEN
    INSERT INTO public.commercial_stock_movements(offering_id, movement_type, quantity_delta, source_ref, created_by)
    VALUES (v_offering_id, 'opening', p_initial_quantity,
      'opening:' || v_offering_id::text || ':location:' || p_location_id::text, v_uid);
  END IF;
  INSERT INTO public.commercial_audit_events(organization_id, actor_user_id, action, entity_type, entity_id, metadata)
  VALUES (p_organization_id, v_uid, 'inventory_item.created', 'offering', v_offering_id::text,
    jsonb_build_object('activity_id', p_activity_id, 'location_id', p_location_id, 'product_id', v_product_id,
      'initial_quantity', p_initial_quantity, 'price_minor', p_price_minor, 'currency_code', upper(p_currency_code)));
  RETURN jsonb_build_object('product_id', v_product_id, 'offering_id', v_offering_id,
    'organization_id', p_organization_id, 'activity_id', p_activity_id, 'location_id', p_location_id,
    'quantity_on_hand', p_initial_quantity, 'price_minor', p_price_minor, 'currency_code', upper(p_currency_code));
END;
$function$;
REVOKE ALL ON FUNCTION commercial_private.commercial_create_inventory_item(uuid,uuid,uuid,text,text,bigint,text,integer,numeric) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION commercial_private.commercial_create_inventory_item(uuid,uuid,uuid,text,text,bigint,text,integer,numeric) TO postgres, service_role;
