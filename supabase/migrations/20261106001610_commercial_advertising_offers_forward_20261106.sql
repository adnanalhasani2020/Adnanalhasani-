BEGIN;
CREATE TABLE IF NOT EXISTS public.commercial_ads (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE CASCADE,
 location_id uuid NOT NULL REFERENCES public.commercial_organization_locations(id) ON DELETE RESTRICT,
 created_by uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
 title text NOT NULL CHECK(length(btrim(title)) BETWEEN 2 AND 160),
 product_name text NOT NULL CHECK(length(btrim(product_name)) BETWEEN 1 AND 160),
 description text NOT NULL DEFAULT '' CHECK(length(description)<=2000),
 discount_reason text NOT NULL DEFAULT '' CHECK(length(discount_reason)<=500),
 original_price_minor bigint NULL CHECK(original_price_minor IS NULL OR original_price_minor>=0),
 offer_price_minor bigint NULL CHECK(offer_price_minor IS NULL OR offer_price_minor>=0),
 currency_code text NOT NULL DEFAULT 'YER' CHECK(currency_code ~ '^[A-Z]{3}$'),
 starts_at timestamptz NOT NULL,
 ends_at timestamptz NOT NULL,
 audience_type text NOT NULL DEFAULT 'all' CHECK(audience_type IN ('consumer','trader','customer','all')),
 activity_code text NULL CHECK(activity_code IS NULL OR length(activity_code)<=100),
 gender_target text NOT NULL DEFAULT 'any' CHECK(gender_target IN ('any','male','female')),
 min_age smallint NULL CHECK(min_age IS NULL OR min_age BETWEEN 13 AND 120),
 max_age smallint NULL CHECK(max_age IS NULL OR max_age BETWEEN 13 AND 120),
 radius_km numeric(7,2) NULL CHECK(radius_km IS NULL OR radius_km BETWEEN 0.1 AND 500),
 latitude numeric(9,6) NULL CHECK(latitude IS NULL OR latitude BETWEEN -90 AND 90),
 longitude numeric(9,6) NULL CHECK(longitude IS NULL OR longitude BETWEEN -180 AND 180),
 status text NOT NULL DEFAULT 'published' CHECK(status IN ('draft','published','paused','expired')),
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT commercial_ads_dates_valid CHECK(ends_at>starts_at),
 CONSTRAINT commercial_ads_age_range_valid CHECK(min_age IS NULL OR max_age IS NULL OR min_age<=max_age),
 CONSTRAINT commercial_ads_price_valid CHECK(original_price_minor IS NULL OR offer_price_minor IS NULL OR offer_price_minor<=original_price_minor),
 CONSTRAINT commercial_ads_coordinates_pair CHECK((latitude IS NULL)=(longitude IS NULL))
);
CREATE INDEX IF NOT EXISTS commercial_ads_org_created_idx ON public.commercial_ads(organization_id,created_at DESC);
CREATE INDEX IF NOT EXISTS commercial_ads_feed_idx ON public.commercial_ads(status,starts_at,ends_at,audience_type);
ALTER TABLE public.commercial_ads ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.commercial_ads FROM PUBLIC,anon,authenticated;

