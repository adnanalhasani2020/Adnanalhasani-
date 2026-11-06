\set ON_ERROR_STOP on
DO $$
DECLARE
 manager_id uuid:='10000000-0000-0000-0000-000000000011';
 staff_id uuid:='10000000-0000-0000-0000-000000000012';
 viewer_id uuid:='10000000-0000-0000-0000-000000000013';
 outsider_id uuid:='10000000-0000-0000-0000-000000000014';
 org_id uuid:='20000000-0000-0000-0000-000000000011';
 other_org_id uuid:='20000000-0000-0000-0000-000000000012';
 activity_id uuid:='30000000-0000-0000-0000-000000000011';
 other_activity_id uuid:='30000000-0000-0000-0000-000000000012';
 lead_id uuid; staff_lead_id uuid; opp_id uuid; staff_opp_id uuid;
 result jsonb; rows jsonb;
BEGIN
 INSERT INTO auth.users(id,email) VALUES
  (manager_id,'manager@opportunity.test'),(staff_id,'staff@opportunity.test'),
  (viewer_id,'viewer@opportunity.test'),(outsider_id,'outsider@opportunity.test');
 INSERT INTO public.commercial_organizations(id,name,default_currency_code)
 VALUES(org_id,'CRM Opportunities','YER'),(other_org_id,'Other CRM','YER');
 INSERT INTO public.commercial_activities(id,owner_user_id,name,organization_id)
 VALUES(activity_id,manager_id,'Main activity',org_id),(other_activity_id,outsider_id,'Other activity',other_org_id);
 INSERT INTO public.commercial_organization_members(organization_id,user_id,role,state) VALUES
  (org_id,manager_id,'manager','active'),(org_id,staff_id,'staff','active'),
  (org_id,viewer_id,'viewer','active'),(other_org_id,outsider_id,'manager','active');

 PERFORM set_config('request.jwt.claim.sub',manager_id::text,true);
 result:=public.commercial_crm_lead_create(org_id,activity_id,'Lead A','','','777000111',
  'referral','',manager_id,NULL,'lead-opportunity-key-001');
 lead_id:=(result->>'id')::uuid;
 IF lead_id IS NULL THEN RAISE EXCEPTION 'manager_lead_setup_failed'; END IF;
 result:=public.commercial_crm_lead_create(org_id,activity_id,'Lead B','','','777000222',
  'phone','',staff_id,NULL,'lead-opportunity-key-002');
 staff_lead_id:=(result->>'id')::uuid;
 IF staff_lead_id IS NULL THEN RAISE EXCEPTION 'staff_lead_setup_failed'; END IF;

 result:=public.commercial_crm_opportunity_create(org_id,activity_id,lead_id,'توريد سنوي','qualification',
  20,500000,NULL,current_date+30,'فرصة توريد',staff_id,'crm-opportunity-key-001');
 opp_id:=(result->>'id')::uuid;
 IF opp_id IS NULL OR result->>'stage'<>'qualification' OR (result->>'replayed')::boolean THEN
  RAISE EXCEPTION 'manager_opportunity_create_failed';
 END IF;
 result:=public.commercial_crm_opportunity_create(org_id,activity_id,lead_id,'توريد سنوي','qualification',
  20,500000,NULL,current_date+30,'فرصة توريد',staff_id,'crm-opportunity-key-001');
 IF (result->>'id')::uuid<>opp_id OR NOT (result->>'replayed')::boolean THEN
  RAISE EXCEPTION 'opportunity_idempotent_replay_failed';
 END IF;
 BEGIN
  PERFORM public.commercial_crm_opportunity_create(org_id,activity_id,lead_id,'عنوان مختلف','proposal',
   50,500000,NULL,current_date+30,'',staff_id,'crm-opportunity-key-001');
  RAISE EXCEPTION 'opportunity_idempotency_reuse_was_allowed';
 EXCEPTION WHEN unique_violation THEN NULL;
 END;

 -- Staff creates a second opportunity for their own lead; the manager sees both.
 PERFORM set_config('request.jwt.claim.sub',staff_id::text,true);
 result:=public.commercial_crm_opportunity_create(org_id,activity_id,staff_lead_id,'بيع مباشر','needs_analysis',
  35,100000,NULL,current_date+15,'',NULL,'crm-opportunity-key-002');
 staff_opp_id:=(result->>'id')::uuid;
 IF staff_opp_id IS NULL THEN RAISE EXCEPTION 'staff_opportunity_create_failed'; END IF;
 rows:=public.commercial_crm_opportunity_list(org_id,activity_id,NULL,100);
 IF jsonb_array_length(rows)<>1 OR rows->0->>'id'<>staff_opp_id::text THEN
  RAISE EXCEPTION 'staff_opportunity_scope_failed';
 END IF;
 BEGIN
  PERFORM public.commercial_crm_opportunity_update(opp_id,1,'proposal',50,500000,current_date+20,'', '');
  RAISE EXCEPTION 'staff_updated_other_assigned_opportunity';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;
 BEGIN
  PERFORM public.commercial_crm_opportunity_create(org_id,activity_id,lead_id,'Reassign','proposal',
   50,100,NULL,current_date+20,'',manager_id,'crm-opportunity-key-003');
  RAISE EXCEPTION 'staff_reassigned_opportunity';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;

 PERFORM set_config('request.jwt.claim.sub',manager_id::text,true);
 rows:=public.commercial_crm_opportunity_list(org_id,activity_id,NULL,100);
 IF jsonb_array_length(rows)<>2 THEN RAISE EXCEPTION 'manager_opportunity_list_failed'; END IF;
 result:=public.commercial_crm_opportunity_update(opp_id,1,'negotiation',75,650000,current_date+45,'تم إرسال العرض','');
 IF result->>'stage'<>'negotiation' OR (result->>'version')::integer<>2 THEN
  RAISE EXCEPTION 'opportunity_update_failed';
 END IF;
 BEGIN
  PERFORM public.commercial_crm_opportunity_update(opp_id,1,'proposal',50,500000,current_date+30,'','');
  RAISE EXCEPTION 'opportunity_optimistic_version_not_enforced';
 EXCEPTION WHEN serialization_failure THEN NULL;
 END;
 BEGIN
  PERFORM public.commercial_crm_opportunity_update(opp_id,2,'lost',0,650000,current_date+45,'','');
  RAISE EXCEPTION 'opportunity_lost_reason_required';
 EXCEPTION WHEN SQLSTATE '22023' THEN NULL;
 END;
 result:=public.commercial_crm_opportunity_update(opp_id,2,'won',100,650000,current_date+45,'تم إغلاق الصفقة','');
 IF result->>'stage'<>'won' THEN RAISE EXCEPTION 'opportunity_won_transition_failed'; END IF;

 -- Viewers are read-only but can see the activity pipeline.
 PERFORM set_config('request.jwt.claim.sub',viewer_id::text,true);
 rows:=public.commercial_crm_opportunity_list(org_id,activity_id,NULL,100);
 IF jsonb_array_length(rows)<>2 THEN RAISE EXCEPTION 'viewer_opportunity_read_failed'; END IF;
 BEGIN
  PERFORM public.commercial_crm_opportunity_create(org_id,activity_id,lead_id,'Viewer attempt','qualification',
   10,0,NULL,NULL,'',NULL,'crm-viewer-opportunity-01');
  RAISE EXCEPTION 'viewer_created_opportunity';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;

 -- Cross-organization and inactive-activity contexts are rejected.
 PERFORM set_config('request.jwt.claim.sub',outsider_id::text,true);
 BEGIN
  PERFORM public.commercial_crm_opportunity_list(org_id,activity_id,NULL,100);
  RAISE EXCEPTION 'cross_tenant_opportunity_list_was_allowed';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;
 BEGIN
  PERFORM public.commercial_crm_opportunity_list(other_org_id,activity_id,NULL,100);
  RAISE EXCEPTION 'cross_activity_opportunity_list_was_allowed';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;

 IF NOT (SELECT relrowsecurity FROM pg_class WHERE oid='public.commercial_crm_opportunities'::regclass)
  OR has_table_privilege('anon','public.commercial_crm_opportunities','SELECT')
  OR has_table_privilege('authenticated','public.commercial_crm_opportunities','SELECT')
  OR has_table_privilege('authenticated','public.commercial_crm_opportunities','INSERT')
  OR has_table_privilege('authenticated','public.commercial_crm_opportunities','UPDATE')
  OR has_table_privilege('authenticated','public.commercial_crm_opportunities','DELETE') THEN
  RAISE EXCEPTION 'opportunity_rls_or_table_privileges_invalid';
 END IF;
 IF has_function_privilege('anon','public.commercial_crm_opportunity_create(uuid,uuid,uuid,text,text,smallint,bigint,text,date,text,uuid,text)','EXECUTE')
  OR NOT has_function_privilege('authenticated','public.commercial_crm_opportunity_create(uuid,uuid,uuid,text,text,smallint,bigint,text,date,text,uuid,text)','EXECUTE')
  OR has_function_privilege('anon','public.commercial_crm_opportunity_list(uuid,uuid,text,integer)','EXECUTE')
  OR NOT has_function_privilege('authenticated','public.commercial_crm_opportunity_list(uuid,uuid,text,integer)','EXECUTE')
  OR has_function_privilege('anon','public.commercial_crm_opportunity_update(uuid,integer,text,smallint,bigint,date,text,text)','EXECUTE')
  OR NOT has_function_privilege('authenticated','public.commercial_crm_opportunity_update(uuid,integer,text,smallint,bigint,date,text,text)','EXECUTE') THEN
  RAISE EXCEPTION 'opportunity_rpc_grants_invalid';
 END IF;
END $$;
