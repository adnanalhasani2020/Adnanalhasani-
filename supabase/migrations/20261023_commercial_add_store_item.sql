-- Add a product and its opening inventory to an existing authorized storefront.
CREATE OR REPLACE FUNCTION public.commercial_add_store_item(
  p_organization_id uuid,
  p_activity_id uuid,
  p_location_id uuid,
  p_product_name text,
  p_unit_name text,
  p_price_minor bigint,
  p_initial_quantity integer
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog', 'public'
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_currency text;
  v_product_id uuid;
  v_offering_id uuid;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'authentication_required' USING ERRCODE = '42501';
  END IF;
  IF p_organization_id IS NULL OR p_activity_id IS NULL OR p_location_id IS NULL THEN
    RAISE EXCEPTION 'organization_activity_and_location_required' USING ERRCODE = '22023';
  END IF;
  IF p_product_name IS NULL OR length(btrim(p_product_name)) NOT BETWEEN 1 AND 180
     OR p_unit_name IS NULL OR length(btrim(p_unit_name)) NOT BETWEEN 1 AND 40 THEN
    RAISE EXCEPTION 'invalid_product_name_or_unit' USING ERRCODE = '22023';
  END IF;
  IF p_price_minor IS NULL OR p_price_minor < 0 THEN
    RAISE EXCEPTION 'invalid_price' USING ERRCODE = '22023';
  END IF;
  IF p_initial_quantity IS NULL OR p_initial_quantity < 1 OR p_initial_quantity > 100000000 THEN
    RAISE EXCEPTION 'invalid_initial_quantity' USING ERRCODE = '22023';
  END IF;

  SELECT o.default_currency_code INTO v_currency
  FROM public.commercial_organizations o
  WHERE o.id = p_organization_id AND o.state = 'active' AND o.deleted_at IS NULL;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'active_organization_not_found' USING ERRCODE = 'P0002';
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id = p_organization_id AND m.user_id = v_uid
      AND m.state = 'active' AND m.role IN ('owner','admin','manager')
  ) THEN
    RAISE EXCEPTION 'organization_management_permission_required' USING ERRCODE = '42501';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_activities a
    WHERE a.id = p_activity_id AND a.organization_id = p_organization_id
      AND a.state = 'active' AND a.deleted_at IS NULL
  ) THEN
    RAISE EXCEPTION 'active_activity_not_found' USING ERRCODE = 'P0002';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_locations l
    WHERE l.id = p_location_id AND l.organization_id = p_organization_id
      AND l.state = 'active'
  ) THEN
    RAISE EXCEPTION 'active_location_not_found' USING ERRCODE = 'P0002';
  END IF;

  INSERT INTO public.commercial_products (name, unit_name, state)
  VALUES (btrim(p_product_name), btrim(p_unit_name), 'active')
  RETURNING id INTO v_product_id;

  INSERT INTO public.commercial_offerings (
    activity_id, product_id, price_minor, currency_code, scope_key,
    location_ref, state, valid_from
  ) VALUES (
    p_activity_id, v_product_id, p_price_minor, v_currency, 'retail',
    p_location_id::text, 'active', now()
  ) RETURNING id INTO v_offering_id;

  INSERT INTO public.commercial_inventory_balances (
    organization_id, location_id, offering_id, quantity_on_hand,
    quantity_reserved, reorder_point
  ) VALUES (
    p_organization_id, p_location_id, v_offering_id, p_initial_quantity, 0, 0
  );

  INSERT INTO public.commercial_stock_movements (
    offering_id, movement_type, quantity_delta, source_ref, created_by
  ) VALUES (
    v_offering_id, 'opening', p_initial_quantity,
    'opening:' || v_offering_id::text || ':location:' || p_location_id::text,
    v_uid
  );

  INSERT INTO public.commercial_audit_events (
    organization_id, actor_user_id, action, entity_type, entity_id, metadata
  ) VALUES (
    p_organization_id, v_uid, 'product.added_to_storefront', 'offering', v_offering_id::text,
    jsonb_build_object('activity_id', p_activity_id, 'location_id', p_location_id,
                       'product_id', v_product_id, 'initial_quantity', p_initial_quantity,
                       'price_minor', p_price_minor, 'currency_code', v_currency)
  );

  RETURN jsonb_build_object(
    'organization_id', p_organization_id, 'activity_id', p_activity_id,
    'location_id', p_location_id, 'product_id', v_product_id,
    'offering_id', v_offering_id, 'currency_code', v_currency,
    'initial_quantity', p_initial_quantity
  );
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_add_store_item(uuid, uuid, uuid, text, text, bigint, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.commercial_add_store_item(uuid, uuid, uuid, text, text, bigint, integer) FROM anon;
GRANT EXECUTE ON FUNCTION public.commercial_add_store_item(uuid, uuid, uuid, text, text, bigint, integer) TO authenticated;
