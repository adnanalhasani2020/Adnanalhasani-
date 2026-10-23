-- Create the first sellable organization storefront in one authenticated transaction.
CREATE OR REPLACE FUNCTION public.commercial_create_storefront(
  p_organization_name text,
  p_organization_slug text,
  p_activity_name text,
  p_product_name text,
  p_unit_name text,
  p_price_minor bigint,
  p_currency_code text,
  p_location_name text,
  p_initial_quantity integer
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog', 'public'
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_org_id uuid;
  v_activity_id uuid;
  v_location_id uuid;
  v_product_id uuid;
  v_offering_id uuid;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'authentication_required' USING ERRCODE = '42501';
  END IF;
  IF p_activity_name IS NULL OR length(btrim(p_activity_name)) NOT BETWEEN 1 AND 160
     OR p_product_name IS NULL OR length(btrim(p_product_name)) NOT BETWEEN 1 AND 180
     OR p_unit_name IS NULL OR length(btrim(p_unit_name)) NOT BETWEEN 1 AND 40
     OR p_location_name IS NULL OR length(btrim(p_location_name)) NOT BETWEEN 1 AND 160 THEN
    RAISE EXCEPTION 'invalid_storefront_name' USING ERRCODE = '22023';
  END IF;
  IF p_price_minor IS NULL OR p_price_minor < 0 THEN
    RAISE EXCEPTION 'invalid_price' USING ERRCODE = '22023';
  END IF;
  IF p_currency_code IS NULL OR p_currency_code !~ '^[A-Z]{3}$' THEN
    RAISE EXCEPTION 'invalid_currency_code' USING ERRCODE = '22023';
  END IF;
  IF p_initial_quantity IS NULL OR p_initial_quantity < 0 OR p_initial_quantity > 100000000 THEN
    RAISE EXCEPTION 'invalid_initial_quantity' USING ERRCODE = '22023';
  END IF;

  v_org_id := public.commercial_create_organization(
    p_organization_name, p_organization_slug, 'retail', p_currency_code,
    'Asia/Aden', 'ar', 'YE', NULL
  );

  INSERT INTO public.commercial_activities (
    owner_user_id, organization_id, name, state, activity_type
  ) VALUES (
    v_uid, v_org_id, btrim(p_activity_name), 'active', 'retail'
  ) RETURNING id INTO v_activity_id;

  INSERT INTO public.commercial_organization_locations (
    organization_id, code, name, location_type, country_code, timezone, state
  ) VALUES (
    v_org_id, 'MAIN', btrim(p_location_name), 'store', 'YE', 'Asia/Aden', 'active'
  ) RETURNING id INTO v_location_id;

  INSERT INTO public.commercial_products (name, unit_name, state)
  VALUES (btrim(p_product_name), btrim(p_unit_name), 'active')
  RETURNING id INTO v_product_id;

  INSERT INTO public.commercial_offerings (
    activity_id, product_id, price_minor, currency_code, scope_key,
    location_ref, state, valid_from
  ) VALUES (
    v_activity_id, v_product_id, p_price_minor, p_currency_code, 'retail',
    v_location_id::text, 'active', now()
  ) RETURNING id INTO v_offering_id;

  INSERT INTO public.commercial_inventory_balances (
    organization_id, location_id, offering_id, quantity_on_hand,
    quantity_reserved, reorder_point
  ) VALUES (
    v_org_id, v_location_id, v_offering_id, p_initial_quantity, 0, 0
  );

  IF p_initial_quantity > 0 THEN
    INSERT INTO public.commercial_stock_movements (
      offering_id, movement_type, quantity_delta, source_ref, created_by
    ) VALUES (
      v_offering_id, 'opening', p_initial_quantity,
      'opening:' || v_offering_id::text || ':location:' || v_location_id::text,
      v_uid
    );
  END IF;

  INSERT INTO public.commercial_audit_events (
    organization_id, actor_user_id, action, entity_type, entity_id, metadata
  ) VALUES (
    v_org_id, v_uid, 'storefront.created', 'storefront', v_org_id::text,
    jsonb_build_object('activity_id', v_activity_id, 'location_id', v_location_id,
                       'product_id', v_product_id, 'offering_id', v_offering_id,
                       'initial_quantity', p_initial_quantity, 'currency_code', p_currency_code)
  );

  RETURN jsonb_build_object(
    'organization_id', v_org_id, 'activity_id', v_activity_id,
    'location_id', v_location_id, 'product_id', v_product_id,
    'offering_id', v_offering_id, 'initial_quantity', p_initial_quantity
  );
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_create_storefront(text, text, text, text, text, bigint, text, text, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.commercial_create_storefront(text, text, text, text, text, bigint, text, text, integer) FROM anon;
GRANT EXECUTE ON FUNCTION public.commercial_create_storefront(text, text, text, text, text, bigint, text, text, integer) TO authenticated;
