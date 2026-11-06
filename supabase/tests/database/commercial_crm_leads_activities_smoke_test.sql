DO $$
DECLARE
 manager_id uuid:=gen_random_uuid(); staff_id uuid:=gen_random_uuid(); outsider_id uuid:=gen_random_uuid();
 org_id uuid:=gen_random_uuid(); other_org_id uuid:=gen_random_uuid(); activity_id uuid:=gen_random_uuid();
 lead_id uuid; other_lead_id uuid; activity_row_id uuid; result jsonb; version_no integer;
BEGIN
 INSERT INTO auth.users VALUES(manager_id),(staff_id),(outsider_id);
 INSERT INTO public.commercial_organizations(id,name,default_currency_code) VALUES
  (org_id,'CRM Test','YER'),(other_org_id,'Other Tenant','YER');
 INSERT INTO public.commercial_organization_members VALUES
  (org_id,manager_id,'manager','active'),(org_id,staff_id,'staff','active'),
  (other_org_id,outsider_id,'owner','active');
 INSERT INTO public.commercial_activities(id,organization_id,name) VALUES(activity_id,org_id,'متجر التجزئة');

 PERFORM set_config('request.jwt.claim.sub',manager_id::text,true);
 result:=public.commercial_crm_create_lead(org_id,activity_id,'عميل محتمل','شركة النور','777123456','lead@example.test',
  'referral',150000,'YER',now()+interval '1 day','مهتم بشراء جملة',staff_id,'crm-lead-key-0001');
 lead_id:=(result->>'id')::uuid;
 IF lead_id IS NULL OR result->>'stage'<>'new' OR (result->>'replayed')::boolean THEN RAISE EXCEPTION 'crm_create_lead_failed'; END IF;
 result:=public.commercial_crm_create_lead(org_id,activity_id,'عميل محتمل','شركة النور','777123456','lead@example.test',
  'referral',150000,'YER',now()+interval '1 day','مهتم بشراء جملة',staff_id,'crm-lead-key-0001');
 IF result->>'id'<>lead_id::text OR NOT (result->>'replayed')::boolean THEN RAISE EXCEPTION 'crm_create_idempotency_failed'; END IF;
 BEGIN
  PERFORM public.commercial_crm_create_lead(org_id,activity_id,'تغيير غير مسموح','شركة النور','','','other',
   0,'YER',NULL,'',staff_id,'crm-lead-key-0001');
  RAISE EXCEPTION 'crm_idempotency_fingerprint_not_enforced';
 EXCEPTION WHEN unique_violation THEN NULL;
 END;

 result:=public.commercial_crm_list_leads(org_id,NULL,100);
 IF jsonb_array_length(result)<>1 OR result->0->>'id'<>lead_id::text THEN RAISE EXCEPTION 'manager_lead_list_failed'; END IF;

 PERFORM set_config('request.jwt.claim.sub',staff_id::text,true);
 result:=public.commercial_crm_list_leads(org_id,NULL,100);
 IF jsonb_array_length(result)<>1 THEN RAISE EXCEPTION 'assigned_staff_lead_list_failed'; END IF;
 result:=public.commercial_crm_add_activity(org_id,lead_id,'call','مكالمة أولى','تم التواصل مع العميل',now()+interval '2 hours',staff_id,'crm-activity-key-0001');
 activity_row_id:=(result->>'id')::uuid;
 IF activity_row_id IS NULL THEN RAISE EXCEPTION 'crm_activity_create_failed'; END IF;
 result:=public.commercial_crm_list_activities(org_id,lead_id,100);
 IF jsonb_array_length(result)<>1 OR result->0->>'subject'<>'مكالمة أولى' THEN RAISE EXCEPTION 'crm_activity_list_failed'; END IF;
 result:=public.commercial_crm_update_lead(org_id,lead_id,'contacted','تم التواصل',now()+interval '1 day','',150000,1);
 IF result->>'stage'<>'contacted' OR (result->>'version')::integer<>2 THEN RAISE EXCEPTION 'crm_stage_update_failed'; END IF;
 BEGIN
  PERFORM public.commercial_crm_update_lead(org_id,lead_id,'qualified','',NULL,'',150000,1);
  RAISE EXCEPTION 'crm_optimistic_version_not_enforced';
 EXCEPTION WHEN SQLSTATE '40001' THEN NULL;
 END;
 BEGIN
  PERFORM public.commercial_crm_update_lead(org_id,lead_id,'lost','',NULL,'',150000,2);
  RAISE EXCEPTION 'crm_lost_reason_must_be_required';
 EXCEPTION WHEN SQLSTATE '22023' THEN NULL;
 END;

 -- A staff user cannot reassign work to another member or access a lead outside their assignment.
 BEGIN
  PERFORM public.commercial_crm_create_lead(org_id,NULL,'تعيين غير مسموح','','','','other',0,'YER',NULL,'',manager_id,'crm-staff-assignment-001');
  RAISE EXCEPTION 'crm_staff_reassignment_was_allowed';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;

 PERFORM set_config('request.jwt.claim.sub',manager_id::text,true);
 result:=public.commercial_crm_create_lead(org_id,NULL,'Lead manager only','','','','other',0,'YER',NULL,'',manager_id,'crm-lead-key-0002');
 other_lead_id:=(result->>'id')::uuid;
 PERFORM set_config('request.jwt.claim.sub',staff_id::text,true);
 BEGIN
  PERFORM public.commercial_crm_update_lead(org_id,other_lead_id,'contacted','',NULL,'',0,1);
  RAISE EXCEPTION 'crm_staff_access_to_unassigned_lead_was_allowed';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;

 PERFORM set_config('request.jwt.claim.sub',outsider_id::text,true);
 BEGIN
  PERFORM public.commercial_crm_list_leads(org_id,NULL,100);
  RAISE EXCEPTION 'crm_cross_tenant_list_was_allowed';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;

 IF has_table_privilege('anon','public.commercial_crm_leads','SELECT')
  OR has_table_privilege('authenticated','public.commercial_crm_leads','SELECT')
  OR has_table_privilege('authenticated','public.commercial_crm_leads','INSERT')
  OR has_table_privilege('authenticated','public.commercial_crm_activities','SELECT')
  OR has_table_privilege('authenticated','public.commercial_crm_activities','INSERT') THEN
  RAISE EXCEPTION 'crm_direct_table_access_not_revoked';
 END IF;
 IF NOT (SELECT relrowsecurity FROM pg_class WHERE oid='public.commercial_crm_leads'::regclass)
  OR NOT (SELECT relrowsecurity FROM pg_class WHERE oid='public.commercial_crm_activities'::regclass) THEN
  RAISE EXCEPTION 'crm_rls_must_be_enabled';
 END IF;
 IF has_function_privilege('anon','public.commercial_crm_create_lead(uuid,uuid,text,text,text,text,text,bigint,text,timestamptz,text,uuid,text)','EXECUTE')
  OR NOT has_function_privilege('authenticated','public.commercial_crm_create_lead(uuid,uuid,text,text,text,text,text,bigint,text,timestamptz,text,uuid,text)','EXECUTE') THEN
  RAISE EXCEPTION 'crm_rpc_grants_invalid';
 END IF;
END $$;
