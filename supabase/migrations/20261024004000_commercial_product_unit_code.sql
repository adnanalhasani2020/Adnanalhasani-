-- Attach a stable unit-of-measure key to commercial product masters.
-- Existing unit_name values are preserved; legacy rows remain nullable until explicitly mapped.
ALTER TABLE public.commercial_products
  ADD COLUMN IF NOT EXISTS unit_code text
  REFERENCES public.commercial_unit_of_measure(code) ON DELETE RESTRICT;

CREATE INDEX IF NOT EXISTS commercial_products_unit_code_idx
  ON public.commercial_products(unit_code)
  WHERE unit_code IS NOT NULL;

CREATE OR REPLACE FUNCTION public.commercial_create_inventory_item_with_category_and_unit(
  p_organization_id uuid,
  p_activity_id uuid,
  p_location_id uuid,
  p_name text,
  p_unit_name text,
  p_price_minor bigint,
  p_currency_code text,
  p_initial_quantity integer,
  p_reorder_point numeric DEFAULT 0,
  p_category_id uuid DEFAULT NULL,
  p_unit_code text DEFAULT NULL
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, commercial_private
AS $function$
DECLARE
  v_result jsonb;
  v_product_id uuid;
  v_unit_name text;
BEGIN
  IF p_unit_code IS NOT NULL THEN
    SELECT u.display_name INTO v_unit_name
    FROM public.commercial_unit_of_measure u
    WHERE u.code = p_unit_code AND u.is_active = true;

    IF v_unit_name IS NULL THEN
      RAISE EXCEPTION 'active_unit_of_measure_not_found' USING ERRCODE = '23514';
    END IF;
  END IF;

  v_result := public.commercial_create_inventory_item_with_category(
    p_organization_id,
    p_activity_id,
    p_location_id,
    p_name,
    COALESCE(v_unit_name, p_unit_name),
    p_price_minor,
    p_currency_code,
    p_initial_quantity,
    p_reorder_point,
    p_category_id
  );

  IF p_unit_code IS NOT NULL THEN
    v_product_id := (v_result->>'product_id')::uuid;
    UPDATE public.commercial_products
       SET unit_code = p_unit_code,
           unit_name = v_unit_name,
           updated_at = now()
     WHERE id = v_product_id;
    v_result := v_result || jsonb_build_object('unit_code', p_unit_code, 'unit_name', v_unit_name);
  END IF;

  RETURN v_result;
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_create_inventory_item_with_category_and_unit(uuid, uuid, uuid, text, text, bigint, text, integer, numeric, uuid, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_create_inventory_item_with_category_and_unit(uuid, uuid, uuid, text, text, bigint, text, integer, numeric, uuid, text) TO authenticated;
COMMENT ON COLUMN public.commercial_products.unit_code IS 'Stable reference to the shared unit-of-measure directory; unit_name remains the human-readable snapshot.';
COMMENT ON FUNCTION public.commercial_create_inventory_item_with_category_and_unit(uuid, uuid, uuid, text, text, bigint, text, integer, numeric, uuid, text)
IS 'Creates a cloud inventory item with optional primary category and a validated active unit code atomically.';