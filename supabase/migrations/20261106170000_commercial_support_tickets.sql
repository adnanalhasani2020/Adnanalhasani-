BEGIN;

CREATE TABLE public.commercial_support_tickets (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 ticket_no bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
 organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
 activity_id uuid NOT NULL REFERENCES public.commercial_activities(id) ON DELETE RESTRICT,
 customer_id uuid REFERENCES public.commercial_customers(id) ON DELETE RESTRICT,
 subject text NOT NULL CHECK(length(btrim(subject)) BETWEEN 1 AND 180),
 description text NOT NULL CHECK(length(btrim(description)) BETWEEN 1 AND 4000),
 category text NOT NULL CHECK(category IN('general','product','payment','delivery','technical','complaint','return','other')),
 priority text NOT NULL DEFAULT 'normal' CHECK(priority IN('low','normal','high','urgent')),
 status text NOT NULL DEFAULT 'open' CHECK(status IN('open','in_progress','waiting_customer','resolved','closed')),
 assigned_to uuid REFERENCES auth.users(id) ON DELETE RESTRICT,
 source text NOT NULL DEFAULT 'internal' CHECK(source IN('internal','phone','whatsapp','email','customer_portal')),
 created_by uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
 idempotency_key text NOT NULL CHECK(length(btrim(idempotency_key)) BETWEEN 8 AND 200),
 request_fingerprint text NOT NULL CHECK(length(request_fingerprint)=32),
 version integer NOT NULL DEFAULT 1 CHECK(version>0),
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT commercial_support_tickets_id_org_activity_uq UNIQUE(id,organization_id,activity_id),
 CONSTRAINT commercial_support_tickets_idempotency_uq UNIQUE(organization_id,activity_id,created_by,idempotency_key)
);
CREATE INDEX commercial_support_tickets_scope_status_idx
 ON public.commercial_support_tickets(organization_id,activity_id,status,priority,updated_at DESC);
CREATE INDEX commercial_support_tickets_assignee_idx
 ON public.commercial_support_tickets(organization_id,activity_id,assigned_to,updated_at DESC);
ALTER TABLE public.commercial_support_tickets ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.commercial_support_tickets FROM PUBLIC,anon,authenticated;

CREATE TABLE public.commercial_support_ticket_messages (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 organization_id uuid NOT NULL,
 activity_id uuid NOT NULL,
 ticket_id uuid NOT NULL,
 body text NOT NULL CHECK(length(btrim(body)) BETWEEN 1 AND 4000),
 created_by uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
 idempotency_key text NOT NULL CHECK(length(btrim(idempotency_key)) BETWEEN 8 AND 200),
 request_fingerprint text NOT NULL CHECK(length(request_fingerprint)=32),
 created_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT commercial_support_ticket_messages_ticket_fk
  FOREIGN KEY(ticket_id,organization_id,activity_id)
  REFERENCES public.commercial_support_tickets(id,organization_id,activity_id) ON DELETE RESTRICT,
 CONSTRAINT commercial_support_ticket_messages_idempotency_uq
  UNIQUE(organization_id,activity_id,created_by,idempotency_key)
);
CREATE INDEX commercial_support_ticket_messages_ticket_idx
 ON public.commercial_support_ticket_messages(organization_id,activity_id,ticket_id,created_at,id);
ALTER TABLE public.commercial_support_ticket_messages ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.commercial_support_ticket_messages FROM PUBLIC,anon,authenticated;

CREATE OR REPLACE FUNCTION public.commercial_support_context_role(
 p_organization_id uuid,p_activity_id uuid
) RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $function$
DECLARE v_uid uuid:=auth.uid();v_role text;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_organization_id IS NULL OR p_activity_id IS NULL THEN RAISE EXCEPTION 'support_context_required' USING ERRCODE='22023'; END IF;
 IF NOT EXISTS(SELECT 1 FROM public.commercial_organizations o WHERE o.id=p_organization_id AND o.state='active' AND o.deleted_at IS NULL)
 THEN RAISE EXCEPTION 'support_organization_not_available' USING ERRCODE='42501'; END IF;
 SELECT m.role INTO v_role FROM public.commercial_organization_members m
 WHERE m.organization_id=p_organization_id AND m.user_id=v_uid AND m.state='active';
 IF v_role IS NULL THEN RAISE EXCEPTION 'support_membership_required' USING ERRCODE='42501'; END IF;
 IF NOT EXISTS(SELECT 1 FROM public.commercial_activities a WHERE a.id=p_activity_id
  AND a.organization_id=p_organization_id AND a.state='active' AND a.deleted_at IS NULL)
 THEN RAISE EXCEPTION 'support_activity_not_available' USING ERRCODE='42501'; END IF;
 RETURN v_role;
