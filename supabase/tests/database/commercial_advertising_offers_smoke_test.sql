\set ON_ERROR_STOP on
DO $$
DECLARE
 u uuid:='10000000-0000-0000-0000-000000000001';
 staff uuid:='10000000-0000-0000-0000-000000000002';
 other_u uuid:='10000000-0000-0000-0000-000000000003';
 org uuid:='20000000-0000-0000-0000-000000000001';
 other_org uuid:='20000000-0000-0000-0000-000000000002';
 loc uuid:='30000000-0000-0000-0000-000000000001';
 other_loc uuid:='30000000-0000-0000-0000-000000000002';
 result jsonb; ad_id uuid; ok boolean;
BEGIN
 INSERT INTO auth.users(id,email) VALUES
  (u,'manager@example.test'),(staff,'staff@example.test'),(other_u,'other@example.test');
 INSERT INTO public.commercial_organizations(id,name) VALUES
  (org,'متجر الاختبار'),(other_org,'متجر آخر');
 INSERT INTO public.commercial_organization_locations VALUES
  (loc,org,'active',15.3694,44.1910),
  (other_loc,other_org,'active',15.3694,44.1910);
 INSERT INTO public.commercial_organization_members VALUES
  (org,u,'manager','active'),(org,staff,'staff','active');
 PERFORM set_config('request.jwt.claim.sub',u::text,true);
 result:=public.commercial_ads_create(
  org,loc,'عرض نهاية الأسبوع','سكر 1 كجم','عرض خاص','تخفيض موسمي',
  1000,800,'YER',now()-interval '1 minute',now()+interval '2 days',
  'consumer','retail','any',18::smallint,65::smallint,15::numeric
 );
 ad_id:=(result->>'id')::uuid;
 IF ad_id IS NULL OR result->>'status'<>'published' THEN RAISE EXCEPTION 'ad_create_failed'; END IF;

 result:=public.commercial_ads_list_mine(org,100);
 IF jsonb_array_length(result)<>1 OR result->0->>'title'<>'عرض نهاية الأسبوع' THEN RAISE EXCEPTION 'ad_list_failed'; END IF;

 -- Nearby feed must honor audience, activity, age, gender, and radius constraints.
 result:=public.commercial_ads_feed(15.3694,44.1910,'consumer','retail','male',30::smallint,30);
 IF jsonb_array_length(result)<>1 THEN RAISE EXCEPTION 'nearby_targeted_feed_should_contain_ad'; END IF;
 result:=public.commercial_ads_feed(15.50,44.50,'consumer','retail','male',30::smallint,30);
 IF jsonb_array_length(result)<>0 THEN RAISE EXCEPTION 'out_of_radius_ad_leaked'; END IF;
 result:=public.commercial_ads_feed(15.3694,44.1910,'trader','retail','male',30::smallint,30);
 IF jsonb_array_length(result)<>0 THEN RAISE EXCEPTION 'audience_filter_failed'; END IF;
 result:=public.commercial_ads_feed(15.3694,44.1910,'consumer','other','male',30::smallint,30);
 IF jsonb_array_length(result)<>0 THEN RAISE EXCEPTION 'activity_filter_failed'; END IF;
 result:=public.commercial_ads_feed(15.3694,44.1910,'consumer','retail','male',17::smallint,30);
 IF jsonb_array_length(result)<>0 THEN RAISE EXCEPTION 'minimum_age_filter_failed'; END IF;

 -- Staff can read their organization's ads but cannot create or modify campaigns.
 PERFORM set_config('request.jwt.claim.sub',staff::text,true);
 result:=public.commercial_ads_list_mine(org,100);
 IF jsonb_array_length(result)<>1 THEN RAISE EXCEPTION 'staff_ad_list_failed'; END IF;
 BEGIN
  PERFORM public.commercial_ads_create(
   org,loc,'إنشاء غير مسموح','منتج','','',NULL,NULL,'YER',
   now(),now()+interval '1 day','all',NULL,'any',NULL,NULL,NULL
  );
  RAISE EXCEPTION 'staff_ad_creation_was_allowed';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;
 IF public.commercial_ads_set_status(ad_id,'paused') THEN RAISE EXCEPTION 'staff_ad_status_change_was_allowed'; END IF;

 PERFORM set_config('request.jwt.claim.sub',u::text,true);
 ok:=public.commercial_ads_set_status(ad_id,'paused');
 IF NOT ok THEN RAISE EXCEPTION 'ad_pause_failed'; END IF;
 result:=public.commercial_ads_feed(15.3694,44.1910,'consumer','retail','male',30::smallint,30);
 IF jsonb_array_length(result)<>0 THEN RAISE EXCEPTION 'paused_ad_still_visible'; END IF;
 IF NOT public.commercial_ads_set_status(ad_id,'published') THEN RAISE EXCEPTION 'ad_republish_failed'; END IF;

 -- A signed-in user outside the merchant organization may see published feed items,
 -- but may not inspect that merchant's private campaign list.
 PERFORM set_config('request.jwt.claim.sub',other_u::text,true);
 result:=public.commercial_ads_feed(15.3694,44.1910,'consumer','retail','male',30::smallint,30);
 IF jsonb_array_length(result)<>1 THEN RAISE EXCEPTION 'authenticated_public_feed_unavailable'; END IF;
 BEGIN
  PERFORM public.commercial_ads_list_mine(org,100);
  RAISE EXCEPTION 'unauthorized_ad_list_was_allowed';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;
 IF public.commercial_ads_set_status(ad_id,'paused') THEN RAISE EXCEPTION 'unauthorized_ad_status_change'; END IF;

 -- A manager cannot attach an ad to another organization's location.
 PERFORM set_config('request.jwt.claim.sub',u::text,true);
 BEGIN
  PERFORM public.commercial_ads_create(
   org,other_loc,'نطاق خاطئ','منتج','','',NULL,NULL,'YER',
   now(),now()+interval '1 day','all',NULL,'any',NULL,NULL,NULL
  );
  RAISE EXCEPTION 'cross_organization_ad_location_was_allowed';
 EXCEPTION WHEN no_data_found THEN NULL;
 END;

 IF has_table_privilege('anon','public.commercial_ads','SELECT')
  OR has_table_privilege('authenticated','public.commercial_ads','SELECT')
  OR has_table_privilege('authenticated','public.commercial_ads','INSERT')
  OR has_table_privilege('authenticated','public.commercial_ads','UPDATE') THEN
  RAISE EXCEPTION 'direct_table_access_not_revoked';
 END IF;
 IF NOT (SELECT c.relrowsecurity FROM pg_class c WHERE c.oid='public.commercial_ads'::regclass) THEN
  RAISE EXCEPTION 'ads_rls_must_be_enabled';
 END IF;
 IF has_function_privilege('anon','public.commercial_ads_create(uuid,uuid,text,text,text,text,bigint,bigint,text,timestamptz,timestamptz,text,text,text,smallint,smallint,numeric)','EXECUTE')
  OR NOT has_function_privilege('authenticated','public.commercial_ads_create(uuid,uuid,text,text,text,text,bigint,bigint,text,timestamptz,timestamptz,text,text,text,smallint,smallint,numeric)','EXECUTE') THEN
  RAISE EXCEPTION 'ads_rpc_grants_invalid';
 END IF;
END $$;
