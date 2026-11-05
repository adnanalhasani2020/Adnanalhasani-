-- Personal work notes and reminders, isolated per authenticated platform user.
CREATE TABLE IF NOT EXISTS public.commercial_personal_notes (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  title text NOT NULL CHECK (char_length(btrim(title)) BETWEEN 1 AND 160),
  body text NOT NULL DEFAULT '' CHECK (char_length(body) <= 4000),
  due_at timestamptz,
  reminder_at timestamptz,
  completed_at timestamptz,
  archived_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK (reminder_at IS NULL OR due_at IS NULL OR reminder_at <= due_at)
);
CREATE INDEX IF NOT EXISTS commercial_personal_notes_user_active_idx
  ON public.commercial_personal_notes(user_id, completed_at, due_at, created_at DESC)
  WHERE archived_at IS NULL;
ALTER TABLE public.commercial_personal_notes ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.commercial_personal_notes FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.commercial_personal_notes_list_my(p_include_completed boolean DEFAULT false, p_limit integer DEFAULT 100)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_uid uuid := auth.uid(); v_result jsonb;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_limit IS NULL OR p_limit < 1 OR p_limit > 200 THEN RAISE EXCEPTION 'invalid_note_limit' USING ERRCODE='22023'; END IF;
  SELECT coalesce(jsonb_agg(jsonb_build_object(
    'id',n.id,'title',n.title,'body',n.body,'due_at',n.due_at,'reminder_at',n.reminder_at,
    'completed_at',n.completed_at,'created_at',n.created_at,'updated_at',n.updated_at,
    'is_overdue',(n.completed_at IS NULL AND n.due_at IS NOT NULL AND n.due_at < now()),
    'reminder_due',(n.completed_at IS NULL AND n.reminder_at IS NOT NULL AND n.reminder_at <= now())
  ) ORDER BY (n.completed_at IS NOT NULL), n.due_at NULLS LAST, n.created_at DESC),'[]'::jsonb)
  INTO v_result
  FROM (SELECT * FROM public.commercial_personal_notes
        WHERE user_id=v_uid AND archived_at IS NULL
          AND (p_include_completed OR completed_at IS NULL)
        ORDER BY (completed_at IS NOT NULL), due_at NULLS LAST, created_at DESC
        LIMIT p_limit) n;
  RETURN v_result;
END; $$;

CREATE OR REPLACE FUNCTION public.commercial_personal_notes_save_my(
  p_note_id uuid, p_title text, p_body text DEFAULT '', p_due_at timestamptz DEFAULT NULL,
  p_reminder_at timestamptz DEFAULT NULL
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_uid uuid := auth.uid(); v_note public.commercial_personal_notes%ROWTYPE;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_title IS NULL OR char_length(btrim(p_title)) NOT BETWEEN 1 AND 160 OR char_length(coalesce(p_body,'')) > 4000 THEN
    RAISE EXCEPTION 'invalid_note_content' USING ERRCODE='22023';
  END IF;
  IF p_reminder_at IS NOT NULL AND p_due_at IS NOT NULL AND p_reminder_at > p_due_at THEN
    RAISE EXCEPTION 'reminder_after_due_date' USING ERRCODE='22023';
  END IF;
  IF p_note_id IS NULL THEN
    INSERT INTO public.commercial_personal_notes(user_id,title,body,due_at,reminder_at)
    VALUES(v_uid,btrim(p_title),coalesce(p_body,''),p_due_at,p_reminder_at) RETURNING * INTO v_note;
  ELSE
    UPDATE public.commercial_personal_notes SET title=btrim(p_title),body=coalesce(p_body,''),
      due_at=p_due_at,reminder_at=p_reminder_at,updated_at=now()
    WHERE id=p_note_id AND user_id=v_uid AND archived_at IS NULL
    RETURNING * INTO v_note;
    IF NOT FOUND THEN RAISE EXCEPTION 'note_not_found' USING ERRCODE='P0002'; END IF;
  END IF;
  RETURN jsonb_build_object('id',v_note.id,'title',v_note.title,'body',v_note.body,
    'due_at',v_note.due_at,'reminder_at',v_note.reminder_at,'completed_at',v_note.completed_at,
    'created_at',v_note.created_at,'updated_at',v_note.updated_at);
END; $$;

CREATE OR REPLACE FUNCTION public.commercial_personal_notes_complete_my(p_note_id uuid, p_completed boolean DEFAULT true)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_uid uuid := auth.uid(); v_count integer;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_note_id IS NULL THEN RAISE EXCEPTION 'note_id_required' USING ERRCODE='22023'; END IF;
  UPDATE public.commercial_personal_notes SET completed_at=CASE WHEN coalesce(p_completed,true) THEN now() ELSE NULL END,
    updated_at=now() WHERE id=p_note_id AND user_id=v_uid AND archived_at IS NULL;
  GET DIAGNOSTICS v_count=ROW_COUNT;
  RETURN v_count=1;
END; $$;

CREATE OR REPLACE FUNCTION public.commercial_personal_notes_archive_my(p_note_id uuid)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_uid uuid := auth.uid(); v_count integer;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  UPDATE public.commercial_personal_notes SET archived_at=now(),updated_at=now()
  WHERE id=p_note_id AND user_id=v_uid AND archived_at IS NULL;
  GET DIAGNOSTICS v_count=ROW_COUNT;
  RETURN v_count=1;
END; $$;

REVOKE ALL ON FUNCTION public.commercial_personal_notes_list_my(boolean,integer) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.commercial_personal_notes_save_my(uuid,text,text,timestamptz,timestamptz) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.commercial_personal_notes_complete_my(uuid,boolean) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.commercial_personal_notes_archive_my(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_personal_notes_list_my(boolean,integer) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_personal_notes_save_my(uuid,text,text,timestamptz,timestamptz) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_personal_notes_complete_my(uuid,boolean) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_personal_notes_archive_my(uuid) TO authenticated;
