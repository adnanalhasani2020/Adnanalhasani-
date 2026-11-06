\set ON_ERROR_STOP on
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
 location_id uuid:='40000000-0000-0000-0000-000000000021';
 other_location_id uuid:='40000000-0000-0000-0000-000000000022';
 asset_id uuid; declining_asset_id uuid; result jsonb; rows jsonb;
BEGIN
 INSERT INTO auth.users(id,email) VALUES
  (manager_id,'manager@asset.test'),(staff_id,'staff@asset.test'),
  (viewer_id,'viewer@asset.test'),(outsider_id,'outsider@asset.test');
 INSERT INTO public.commercial_organizations(id,name,default_currency_code) VALUES
  (org_id,'Asset Org','YER'),(other_org_id,'Other Asset Org','YER');
 INSERT INTO public.commercial_organization_members(organization_id,user_id,role,state) VALUES
  (org_id,manager_id,'manager','active'),(org_id,staff_id,'staff','active'),
  (org_id,viewer_id,'viewer','active'),(other_org_id,outsider_id,'manager','active');
 INSERT INTO public.commercial_activities(id,organization_id,owner_user_id,name) VALUES
  (activity_id,org_id,manager_id,'Main activity'),(other_activity_id,other_org_id,outsider_id,'Other activity');
 INSERT INTO public.commercial_organization_locations(id,organization_id,name) VALUES
  (location_id,org_id,'المستودع الرئيسي'),(other_location_id,other_org_id,'موقع آخر');

 PERFORM set_config('request.jwt.claim.sub',manager_id::text,true);
 result:=public.commercial_fixed_asset_create(org_id,activity_id,'VEH-001','سيارة نقل','مركبات',
  'VIN-001','سيارة توصيل',date '2026-01-01',date '2026-01-15',120000::bigint,20000::bigint,
  NULL,10,'straight_line',location_id,staff_id,'أصل للاختبار','asset-create-key-001');
 asset_id:=(result->>'id')::uuid;
 IF asset_id IS NULL OR result->>'status'<>'draft' OR (result->>'replayed')::boolean THEN
  RAISE EXCEPTION 'asset_create_failed';
 END IF;
 result:=public.commercial_fixed_asset_create(org_id,activity_id,'VEH-001','سيارة نقل','مركبات',
  'VIN-001','سيارة توصيل',date '2026-01-01',date '2026-01-15',120000::bigint,20000::bigint,
  NULL,10,'straight_line',location_id,staff_id,'أصل للاختبار','asset-create-key-001');
 IF (result->>'id')::uuid<>asset_id OR NOT (result->>'replayed')::boolean THEN
  RAISE EXCEPTION 'asset_idempotency_replay_failed';
 END IF;
 BEGIN
  PERFORM public.commercial_fixed_asset_create(org_id,activity_id,'VEH-002','تغيير','مركبات',
   'VIN-002','',date '2026-01-01',date '2026-01-15',120000::bigint,20000::bigint,
   NULL,10,'straight_line',location_id,staff_id,'','asset-create-key-001');
  RAISE EXCEPTION 'asset_idempotency_fingerprint_not_enforced';
 EXCEPTION WHEN unique_violation THEN NULL;
 END;
 BEGIN
  PERFORM public.commercial_fixed_asset_create(org_id,activity_id,'VEH-003','موقع خاطئ','مركبات',
   'VIN-003','',date '2026-01-01',date '2026-01-15',120000::bigint,20000::bigint,
   NULL,10,'straight_line',other_location_id,staff_id,'','asset-create-key-003');
  RAISE EXCEPTION 'cross_org_asset_location_was_allowed';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;
 BEGIN
  PERFORM public.commercial_fixed_asset_create(org_id,activity_id,'VEH-004','مسؤول خاطئ','مركبات',
   'VIN-004','',date '2026-01-01',date '2026-01-15',120000::bigint,20000::bigint,
   NULL,10,'straight_line',location_id,outsider_id,'','asset-create-key-004');
  RAISE EXCEPTION 'inactive_asset_custodian_was_allowed';
 EXCEPTION WHEN SQLSTATE '22023' THEN NULL;
 END;

 -- Staff and viewers can read within their activity, but cannot write.
 PERFORM set_config('request.jwt.claim.sub',staff_id::text,true);
 rows:=public.commercial_fixed_asset_list(org_id,activity_id,NULL,100);
 IF jsonb_array_length(rows)<>1 OR rows->0->>'id'<>asset_id::text THEN RAISE EXCEPTION 'staff_asset_list_failed'; END IF;
 BEGIN
  PERFORM public.commercial_fixed_asset_create(org_id,activity_id,'VEH-005','غير مسموح','مركبات',
   'VIN-005','',date '2026-01-01',date '2026-01-15',100000::bigint,0::bigint,
   NULL,10,'straight_line',NULL,NULL,'','asset-staff-key-001');
  RAISE EXCEPTION 'staff_asset_create_was_allowed';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;
 BEGIN
  PERFORM public.commercial_fixed_asset_update(asset_id,1,'active',location_id,staff_id,'',NULL,NULL);
  RAISE EXCEPTION 'staff_asset_update_was_allowed';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;
 PERFORM set_config('request.jwt.claim.sub',viewer_id::text,true);
 rows:=public.commercial_fixed_asset_list(org_id,activity_id,NULL,100);
 IF jsonb_array_length(rows)<>1 THEN RAISE EXCEPTION 'viewer_asset_read_failed'; END IF;

 PERFORM set_config('request.jwt.claim.sub',manager_id::text,true);
 result:=public.commercial_fixed_asset_update(asset_id,1,'active',location_id,staff_id,'أصل مفعل',NULL,NULL);
 IF result->>'status'<>'active' OR (result->>'version')::integer<>2 THEN RAISE EXCEPTION 'asset_activation_failed'; END IF;
 rows:=public.commercial_fixed_asset_depreciation_preview(org_id,activity_id,asset_id,600);
 IF jsonb_array_length(rows->'periods')<>10
  OR (rows->>'total_preview_depreciation_minor')::bigint<>100000
  OR (rows->>'closing_book_value_minor')::bigint<>20000 THEN
  RAISE EXCEPTION 'straight_line_depreciation_preview_failed';
 END IF;
 rows:=public.commercial_fixed_asset_depreciation_preview(org_id,activity_id,asset_id,3);
 IF jsonb_array_length(rows->'periods')<>3
  OR (rows->>'total_preview_depreciation_minor')::bigint<>30000
  OR (rows->>'closing_book_value_minor')::bigint<>90000 THEN
  RAISE EXCEPTION 'bounded_depreciation_preview_failed';
 END IF;

 result:=public.commercial_fixed_asset_create(org_id,activity_id,'MACH-001','آلة إنتاج','معدات',
  'SER-001','',date '2026-01-01',date '2026-01-01',100000::bigint,10000::bigint,
  NULL,10,'double_declining',NULL,NULL,'','asset-create-key-002');
 declining_asset_id:=(result->>'id')::uuid;
 PERFORM public.commercial_fixed_asset_update(declining_asset_id,1,'active',NULL,NULL,'',NULL,NULL);
 rows:=public.commercial_fixed_asset_depreciation_preview(org_id,activity_id,declining_asset_id,600);
 IF (rows->>'closing_book_value_minor')::bigint<>10000
  OR (rows->>'total_preview_depreciation_minor')::bigint<>90000
  OR jsonb_array_length(rows->'periods')<1 THEN
  RAISE EXCEPTION 'double_declining_depreciation_preview_failed';
 END IF;

 result:=public.commercial_fixed_asset_update(asset_id,2,'disposed',location_id,staff_id,
  'تم الاستبعاد',date '2026-10-01',15000::bigint);
 IF result->>'status'<>'disposed' OR (result->>'version')::integer<>3 THEN RAISE EXCEPTION 'asset_disposal_failed'; END IF;
 BEGIN
  PERFORM public.commercial_fixed_asset_depreciation_preview(org_id,activity_id,asset_id,600);
  RAISE EXCEPTION 'disposed_asset_preview_was_allowed';
 EXCEPTION WHEN SQLSTATE '22023' THEN NULL;
 END;
 BEGIN
  PERFORM public.commercial_fixed_asset_update(asset_id,3,'active',location_id,staff_id,'',NULL,NULL);
  RAISE EXCEPTION 'disposed_asset_reactivation_was_allowed';
 EXCEPTION WHEN SQLSTATE '22023' THEN NULL;
 END;

 PERFORM set_config('request.jwt.claim.sub',outsider_id::text,true);
 BEGIN
  PERFORM public.commercial_fixed_asset_list(org_id,activity_id,NULL,100);
  RAISE EXCEPTION 'cross_tenant_asset_list_was_allowed';
 EXCEPTION WHEN insufficient_privilege THEN NULL;
 END;

 IF NOT (SELECT relrowsecurity FROM pg_class WHERE oid='public.commercial_fixed_assets'::regclass)
  OR has_table_privilege('anon','public.commercial_fixed_assets','SELECT')
  OR has_table_privilege('authenticated','public.commercial_fixed_assets','SELECT')
  OR has_table_privilege('authenticated','public.commercial_fixed_assets','INSERT')
  OR has_table_privilege('authenticated','public.commercial_fixed_assets','UPDATE')
  OR has_table_privilege('authenticated','public.commercial_fixed_assets','DELETE') THEN
  RAISE EXCEPTION 'asset_rls_or_table_privileges_invalid';
 END IF;
 IF has_function_privilege('anon','public.commercial_fixed_asset_create(uuid,uuid,text,text,text,text,text,date,date,bigint,bigint,text,integer,text,uuid,uuid,text,text)','EXECUTE')
  OR NOT has_function_privilege('authenticated','public.commercial_fixed_asset_create(uuid,uuid,text,text,text,text,text,date,date,bigint,bigint,text,integer,text,uuid,uuid,text,text)','EXECUTE')
  OR has_function_privilege('anon','public.commercial_fixed_asset_list(uuid,uuid,text,integer)','EXECUTE')
  OR NOT has_function_privilege('authenticated','public.commercial_fixed_asset_list(uuid,uuid,text,integer)','EXECUTE')
  OR has_function_privilege('anon','public.commercial_fixed_asset_update(uuid,integer,text,uuid,uuid,text,date,bigint)','EXECUTE')
  OR NOT has_function_privilege('authenticated','public.commercial_fixed_asset_update(uuid,integer,text,uuid,uuid,text,date,bigint)','EXECUTE')
  OR has_function_privilege('anon','public.commercial_fixed_asset_depreciation_preview(uuid,uuid,uuid,integer)','EXECUTE')
  OR NOT has_function_privilege('authenticated','public.commercial_fixed_asset_depreciation_preview(uuid,uuid,uuid,integer)','EXECUTE') THEN
  RAISE EXCEPTION 'asset_rpc_grants_invalid';
 END IF;
END $$;
