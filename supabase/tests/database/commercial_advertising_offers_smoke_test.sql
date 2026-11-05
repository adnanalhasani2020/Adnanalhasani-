\set ON_ERROR_STOP on
DO $$
DECLARE
 u uuid:='10000000-0000-0000-0000-000000000001';
 other_u uuid:='10000000-0000-0000-0000-000000000002';
 org uuid:='20000000-0000-0000-0000-000000000001';
 loc uuid:='30000000-0000-0000-0000-000000000001';
 result jsonb; ad_id uuid; ok boolean;
BEGIN
 INSERT INTO auth.users(id,email) VALUES(u,'owner@example.test'),(other_u,'other@example.test');
 INSERT INTO public.commercial_organizations(id,name) VALUES(org,'متجر الاختبار');
 INSERT INTO public.commercial_organization_locations VALUES(loc,org,'active',15.3694,44.1910);
 INSERT INTO public.commercial_organization_members VALUES(org,u,'owner','active');
 PERFORM set_config('request.jwt.claim.sub',u::text,true);
 result:=public.commercial_ads_create(org,loc,'عرض نهاية الأسبوع','سكر 1 كجم','عرض خاص','تخفيض موسمي',1000,800,'YER',now()-interval '1 minute',now()+interval '2 days','consumer',NULL,'any',NULL,NULL,15);
 ad_id:=(result->>'id')::uuid;
 IF ad_id IS NULL OR result->>'status'<>'published' THEN RAISE EXCEPTION 'ad_create_failed'; END IF;
 result:=public.commercial_ads_list_mine(org,100);
 IF jsonb_array_length(result)<>1 OR result->0->>'title'<>'عرض نهاية الأسبوع' THEN RAISE EXCEPTION 'ad_list_failed'; END IF;
 result:=public.commercial_ads_feed(15.3694,44.1910,'consumer',NULL,NULL,NULL,30);
 IF jsonb_array_length(result)<>1 THEN RAISE EXCEPTION 'nearby_feed_should_contain_ad'; END IF;
 result:=public.commercial_ads_feed(15.50,44.50,'consumer',NULL,NULL,NULL,30);
 IF jsonb_array_length(result)<>0 THEN RAISE EXCEPTION 'out_of_radius_ad_leaked'; END IF;
 result:=public.commercial_ads_feed(15.3694,44.1910,'trader',NULL,NULL,NULL,30);
 IF jsonb_array_length(result)<>0 THEN RAISE EXCEPTION 'audience_filter_failed'; END IF;
 ok:=public.commercial_ads_set_status(ad_id,'paused');
 IF NOT ok THEN RAISE EXCEPTION 'ad_pause_failed'; END IF;
 result:=public.commercial_ads_feed(15.3694,44.1910,'consumer',NULL,NULL,NULL,30);
 IF jsonb_array_length(result)<>0 THEN RAISE EXCEPTION 'paused_ad_still_visible'; END IF;
 PERFORM set_config('request.jwt.claim.sub',other_u::text,true);
 BEGIN
  PERFORM public.commercial_ads_list_mine(org,100);
  RAISE EXCEPTION 'unauthorized_ad_list_was_allowed';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;
 IF public.commercial_ads_set_status(ad_id,'published') THEN RAISE EXCEPTION 'unauthorized_ad_status_change'; END IF;
 IF has_table_privilege('anon','public.commercial_ads','SELECT') OR has_table_privilege('authenticated','public.commercial_ads','SELECT')
 THEN RAISE EXCEPTION 'direct_table_access_not_revoked'; END IF;
END $$;
