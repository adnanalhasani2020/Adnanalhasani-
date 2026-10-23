-- Keep privileged write implementations outside the exposed public API schema.
CREATE SCHEMA IF NOT EXISTS commercial_private;
REVOKE ALL ON SCHEMA commercial_private FROM PUBLIC;
REVOKE ALL ON SCHEMA commercial_private FROM anon;
GRANT USAGE ON SCHEMA commercial_private TO authenticated;

ALTER FUNCTION public.commercial_checkout_sale(uuid, uuid, integer, text) SET SCHEMA commercial_private;
ALTER FUNCTION public.commercial_create_storefront(text, text, text, text, text, bigint, text, text, integer) SET SCHEMA commercial_private;
ALTER FUNCTION public.commercial_add_store_item(uuid, uuid, uuid, text, text, bigint, integer) SET SCHEMA commercial_private;

REVOKE ALL ON FUNCTION commercial_private.commercial_checkout_sale(uuid, uuid, integer, text) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION commercial_private.commercial_create_storefront(text, text, text, text, text, bigint, text, text, integer) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION commercial_private.commercial_add_store_item(uuid, uuid, uuid, text, text, bigint, integer) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION commercial_private.commercial_checkout_sale(uuid, uuid, integer, text) TO authenticated;
GRANT EXECUTE ON FUNCTION commercial_private.commercial_create_storefront(text, text, text, text, text, bigint, text, text, integer) TO authenticated;
GRANT EXECUTE ON FUNCTION commercial_private.commercial_add_store_item(uuid, uuid, uuid, text, text, bigint, integer) TO authenticated;

CREATE OR REPLACE FUNCTION public.commercial_checkout_sale(
  p_offering_id uuid, p_location_id uuid, p_quantity integer, p_idempotency_key text
) RETURNS jsonb
LANGUAGE sql SECURITY INVOKER
SET search_path TO 'pg_catalog', 'public', 'commercial_private'
AS $function$
  SELECT commercial_private.commercial_checkout_sale(p_offering_id, p_location_id, p_quantity, p_idempotency_key);
$function$;

CREATE OR REPLACE FUNCTION public.commercial_create_storefront(
  p_organization_name text, p_organization_slug text, p_activity_name text,
  p_product_name text, p_unit_name text, p_price_minor bigint,
  p_currency_code text, p_location_name text, p_initial_quantity integer
) RETURNS jsonb
LANGUAGE sql SECURITY INVOKER
SET search_path TO 'pg_catalog', 'public', 'commercial_private'
AS $function$
  SELECT commercial_private.commercial_create_storefront(
    p_organization_name, p_organization_slug, p_activity_name, p_product_name,
    p_unit_name, p_price_minor, p_currency_code, p_location_name, p_initial_quantity
  );
$function$;

CREATE OR REPLACE FUNCTION public.commercial_add_store_item(
  p_organization_id uuid, p_activity_id uuid, p_location_id uuid,
  p_product_name text, p_unit_name text, p_price_minor bigint, p_initial_quantity integer
) RETURNS jsonb
LANGUAGE sql SECURITY INVOKER
SET search_path TO 'pg_catalog', 'public', 'commercial_private'
AS $function$
  SELECT commercial_private.commercial_add_store_item(
    p_organization_id, p_activity_id, p_location_id, p_product_name,
    p_unit_name, p_price_minor, p_initial_quantity
  );
$function$;

REVOKE ALL ON FUNCTION public.commercial_checkout_sale(uuid, uuid, integer, text) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.commercial_create_storefront(text, text, text, text, text, bigint, text, text, integer) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.commercial_add_store_item(uuid, uuid, uuid, text, text, bigint, integer) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_checkout_sale(uuid, uuid, integer, text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_create_storefront(text, text, text, text, text, bigint, text, text, integer) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_add_store_item(uuid, uuid, uuid, text, text, bigint, integer) TO authenticated;
