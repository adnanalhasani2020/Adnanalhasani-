-- Image and identity documents are hosted externally. Supabase stores URLs only.
-- Remove the upload buckets created by the previous onboarding revision when empty.
DROP POLICY IF EXISTS commercial_brand_assets_owner_insert ON storage.objects;
DROP POLICY IF EXISTS commercial_brand_assets_owner_update ON storage.objects;
DROP POLICY IF EXISTS commercial_owner_identity_insert ON storage.objects;
DROP POLICY IF EXISTS commercial_owner_identity_read ON storage.objects;
DROP POLICY IF EXISTS commercial_owner_identity_delete ON storage.objects;

DELETE FROM storage.buckets b
WHERE b.id IN ('commercial-brand-assets','commercial-owner-identities')
  AND NOT EXISTS (SELECT 1 FROM storage.objects o WHERE o.bucket_id = b.id);

-- Parameter names change from object paths to external URLs; recreate the RPC signature.
DROP FUNCTION IF EXISTS public.commercial_create_activity_profile(text,text,text,text,text,text,date,text,text,text,text,text,text,text,text,text,text,text,text,text,text,text,text,text,text,jsonb);

CREATE FUNCTION public.commercial_create_activity_profile(
  p_organization_name text,
  p_organization_slug text,
  p_activity_name text,
  p_activity_type text,
  p_business_level text,
  p_commercial_registration_number text,
  p_opened_on date,
  p_owner_full_name text,
  p_legal_name text,
  p_country_code text,
  p_timezone text,
  p_locale text,
  p_currency_code text,
  p_public_phone text DEFAULT NULL,
  p_public_email text DEFAULT NULL,
  p_website_url text DEFAULT NULL,
  p_public_description text DEFAULT NULL,
  p_city text DEFAULT NULL,
  p_district text DEFAULT NULL,
  p_street text DEFAULT NULL,
  p_landmark text DEFAULT NULL,
  p_logo_url text DEFAULT NULL,
  p_cover_image_url text DEFAULT NULL,
  p_owner_identity_front_url text DEFAULT NULL,
  p_owner_identity_back_url text DEFAULT NULL,
  p_locations jsonb DEFAULT '[]'::jsonb
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog', 'public'
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_org_id uuid;
  v_activity_id uuid;
  v_headquarters_id uuid;
  v_location_id uuid;
  v_parent_id uuid;
  v_address jsonb;
  v_location_ids uuid[] := ARRAY[]::uuid[];
  v_location jsonb;
  v_index integer := 0;
  v_parent_index integer;
  v_location_name text;
  v_location_type text;
  v_org_type text;
  v_size_tier text;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE = '42501'; END IF;
  IF p_organization_name IS NULL OR length(btrim(p_organization_name)) NOT BETWEEN 2 AND 160 THEN
    RAISE EXCEPTION 'invalid_organization_name' USING ERRCODE = '22023';
  END IF;
  IF p_organization_slug IS NULL OR p_organization_slug !~ '^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$' THEN
    RAISE EXCEPTION 'invalid_organization_slug' USING ERRCODE = '22023';
  END IF;
  IF p_activity_name IS NULL OR length(btrim(p_activity_name)) NOT BETWEEN 1 AND 160 THEN
    RAISE EXCEPTION 'invalid_activity_name' USING ERRCODE = '22023';
  END IF;
  IF p_activity_type NOT IN ('service','commercial','production') THEN
    RAISE EXCEPTION 'invalid_activity_type' USING ERRCODE = '22023';
  END IF;
  IF p_business_level NOT IN ('company','institution','supermarket','grocery','stall','shop','office','factory','clinic','independent','other') THEN
    RAISE EXCEPTION 'invalid_business_level' USING ERRCODE = '22023';
  END IF;
  IF p_commercial_registration_number IS NULL OR length(btrim(p_commercial_registration_number)) NOT BETWEEN 1 AND 100 THEN
    RAISE EXCEPTION 'invalid_commercial_registration_number' USING ERRCODE = '22023';
  END IF;
  IF p_opened_on IS NULL OR p_opened_on > current_date THEN
    RAISE EXCEPTION 'invalid_opening_date' USING ERRCODE = '22023';
  END IF;
  IF p_owner_full_name IS NULL OR length(btrim(p_owner_full_name)) NOT BETWEEN 2 AND 160 THEN
    RAISE EXCEPTION 'invalid_owner_name' USING ERRCODE = '22023';
  END IF;
  IF p_country_code IS NULL OR p_country_code !~ '^[A-Z]{2}$' THEN RAISE EXCEPTION 'invalid_country_code' USING ERRCODE = '22023'; END IF;
  IF p_timezone IS NULL OR NOT EXISTS (SELECT 1 FROM pg_catalog.pg_timezone_names z WHERE z.name = p_timezone) THEN
    RAISE EXCEPTION 'invalid_timezone' USING ERRCODE = '22023';
  END IF;
  IF p_locale IS NULL OR NOT EXISTS (SELECT 1 FROM public.commercial_supported_locales l WHERE l.locale_code = p_locale AND l.is_active) THEN
    RAISE EXCEPTION 'unsupported_locale' USING ERRCODE = '22023';
  END IF;
  IF p_currency_code IS NULL OR p_currency_code !~ '^[A-Z]{3}$' THEN RAISE EXCEPTION 'invalid_currency_code' USING ERRCODE = '22023'; END IF;
  IF jsonb_typeof(coalesce(p_locations, '[]'::jsonb)) <> 'array' OR jsonb_array_length(coalesce(p_locations, '[]'::jsonb)) > 100 THEN
    RAISE EXCEPTION 'invalid_locations' USING ERRCODE = '22023';
  END IF;
  IF p_public_email IS NOT NULL AND (length(btrim(p_public_email)) > 320 OR btrim(p_public_email) !~* '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$') THEN
    RAISE EXCEPTION 'invalid_public_email' USING ERRCODE = '22023';
  END IF;
  IF p_website_url IS NOT NULL AND (length(btrim(p_website_url)) > 500 OR btrim(p_website_url) !~* '^https?://[^[:space:]]+$') THEN
    RAISE EXCEPTION 'invalid_website_url' USING ERRCODE = '22023';
  END IF;
  IF p_logo_url IS NOT NULL AND (length(p_logo_url) > 500 OR p_logo_url !~* '^https://[^[:space:]]+$') THEN
    RAISE EXCEPTION 'invalid_logo_url' USING ERRCODE = '22023';
  END IF;
  IF p_cover_image_url IS NOT NULL AND (length(p_cover_image_url) > 500 OR p_cover_image_url !~* '^https://[^[:space:]]+$') THEN
    RAISE EXCEPTION 'invalid_cover_image_url' USING ERRCODE = '22023';
  END IF;
  IF p_owner_identity_front_url IS NOT NULL AND (length(p_owner_identity_front_url) > 500 OR p_owner_identity_front_url !~* '^https://[^[:space:]]+$') THEN
    RAISE EXCEPTION 'invalid_owner_identity_front_url' USING ERRCODE = '22023';
  END IF;
  IF p_owner_identity_back_url IS NOT NULL AND (length(p_owner_identity_back_url) > 500 OR p_owner_identity_back_url !~* '^https://[^[:space:]]+$') THEN
    RAISE EXCEPTION 'invalid_owner_identity_back_url' USING ERRCODE = '22023';
  END IF;

  v_org_type := CASE p_activity_type WHEN 'service' THEN 'services' WHEN 'production' THEN 'manufacturing' ELSE 'retail' END;
  v_size_tier := CASE p_business_level WHEN 'company' THEN 'medium' WHEN 'institution' THEN 'small' WHEN 'supermarket' THEN 'small' WHEN 'factory' THEN 'medium' WHEN 'clinic' THEN 'small' ELSE 'micro' END;
  v_address := jsonb_strip_nulls(jsonb_build_object('city',nullif(btrim(p_city),''),'district',nullif(btrim(p_district),''),'street',nullif(btrim(p_street),''),'landmark',nullif(btrim(p_landmark),'')));

  INSERT INTO public.commercial_organizations
    (name, slug, default_currency_code, default_timezone, created_by, organization_type, size_tier,
     country_code, default_locale, legal_name, commercial_registration_number,
     opened_on, owner_full_name, logo_url, cover_image_url, public_description, public_phone,
     public_email, website_url, metadata, public_listing)
  VALUES
    (btrim(p_organization_name), lower(btrim(p_organization_slug)), p_currency_code, p_timezone, v_uid,
     v_org_type, v_size_tier, p_country_code, p_locale, nullif(btrim(p_legal_name), ''),
     btrim(p_commercial_registration_number), p_opened_on,
     btrim(p_owner_full_name), nullif(btrim(p_logo_url), ''), nullif(btrim(p_cover_image_url), ''),
     nullif(btrim(p_public_description), ''), nullif(btrim(p_public_phone), ''),
     nullif(lower(btrim(p_public_email)), ''), nullif(btrim(p_website_url), ''),
     jsonb_build_object('business_level',p_business_level,
       'owner_identity_front_url',nullif(btrim(p_owner_identity_front_url),''),
       'owner_identity_back_url',nullif(btrim(p_owner_identity_back_url),'')), false)
  RETURNING id INTO v_org_id;

  INSERT INTO public.commercial_organization_members (organization_id,user_id,role,state,joined_at)
  VALUES (v_org_id,v_uid,'owner','active',now());

  INSERT INTO public.commercial_activities (owner_user_id,organization_id,name,activity_type,description,metadata)
  VALUES (v_uid,v_org_id,btrim(p_activity_name),p_activity_type,
          nullif(btrim(p_public_description),''),jsonb_build_object('business_level',p_business_level))
  RETURNING id INTO v_activity_id;

  INSERT INTO public.commercial_organization_locations
    (organization_id,code,name,location_type,country_code,address,timezone,metadata)
  VALUES (v_org_id,'HQ','المقر الرئيسي','headquarters',p_country_code,v_address,p_timezone,
          jsonb_build_object('opened_on',p_opened_on))
  RETURNING id INTO v_headquarters_id;
  v_location_ids := array_append(v_location_ids,v_headquarters_id);

  FOR v_location IN SELECT value FROM jsonb_array_elements(coalesce(p_locations,'[]'::jsonb)) LOOP
    v_location_name := nullif(btrim(v_location->>'name'),'');
    v_location_type := coalesce(v_location->>'location_type','branch');
    v_parent_index := coalesce((v_location->>'parent_index')::integer,-1);
    IF v_location_name IS NULL OR length(v_location_name)>160 THEN RAISE EXCEPTION 'invalid_location_name' USING ERRCODE = '22023'; END IF;
    IF v_location_type NOT IN ('branch','store','office','department','warehouse','factory','other') THEN RAISE EXCEPTION 'invalid_location_type' USING ERRCODE = '22023'; END IF;
    IF v_parent_index < -1 OR v_parent_index >= v_index THEN RAISE EXCEPTION 'invalid_location_parent' USING ERRCODE = '22023'; END IF;
    v_parent_id := CASE WHEN v_parent_index = -1 THEN v_headquarters_id ELSE v_location_ids[v_parent_index + 2] END;
    INSERT INTO public.commercial_organization_locations
      (organization_id,parent_location_id,code,name,location_type,country_code,address,timezone,metadata)
    VALUES (v_org_id,v_parent_id,'LOC-' || lpad((v_index+1)::text,4,'0'),v_location_name,v_location_type,
            p_country_code,'{}'::jsonb,p_timezone,jsonb_build_object('hierarchy_level',v_index+1))
    RETURNING id INTO v_location_id;
    v_location_ids := array_append(v_location_ids,v_location_id);
    v_index := v_index + 1;
  END LOOP;

  RETURN jsonb_build_object('organization_id',v_org_id,'activity_id',v_activity_id,
    'location_id',v_headquarters_id,'location_count',v_index+1,'commercial_registration_number',btrim(p_commercial_registration_number));
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_create_activity_profile(text,text,text,text,text,text,date,text,text,text,text,text,text,text,text,text,text,text,text,text,text,text,text,text,text,jsonb) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_create_activity_profile(text,text,text,text,text,text,date,text,text,text,text,text,text,text,text,text,text,text,text,text,text,text,text,text,text,jsonb) TO authenticated;
