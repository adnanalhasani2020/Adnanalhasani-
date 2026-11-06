\set ON_ERROR_STOP on
DO $$
DECLARE
 v_manager uuid:='10000000-0000-0000-0000-000000000001';
 v_staff uuid:='10000000-0000-0000-0000-000000000002';
 v_viewer uuid:='10000000-0000-0000-0000-000000000003';
 v_outsider uuid:='10000000-0000-0000-0000-000000000004';
 v_org uuid:='20000000-0000-0000-0000-000000000001';
 v_other_org uuid:='20000000-0000-0000-0000-000000000002';
 v_activity uuid:='30000000-0000-0000-0000-000000000001';
 v_other_activity uuid:='30000000-0000-0000-0000-000000000002';
 v_lead jsonb; v_lead2 jsonb; v_id uuid; v_other_id uuid;
 v_follow jsonb; v_follow_id uuid; v_list jsonb; v_updated jsonb;
BEGIN
 INSERT INTO auth.users(id,email) VALUES
  (v_manager,'manager@crm.test'),(v_staff,'staff@crm.test'),
  (v_viewer,'viewer@crm.test'),(v_outsider,'outsider@crm.test');
 INSERT INTO public.commercial_organizations(id,name) VALUES(v_org,'CRM test'),(v_other_org,'Other org');
 INSERT INTO public.commercial_activities(id,owner_user_id,name,organization_id)
 VALUES(v_activity,v_manager,'Store',v_org),(v_other_activity,v_manager,'Other store',v_other_org);
 INSERT INTO public.commercial_organization_members(organization_id,user_id,role,state) VALUES
  (v_org,v_manager,'manager','active'),(v_org,v_staff,'staff','active'),(v_org,v_viewer,'viewer','active'),
  (v_other_org,v_outsider,'manager','active');

 PERFORM set_config('request.jwt.claim.sub',v_manager::text,true);
 v_lead:=public.commercial_crm_lead_create(
  v_org,v_activity,'شركة النور','النور للتجارة','INFO@NOOR.TEST','777000111',
  'referral','مهتم بالشراء',v_manager,now()+interval '1 day','crm-lead-key-0001'
 );
 v_id:=(v_lead->>'id')::uuid;
 IF v_id IS NULL OR v_lead->>'status'<>'new' OR (v_lead->>'replayed')::boolean THEN
  RAISE EXCEPTION 'manager_lead_create_failed';
 END IF;
 v_lead2:=public.commercial_crm_lead_create(
  v_org,v_activity,'شركة النور','النور للتجارة','info@noor.test','777000111',
  'referral','مهتم بالشراء',v_manager,now()+interval '1 day','crm-lead-key-0001'
 );
 IF (v_lead2->>'id')::uuid<>v_id OR NOT (v_lead2->>'replayed')::boolean THEN
  RAISE EXCEPTION 'lead_idempotent_replay_failed';
 END IF;
 BEGIN
  PERFORM public.commercial_crm_lead_create(
   v_org,v_activity,'اسم مختلف','','','','referral','',v_staff,NULL,'crm-lead-key-0001'
  );
  RAISE EXCEPTION 'lead_idempotency_key_reuse_was_allowed';
 EXCEPTION WHEN unique_violation THEN NULL;
 END;

 v_list:=public.commercial_crm_lead_list(v_org,v_activity,100);
 IF jsonb_array_length(v_list)<>1 THEN RAISE EXCEPTION 'manager_lead_list_failed'; END IF;

 -- Staff can create and see their own assigned leads only.
 PERFORM set_config('request.jwt.claim.sub',v_staff::text,true);
 v_lead2:=public.commercial_crm_lead_create(
  v_org,v_activity,'عميل فردي','','','777111222','phone','اتصال لاحق',NULL,
  now()+interval '2 days','crm-lead-key-0002'
 );
 v_other_id:=(v_lead2->>'id')::uuid;
 v_list:=public.commercial_crm_lead_list(v_org,v_activity,100);
 IF jsonb_array_length(v_list)<>1 OR v_list->0->>'id'<>v_other_id::text THEN
  RAISE EXCEPTION 'staff_lead_scope_failed';
 END IF;
 BEGIN
  PERFORM public.commercial_crm_lead_create(
   v_org,v_activity,'تعيين غير مسموح','','','','other','',v_manager,NULL,'crm-lead-key-0003'
  );
  RAISE EXCEPTION 'staff_assignment_to_other_user_was_allowed';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;
 BEGIN
  PERFORM public.commercial_crm_lead_update(v_id,1,'contacted','',now(),NULL);
  RAISE EXCEPTION 'staff_updated_other_users_lead';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;

 -- Staff can create a follow-up for their own lead and complete it.
 v_follow:=public.commercial_crm_activity_create(
  v_org,v_activity,v_other_id,'call','اتصال بالعميل','شرح العرض',
  now()+interval '3 hours',NULL,'crm-follow-key-0001'
 );
 v_follow_id:=(v_follow->>'id')::uuid;
 IF v_follow_id IS NULL OR v_follow->>'status'<>'open' THEN RAISE EXCEPTION 'followup_create_failed'; END IF;
 v_updated:=public.commercial_crm_activity_update(v_follow_id,1,'completed','تم التواصل');
 IF v_updated->>'status'<>'completed' OR (v_updated->>'version')::integer<>2 THEN
  RAISE EXCEPTION 'followup_completion_failed';
 END IF;
 BEGIN
  PERFORM public.commercial_crm_activity_update(v_follow_id,1,'open','تحديث قديم');
  RAISE EXCEPTION 'stale_followup_version_was_allowed';
 EXCEPTION WHEN serialization_failure THEN NULL;
 END;
 v_list:=public.commercial_crm_activity_list(v_org,v_activity,100);
 IF jsonb_array_length(v_list)<>1 OR v_list->0->>'status'<>'completed' THEN
  RAISE EXCEPTION 'staff_followup_list_failed';
 END IF;

 -- Manager can update lead status and assign it to an active member.
 PERFORM set_config('request.jwt.claim.sub',v_manager::text,true);
 v_updated:=public.commercial_crm_lead_update(v_id,1,'qualified','تم التأهيل',now()+interval '2 days',v_staff);
 IF v_updated->>'status'<>'qualified' OR (v_updated->>'version')::integer<>2 THEN
  RAISE EXCEPTION 'manager_lead_update_failed';
 END IF;

 -- Viewer is read-only.
 PERFORM set_config('request.jwt.claim.sub',v_viewer::text,true);
 v_list:=public.commercial_crm_lead_list(v_org,v_activity,100);
 IF jsonb_array_length(v_list)<>2 THEN RAISE EXCEPTION 'viewer_read_scope_failed'; END IF;
 BEGIN
  PERFORM public.commercial_crm_lead_create(
   v_org,v_activity,'مرفوض','','','','other','',NULL,NULL,'crm-viewer-key-01'
  );
  RAISE EXCEPTION 'viewer_created_lead';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;

 -- An outsider from another organization cannot query this activity.
 PERFORM set_config('request.jwt.claim.sub',v_outsider::text,true);
 BEGIN
  PERFORM public.commercial_crm_lead_list(v_org,v_activity,100);
  RAISE EXCEPTION 'cross_organization_lead_list_was_allowed';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;

 -- Membership alone is insufficient when the organization has been suspended.
 UPDATE public.commercial_organizations SET state='suspended' WHERE id=v_other_org;
 BEGIN
  PERFORM public.commercial_crm_lead_list(v_other_org,v_other_activity,100);
  RAISE EXCEPTION 'suspended_organization_crm_access_was_allowed';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;

 -- RLS-only tables: no direct access, and every public RPC is authenticated-only.
 IF NOT (SELECT relrowsecurity FROM pg_class WHERE oid='public.commercial_crm_leads'::regclass)
  OR NOT (SELECT relrowsecurity FROM pg_class WHERE oid='public.commercial_crm_activities'::regclass) THEN
  RAISE EXCEPTION 'crm_rls_not_enabled';
 END IF;
 IF has_table_privilege('anon','public.commercial_crm_leads','SELECT')
  OR has_table_privilege('authenticated','public.commercial_crm_leads','SELECT')
  OR has_table_privilege('authenticated','public.commercial_crm_leads','INSERT')
  OR has_table_privilege('authenticated','public.commercial_crm_leads','UPDATE')
  OR has_table_privilege('authenticated','public.commercial_crm_leads','DELETE')
  OR has_table_privilege('anon','public.commercial_crm_activities','SELECT')
  OR has_table_privilege('authenticated','public.commercial_crm_activities','SELECT')
  OR has_table_privilege('authenticated','public.commercial_crm_activities','INSERT')
  OR has_table_privilege('authenticated','public.commercial_crm_activities','UPDATE')
  OR has_table_privilege('authenticated','public.commercial_crm_activities','DELETE') THEN
  RAISE EXCEPTION 'crm_direct_table_access_not_revoked';
 END IF;
 IF has_function_privilege('anon','public.commercial_crm_lead_create(uuid,uuid,text,text,text,text,text,text,uuid,timestamp with time zone,text)','EXECUTE')
  OR NOT has_function_privilege('authenticated','public.commercial_crm_lead_create(uuid,uuid,text,text,text,text,text,text,uuid,timestamp with time zone,text)','EXECUTE')
  OR has_function_privilege('anon','public.commercial_crm_lead_list(uuid,uuid,integer)','EXECUTE')
  OR NOT has_function_privilege('authenticated','public.commercial_crm_lead_list(uuid,uuid,integer)','EXECUTE')
  OR has_function_privilege('anon','public.commercial_crm_lead_update(uuid,integer,text,text,timestamp with time zone,uuid)','EXECUTE')
  OR NOT has_function_privilege('authenticated','public.commercial_crm_lead_update(uuid,integer,text,text,timestamp with time zone,uuid)','EXECUTE')
  OR has_function_privilege('anon','public.commercial_crm_activity_create(uuid,uuid,uuid,text,text,text,timestamp with time zone,uuid,text)','EXECUTE')
  OR NOT has_function_privilege('authenticated','public.commercial_crm_activity_create(uuid,uuid,uuid,text,text,text,timestamp with time zone,uuid,text)','EXECUTE')
  OR has_function_privilege('anon','public.commercial_crm_activity_list(uuid,uuid,integer)','EXECUTE')
  OR NOT has_function_privilege('authenticated','public.commercial_crm_activity_list(uuid,uuid,integer)','EXECUTE')
  OR has_function_privilege('anon','public.commercial_crm_activity_update(uuid,integer,text,text)','EXECUTE')
  OR NOT has_function_privilege('authenticated','public.commercial_crm_activity_update(uuid,integer,text,text)','EXECUTE')
  OR has_function_privilege('authenticated','public.commercial_crm_context_role(uuid,uuid)','EXECUTE') THEN
  RAISE EXCEPTION 'crm_function_grants_invalid';
 END IF;
END;
$$;