END;
$function$;
REVOKE ALL ON FUNCTION public.commercial_support_context_role(uuid,uuid) FROM PUBLIC,anon,authenticated;

CREATE OR REPLACE FUNCTION public.commercial_support_my_role(
 p_organization_id uuid,p_activity_id uuid
) RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $function$
BEGIN
 RETURN public.commercial_support_context_role(p_organization_id,p_activity_id);
END;
$function$;
REVOKE ALL ON FUNCTION public.commercial_support_my_role(uuid,uuid) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_support_my_role(uuid,uuid) TO authenticated;

CREATE OR REPLACE FUNCTION public.commercial_support_ticket_create(
 p_organization_id uuid,p_activity_id uuid,p_customer_id uuid,p_subject text,p_description text,
 p_category text,p_priority text,p_assigned_to uuid,p_source text,p_idempotency_key text
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $function$
DECLARE v_uid uuid:=auth.uid();v_role text;v_assignee uuid;v_fp text;v_existing public.commercial_support_tickets%ROWTYPE;v_id uuid;v_no bigint;
BEGIN
 v_role:=public.commercial_support_context_role(p_organization_id,p_activity_id);
 IF v_role='viewer' THEN RAISE EXCEPTION 'support_write_permission_required' USING ERRCODE='42501'; END IF;
 IF length(btrim(coalesce(p_subject,''))) NOT BETWEEN 1 AND 180
  OR length(btrim(coalesce(p_description,''))) NOT BETWEEN 1 AND 4000
  OR p_category IS NULL OR p_category NOT IN('general','product','payment','delivery','technical','complaint','return','other')
  OR coalesce(p_priority,'normal') NOT IN('low','normal','high','urgent')
  OR coalesce(p_source,'internal') NOT IN('internal','phone','whatsapp','email')
  OR p_idempotency_key IS NULL OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200 THEN
  RAISE EXCEPTION 'invalid_support_ticket' USING ERRCODE='22023';
 END IF;
 IF p_customer_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM public.commercial_customers c
  WHERE c.id=p_customer_id AND c.organization_id=p_organization_id AND c.state='active') THEN
  RAISE EXCEPTION 'support_customer_not_in_organization' USING ERRCODE='42501';
 END IF;
 v_assignee:=coalesce(p_assigned_to,v_uid);
 IF v_role='staff' AND v_assignee<>v_uid THEN RAISE EXCEPTION 'support_staff_cannot_assign_other_users' USING ERRCODE='42501'; END IF;
 IF NOT EXISTS(SELECT 1 FROM public.commercial_organization_members m
  WHERE m.organization_id=p_organization_id AND m.user_id=v_assignee AND m.state='active') THEN
  RAISE EXCEPTION 'support_assignee_not_active_member' USING ERRCODE='22023';
 END IF;
 v_fp:=md5(jsonb_build_object('customer_id',p_customer_id,'subject',btrim(p_subject),
  'description',btrim(p_description),'category',p_category,'priority',coalesce(p_priority,'normal'),
  'assigned_to',v_assignee,'source',coalesce(p_source,'internal'))::text);
 PERFORM pg_advisory_xact_lock(hashtextextended(p_organization_id::text||':'||p_activity_id::text||':'||v_uid::text||':'||btrim(p_idempotency_key),0));
 SELECT * INTO v_existing FROM public.commercial_support_tickets t
 WHERE t.organization_id=p_organization_id AND t.activity_id=p_activity_id
  AND t.created_by=v_uid AND t.idempotency_key=btrim(p_idempotency_key) FOR UPDATE;
 IF FOUND THEN
  IF v_existing.request_fingerprint IS DISTINCT FROM v_fp THEN RAISE EXCEPTION 'support_ticket_idempotency_conflict' USING ERRCODE='23505'; END IF;
  RETURN jsonb_build_object('id',v_existing.id,'ticket_no',v_existing.ticket_no,'status',v_existing.status,'version',v_existing.version,'replayed',true);
 END IF;
 INSERT INTO public.commercial_support_tickets(organization_id,activity_id,customer_id,subject,description,
  category,priority,assigned_to,source,created_by,idempotency_key,request_fingerprint)
 VALUES(p_organization_id,p_activity_id,p_customer_id,btrim(p_subject),btrim(p_description),
  p_category,coalesce(p_priority,'normal'),v_assignee,coalesce(p_source,'internal'),v_uid,btrim(p_idempotency_key),v_fp)
 RETURNING id,ticket_no INTO v_id,v_no;
 INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
 VALUES(p_organization_id,v_uid,'support.ticket.created','support_ticket',v_id::text,
  jsonb_build_object('activity_id',p_activity_id,'ticket_no',v_no,'assigned_to',v_assignee,'priority',coalesce(p_priority,'normal')));
 RETURN jsonb_build_object('id',v_id,'ticket_no',v_no,'status','open','version',1,'replayed',false);
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_support_ticket_list(
 p_organization_id uuid,p_activity_id uuid,p_status text DEFAULT NULL,p_limit integer DEFAULT 100
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $function$
DECLARE v_uid uuid:=auth.uid();v_role text;v_result jsonb;
BEGIN
 v_role:=public.commercial_support_context_role(p_organization_id,p_activity_id);
 IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 200
  OR (p_status IS NOT NULL AND p_status NOT IN('open','in_progress','waiting_customer','resolved','closed')) THEN
  RAISE EXCEPTION 'invalid_support_ticket_query' USING ERRCODE='22023';
 END IF;
 SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.updated_at DESC,x.ticket_no DESC),'[]'::jsonb) INTO v_result
 FROM(
  SELECT t.id,t.ticket_no,t.customer_id,c.display_name AS customer_name,t.subject,t.description,
   t.category,t.priority,t.status,t.assigned_to,t.source,t.created_by,t.version,t.created_at,t.updated_at
  FROM public.commercial_support_tickets t
  LEFT JOIN public.commercial_customers c ON c.id=t.customer_id AND c.organization_id=t.organization_id
  WHERE t.organization_id=p_organization_id AND t.activity_id=p_activity_id
   AND (p_status IS NULL OR t.status=p_status)
   AND (v_role<>'staff' OR t.assigned_to=v_uid OR t.created_by=v_uid)
  ORDER BY t.updated_at DESC,t.ticket_no DESC LIMIT p_limit
 ) x;
 RETURN v_result;
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_support_ticket_update(
 p_ticket_id uuid,p_expected_version integer,p_status text,p_priority text,p_assigned_to uuid
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $function$
DECLARE v_uid uuid:=auth.uid();v_ticket public.commercial_support_tickets%ROWTYPE;v_role text;v_assignee uuid;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_status IS NULL OR p_status NOT IN('open','in_progress','waiting_customer','resolved','closed')
  OR p_priority IS NULL OR p_priority NOT IN('low','normal','high','urgent') THEN RAISE EXCEPTION 'invalid_support_ticket_update' USING ERRCODE='22023'; END IF;
 SELECT * INTO v_ticket FROM public.commercial_support_tickets t WHERE t.id=p_ticket_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'support_ticket_not_found' USING ERRCODE='P0002'; END IF;
 v_role:=public.commercial_support_context_role(v_ticket.organization_id,v_ticket.activity_id);
 IF v_role='viewer' OR (v_role='staff' AND v_ticket.assigned_to IS DISTINCT FROM v_uid AND v_ticket.created_by IS DISTINCT FROM v_uid) THEN
  RAISE EXCEPTION 'support_ticket_update_forbidden' USING ERRCODE='42501';
 END IF;
 IF p_expected_version IS NULL OR p_expected_version<>v_ticket.version THEN
  RAISE EXCEPTION 'support_ticket_version_conflict' USING ERRCODE='40001';
 END IF;
 v_assignee:=coalesce(p_assigned_to,v_ticket.assigned_to);
 IF v_role='staff' AND (v_assignee IS DISTINCT FROM v_ticket.assigned_to OR p_status='closed') THEN
  RAISE EXCEPTION 'support_staff_cannot_reassign_or_close_ticket' USING ERRCODE='42501';
 END IF;
 IF v_assignee IS NOT NULL AND NOT EXISTS(SELECT 1 FROM public.commercial_organization_members m
  WHERE m.organization_id=v_ticket.organization_id AND m.user_id=v_assignee AND m.state='active') THEN
  RAISE EXCEPTION 'support_assignee_not_active_member' USING ERRCODE='22023';
 END IF;
 UPDATE public.commercial_support_tickets SET status=p_status,priority=p_priority,assigned_to=v_assignee,
  version=version+1,updated_at=now()
 WHERE id=p_ticket_id AND version=p_expected_version;
 INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
 VALUES(v_ticket.organization_id,v_uid,'support.ticket.updated','support_ticket',p_ticket_id::text,
  jsonb_build_object('from_status',v_ticket.status,'to_status',p_status,'from_priority',v_ticket.priority,'to_priority',p_priority,'assigned_to',v_assignee,'version',v_ticket.version+1));
 RETURN jsonb_build_object('id',p_ticket_id,'status',p_status,'priority',p_priority,'version',v_ticket.version+1);
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_support_ticket_message_create(
 p_ticket_id uuid,p_body text,p_idempotency_key text
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $function$
DECLARE v_uid uuid:=auth.uid();v_ticket public.commercial_support_tickets%ROWTYPE;v_role text;
 v_fp text;v_existing public.commercial_support_ticket_messages%ROWTYPE;v_id uuid;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF length(btrim(coalesce(p_body,''))) NOT BETWEEN 1 AND 4000
  OR p_idempotency_key IS NULL OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200 THEN
  RAISE EXCEPTION 'invalid_support_ticket_message' USING ERRCODE='22023';
 END IF;
 SELECT * INTO v_ticket FROM public.commercial_support_tickets t WHERE t.id=p_ticket_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'support_ticket_not_found' USING ERRCODE='P0002'; END IF;
 v_role:=public.commercial_support_context_role(v_ticket.organization_id,v_ticket.activity_id);
 IF v_role='viewer' OR (v_role='staff' AND v_ticket.assigned_to IS DISTINCT FROM v_uid AND v_ticket.created_by IS DISTINCT FROM v_uid) THEN
  RAISE EXCEPTION 'support_ticket_message_forbidden' USING ERRCODE='42501';
 END IF;
 IF v_ticket.status='closed' THEN RAISE EXCEPTION 'support_ticket_closed' USING ERRCODE='22023'; END IF;
 v_fp:=md5(jsonb_build_object('ticket_id',p_ticket_id,'body',btrim(p_body))::text);
 PERFORM pg_advisory_xact_lock(hashtextextended(v_ticket.organization_id::text||':'||v_ticket.activity_id::text||':'||v_uid::text||':'||btrim(p_idempotency_key),0));
 SELECT * INTO v_existing FROM public.commercial_support_ticket_messages m
 WHERE m.organization_id=v_ticket.organization_id AND m.activity_id=v_ticket.activity_id
  AND m.created_by=v_uid AND m.idempotency_key=btrim(p_idempotency_key) FOR UPDATE;
 IF FOUND THEN
  IF v_existing.request_fingerprint IS DISTINCT FROM v_fp THEN RAISE EXCEPTION 'support_message_idempotency_conflict' USING ERRCODE='23505'; END IF;
  RETURN jsonb_build_object('id',v_existing.id,'ticket_id',p_ticket_id,'replayed',true);
 END IF;
 INSERT INTO public.commercial_support_ticket_messages(organization_id,activity_id,ticket_id,body,created_by,idempotency_key,request_fingerprint)
 VALUES(v_ticket.organization_id,v_ticket.activity_id,p_ticket_id,btrim(p_body),v_uid,btrim(p_idempotency_key),v_fp)
 RETURNING id INTO v_id;
 UPDATE public.commercial_support_tickets SET updated_at=now(),version=version+1 WHERE id=p_ticket_id;
 INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
 VALUES(v_ticket.organization_id,v_uid,'support.ticket.message_added','support_ticket',p_ticket_id::text,
  jsonb_build_object('message_id',v_id));
 RETURN jsonb_build_object('id',v_id,'ticket_id',p_ticket_id,'replayed',false);
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_support_ticket_message_list(
 p_ticket_id uuid,p_limit integer DEFAULT 100
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $function$
DECLARE v_ticket public.commercial_support_tickets%ROWTYPE;v_role text;v_uid uuid:=auth.uid();v_result jsonb;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 200 THEN RAISE EXCEPTION 'invalid_support_message_limit' USING ERRCODE='22023'; END IF;
 SELECT * INTO v_ticket FROM public.commercial_support_tickets t WHERE t.id=p_ticket_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'support_ticket_not_found' USING ERRCODE='P0002'; END IF;
 v_role:=public.commercial_support_context_role(v_ticket.organization_id,v_ticket.activity_id);
 IF v_role='staff' AND v_ticket.assigned_to IS DISTINCT FROM v_uid AND v_ticket.created_by IS DISTINCT FROM v_uid THEN
  RAISE EXCEPTION 'support_ticket_message_forbidden' USING ERRCODE='42501';
 END IF;
 SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.created_at,x.id),'[]'::jsonb) INTO v_result
 FROM(
  SELECT id,body,created_by,created_at FROM public.commercial_support_ticket_messages
  WHERE ticket_id=p_ticket_id AND organization_id=v_ticket.organization_id AND activity_id=v_ticket.activity_id
  ORDER BY created_at,id LIMIT p_limit
 ) x;
 RETURN v_result;
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_support_ticket_create(uuid,uuid,uuid,text,text,text,text,uuid,text,text) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_support_ticket_list(uuid,uuid,text,integer) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_support_ticket_update(uuid,integer,text,text,uuid) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_support_ticket_message_create(uuid,text,text) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_support_ticket_message_list(uuid,integer) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_support_ticket_create(uuid,uuid,uuid,text,text,text,text,uuid,text,text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_support_ticket_list(uuid,uuid,text,integer) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_support_ticket_update(uuid,integer,text,text,uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_support_ticket_message_create(uuid,text,text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_support_ticket_message_list(uuid,integer) TO authenticated;

COMMIT;
