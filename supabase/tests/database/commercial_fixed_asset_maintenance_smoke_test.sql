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
 asset_id uuid; disposed_asset_id uuid; maintenance_id uuid; manager_maintenance_id uuid;
 result jsonb; rows jsonb;
BEGIN
 INSERT INTO auth.users(id,email) VALUES
  (manager_id,'manager@maintenance.test'),(staff_id,'staff@maintenance.test'),
  (viewer_id,'viewer@maintenance.test'),(outsider_id,'outsider@maintenance.test');
 INSERT INTO public.commercial_organizations(id,name,default_currency_code) VALUES
  (org_id,'Maintenance Org','YER'),(other_org_id,'Other Maintenance Org','YER');
 INSERT INTO public.commercial_organization_members(organization_id,user_id,role,state) VALUES
  (org_id,manager_id,'manager','active'),(org_id,staff_id,'staff','active'),
  (org_id,viewer_id,'viewer','active'),(other_org_id,outsider_id,'manager','active');
 INSERT INTO public.commercial_activities(id,organization_id,owner_user_id,name) VALUES
  (activity_id,org_id,manager_id,'Main activity'),(other_activity_id,other_org_id,outsider_id,'Other activity');
 INSERT INTO public.commercial_organization_locations(id,organization_id,name) VALUES
  ('40000000-0000-0000-0000-000000000031',org_id,'الموقع الرئيسي'),
  ('40000000-0000-0000-0000-000000000032',other_org_id,'موقع آخر');

 PERFORM set_config('request.jwt.claim.sub',manager_id::text,true);
 result:=public.commercial_fixed_asset_create(org_id,activity_id,'MAINT-001','مولد كهربائي','معدات',
  'GEN-001','',date '2026-01-01',date '2026-01-01',100000::bigint,10000::bigint,
  NULL,60,'straight_line',NULL,NULL,'','maintenance-asset-key-001');
 asset_id:=(result->>'id')::uuid;
 PERFORM public.commercial_fixed_asset_update(asset_id,1,'active',NULL,NULL,'',NULL,NULL);

 result:=public.commercial_fixed_asset_maintenance_create(org_id,activity_id,asset_id,'preventive',
  'تغيير الزيت والفلاتر','صيانة دورية كل ثلاثة أشهر',date '2026-11-10',date '2026-11-12',
  staff_id,'مركز الصيانة',2500::bigint,NULL,'فحص مستوى السوائل','maintenance-key-0001');
 maintenance_id:=(result->>'id')::uuid;
 IF maintenance_id IS NULL OR result->>'status'<>'scheduled' OR (result->>'replayed')::boolean THEN
  RAISE EXCEPTION 'maintenance_create_failed';
 END IF;
 result:=public.commercial_fixed_asset_maintenance_create(org_id,activity_id,asset_id,'preventive',
  'تغيير الزيت والفلاتر','صيانة دورية كل ثلاثة أشهر',date '2026-11-10',date '2026-11-12',
  staff_id,'مركز الصيانة',2500::bigint,NULL,'فحص مستوى السوائل','maintenance-key-0001');
 IF (result->>'id')::uuid<>maintenance_id OR NOT (result->>'replayed')::boolean THEN
  RAISE EXCEPTION 'maintenance_idempotency_replay_failed';
 END IF;
 BEGIN
  PERFORM public.commercial_fixed_asset_maintenance_create(org_id,activity_id,asset_id,'corrective',
   'عنوان مختلف','',date '2026-11-10',date '2026-11-12',staff_id,'',2500::bigint,NULL,'','maintenance-key-0001');
  RAISE EXCEPTION 'maintenance_idempotency_reuse_was_allowed';
 EXCEPTION WHEN unique_violation THEN NULL;
 END;
 BEGIN
  PERFORM public.commercial_fixed_asset_maintenance_create(org_id,activity_id,asset_id,'preventive',
   'تاريخ خاطئ','',date '2026-11-12',date '2026-11-10',staff_id,'',0::bigint,NULL,'','maintenance-key-0002');
  RAISE EXCEPTION 'maintenance_due_before_schedule_was_allowed';
 EXCEPTION WHEN SQLSTATE '22023' THEN NULL;
 END;

 -- Manager can create an additional task assigned to self; staff only sees assigned tasks.
 result:=public.commercial_fixed_asset_maintenance_create(org_id,activity_id,asset_id,'inspection',
  'فحص شامل','',date '2026-11-15',NULL,manager_id,'',0::bigint,NULL,'','maintenance-key-0003');
 manager_maintenance_id:=(result->>'id')::uuid;
 PERFORM set_config('request.jwt.claim.sub',staff_id::text,true);
 rows:=public.commercial_fixed_asset_maintenance_list(org_id,activity_id,NULL,100);
 IF jsonb_array_length(rows)<>1 OR rows->0->>'id'<>maintenance_id::text THEN
  RAISE EXCEPTION 'maintenance_staff_scope_failed';
 END IF;
 result:=public.commercial_fixed_asset_maintenance_update(maintenance_id,1,'in_progress',NULL,
  'مركز الصيانة',2500::bigint,'بدأ الفني العمل');
 IF result->>'status'<>'in_progress' OR (result->>'version')::integer<>2 THEN
  RAISE EXCEPTION 'maintenance_start_failed';
 END IF;
 result:=public.commercial_fixed_asset_maintenance_update(maintenance_id,2,'completed',date '2026-11-11',
  'مركز الصيانة',2750::bigint,'تم التنفيذ بنجاح');
 IF result->>'status'<>'completed' OR (result->>'version')::integer<>3 THEN
  RAISE EXCEPTION 'maintenance_completion_failed';
 END IF;
 BEGIN
  PERFORM public.commercial_fixed_asset_maintenance_update(maintenance_id,3,'scheduled',NULL,'',0::bigint,'');
  RAISE EXCEPTION 'completed_maintenance_reopened';
 EXCEPTION WHEN SQLSTATE '22023' THEN NULL;
 END;
 BEGIN
  PERFORM public.commercial_fixed_asset_maintenance_update(manager_maintenance_id,1,'in_progress',NULL,'',0::bigint,'');
  RAISE EXCEPTION 'staff_updated_unassigned_maintenance';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;

 PERFORM set_config('request.jwt.claim.sub',viewer_id::text,true);
 rows:=public.commercial_fixed_asset_maintenance_list(org_id,activity_id,NULL,100);
 IF jsonb_array_length(rows)<>2 THEN RAISE EXCEPTION 'maintenance_viewer_read_failed'; END IF;
 BEGIN
  PERFORM public.commercial_fixed_asset_maintenance_update(manager_maintenance_id,1,'in_progress',NULL,'',0::bigint,'');
  RAISE EXCEPTION 'viewer_updated_maintenance';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;

 -- A disposed asset cannot receive a new maintenance order.
 PERFORM set_config('request.jwt.claim.sub',manager_id::text,true);
 result:=public.commercial_fixed_asset_create(org_id,activity_id,'MAINT-002','مضخة قديمة','معدات',
  'PUMP-001','',date '2026-01-01',date '2026-01-01',50000::bigint,5000::bigint,
  NULL,60,'straight_line',NULL,NULL,'','maintenance-asset-key-002');
 disposed_asset_id:=(result->>'id')::uuid;
 PERFORM public.commercial_fixed_asset_update(disposed_asset_id,1,'active',NULL,NULL,'',NULL,NULL);
 PERFORM public.commercial_fixed_asset_update(disposed_asset_id,2,'disposed',NULL,NULL,'',date '2026-11-01',1000::bigint);
 BEGIN
  PERFORM public.commercial_fixed_asset_maintenance_create(org_id,activity_id,disposed_asset_id,'corrective',
   'إصلاح أصل مستبعد','',date '2026-11-10',NULL,manager_id,'',0::bigint,NULL,'','maintenance-key-0004');
  RAISE EXCEPTION 'maintenance_for_disposed_asset_was_allowed';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;

 PERFORM set_config('request.jwt.claim.sub',outsider_id::text,true);
 BEGIN
  PERFORM public.commercial_fixed_asset_maintenance_list(org_id,activity_id,NULL,100);
  RAISE EXCEPTION 'maintenance_cross_tenant_list_was_allowed';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;

 IF NOT (SELECT relrowsecurity FROM pg_class WHERE oid='public.commercial_fixed_asset_maintenance'::regclass)
  OR has_table_privilege('anon','public.commercial_fixed_asset_maintenance','SELECT')
  OR has_table_privilege('authenticated','public.commercial_fixed_asset_maintenance','SELECT')
  OR has_table_privilege('authenticated','public.commercial_fixed_asset_maintenance','INSERT')
  OR has_table_privilege('authenticated','public.commercial_fixed_asset_maintenance','UPDATE')
  OR has_table_privilege('authenticated','public.commercial_fixed_asset_maintenance','DELETE') THEN
  RAISE EXCEPTION 'maintenance_rls_or_table_privileges_invalid';
 END IF;
 IF has_function_privilege('anon','public.commercial_fixed_asset_maintenance_create(uuid,uuid,uuid,text,text,text,date,date,uuid,text,bigint,text,text,text)','EXECUTE')
  OR NOT has_function_privilege('authenticated','public.commercial_fixed_asset_maintenance_create(uuid,uuid,uuid,text,text,text,date,date,uuid,text,bigint,text,text,text)','EXECUTE')
  OR has_function_privilege('anon','public.commercial_fixed_asset_maintenance_list(uuid,uuid,text,integer)','EXECUTE')
  OR NOT has_function_privilege('authenticated','public.commercial_fixed_asset_maintenance_list(uuid,uuid,text,integer)','EXECUTE')
  OR has_function_privilege('anon','public.commercial_fixed_asset_maintenance_update(uuid,integer,text,date,text,bigint,text)','EXECUTE')
  OR NOT has_function_privilege('authenticated','public.commercial_fixed_asset_maintenance_update(uuid,integer,text,date,text,bigint,text)','EXECUTE') THEN
  RAISE EXCEPTION 'maintenance_rpc_grants_invalid';
 END IF;
END $$;
