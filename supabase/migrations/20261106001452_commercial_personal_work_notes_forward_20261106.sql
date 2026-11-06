-- User-owned work notes and due reminders. Reminders are materialized into the inbox
-- idempotently when the owner opens or refreshes the notes screen.
BEGIN;

ALTER TABLE public.commercial_user_notifications
  DROP CONSTRAINT IF EXISTS commercial_user_notifications_notification_type_check;
ALTER TABLE public.commercial_user_notifications
  ADD CONSTRAINT commercial_user_notifications_notification_type_check
  CHECK (notification_type = ANY (ARRAY[
    'order','payment','booking','account_access','approval','system','security','message','reminder'
  ]::text[]));


CREATE TABLE IF NOT EXISTS public.commercial_user_work_notes (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
 organization_id uuid NULL REFERENCES public.commercial_organizations(id) ON DELETE CASCADE,
 title text NOT NULL CHECK(length(btrim(title)) BETWEEN 1 AND 160),
 body text NOT NULL DEFAULT '' CHECK(length(body)<=4000),
 reminder_at timestamptz NULL,
 reminder_notified_at timestamptz NULL,
 status text NOT NULL DEFAULT 'open' CHECK(status IN ('open','completed','archived')),
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 completed_at timestamptz NULL
);
CREATE INDEX IF NOT EXISTS commercial_user_work_notes_owner_idx ON public.commercial_user_work_notes(user_id,status,updated_at DESC);
CREATE INDEX IF NOT EXISTS commercial_user_work_notes_due_idx ON public.commercial_user_work_notes(user_id,reminder_at)
 WHERE status='open' AND reminder_at IS NOT NULL AND reminder_notified_at IS NULL;
ALTER TABLE public.commercial_user_work_notes ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.commercial_user_work_notes FROM PUBLIC,anon,authenticated;

CREATE OR REPLACE FUNCTION public.commercial_user_work_notes_create(
 p_title text,p_body text DEFAULT '',p_organization_id uuid DEFAULT NULL,p_reminder_at timestamptz DEFAULT NULL
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $function$
DECLARE v_uid uuid:=auth.uid(); v_row public.commercial_user_work_notes;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_title IS NULL OR length(btrim(p_title)) NOT BETWEEN 1 AND 160 OR coalesce(length(p_body),0)>4000
 THEN RAISE EXCEPTION 'invalid_work_note' USING ERRCODE='22023'; END IF;
 IF p_organization_id IS NOT NULL AND NOT EXISTS(
  SELECT 1 FROM public.commercial_organization_members m
  WHERE m.organization_id=p_organization_id AND m.user_id=v_uid AND m.state='active'
 ) THEN RAISE EXCEPTION 'organization_access_denied' USING ERRCODE='42501'; END IF;
 INSERT INTO public.commercial_user_work_notes(user_id,organization_id,title,body,reminder_at)
 VALUES(v_uid,p_organization_id,btrim(p_title),coalesce(p_body,''),p_reminder_at) RETURNING * INTO v_row;
 RETURN jsonb_build_object('id',v_row.id,'status',v_row.status,'created_at',v_row.created_at);
END;$function$;

CREATE OR REPLACE FUNCTION public.commercial_user_work_notes_list_my(p_limit integer DEFAULT 100)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $function$
DECLARE v_uid uuid:=auth.uid(); v_result jsonb;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_limit IS NULL OR p_limit<1 OR p_limit>200 THEN RAISE EXCEPTION 'invalid_work_note_limit' USING ERRCODE='22023'; END IF;
 WITH due AS (
  UPDATE public.commercial_user_work_notes n SET reminder_notified_at=now(),updated_at=now()
  WHERE n.user_id=v_uid AND n.status='open' AND n.reminder_at IS NOT NULL
    AND n.reminder_at<=now() AND n.reminder_notified_at IS NULL
  RETURNING n.id,n.organization_id,n.title,n.reminder_at
 )
 INSERT INTO public.commercial_user_notifications(user_id,organization_id,notification_type,title,body,entity_type,entity_id,priority,metadata)
 SELECT v_uid,d.organization_id,'reminder','تذكير بمهمة: '||d.title,
  'حان موعد التذكير بالملاحظة: '||d.title,'user_work_note',d.id,'normal',
  jsonb_build_object('note_id',d.id,'reminder_at',d.reminder_at) FROM due d;
 SELECT coalesce(jsonb_agg(jsonb_build_object(
  'id',n.id,'organization_id',n.organization_id,'title',n.title,'body',n.body,
  'reminder_at',n.reminder_at,'reminder_notified_at',n.reminder_notified_at,
  'status',n.status,'created_at',n.created_at,'updated_at',n.updated_at,'completed_at',n.completed_at
 ) ORDER BY CASE n.status WHEN 'open' THEN 0 WHEN 'completed' THEN 1 ELSE 2 END,
 n.reminder_at NULLS LAST,n.updated_at DESC),'[]'::jsonb) INTO v_result
 FROM (SELECT * FROM public.commercial_user_work_notes WHERE user_id=v_uid AND status<>'archived'
       ORDER BY updated_at DESC LIMIT p_limit) n;
 RETURN v_result;
END;$function$;

CREATE OR REPLACE FUNCTION public.commercial_user_work_notes_update_my(
 p_note_id uuid,p_title text,p_body text DEFAULT '',p_reminder_at timestamptz DEFAULT NULL,p_status text DEFAULT 'open'
) RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $function$
DECLARE v_uid uuid:=auth.uid(); v_updated integer;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_note_id IS NULL OR p_title IS NULL OR length(btrim(p_title)) NOT BETWEEN 1 AND 160
  OR coalesce(length(p_body),0)>4000 OR p_status NOT IN ('open','completed','archived')
 THEN RAISE EXCEPTION 'invalid_work_note_update' USING ERRCODE='22023'; END IF;
 UPDATE public.commercial_user_work_notes SET title=btrim(p_title),body=coalesce(p_body,''),
  reminder_notified_at=CASE WHEN reminder_at IS DISTINCT FROM p_reminder_at THEN NULL ELSE reminder_notified_at END,
  reminder_at=p_reminder_at,status=p_status,
  completed_at=CASE WHEN p_status='completed' THEN coalesce(completed_at,now()) ELSE NULL END,updated_at=now()
 WHERE id=p_note_id AND user_id=v_uid;
 GET DIAGNOSTICS v_updated=ROW_COUNT; RETURN v_updated=1;
END;$function$;
REVOKE ALL ON FUNCTION public.commercial_user_work_notes_create(text,text,uuid,timestamptz) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_user_work_notes_list_my(integer) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_user_work_notes_update_my(uuid,text,text,timestamptz,text) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_user_work_notes_create(text,text,uuid,timestamptz) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_user_work_notes_list_my(integer) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_user_work_notes_update_my(uuid,text,text,timestamptz,text) TO authenticated;
COMMIT;
