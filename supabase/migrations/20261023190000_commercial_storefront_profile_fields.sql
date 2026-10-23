-- Extend the atomic storefront RPC with optional public profile and address fields.
CREATE OR REPLACE FUNCTION public.commercial_create_storefront_profiled(p_organization_name text, p_organization_slug text, p_activity_name text, p_product_name text, p_unit_name text, p_price_minor bigint, p_currency_code text, p_location_name text, p_initial_quantity integer, p_country_code text DEFAULT 'YE'::text, p_timezone text DEFAULT 'Asia/Aden'::text, p_locale text DEFAULT 'ar'::text, p_public_phone text DEFAULT NULL::text, p_public_email text DEFAULT NULL::text, p_website_url text DEFAULT NULL::text, p_public_description text DEFAULT NULL::text, p_city text DEFAULT NULL::text, p_district text DEFAULT NULL::text, p_street text DEFAULT NULL::text, p_landmark text DEFAULT NULL::text, p_public_listing boolean DEFAULT false)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'public'
AS $function$
DECLARE
  v_result jsonb;
  v_org_id uuid;
  v_location_id uuid;
  v_uid uuid := auth.uid();
  v_address jsonb;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'authentication_required' USING ERRCODE = '42501';
  END IF;
  IF p_country_code IS NULL OR p_country_code !~ '^[A-Z]{2}$' THEN
    RAISE EXCEPTION 'invalid_country_code' USING ERRCODE = '22023';
  END IF;
  IF p_timezone IS NULL OR NOT EXISTS (
    SELECT 1 FROM pg_catalog.pg_timezone_names z WHERE z.name = p_timezone
  ) THEN
    RAISE EXCEPTION 'invalid_timezone' USING ERRCODE = '22023';
  END IF;
  IF p_locale IS NULL OR NOT EXISTS (
    SELECT 1 FROM public.commercial_supported_locales l
    WHERE l.locale_code = p_locale AND l.is_active
  ) THEN
    RAISE EXCEPTION 'unsupported_locale' USING ERRCODE = '22023';
  END IF;
  IF p_public_phone IS NOT NULL AND length(btrim(p_public_phone)) > 40 THEN
    RAISE EXCEPTION 'invalid_public_phone' USING ERRCODE = '22023';
  END IF;
  IF p_public_email IS NOT NULL AND (
    length(btrim(p_public_email)) > 320 OR btrim(p_public_email) !~* '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$'
  ) THEN
    RAISE EXCEPTION 'invalid_public_email' USING ERRCODE = '22023';
  END IF;
  IF p_website_url IS NOT NULL AND (
    length(btrim(p_website_url)) > 500 OR btrim(p_website_url) !~* '^https?://[^[:space:]]+$'
  ) THEN
    RAISE EXCEPTION 'invalid_website_url' USING ERRCODE = '22023';
  END IF;
  IF p_public_description IS NOT NULL AND length(btrim(p_public_description)) > 2000 THEN
    RAISE EXCEPTION 'public_description_too_long' USING ERRCODE = '22023';
  END IF;
  IF EXISTS (
    SELECT 1 FROM (VALUES (p_city), (p_district), (p_street), (p_landmark)) AS a(value)
    WHERE length(coalesce(a.value, '')) > 240
  ) THEN
    RAISE EXCEPTION 'address_field_too_long' USING ERRCODE = '22023';
  END IF;

  v_result := public.commercial_create_storefront(
    p_organization_name, p_organization_slug, p_activity_name,
    p_product_name, p_unit_name, p_price_minor, p_currency_code,
    p_location_name, p_initial_quantity
  );
  v_org_id := (v_result->>'organization_id')::uuid;
  v_location_id := (v_result->>'location_id')::uuid;

  UPDATE public.commercial_organizations
  SET country_code = p_country_code,
      default_timezone = p_timezone,
      default_locale = p_locale,
      public_phone = nullif(btrim(p_public_phone), ''),
      public_email = nullif(lower(btrim(p_public_email)), ''),
      website_url = nullif(btrim(p_website_url), ''),
      public_description = nullif(btrim(p_public_description), ''),
      public_listing = coalesce(p_public_listing, false),
      updated_at = now()
  WHERE id = v_org_id AND created_by = v_uid;

  v_address := jsonb_strip_nulls(jsonb_build_object(
    'city', nullif(btrim(p_city), ''),
    'district', nullif(btrim(p_district), ''),
    'street', nullif(btrim(p_street), ''),
    'landmark', nullif(btrim(p_landmark), '')
  ));
  UPDATE public.commercial_organization_locations
  SET country_code = p_country_code, timezone = p_timezone,
      address = v_address, updated_at = now()
  WHERE id = v_location_id AND organization_id = v_org_id;

  UPDATE public.commercial_organization_settings
  SET setting_value = to_jsonb(p_country_code), updated_by = v_uid, updated_at = now()
  WHERE organization_id = v_org_id AND setting_key = 'country_code';
  UPDATE public.commercial_organization_settings
  SET setting_value = to_jsonb(p_timezone), updated_by = v_uid, updated_at = now()
  WHERE organization_id = v_org_id AND setting_key = 'timezone';
  UPDATE public.commercial_organization_settings
  SET setting_value = to_jsonb(p_locale), updated_by = v_uid, updated_at = now()
  WHERE organization_id = v_org_id AND setting_key = 'locale';

  RETURN v_result || jsonb_build_object(
    'country_code', p_country_code,
    'timezone', p_timezone,
    'locale', p_locale,
    'public_listing', coalesce(p_public_listing, false),
    'location_address', v_address
  );
END;
$function$
;

REVOKE ALL ON FUNCTION public.commercial_create_storefront_profiled(
  text, text, text, text, text, bigint, text, text, integer,
  text, text, text, text, text, text, text, text, text, text, text, boolean
) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_create_storefront_profiled(
  text, text, text, text, text, bigint, text, text, integer,
  text, text, text, text, text, text, text, text, text, text, text, boolean
) TO authenticated;