CREATE OR REPLACE FUNCTION public.commercial_ads_create(
 p_organization_id uuid,p_location_id uuid,p_title text,p_product_name text,
 p_description text DEFAULT '',p_discount_reason text DEFAULT '',
 p_original_price_minor bigint DEFAULT NULL,p_offer_price_minor bigint DEFAULT NULL,
 p_currency_code text DEFAULT 'YER',p_starts_at timestamptz DEFAULT now(),
 p_ends_at timestamptz DEFAULT (now()+interval '7 days'),
 p_audience_type text DEFAULT 'all',p_activity_code text DEFAULT NULL,
 p_gender_target text DEFAULT 'any',p_min_age smallint DEFAULT NULL,
 p_max_age smallint DEFAULT NULL,p_radius_km numeric DEFAULT NULL
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $function$
DECLARE v_uid uuid:=auth.uid(); v_row public.commercial_ads;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_organization_id IS NULL OR p_location_id IS NULL THEN RAISE EXCEPTION 'ad_location_required' USING ERRCODE='22023'; END IF;
 IF NOT EXISTS(SELECT 1 FROM public.commercial_organization_members m
  WHERE m.organization_id=p_organization_id AND m.user_id=v_uid AND m.state='active'
   AND m.role IN ('owner','admin','manager')) THEN
  RAISE EXCEPTION 'ad_management_permission_required' USING ERRCODE='42501';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM public.commercial_organization_locations l
  WHERE l.id=p_location_id AND l.organization_id=p_organization_id AND l.state='active') THEN
  RAISE EXCEPTION 'active_ad_location_not_found' USING ERRCODE='P0002';
 END IF;
 IF p_title IS NULL OR length(btrim(p_title)) NOT BETWEEN 2 AND 160
  OR p_product_name IS NULL OR length(btrim(p_product_name)) NOT BETWEEN 1 AND 160
  OR coalesce(length(p_description),0)>2000 OR coalesce(length(p_discount_reason),0)>500
  OR p_currency_code !~ '^[A-Z]{3}$'
  OR p_starts_at IS NULL OR p_ends_at IS NULL OR p_ends_at<=p_starts_at
  OR p_audience_type NOT IN ('consumer','trader','customer','all')
  OR p_gender_target NOT IN ('any','male','female')
  OR (p_min_age IS NOT NULL AND p_min_age NOT BETWEEN 13 AND 120)
  OR (p_max_age IS NOT NULL AND p_max_age NOT BETWEEN 13 AND 120)
  OR (p_min_age IS NOT NULL AND p_max_age IS NOT NULL AND p_min_age>p_max_age)
  OR (p_radius_km IS NOT NULL AND p_radius_km NOT BETWEEN 0.1 AND 500)
  OR (p_original_price_minor IS NOT NULL AND p_original_price_minor<0)
  OR (p_offer_price_minor IS NOT NULL AND p_offer_price_minor<0)
  OR (p_original_price_minor IS NOT NULL AND p_offer_price_minor IS NOT NULL AND p_offer_price_minor>p_original_price_minor)
 THEN RAISE EXCEPTION 'invalid_ad_campaign' USING ERRCODE='22023'; END IF;
 INSERT INTO public.commercial_ads(
  organization_id,location_id,created_by,title,product_name,description,discount_reason,
  original_price_minor,offer_price_minor,currency_code,starts_at,ends_at,audience_type,
  activity_code,gender_target,min_age,max_age,radius_km,latitude,longitude,status
 )
 SELECT p_organization_id,p_location_id,v_uid,btrim(p_title),btrim(p_product_name),
  coalesce(p_description,''),coalesce(p_discount_reason,''),p_original_price_minor,p_offer_price_minor,
  p_currency_code,p_starts_at,p_ends_at,p_audience_type,nullif(btrim(p_activity_code),''),
  p_gender_target,p_min_age,p_max_age,p_radius_km,l.latitude,l.longitude,
  CASE WHEN p_starts_at<=now() AND p_ends_at>now() THEN 'published' ELSE 'draft' END
 FROM public.commercial_organization_locations l
 WHERE l.id=p_location_id AND l.organization_id=p_organization_id
 RETURNING * INTO v_row;
 RETURN jsonb_build_object('id',v_row.id,'status',v_row.status,'created_at',v_row.created_at);
END;$function$;

CREATE OR REPLACE FUNCTION public.commercial_ads_list_mine(p_organization_id uuid,p_limit integer DEFAULT 100)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $function$
DECLARE v_uid uuid:=auth.uid(); v_result jsonb;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_limit NOT BETWEEN 1 AND 200 THEN RAISE EXCEPTION 'invalid_ad_limit' USING ERRCODE='22023'; END IF;
 IF NOT EXISTS(SELECT 1 FROM public.commercial_organization_members m
  WHERE m.organization_id=p_organization_id AND m.user_id=v_uid AND m.state='active') THEN
  RAISE EXCEPTION 'ad_list_permission_required' USING ERRCODE='42501';
 END IF;
 SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.created_at DESC),'[]'::jsonb) INTO v_result
 FROM (SELECT id,title,product_name,description,discount_reason,original_price_minor,offer_price_minor,
  currency_code,starts_at,ends_at,audience_type,activity_code,gender_target,min_age,max_age,radius_km,status,created_at
  FROM public.commercial_ads WHERE organization_id=p_organization_id ORDER BY created_at DESC LIMIT p_limit) x;
 RETURN v_result;
END;$function$;

