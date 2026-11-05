\set ON_ERROR_STOP on
DO $$
DECLARE
 u uuid:='10000000-0000-0000-0000-000000000001';
 other_u uuid:='10000000-0000-0000-0000-000000000002';
 org uuid:='20000000-0000-0000-0000-000000000001';
 note_id uuid; result jsonb; ok boolean; cnt integer;
BEGIN
 INSERT INTO auth.users(id,email) VALUES(u,'owner@example.test'),(other_u,'other@example.test');
 INSERT INTO public.commercial_organizations(id,name) VALUES(org,'متجر الاختبار');
 INSERT INTO public.commercial_organization_members VALUES(org,u,'active','owner');
 PERFORM set_config('request.jwt.claim.sub',u::text,true);
 result:=public.commercial_user_work_notes_create('متابعة مورد','اتصل بالمورد',org,now()-interval '1 minute');
 note_id:=(result->>'id')::uuid;
 IF note_id IS NULL OR result->>'status'<>'open' THEN RAISE EXCEPTION 'note_create_failed'; END IF;
 result:=public.commercial_user_work_notes_list_my(100);
 IF jsonb_array_length(result)<>1 OR result->0->>'title'<>'متابعة مورد' THEN RAISE EXCEPTION 'note_list_failed'; END IF;
 SELECT count(*) INTO cnt FROM public.commercial_user_notifications WHERE user_id=u AND entity_type='user_work_note' AND entity_id=note_id AND notification_type='reminder';
 IF cnt<>1 THEN RAISE EXCEPTION 'due_reminder_not_created'; END IF;
 PERFORM public.commercial_user_work_notes_list_my(100);
 SELECT count(*) INTO cnt FROM public.commercial_user_notifications WHERE user_id=u AND entity_type='user_work_note' AND entity_id=note_id AND notification_type='reminder';
 IF cnt<>1 THEN RAISE EXCEPTION 'reminder_not_idempotent'; END IF;
 ok:=public.commercial_user_work_notes_update_my(note_id,'متابعة المورد','تم الاتصال',NULL,'completed');
 IF NOT ok THEN RAISE EXCEPTION 'note_update_failed'; END IF;
 result:=public.commercial_user_work_notes_list_my(100);
 IF result->0->>'status'<>'completed' THEN RAISE EXCEPTION 'note_completion_failed'; END IF;
 PERFORM set_config('request.jwt.claim.sub',other_u::text,true);
 IF jsonb_array_length(public.commercial_user_work_notes_list_my(100))<>0 THEN RAISE EXCEPTION 'user_note_isolation_failed'; END IF;
 IF public.commercial_user_work_notes_update_my(note_id,'محاولة تعديل','',NULL,'open') THEN RAISE EXCEPTION 'cross_user_update_allowed'; END IF;
 IF has_table_privilege('anon','public.commercial_user_work_notes','SELECT')
  OR has_table_privilege('authenticated','public.commercial_user_work_notes','SELECT') THEN
  RAISE EXCEPTION 'direct_table_access_not_revoked';
 END IF;
END $$;
