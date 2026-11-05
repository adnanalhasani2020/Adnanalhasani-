\set ON_ERROR_STOP on
DO $$
DECLARE
 v_user uuid:='21000000-0000-0000-0000-000000000001';
 v_other uuid:='21000000-0000-0000-0000-000000000002';
 v_note uuid; v_result jsonb; v_ok boolean;
BEGIN
 INSERT INTO auth.users(id,email) VALUES(v_user,'notes@example.test'),(v_other,'other@example.test');
 PERFORM set_config('request.jwt.claim.sub',v_user::text,true);
 v_result:=public.commercial_personal_notes_save_my(NULL,'متابعة طلب المورد','اتصل بالمورد غدًا',
   now()+interval '1 day',now()+interval '12 hours');
 v_note:=(v_result->>'id')::uuid;
 IF v_note IS NULL OR v_result->>'title'<>'متابعة طلب المورد' THEN RAISE EXCEPTION 'note_create_failed'; END IF;
 v_result:=public.commercial_personal_notes_list_my(false,100);
 IF jsonb_array_length(v_result)<>1 OR v_result->0->>'body'<>'اتصل بالمورد غدًا' THEN RAISE EXCEPTION 'note_list_failed'; END IF;
 PERFORM set_config('request.jwt.claim.sub',v_other::text,true);
 IF jsonb_array_length(public.commercial_personal_notes_list_my(false,100))<>0 THEN RAISE EXCEPTION 'cross_user_note_leak'; END IF;
 v_ok:=public.commercial_personal_notes_complete_my(v_note,true);
 IF v_ok THEN RAISE EXCEPTION 'other_user_completed_note'; END IF;
 PERFORM set_config('request.jwt.claim.sub',v_user::text,true);
 v_ok:=public.commercial_personal_notes_complete_my(v_note,true);
 IF NOT v_ok OR jsonb_array_length(public.commercial_personal_notes_list_my(false,100))<>0 THEN RAISE EXCEPTION 'note_completion_failed'; END IF;
 IF jsonb_array_length(public.commercial_personal_notes_list_my(true,100))<>1 THEN RAISE EXCEPTION 'completed_note_filter_failed'; END IF;
 v_result:=public.commercial_personal_notes_save_my(v_note,'موعد محدث','تم تعديل الملاحظة',NULL,NULL);
 IF v_result->>'title'<>'موعد محدث' THEN RAISE EXCEPTION 'note_update_failed'; END IF;
 v_ok:=public.commercial_personal_notes_archive_my(v_note);
 IF NOT v_ok OR jsonb_array_length(public.commercial_personal_notes_list_my(true,100))<>0 THEN RAISE EXCEPTION 'note_archive_failed'; END IF;
 IF has_table_privilege('anon','public.commercial_personal_notes','SELECT')
  OR has_table_privilege('authenticated','public.commercial_personal_notes','SELECT') THEN
  RAISE EXCEPTION 'direct_table_access_not_revoked';
 END IF;
END $$;