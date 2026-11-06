\set ON_ERROR_STOP on
DO $$
DECLARE
 manager_id uuid:='10000000-0000-0000-0000-000000000031';
 staff_id uuid:='10000000-0000-0000-0000-000000000032';
 viewer_id uuid:='10000000-0000-0000-0000-000000000033';
 outsider_id uuid:='10000000-0000-0000-0000-000000000034';
 org_id uuid:='20000000-0000-0000-0000-000000000031';
 other_org_id uuid:='20000000-0000-0000-0000-000000000032';
 activity_id uuid:='30000000-0000-0000-0000-000000000031';
 other_activity_id uuid:='30000000-0000-0000-0000-000000000032';
 customer_id uuid:='40000000-0000-0000-0000-000000000031';
 other_customer_id uuid:='40000000-0000-0000-0000-000000000032';
 result jsonb;ticket_id uuid;ticket_no bigint;messages jsonb;
BEGIN
 INSERT INTO auth.users(id,email) VALUES
  (manager_id,'manager@support.test'),(staff_id,'staff@support.test'),
  (viewer_id,'viewer@support.test'),(outsider_id,'outsider@support.test');
 INSERT INTO public.commercial_organizations(id,name) VALUES
  (org_id,'Support Org'),(other_org_id,'Other Support Org');
 INSERT INTO public.commercial_activities(id,owner_user_id,name,organization_id) VALUES
  (activity_id,manager_id,'Main support activity',org_id),
  (other_activity_id,outsider_id,'Other support activity',other_org_id);
 INSERT INTO public.commercial_organization_members(organization_id,user_id,role,state) VALUES
  (org_id,manager_id,'manager','active'),(org_id,staff_id,'staff','active'),
  (org_id,viewer_id,'viewer','active'),(other_org_id,outsider_id,'manager','active');
 INSERT INTO public.commercial_customers(id,organization_id,display_name,created_by) VALUES
  (customer_id,org_id,'عميل المتجر',manager_id),
  (other_customer_id,other_org_id,'عميل آخر',outsider_id);

 PERFORM set_config('request.jwt.claim.sub',manager_id::text,true);
 result:=public.commercial_support_ticket_create(org_id,activity_id,customer_id,
  'مشكلة في المنتج','العميل أبلغ عن عطل في المنتج بعد الشراء.','product','high',
  staff_id,'whatsapp','support-ticket-create-0001');
 ticket_id:=(result->>'id')::uuid;ticket_no:=(result->>'ticket_no')::bigint;
 IF ticket_id IS NULL OR ticket_no IS NULL OR result->>'status'<>'open'
  OR (result->>'replayed')::boolean THEN RAISE EXCEPTION 'support_ticket_create_failed'; END IF;

 result:=public.commercial_support_ticket_create(org_id,activity_id,customer_id,
  'مشكلة في المنتج','العميل أبلغ عن عطل في المنتج بعد الشراء.','product','high',
  staff_id,'whatsapp','support-ticket-create-0001');
 IF (result->>'id')::uuid<>ticket_id OR NOT (result->>'replayed')::boolean THEN
  RAISE EXCEPTION 'support_ticket_idempotency_replay_failed';
 END IF;
 BEGIN
  PERFORM public.commercial_support_ticket_create(org_id,activity_id,customer_id,
   'موضوع مختلف','نص مختلف','product','high',staff_id,'whatsapp','support-ticket-create-0001');
  RAISE EXCEPTION 'support_ticket_idempotency_conflict_not_detected';
 EXCEPTION WHEN unique_violation THEN NULL;
 END;
 BEGIN
  PERFORM public.commercial_support_ticket_create(org_id,activity_id,other_customer_id,
   'عميل خارج المؤسسة','تفاصيل','general','normal',staff_id,'internal','support-ticket-cross-customer-001');
  RAISE EXCEPTION 'cross_org_customer_ticket_was_allowed';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;
 BEGIN
  PERFORM public.commercial_support_ticket_create(org_id,other_activity_id,customer_id,
   'نشاط آخر','تفاصيل','general','normal',staff_id,'internal','support-ticket-cross-activity-001');
  RAISE EXCEPTION 'cross_activity_ticket_was_allowed';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;

 PERFORM set_config('request.jwt.claim.sub',staff_id::text,true);
 result:=public.commercial_support_ticket_list(org_id,activity_id,NULL,100);
 IF jsonb_array_length(result)<>1 OR result->0->>'id'<>ticket_id::text
  OR result->0->>'customer_name'<>'عميل المتجر' THEN
  RAISE EXCEPTION 'assigned_staff_ticket_list_failed';
 END IF;
 result:=public.commercial_support_ticket_message_create(ticket_id,'بدأت فحص المنتج.','support-message-create-0001');
 IF result->>'replayed'<>'false' THEN RAISE EXCEPTION 'support_message_create_failed'; END IF;
 result:=public.commercial_support_ticket_message_create(ticket_id,'بدأت فحص المنتج.','support-message-create-0001');
 IF result->>'replayed'<>'true' THEN RAISE EXCEPTION 'support_message_replay_failed'; END IF;
 messages:=public.commercial_support_ticket_message_list(ticket_id,100);
 IF jsonb_array_length(messages)<>1 OR messages->0->>'body'<>'بدأت فحص المنتج.' THEN
  RAISE EXCEPTION 'support_message_list_failed';
 END IF;
 result:=public.commercial_support_ticket_update(ticket_id,2,'in_progress','high',staff_id);
 IF result->>'status'<>'in_progress' OR (result->>'version')::integer<>3 THEN
  RAISE EXCEPTION 'staff_ticket_status_update_failed';
 END IF;
 BEGIN
  PERFORM public.commercial_support_ticket_update(ticket_id,3,'closed','high',staff_id);
  RAISE EXCEPTION 'staff_closed_ticket';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;

 PERFORM set_config('request.jwt.claim.sub',viewer_id::text,true);
 result:=public.commercial_support_ticket_list(org_id,activity_id,NULL,100);
 IF jsonb_array_length(result)<>1 THEN RAISE EXCEPTION 'viewer_ticket_read_failed'; END IF;
 BEGIN
  PERFORM public.commercial_support_ticket_message_create(ticket_id,'رسالة غير مسموحة','viewer-message-key-001');
  RAISE EXCEPTION 'viewer_added_ticket_message';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;
 BEGIN
  PERFORM public.commercial_support_ticket_create(org_id,activity_id,NULL,'غير مسموح','تفاصيل',
   'general','normal',viewer_id,'internal','viewer-ticket-create-001');
  RAISE EXCEPTION 'viewer_created_ticket';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;

 PERFORM set_config('request.jwt.claim.sub',manager_id::text,true);
 result:=public.commercial_support_ticket_update(ticket_id,3,'resolved','urgent',manager_id);
 IF result->>'status'<>'resolved' OR result->>'priority'<>'urgent'
  OR (result->>'version')::integer<>4 THEN RAISE EXCEPTION 'manager_ticket_update_failed'; END IF;

 PERFORM set_config('request.jwt.claim.sub',staff_id::text,true);
 result:=public.commercial_support_ticket_list(org_id,activity_id,NULL,100);
 IF jsonb_array_length(result)<>0 THEN RAISE EXCEPTION 'reassigned_ticket_visible_to_previous_assignee'; END IF;

 PERFORM set_config('request.jwt.claim.sub',manager_id::text,true);
 result:=public.commercial_support_ticket_message_create(ticket_id,'ملاحظة قبل الإغلاق','support-message-before-close-001');
 IF result->>'replayed'<>'false' THEN RAISE EXCEPTION 'manager_preclose_message_failed'; END IF;
 result:=public.commercial_support_ticket_update(ticket_id,5,'closed','urgent',manager_id);
 IF result->>'status'<>'closed' OR (result->>'version')::integer<>6 THEN
  RAISE EXCEPTION 'manager_close_ticket_failed';
 END IF;
 result:=public.commercial_support_ticket_message_create(ticket_id,'ملاحظة قبل الإغلاق','support-message-before-close-001');
 IF result->>'replayed'<>'true' THEN RAISE EXCEPTION 'closed_ticket_message_replay_failed'; END IF;
 BEGIN
  PERFORM public.commercial_support_ticket_message_create(ticket_id,'رسالة جديدة بعد الإغلاق','support-message-closed-001');
  RAISE EXCEPTION 'closed_ticket_accepted_new_message';
 EXCEPTION WHEN SQLSTATE '22023' THEN NULL;
 END;

 PERFORM set_config('request.jwt.claim.sub',outsider_id::text,true);
 BEGIN
  PERFORM public.commercial_support_ticket_list(org_id,activity_id,NULL,100);
  RAISE EXCEPTION 'cross_tenant_ticket_list_was_allowed';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;

 IF NOT (SELECT relrowsecurity FROM pg_class WHERE oid='public.commercial_support_tickets'::regclass)
  OR NOT (SELECT relrowsecurity FROM pg_class WHERE oid='public.commercial_support_ticket_messages'::regclass)
  OR has_table_privilege('anon','public.commercial_support_tickets','SELECT')
  OR has_table_privilege('authenticated','public.commercial_support_tickets','SELECT')
  OR has_table_privilege('authenticated','public.commercial_support_tickets','INSERT')
  OR has_table_privilege('authenticated','public.commercial_support_tickets','UPDATE')
  OR has_table_privilege('authenticated','public.commercial_support_tickets','DELETE')
  OR has_table_privilege('anon','public.commercial_support_ticket_messages','SELECT')
  OR has_table_privilege('authenticated','public.commercial_support_ticket_messages','SELECT')
  OR has_table_privilege('authenticated','public.commercial_support_ticket_messages','INSERT')
  OR has_table_privilege('authenticated','public.commercial_support_ticket_messages','UPDATE')
  OR has_table_privilege('authenticated','public.commercial_support_ticket_messages','DELETE') THEN
  RAISE EXCEPTION 'support_ticket_table_privileges_invalid';
 END IF;
 IF has_function_privilege('anon','public.commercial_support_ticket_create(uuid,uuid,uuid,text,text,text,text,uuid,text,text)','EXECUTE')
  OR NOT has_function_privilege('authenticated','public.commercial_support_ticket_create(uuid,uuid,uuid,text,text,text,text,uuid,text,text)','EXECUTE')
  OR has_function_privilege('anon','public.commercial_support_ticket_update(uuid,integer,text,text,uuid)','EXECUTE')
  OR NOT has_function_privilege('authenticated','public.commercial_support_ticket_update(uuid,integer,text,text,uuid)','EXECUTE')
  OR has_function_privilege('anon','public.commercial_support_ticket_message_create(uuid,text,text)','EXECUTE')
  OR NOT has_function_privilege('authenticated','public.commercial_support_ticket_message_create(uuid,text,text)','EXECUTE')
  OR has_function_privilege('anon','public.commercial_support_ticket_list(uuid,uuid,text,integer)','EXECUTE')
  OR NOT has_function_privilege('authenticated','public.commercial_support_ticket_list(uuid,uuid,text,integer)','EXECUTE')
  OR has_function_privilege('anon','public.commercial_support_ticket_message_list(uuid,integer)','EXECUTE')
  OR NOT has_function_privilege('authenticated','public.commercial_support_ticket_message_list(uuid,integer)','EXECUTE')
  OR has_function_privilege('anon','public.commercial_support_my_role(uuid,uuid)','EXECUTE')
  OR NOT has_function_privilege('authenticated','public.commercial_support_my_role(uuid,uuid)','EXECUTE') THEN
  RAISE EXCEPTION 'support_ticket_rpc_grants_invalid';
 END IF;
END $$;
