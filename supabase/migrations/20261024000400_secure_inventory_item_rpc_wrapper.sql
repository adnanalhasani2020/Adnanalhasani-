CREATE OR REPLACE FUNCTION public.commercial_create_inventory_item(
  p_organization_id uuid, p_activity_id uuid, p_location_id uuid,
  p_name text, p_unit_name text, p_price_minor bigint, p_currency_code text,
  p_initial_quantity integer, p_reorder_point numeric DEFAULT 0
) RETURNS jsonb
LANGUAGE sql
SECURITY DEFINER
SET search_path TO 'pg_catalog', 'public', 'commercial_private'
AS $function$
  SELECT commercial_private.commercial_create_inventory_item(
    p_organization_id, p_activity_id, p_location_id, p_name, p_unit_name,
    p_price_minor, p_currency_code, p_initial_quantity, p_reorder_point
  );
$function$;
REVOKE ALL ON FUNCTION public.commercial_create_inventory_item(uuid,uuid,uuid,text,text,bigint,text,integer,numeric) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_create_inventory_item(uuid,uuid,uuid,text,text,bigint,text,integer,numeric) TO authenticated, service_role;
