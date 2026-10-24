CREATE OR REPLACE FUNCTION public.commercial_create_inventory_item_with_category(
  p_organization_id uuid,
  p_activity_id uuid,
  p_location_id uuid,
  p_name text,
  p_unit_name text,
  p_price_minor bigint,
  p_currency_code text,
  p_initial_quantity integer,
  p_reorder_point numeric DEFAULT 0,
  p_category_id uuid DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, commercial_private
AS $function$
DECLARE
  v_result jsonb;
  v_offering_id uuid;
BEGIN
  IF p_category_id IS NOT NULL AND NOT EXISTS (
    SELECT 1
    FROM public.commercial_product_categories c
    WHERE c.id = p_category_id
      AND c.organization_id = p_organization_id
      AND c.state = 'active'
  ) THEN
    RAISE EXCEPTION 'active_category_not_found_in_organization' USING ERRCODE = '23514';
  END IF;

  v_result := commercial_private.commercial_create_inventory_item(
    p_organization_id, p_activity_id, p_location_id, p_name, p_unit_name,
    p_price_minor, p_currency_code, p_initial_quantity, p_reorder_point
  );

  IF p_category_id IS NOT NULL THEN
    v_offering_id := (v_result->>'offering_id')::uuid;
    PERFORM public.commercial_assign_offering_category(
      p_organization_id, p_activity_id, v_offering_id, p_category_id
    );
    v_result := v_result || jsonb_build_object('category_id', p_category_id);
  END IF;

  RETURN v_result;
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_create_inventory_item_with_category(uuid, uuid, uuid, text, text, bigint, text, integer, numeric, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_create_inventory_item_with_category(uuid, uuid, uuid, text, text, bigint, text, integer, numeric, uuid) TO authenticated;
COMMENT ON FUNCTION public.commercial_create_inventory_item_with_category(uuid, uuid, uuid, text, text, bigint, text, integer, numeric, uuid)
IS 'Creates product, offering, opening stock movement and optional primary category assignment atomically.';