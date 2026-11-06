\\set ON_ERROR_STOP on
DO $$
DECLARE
 manager_id uuid:='10000000-0000-0000-0000-000000000021';
 staff_id uuid:='10000000-0000-0000-0000-000000000022';
 viewer_id uuid:='10000000-0000-0000-0000-000000000023';
 outsider_id uuid:='10000000-0000-0000-0000-000000000024';
 org_id uuid:='20000000-0000-0000-0000-000000000021';
 other_org_id uuid:='20000000-0000-0000-0000-000000000022';
 activity_id uuid:='30000000-0000-0000-0000-000000000021';
 other_activity_id uuid:='30000000-0000-0000-0000-000000000022';
 lead_result jsonb; result jsonb; lead_new uuid; lead_existing uuid; staff_lead uuid;
 new_customer uuid; existing_customer uuid; reused_customer uuid; conversion_count integer;
BEGIN
 INSERT INTO auth.users(id,email) VALUES
  (manager_id,'manager@crm-convert.test'),(staff_id,'staff@crm-convert.test'),
  (viewer_id,'viewer@crm-convert.test'),(outsider_id,'outsider@crm-convert.test');
 INSERT INTO public.commercial_organizations(id,name) VALUES
  (org_id,'CRM conversion'),(other_org_id,'Other CRM');
 INSERT INTO public.commercial_activities(id,owner_user_id,name,organization_id) VALUES
  (activity_id,manager_id,'Main activity',org_id),(other_activity_id,outsider_id,'Other activity',other_org_id);
 INSERT INTO public.commercial_organization_members(organization_id,user_id,role,state) VALUES
  (org_id,manager_id,'manager','active'),(org_id,staff_id,'staff','active'),
  (org_id,viewer_id,'viewer','active'),(other_org_id,outsider_id,'manager','active');

 -- A lead with no matching contact creates one organization-scoped customer directory row.
 PERFORM set_config('request.jwt.claim.sub',manager_id::text,true);
 lead_result:=public.commercial_crm_lead_create(org_id,activity_id,'عميل جديد','شركة الاختبار',
  'new.customer@example.test','777000001','referral','طلب عرض سعر',manager_id,NULL,'convert-lead-new-0001');
 lead_new:=(lead_result->>'id')::uuid;
 result:=public.commercial_crm_lead_convert(lead_new,1,'convert-new-customer-0001');
 new_customer:=(result->>'customer_id')::uuid;
 IF new_customer IS NULL OR (result->>'reused_existing_customer')::boolean
  OR (result->>'already_converted')::boolean OR (result->>'replayed')::boolean THEN
  RAISE EXCEPTION 'new_customer_conversion_failed';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM public.commercial_customers c WHERE c.id=new_customer
  AND c.organization_id=org_id AND c.display_name='عميل جديد'
  AND c.email='new.customer@example.test' AND c.phone='777000001'
  AND c.external_reference='crm-lead:'||lead_new::text) THEN
  RAISE EXCEPTION 'converted_customer_directory_record_invalid';
 END IF;

 -- Same key replays; a new key returns the existing conversion without another customer.
 result:=public.commercial_crm_lead_convert(lead_new,1,'convert-new-customer-0001');
 IF (result->>'customer_id')::uuid<>new_customer OR NOT (result->>'replayed')::boolean THEN
  RAISE EXCEPTION 'conversion_idempotent_replay_failed';
 END IF;
 result:=public.commercial_crm_lead_convert(lead_new,1,'convert-new-customer-0002');
 IF (result->>'customer_id')::uuid<>new_customer OR NOT (result->>'already_converted')::boolean THEN
  RAISE EXCEPTION 'already_converted_lead_created_duplicate';
 END IF;
 IF (SELECT count(*) FROM public.commercial_customers WHERE organization_id=org_id AND external_reference='crm-lead:'||lead_new::text)<>1 THEN
  RAISE EXCEPTION 'duplicate_customer_created_for_same_lead';
 END IF;

 -- An exact unique email/phone match reuses the existing customer instead of creating a duplicate.
 INSERT INTO public.commercial_customers(
  id,organization_id,display_name,email,phone,created_by
 ) VALUES('40000000-0000-0000-0000-000000000021',org_id,'عميل سابق','known@example.test','777000002',manager_id);
 existing_customer:='40000000-0000-0000-0000-000000000021';
 lead_result:=public.commercial_crm_lead_create(org_id,activity_id,'عميل معروف','',
  'KNOWN@example.test','+967 777 000 002','phone','',manager_id,NULL,'convert-lead-existing-0001');
 lead_existing:=(lead_result->>'id')::uuid;
 result:=public.commercial_crm_lead_convert(lead_existing,1,'convert-existing-customer-0001');
 reused_customer:=(result->>'customer_id')::uuid;
 IF reused_customer<>existing_customer OR NOT (result->>'reused_existing_customer')::boolean THEN
  RAISE EXCEPTION 'existing_customer_was_not_reused';
 END IF;

 -- Staff can convert their own lead but cannot convert a manager-owned lead.
 lead_result:=public.commercial_crm_lead_create(org_id,activity_id,'عميل الموظف','','','777000003',
  'phone','',NULL,NULL,'convert-lead-staff-0001');
 staff_lead:=(lead_result->>'id')::uuid;
 PERFORM set_config('request.jwt.claim.sub',staff_id::text,true);
 BEGIN
  PERFORM public.commercial_crm_lead_convert(lead_new,1,'staff-cannot-convert-manager-0001');
  RAISE EXCEPTION 'staff_converted_another_users_lead';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;
 result:=public.commercial_crm_lead_convert(staff_lead,1,'staff-convert-own-lead-0001');
 IF (result->>'customer_id') IS NULL THEN RAISE EXCEPTION 'staff_own_lead_conversion_failed'; END IF;

 -- Viewers and members of another organization cannot convert leads.
 PERFORM set_config('request.jwt.claim.sub',viewer_id::text,true);
 BEGIN
  PERFORM public.commercial_crm_lead_convert(lead_existing,1,'viewer-convert-lead-0001');
  RAISE EXCEPTION 'viewer_converted_lead';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;
 PERFORM set_config('request.jwt.claim.sub',outsider_id::text,true);
 BEGIN
  PERFORM public.commercial_crm_lead_convert(lead_new,1,'outsider-convert-lead-0001');
  RAISE EXCEPTION 'outsider_converted_lead';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;

 IF NOT (SELECT relrowsecurity FROM pg_class WHERE oid='public.commercial_crm_lead_conversions'::regclass)
  OR has_table_privilege('anon','public.commercial_crm_lead_conversions','SELECT')
  OR has_table_privilege('authenticated','public.commercial_crm_lead_conversions','SELECT')
  OR has_table_privilege('authenticated','public.commercial_crm_lead_conversions','INSERT')
  OR has_table_privilege('authenticated','public.commercial_crm_lead_conversions','UPDATE')
  OR has_table_privilege('authenticated','public.commercial_crm_lead_conversions','DELETE') THEN
  RAISE EXCEPTION 'conversion_table_privileges_invalid';
 END IF;
 IF has_function_privilege('anon','public.commercial_crm_lead_convert(uuid,integer,text)','EXECUTE')
  OR NOT has_function_privilege('authenticated','public.commercial_crm_lead_convert(uuid,integer,text)','EXECUTE') THEN
  RAISE EXCEPTION 'conversion_rpc_grants_invalid';
 END IF;
 IF (SELECT count(*) FROM public.commercial_crm_lead_conversions)<>3 THEN
  RAISE EXCEPTION 'conversion_count_invalid';
 END IF;
END $$;
