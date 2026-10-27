-- User-scoped notification inbox for merchant delegation approval and account access events.
CREATE OR REPLACE FUNCTION public.commercial_user_notifications_list_my(p_limit integer DEFAULT 50)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE v_uid uuid := auth.uid(); v_result jsonb;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_limit IS NULL OR p_limit < 1 OR p_limit > 100 THEN
    RAISE EXCEPTION 'invalid_notification_limit' USING ERRCODE='22023';
  END IF;
  SELECT coalesce(jsonb_agg(jsonb_build_object(
    'id',n.id,'organization_id',n.organization_id,'notification_type',n.notification_type,
    'title',n.title,'body',n.body,'entity_type',n.entity_type,'entity_id',n.entity_id,
    'priority',n.priority,'read_at',n.read_at,'created_at',n.created_at,'metadata',n.metadata
  ) ORDER BY n.created_at DESC),'[]'::jsonb)
  INTO v_result
  FROM (
    SELECT * FROM public.commercial_user_notifications
    WHERE user_id=v_uid
    ORDER BY created_at DESC
    LIMIT p_limit
  ) n;
  RETURN v_result;
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_user_notifications_mark_read(p_notification_id uuid)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE v_uid uuid := auth.uid(); v_updated integer;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_notification_id IS NULL THEN RAISE EXCEPTION 'notification_id_required' USING ERRCODE='22023'; END IF;
  UPDATE public.commercial_user_notifications
  SET read_at=coalesce(read_at,now())
  WHERE id=p_notification_id AND user_id=v_uid;
  GET DIAGNOSTICS v_updated = ROW_COUNT;
  RETURN v_updated=1;
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_user_notifications_list_my(integer) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.commercial_user_notifications_mark_read(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_user_notifications_list_my(integer) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_user_notifications_mark_read(uuid) TO authenticated;