CREATE OR REPLACE FUNCTION public.commercial_ads_set_status(p_ad_id uuid,p_status text)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $function$
DECLARE v_uid uuid:=auth.uid(); v_count integer;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_status NOT IN ('published','paused','expired','draft') THEN RAISE EXCEPTION 'invalid_ad_status' USING ERRCODE='22023'; END IF;
 UPDATE public.commercial_ads a SET status=p_status,updated_at=now()
 WHERE a.id=p_ad_id AND EXISTS(
  SELECT 1 FROM public.commercial_organization_members m
  WHERE m.organization_id=a.organization_id AND m.user_id=v_uid AND m.state='active'
   AND m.role IN ('owner','admin','manager')
 );
 GET DIAGNOSTICS v_count=ROW_COUNT; RETURN v_count=1;
END;$function$;

CREATE OR REPLACE FUNCTION public.commercial_ads_feed(
 p_latitude numeric DEFAULT NULL,p_longitude numeric DEFAULT NULL,
 p_audience_type text DEFAULT 'all',p_activity_code text DEFAULT NULL,
 p_gender text DEFAULT NULL,p_age smallint DEFAULT NULL,p_limit integer DEFAULT 30
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $function$
DECLARE v_uid uuid:=auth.uid(); v_result jsonb;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF (p_latitude IS NULL)<>(p_longitude IS NULL)
  OR (p_latitude IS NOT NULL AND p_latitude NOT BETWEEN -90 AND 90)
  OR (p_longitude IS NOT NULL AND p_longitude NOT BETWEEN -180 AND 180)
  OR p_audience_type NOT IN ('consumer','trader','customer','all')
  OR (p_gender IS NOT NULL AND p_gender NOT IN ('male','female'))
  OR (p_age IS NOT NULL AND p_age NOT BETWEEN 13 AND 120)
  OR p_limit NOT BETWEEN 1 AND 100 THEN RAISE EXCEPTION 'invalid_ad_feed_filter' USING ERRCODE='22023'; END IF;
 SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.created_at DESC),'[]'::jsonb) INTO v_result
 FROM (
  SELECT a.id,a.organization_id,o.name AS organization_name,a.title,a.product_name,a.description,
   a.discount_reason,a.original_price_minor,a.offer_price_minor,a.currency_code,a.starts_at,a.ends_at,
   a.audience_type,a.activity_code,a.gender_target,a.min_age,a.max_age,a.radius_km,a.created_at,
   CASE WHEN p_latitude IS NOT NULL AND a.latitude IS NOT NULL THEN
    round((6371*2*asin(sqrt(least(1,
     power(sin(radians(p_latitude-a.latitude)/2),2)+
     cos(radians(p_latitude))*cos(radians(a.latitude))*power(sin(radians(p_longitude-a.longitude)/2),2)
    ))))::numeric,2) ELSE NULL END AS distance_km
  FROM public.commercial_ads a JOIN public.commercial_organizations o ON o.id=a.organization_id
  WHERE a.status='published' AND a.starts_at<=now() AND a.ends_at>now()
   AND (a.audience_type='all' OR a.audience_type=p_audience_type)
   AND (a.activity_code IS NULL OR a.activity_code=p_activity_code)
   AND (a.gender_target='any' OR a.gender_target=p_gender)
   AND (a.min_age IS NULL OR (p_age IS NOT NULL AND p_age>=a.min_age))
   AND (a.max_age IS NULL OR (p_age IS NOT NULL AND p_age<=a.max_age))
   AND (a.radius_km IS NULL OR (
     p_latitude IS NOT NULL AND a.latitude IS NOT NULL AND
     6371*2*asin(sqrt(least(1,
      power(sin(radians(p_latitude-a.latitude)/2),2)+
      cos(radians(p_latitude))*cos(radians(a.latitude))*power(sin(radians(p_longitude-a.longitude)/2),2)
     )))<=a.radius_km
   ))
  ORDER BY a.created_at DESC LIMIT p_limit
 ) x;
 RETURN v_result;
END;$function$;

REVOKE ALL ON FUNCTION public.commercial_ads_create(uuid,uuid,text,text,text,text,bigint,bigint,text,timestamptz,timestamptz,text,text,text,smallint,smallint,numeric) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_ads_list_mine(uuid,integer) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_ads_set_status(uuid,text) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_ads_feed(numeric,numeric,text,text,text,smallint,integer) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_ads_create(uuid,uuid,text,text,text,text,bigint,bigint,text,timestamptz,timestamptz,text,text,text,smallint,smallint,numeric) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_ads_list_mine(uuid,integer) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_ads_set_status(uuid,text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_ads_feed(numeric,numeric,text,text,text,smallint,integer) TO authenticated;
COMMIT;
