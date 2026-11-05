-- Merchant advertisements and offers with consent-based demographic targeting and optional radius.
BEGIN;
CREATE TABLE IF NOT EXISTS public.commercial_ad_campaigns (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE CASCADE,
 location_id uuid NOT NULL REFERENCES public.commercial_organization_locations(id) ON DELETE RESTRICT,
 created_by uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
 title text NOT NULL CHECK(length(btrim(title)) BETWEEN 2 AND 160),
 product_or_offer text NOT NULL CHECK(length(btrim(product_or_offer)) BETWEEN 2 AND 180),
 description text NOT NULL DEFAULT '' CHECK(length(description)<=4000),
 discount_percent numeric(5,2) NOT NULL DEFAULT 0 CHECK(discount_percent BETWEEN 0 AND 100),
 discount_reason text NOT NULL DEFAULT '' CHECK(length(discount_reason)<=500),
 target_audience text NOT NULL CHECK(target_audience IN ('all','traders','consumers','customers')),
 target_activity_type text NULL CHECK(target_activity_type IS NULL OR target_activity_type IN ('commercial','service','production')),
 target_gender text NOT NULL DEFAULT 'all' CHECK(target_gender IN ('all','male','female')),
 min_age smallint NULL CHECK(min_age IS NULL OR min_age BETWEEN 0 AND 120),
 max_age smallint NULL CHECK(max_age IS NULL OR max_age BETWEEN 0 AND 120),
 center_latitude numeric(10,7) NULL CHECK(center_latitude IS NULL OR center_latitude BETWEEN -90 AND 90),
 center_longitude numeric(10,7) NULL CHECK(center_longitude IS NULL OR center_longitude BETWEEN -180 AND 180),
 radius_km numeric(8,2) NULL CHECK(radius_km IS NULL OR radius_km BETWEEN 1 AND 500),
 starts_at timestamptz NOT NULL,
 ends_at timestamptz NOT NULL,
 status text NOT NULL DEFAULT 'active' CHECK(status IN ('active','paused','ended')),
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK(min_age IS NULL OR max_age IS NULL OR min_age<=max_age),
 CHECK(ends_at>starts_at),
 CHECK((center_latitude IS NULL)=(center_longitude IS NULL)),
 CHECK(radius_km IS NULL OR (center_latitude IS NOT NULL AND center_longitude IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS commercial_ad_campaigns_feed_idx
 ON public.commercial_ad_campaigns(status,starts_at,ends_at,created_at DESC);
CREATE INDEX IF NOT EXISTS commercial_ad_campaigns_owner_idx
 ON public.commercial_ad_campaigns(organization_id,status,created_at DESC);
ALTER TABLE public.commercial_ad_campaigns ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.commercial_ad_campaigns FROM PUBLIC,anon,authenticated;

CREATE OR REPLACE FUNCTION public.commercial_ad_campaigns_create(
 p_organization_id uuid,p_location_id uuid,p_title text,p_product_or_offer text,p_description text,
 p_discount_percent numeric,p_discount_reason text,p_target_audience text,p_target_activity_type text,
 p_target_gender text,p_min_age smallint,p_max_age smallint,p_radius_km numeric,
 p_starts_at timestamptz,p_ends_at timestamptz
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $function$
DECLARE v_uid uuid:=auth.uid(); v_lat numeric; v_lon numeric; v_id uuid;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_organization_id IS NULL OR p_location_id IS NULL OR NOT EXISTS(
  SELECT 1 FROM public.commercial_organization_members m
  WHERE m.organization_id=p_organization_id AND m.user_id=v_uid AND m.state='active'
   AND m.role IN ('owner','admin','manager')
 ) THEN RAISE EXCEPTION 'ad_campaign_permission_required' USING ERRCODE='42501'; END IF;
 IF p_title IS NULL OR length(btrim(p_title)) NOT BETWEEN 2 AND 160
  OR p_product_or_offer IS NULL OR length(btrim(p_product_or_offer)) NOT BETWEEN 2 AND 180
  OR coalesce(length(p_description),0)>4000 OR coalesce(length(p_discount_reason),0)>500
  OR p_discount_percent IS NULL OR p_discount_percent NOT BETWEEN 0 AND 100
  OR p_target_audience NOT IN ('all','traders','consumers','customers')
  OR (p_target_activity_type IS NOT NULL AND p_target_activity_type NOT IN ('commercial','service','production'))
  OR p_target_gender NOT IN ('all','male','female')
  OR (p_min_age IS NOT NULL AND p_min_age NOT BETWEEN 0 AND 120)
  OR (p_max_age IS NOT NULL AND p_max_age NOT BETWEEN 0 AND 120)
  OR (p_min_age IS NOT NULL AND p_max_age IS NOT NULL AND p_min_age>p_max_age)
  OR (p_radius_km IS NOT NULL AND p_radius_km NOT BETWEEN 1 AND 500)
  OR p_starts_at IS NULL OR p_ends_at IS NULL OR p_ends_at<=p_starts_at OR p_ends_at<=now()
 THEN RAISE EXCEPTION 'invalid_ad_campaign' USING ERRCODE='22023'; END IF;
 SELECT l.latitude,l.longitude INTO v_lat,v_lon
 FROM public.commercial_organization_locations l
 WHERE l.id=p_location_id AND l.organization_id=p_organization_id AND l.state='active';
 IF NOT FOUND THEN RAISE EXCEPTION 'active_ad_location_not_found' USING ERRCODE='P0002'; END IF;
 IF p_radius_km IS NOT NULL AND (v_lat IS NULL OR v_lon IS NULL) THEN
  RAISE EXCEPTION 'branch_coordinates_required_for_radius_targeting' USING ERRCODE='22023';
 END IF;
 INSERT INTO public.commercial_ad_campaigns(
  organization_id,location_id,created_by,title,product_or_offer,description,discount_percent,discount_reason,
  target_audience,target_activity_type,target_gender,min_age,max_age,center_latitude,center_longitude,
  radius_km,starts_at,ends_at
 ) VALUES(
  p_organization_id,p_location_id,v_uid,btrim(p_title),btrim(p_product_or_offer),coalesce(p_description,''),
  p_discount_percent,coalesce(btrim(p_discount_reason),''),p_target_audience,p_target_activity_type,
  p_target_gender,p_min_age,p_max_age,v_lat,v_lon,p_radius_km,p_starts_at,p_ends_at
 ) RETURNING id INTO v_id;
 INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
 VALUES(p_organization_id,v_uid,'advertisement.created','ad_campaign',v_id::text,
  jsonb_build_object('target_audience',p_target_audience,'target_activity_type',p_target_activity_type,
   'target_gender',p_target_gender,'min_age',p_min_age,'max_age',p_max_age,'radius_km',p_radius_km));
 RETURN jsonb_build_object('campaign_id',v_id,'status','active');
END;$function$;

CREATE OR REPLACE FUNCTION public.commercial_ad_campaigns_list_my(p_organization_id uuid,p_limit integer DEFAULT 50)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $function$
DECLARE v_uid uuid:=auth.uid(); v_result jsonb;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 100 THEN RAISE EXCEPTION 'invalid_ad_limit' USING ERRCODE='22023'; END IF;
 IF NOT EXISTS(SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=p_organization_id
  AND m.user_id=v_uid AND m.state='active' AND m.role IN ('owner','admin','manager')) THEN
  RAISE EXCEPTION 'ad_campaign_permission_required' USING ERRCODE='42501';
 END IF;
 SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.created_at DESC),'[]'::jsonb) INTO v_result
 FROM (SELECT id,title,product_or_offer,description,discount_percent,discount_reason,target_audience,
  target_activity_type,target_gender,min_age,max_age,radius_km,starts_at,ends_at,status,created_at
  FROM public.commercial_ad_campaigns WHERE organization_id=p_organization_id
  ORDER BY created_at DESC LIMIT p_limit) x;
 RETURN v_result;
END;$function$;

CREATE OR REPLACE FUNCTION public.commercial_ad_campaigns_set_status_my(
 p_organization_id uuid,p_campaign_id uuid,p_status text
) RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $function$
DECLARE v_uid uuid:=auth.uid(); v_count integer;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_status NOT IN ('active','paused','ended') OR p_campaign_id IS NULL THEN
  RAISE EXCEPTION 'invalid_ad_campaign_status' USING ERRCODE='22023';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=p_organization_id
  AND m.user_id=v_uid AND m.state='active' AND m.role IN ('owner','admin','manager')) THEN
  RAISE EXCEPTION 'ad_campaign_permission_required' USING ERRCODE='42501';
 END IF;
 UPDATE public.commercial_ad_campaigns SET status=p_status,updated_at=now()
 WHERE id=p_campaign_id AND organization_id=p_organization_id AND status<>'ended';
 GET DIAGNOSTICS v_count=ROW_COUNT;
 IF v_count=1 THEN
  INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
  VALUES(p_organization_id,v_uid,'advertisement.status_changed','ad_campaign',p_campaign_id::text,jsonb_build_object('status',p_status));
 END IF;
 RETURN v_count=1;
END;$function$;

CREATE OR REPLACE FUNCTION public.commercial_ad_campaigns_feed_my(
 p_viewer_latitude numeric DEFAULT NULL,p_viewer_longitude numeric DEFAULT NULL,
 p_viewer_gender text DEFAULT 'all',p_viewer_age smallint DEFAULT NULL,p_limit integer DEFAULT 30
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $function$
DECLARE v_uid uuid:=auth.uid(); v_result jsonb;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF (p_viewer_latitude IS NULL)<>(p_viewer_longitude IS NULL)
  OR (p_viewer_latitude IS NOT NULL AND p_viewer_latitude NOT BETWEEN -90 AND 90)
  OR (p_viewer_longitude IS NOT NULL AND p_viewer_longitude NOT BETWEEN -180 AND 180)
  OR p_viewer_gender NOT IN ('all','male','female')
  OR (p_viewer_age IS NOT NULL AND p_viewer_age NOT BETWEEN 0 AND 120)
  OR p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 50
 THEN RAISE EXCEPTION 'invalid_ad_feed_filters' USING ERRCODE='22023'; END IF;
 SELECT coalesce(jsonb_agg(jsonb_build_object(
  'id',c.id,'organization_id',c.organization_id,'merchant_name',o.name,'merchant_logo_url',o.logo_url,
  'title',c.title,'product_or_offer',c.product_or_offer,'description',c.description,
  'discount_percent',c.discount_percent,'discount_reason',c.discount_reason,
  'target_audience',c.target_audience,'starts_at',c.starts_at,'ends_at',c.ends_at,
  'created_at',c.created_at,'radius_km',c.radius_km
 ) ORDER BY c.created_at DESC),'[]'::jsonb) INTO v_result
 FROM (
  SELECT c.* FROM public.commercial_ad_campaigns c
  WHERE c.status='active' AND c.starts_at<=now() AND c.ends_at>now()
   AND (
    c.target_audience='all'
    OR (c.target_audience='traders' AND EXISTS(
      SELECT 1 FROM public.commercial_organization_members m
      WHERE m.user_id=v_uid AND m.state='active'
    ))
    OR (c.target_audience='customers' AND EXISTS(
      SELECT 1 FROM public.commercial_customers cu WHERE cu.auth_user_id=v_uid AND cu.state='active'
    ))
    OR (c.target_audience='consumers'
      AND NOT EXISTS(SELECT 1 FROM public.commercial_organization_members m WHERE m.user_id=v_uid AND m.state='active')
      AND NOT EXISTS(SELECT 1 FROM public.commercial_customers cu WHERE cu.auth_user_id=v_uid AND cu.state='active'))
   )
   AND (c.target_activity_type IS NULL OR EXISTS(
    SELECT 1 FROM public.commercial_organization_members m
    JOIN public.commercial_activities a ON a.organization_id=m.organization_id
    WHERE m.user_id=v_uid AND m.state='active' AND a.activity_type=c.target_activity_type
   ) OR EXISTS(
    SELECT 1 FROM public.commercial_customers cu
    JOIN public.commercial_activities a ON a.organization_id=cu.organization_id
    WHERE cu.auth_user_id=v_uid AND cu.state='active' AND a.activity_type=c.target_activity_type
   ))
   AND (c.target_gender='all' OR c.target_gender=p_viewer_gender)
   AND (c.min_age IS NULL OR (p_viewer_age IS NOT NULL AND p_viewer_age>=c.min_age))
   AND (c.max_age IS NULL OR (p_viewer_age IS NOT NULL AND p_viewer_age<=c.max_age))
   AND (c.radius_km IS NULL OR (
    p_viewer_latitude IS NOT NULL AND
    6371.0*2*asin(sqrt(least(1.0,greatest(0.0,
     power(sin(radians(p_viewer_latitude-c.center_latitude)/2),2)+
     cos(radians(c.center_latitude))*cos(radians(p_viewer_latitude))*
     power(sin(radians(p_viewer_longitude-c.center_longitude)/2),2)
    ))))<=c.radius_km
   ))
  ORDER BY c.created_at DESC LIMIT p_limit
 ) c JOIN public.commercial_organizations o ON o.id=c.organization_id;
 RETURN v_result;
END;$function$;

REVOKE ALL ON FUNCTION public.commercial_ad_campaigns_create(uuid,uuid,text,text,text,numeric,text,text,text,text,smallint,smallint,numeric,timestamptz,timestamptz) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_ad_campaigns_list_my(uuid,integer) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_ad_campaigns_set_status_my(uuid,uuid,text) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_ad_campaigns_feed_my(numeric,numeric,text,smallint,integer) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_ad_campaigns_create(uuid,uuid,text,text,text,numeric,text,text,text,text,smallint,smallint,numeric,timestamptz,timestamptz) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_ad_campaigns_list_my(uuid,integer) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_ad_campaigns_set_status_my(uuid,uuid,text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_ad_campaigns_feed_my(numeric,numeric,text,smallint,integer) TO authenticated;
COMMIT;
