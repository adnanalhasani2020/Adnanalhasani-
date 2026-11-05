\set ON_ERROR_STOP on
DO $$
DECLARE
 merchant uuid:='10000000-0000-0000-0000-000000000001';
 trader uuid:='10000000-0000-0000-0000-000000000002';
 customer uuid:='10000000-0000-0000-0000-000000000003';
 outsider uuid:='10000000-0000-0000-0000-000000000004';
 org uuid:='20000000-0000-0000-0000-000000000001';
 loc uuid:='30000000-0000-0000-0000-000000000001';
 camp uuid; result jsonb; count_rows integer; ok boolean;
BEGIN
 INSERT INTO auth.users(id,email) VALUES(merchant,'merchant@test'),(trader,'trader@test'),(customer,'customer@test'),(outsider,'outsider@test');
 INSERT INTO public.commercial_organizations(id,name,logo_url) VALUES(org,'متجر الاختبار',NULL);
 INSERT INTO public.commercial_organization_members VALUES(org,merchant,'active','owner'),(org,trader,'active','staff');
 INSERT INTO public.commercial_organization_locations VALUES(loc,org,'active',15.3694,44.1910);
 INSERT INTO public.commercial_activities VALUES('40000000-0000-0000-0000-000000000001',org,'commercial');
 INSERT INTO public.commercial_customers(id,organization_id,auth_user_id,state)
 VALUES('50000000-0000-0000-0000-000000000001',org,customer,'active');
 PERFORM set_config('request.jwt.claim.sub',merchant::text,true);
 result:=public.commercial_ad_campaigns_create(org,loc,'عرض نهاية الأسبوع','زيت طبخ 1 لتر','عرض محدود للمستهلكين',15,'تصفية مخزون','customers','commercial','female',20,45,25,now()-interval '1 minute',now()+interval '2 days');
 camp:=(result->>'campaign_id')::uuid;
 IF camp IS NULL THEN RAISE EXCEPTION 'campaign_create_failed'; END IF;
 IF jsonb_array_length(public.commercial_ad_campaigns_list_my(org,50))<>1 THEN RAISE EXCEPTION 'owner_campaign_list_failed'; END IF;
 PERFORM set_config('request.jwt.claim.sub',customer::text,true);
 result:=public.commercial_ad_campaigns_feed_my(15.3694,44.1910,'female',30,30);
 IF jsonb_array_length(result)<>1 OR result->0->>'id'<>camp::text THEN RAISE EXCEPTION 'targeted_customer_feed_failed'; END IF;
 IF jsonb_array_length(public.commercial_ad_campaigns_feed_my(15.3694,44.1910,'male',30,30))<>0 THEN RAISE EXCEPTION 'gender_filter_failed'; END IF;
 IF jsonb_array_length(public.commercial_ad_campaigns_feed_my(15.3694,44.1910,'female',50,30))<>0 THEN RAISE EXCEPTION 'age_filter_failed'; END IF;
 IF jsonb_array_length(public.commercial_ad_campaigns_feed_my(16.0,44.1910,'female',30,30))<>0 THEN RAISE EXCEPTION 'radius_filter_failed'; END IF;
 PERFORM set_config('request.jwt.claim.sub',outsider::text,true);
 IF jsonb_array_length(public.commercial_ad_campaigns_feed_my(15.3694,44.1910,'female',30,30))<>0 THEN RAISE EXCEPTION 'customer_audience_leaked'; END IF;
 PERFORM set_config('request.jwt.claim.sub',merchant::text,true);
 ok:=public.commercial_ad_campaigns_set_status_my(org,camp,'paused');
 IF NOT ok THEN RAISE EXCEPTION 'campaign_pause_failed'; END IF;
 PERFORM set_config('request.jwt.claim.sub',customer::text,true);
 IF jsonb_array_length(public.commercial_ad_campaigns_feed_my(15.3694,44.1910,'female',30,30))<>0 THEN RAISE EXCEPTION 'paused_campaign_visible'; END IF;
 PERFORM set_config('request.jwt.claim.sub',outsider::text,true);
 BEGIN
  PERFORM public.commercial_ad_campaigns_create(org,loc,'غير مصرح','عرض','',0,'','all',NULL,'all',NULL,NULL,NULL,now(),now()+interval '1 day');
  RAISE EXCEPTION 'unauthorized_campaign_create_allowed';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;
 IF has_table_privilege('anon','public.commercial_ad_campaigns','SELECT')
  OR has_table_privilege('authenticated','public.commercial_ad_campaigns','SELECT') THEN
  RAISE EXCEPTION 'direct_campaign_table_access_not_revoked';
 END IF;
END $$;
