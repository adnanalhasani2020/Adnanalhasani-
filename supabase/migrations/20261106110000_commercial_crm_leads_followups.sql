BEGIN;

CREATE TABLE public.commercial_crm_leads (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
  activity_id uuid NOT NULL REFERENCES public.commercial_activities(id) ON DELETE RESTRICT,
  name text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 160),
  company_name text NOT NULL DEFAULT '' CHECK (length(company_name) <= 160),
  email text NOT NULL DEFAULT '' CHECK (length(email) <= 254),
  phone text NOT NULL DEFAULT '' CHECK (length(phone) <= 40),
  source text NOT NULL DEFAULT 'other' CHECK (source IN ('walk_in','referral','social','website','campaign','phone','other')),
  status text NOT NULL DEFAULT 'new' CHECK (status IN ('new','contacted','qualified','unqualified','lost')),
  assigned_to uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
  notes text NOT NULL DEFAULT '' CHECK (length(notes) <= 4000),
  next_follow_up_at timestamptz,
  converted_customer_id uuid REFERENCES public.commercial_customers(id) ON DELETE RESTRICT,
  created_by uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
  idempotency_key text NOT NULL CHECK (length(btrim(idempotency_key)) BETWEEN 8 AND 200),
  request_fingerprint text NOT NULL CHECK (length(request_fingerprint)=32),
  version integer NOT NULL DEFAULT 1 CHECK (version > 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT commercial_crm_leads_id_org_activity_uq UNIQUE(id,organization_id,activity_id),
  CONSTRAINT commercial_crm_leads_idempotency_uq UNIQUE(organization_id,activity_id,created_by,idempotency_key)
);
CREATE INDEX commercial_crm_leads_activity_status_idx
  ON public.commercial_crm_leads(organization_id,activity_id,status,updated_at DESC);
CREATE INDEX commercial_crm_leads_assignee_followup_idx
  ON public.commercial_crm_leads(organization_id,activity_id,assigned_to,next_follow_up_at)
  WHERE next_follow_up_at IS NOT NULL;

CREATE TABLE public.commercial_crm_activities (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL,
  activity_id uuid NOT NULL,
  lead_id uuid NOT NULL,
  activity_type text NOT NULL CHECK (activity_type IN ('call','meeting','email','task','note')),
  title text NOT NULL CHECK (length(btrim(title)) BETWEEN 1 AND 160),
  description text NOT NULL DEFAULT '' CHECK (length(description) <= 4000),
  due_at timestamptz,
  status text NOT NULL DEFAULT 'open' CHECK (status IN ('open','completed','cancelled')),
  assigned_to uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
  created_by uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
  idempotency_key text NOT NULL CHECK (length(btrim(idempotency_key)) BETWEEN 8 AND 200),
  request_fingerprint text NOT NULL CHECK (length(request_fingerprint)=32),
  version integer NOT NULL DEFAULT 1 CHECK (version > 0),
  completed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT commercial_crm_activities_lead_fk
    FOREIGN KEY(lead_id,organization_id,activity_id)
    REFERENCES public.commercial_crm_leads(id,organization_id,activity_id) ON DELETE RESTRICT,
  CONSTRAINT commercial_crm_activities_idempotency_uq
    UNIQUE(organization_id,activity_id,created_by,idempotency_key)
);
CREATE INDEX commercial_crm_activities_assignee_due_idx
  ON public.commercial_crm_activities(organization_id,activity_id,assigned_to,status,due_at);

ALTER TABLE public.commercial_crm_leads ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.commercial_crm_activities ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.commercial_crm_leads FROM PUBLIC,anon,authenticated;
REVOKE ALL ON TABLE public.commercial_crm_activities FROM PUBLIC,anon,authenticated;

CREATE OR REPLACE FUNCTION public.commercial_crm_context_role(
  p_organization_id uuid,p_activity_id uuid
) RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE v_uid uuid:=auth.uid(); v_role text;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_organization_id IS NULL OR p_activity_id IS NULL THEN
  RAISE EXCEPTION 'crm_context_required' USING ERRCODE='22023';
 END IF;
 SELECT m.role INTO v_role
 FROM public.commercial_organization_members m
 WHERE m.organization_id=p_organization_id AND m.user_id=v_uid AND m.state='active';
 IF NOT FOUND THEN RAISE EXCEPTION 'crm_organization_membership_required' USING ERRCODE='42501'; END IF;
 IF NOT EXISTS (
  SELECT 1 FROM public.commercial_activities a
  WHERE a.id=p_activity_id AND a.organization_id=p_organization_id
    AND a.state='active' AND a.deleted_at IS NULL
 ) THEN RAISE EXCEPTION 'crm_activity_not_available' USING ERRCODE='42501'; END IF;
 RETURN v_role;
END;
$function$;
REVOKE ALL ON FUNCTION public.commercial_crm_context_role(uuid,uuid) FROM PUBLIC,anon,authenticated;

CREATE OR REPLACE FUNCTION public.commercial_crm_lead_create(
 p_organization_id uuid,p_activity_id uuid,p_name text,p_company_name text,
 p_email text,p_phone text,p_source text,p_notes text,p_assigned_to uuid,
 p_next_follow_up_at timestamptz,p_idempotency_key text
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE
 v_uid uuid:=auth.uid(); v_role text; v_assignee uuid; v_fp text;
 v_existing public.commercial_crm_leads%ROWTYPE; v_id uuid;
BEGIN
 v_role:=public.commercial_crm_context_role(p_organization_id,p_activity_id);
 IF v_role='viewer' THEN RAISE EXCEPTION 'crm_write_permission_required' USING ERRCODE='42501'; END IF;
 IF p_name IS NULL OR length(btrim(p_name)) NOT BETWEEN 1 AND 160
  OR length(coalesce(p_company_name,''))>160 OR length(coalesce(p_email,''))>254
  OR length(coalesce(p_phone,''))>40 OR length(coalesce(p_notes,''))>4000
  OR p_source IS NULL OR p_source NOT IN ('walk_in','referral','social','website','campaign','phone','other')
  OR p_idempotency_key IS NULL OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200 THEN
  RAISE EXCEPTION 'invalid_crm_lead' USING ERRCODE='22023';
 END IF;
 v_assignee:=coalesce(p_assigned_to,v_uid);
 IF v_role='staff' AND v_assignee<>v_uid THEN
  RAISE EXCEPTION 'crm_staff_cannot_assign_other_users' USING ERRCODE='42501';
 END IF;
 IF NOT EXISTS (SELECT 1 FROM public.commercial_organization_members m
  WHERE m.organization_id=p_organization_id AND m.user_id=v_assignee AND m.state='active') THEN
  RAISE EXCEPTION 'crm_assignee_not_active_member' USING ERRCODE='22023';
 END IF;
 v_fp:=md5(concat_ws('|',p_organization_id::text,p_activity_id::text,btrim(p_name),
  coalesce(p_company_name,''),lower(coalesce(p_email,'')),coalesce(p_phone,''),p_source,
  coalesce(p_notes,''),v_assignee::text,coalesce(p_next_follow_up_at::text,'')));
 PERFORM pg_advisory_xact_lock(hashtextextended(p_organization_id::text||':'||p_activity_id::text||':'||v_uid::text||':'||btrim(p_idempotency_key),0));
 SELECT * INTO v_existing FROM public.commercial_crm_leads l
 WHERE l.organization_id=p_organization_id AND l.activity_id=p_activity_id
  AND l.created_by=v_uid AND l.idempotency_key=btrim(p_idempotency_key) FOR UPDATE;
 IF FOUND THEN
  IF v_existing.request_fingerprint IS DISTINCT FROM v_fp THEN
   RAISE EXCEPTION 'crm_lead_idempotency_key_reused' USING ERRCODE='23505';
  END IF;
  RETURN jsonb_build_object('id',v_existing.id,'status',v_existing.status,'version',v_existing.version,'replayed',true);
 END IF;
 INSERT INTO public.commercial_crm_leads(
  organization_id,activity_id,name,company_name,email,phone,source,assigned_to,
  notes,next_follow_up_at,created_by,idempotency_key,request_fingerprint
 ) VALUES (
  p_organization_id,p_activity_id,btrim(p_name),coalesce(btrim(p_company_name),''),
  lower(btrim(coalesce(p_email,''))),btrim(coalesce(p_phone,'')),p_source,v_assignee,
  coalesce(p_notes,''),p_next_follow_up_at,v_uid,btrim(p_idempotency_key),v_fp
 ) RETURNING id INTO v_id;
 INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
 VALUES(p_organization_id,v_uid,'crm.lead_created','crm_lead',v_id::text,
  jsonb_build_object('activity_id',p_activity_id,'assigned_to',v_assignee,'source',p_source));
 RETURN jsonb_build_object('id',v_id,'status','new','version',1,'replayed',false);
END;
$function$;
REVOKE ALL ON FUNCTION public.commercial_crm_lead_create(uuid,uuid,text,text,text,text,text,text,uuid,timestamptz,text) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_crm_lead_create(uuid,uuid,text,text,text,text,text,text,uuid,timestamptz,text) TO authenticated;

CREATE OR REPLACE FUNCTION public.commercial_crm_lead_list(
 p_organization_id uuid,p_activity_id uuid,p_limit integer DEFAULT 100
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE v_uid uuid:=auth.uid(); v_role text; v_result jsonb;
BEGIN
 v_role:=public.commercial_crm_context_role(p_organization_id,p_activity_id);
 IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 200 THEN RAISE EXCEPTION 'invalid_crm_list_limit' USING ERRCODE='22023'; END IF;
 SELECT coalesce(jsonb_agg(jsonb_build_object(
  'id',l.id,'name',l.name,'company_name',l.company_name,'email',l.email,'phone',l.phone,
  'source',l.source,'status',l.status,'assigned_to',l.assigned_to,'notes',l.notes,
  'next_follow_up_at',l.next_follow_up_at,'converted_customer_id',l.converted_customer_id,
  'version',l.version,'created_at',l.created_at,'updated_at',l.updated_at
 ) ORDER BY l.updated_at DESC,l.id),'[]'::jsonb) INTO v_result
 FROM (
  SELECT * FROM public.commercial_crm_leads
  WHERE organization_id=p_organization_id AND activity_id=p_activity_id
   AND (v_role<>'staff' OR assigned_to=v_uid)
  ORDER BY updated_at DESC,id LIMIT p_limit
 ) l;
 RETURN v_result;
END;
$function$;
REVOKE ALL ON FUNCTION public.commercial_crm_lead_list(uuid,uuid,integer) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_crm_lead_list(uuid,uuid,integer) TO authenticated;

CREATE OR REPLACE FUNCTION public.commercial_crm_lead_update(
 p_lead_id uuid,p_expected_version integer,p_status text,p_notes text,
 p_next_follow_up_at timestamptz,p_assigned_to uuid
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE v_uid uuid:=auth.uid(); v_lead public.commercial_crm_leads%ROWTYPE;
 v_role text; v_assignee uuid;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 SELECT * INTO v_lead FROM public.commercial_crm_leads l WHERE l.id=p_lead_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'crm_lead_not_found' USING ERRCODE='P0002'; END IF;
 v_role:=public.commercial_crm_context_role(v_lead.organization_id,v_lead.activity_id);
 IF v_role='viewer' OR (v_role='staff' AND v_lead.assigned_to<>v_uid) THEN
  RAISE EXCEPTION 'crm_lead_update_forbidden' USING ERRCODE='42501';
 END IF;
 IF p_expected_version IS NULL OR p_expected_version<>v_lead.version THEN
  RAISE EXCEPTION 'crm_lead_version_conflict' USING ERRCODE='40001';
 END IF;
 IF p_status IS NULL OR p_status NOT IN ('new','contacted','qualified','unqualified','lost')
  OR length(coalesce(p_notes,''))>4000 THEN
  RAISE EXCEPTION 'invalid_crm_lead_update' USING ERRCODE='22023';
 END IF;
 v_assignee:=coalesce(p_assigned_to,v_lead.assigned_to);
 IF v_role='staff' AND v_assignee<>v_uid THEN RAISE EXCEPTION 'crm_staff_cannot_reassign_lead' USING ERRCODE='42501'; END IF;
 IF NOT EXISTS(SELECT 1 FROM public.commercial_organization_members m
  WHERE m.organization_id=v_lead.organization_id AND m.user_id=v_assignee AND m.state='active') THEN
  RAISE EXCEPTION 'crm_assignee_not_active_member' USING ERRCODE='22023';
 END IF;
 UPDATE public.commercial_crm_leads SET status=p_status,
  notes=coalesce(p_notes,notes),next_follow_up_at=p_next_follow_up_at,
  assigned_to=v_assignee,version=version+1,updated_at=now()
 WHERE id=p_lead_id AND version=p_expected_version;
 IF NOT FOUND THEN RAISE EXCEPTION 'crm_lead_version_conflict' USING ERRCODE='40001'; END IF;
 INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
 VALUES(v_lead.organization_id,v_uid,'crm.lead_updated','crm_lead',p_lead_id::text,
  jsonb_build_object('from_status',v_lead.status,'to_status',p_status,'assigned_to',v_assignee,'version',v_lead.version+1));
 RETURN jsonb_build_object('id',p_lead_id,'status',p_status,'version',v_lead.version+1);
END;
$function$;
REVOKE ALL ON FUNCTION public.commercial_crm_lead_update(uuid,integer,text,text,timestamptz,uuid) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_crm_lead_update(uuid,integer,text,text,timestamptz,uuid) TO authenticated;

CREATE OR REPLACE FUNCTION public.commercial_crm_activity_create(
 p_organization_id uuid,p_activity_id uuid,p_lead_id uuid,p_activity_type text,
 p_title text,p_description text,p_due_at timestamptz,p_assigned_to uuid,p_idempotency_key text
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE v_uid uuid:=auth.uid(); v_role text; v_lead public.commercial_crm_leads%ROWTYPE;
 v_assignee uuid; v_fp text; v_existing public.commercial_crm_activities%ROWTYPE; v_id uuid;
BEGIN
 v_role:=public.commercial_crm_context_role(p_organization_id,p_activity_id);
 IF v_role='viewer' THEN RAISE EXCEPTION 'crm_write_permission_required' USING ERRCODE='42501'; END IF;
 IF p_title IS NULL OR length(btrim(p_title)) NOT BETWEEN 1 AND 160
  OR length(coalesce(p_description,''))>4000
  OR p_activity_type IS NULL OR p_activity_type NOT IN ('call','meeting','email','task','note')
  OR p_idempotency_key IS NULL OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200 THEN
  RAISE EXCEPTION 'invalid_crm_activity' USING ERRCODE='22023';
 END IF;
 SELECT * INTO v_lead FROM public.commercial_crm_leads l
 WHERE l.id=p_lead_id AND l.organization_id=p_organization_id AND l.activity_id=p_activity_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'crm_lead_not_found' USING ERRCODE='P0002'; END IF;
 IF v_role='staff' AND v_lead.assigned_to<>v_uid THEN
  RAISE EXCEPTION 'crm_lead_update_forbidden' USING ERRCODE='42501';
 END IF;
 v_assignee:=coalesce(p_assigned_to,v_uid);
 IF v_role='staff' AND v_assignee<>v_uid THEN RAISE EXCEPTION 'crm_staff_cannot_assign_other_users' USING ERRCODE='42501'; END IF;
 IF NOT EXISTS(SELECT 1 FROM public.commercial_organization_members m
  WHERE m.organization_id=p_organization_id AND m.user_id=v_assignee AND m.state='active') THEN
  RAISE EXCEPTION 'crm_assignee_not_active_member' USING ERRCODE='22023';
 END IF;
 v_fp:=md5(concat_ws('|',p_organization_id::text,p_activity_id::text,p_lead_id::text,p_activity_type,
  btrim(p_title),coalesce(p_description,''),coalesce(p_due_at::text,''),v_assignee::text));
 PERFORM pg_advisory_xact_lock(hashtextextended(p_organization_id::text||':'||p_activity_id::text||':'||v_uid::text||':'||btrim(p_idempotency_key),0));
 SELECT * INTO v_existing FROM public.commercial_crm_activities a
 WHERE a.organization_id=p_organization_id AND a.activity_id=p_activity_id
  AND a.created_by=v_uid AND a.idempotency_key=btrim(p_idempotency_key) FOR UPDATE;
 IF FOUND THEN
  IF v_existing.request_fingerprint IS DISTINCT FROM v_fp THEN
   RAISE EXCEPTION 'crm_activity_idempotency_key_reused' USING ERRCODE='23505';
  END IF;
  RETURN jsonb_build_object('id',v_existing.id,'status',v_existing.status,'version',v_existing.version,'replayed',true);
 END IF;
 INSERT INTO public.commercial_crm_activities(
  organization_id,activity_id,lead_id,activity_type,title,description,due_at,
  assigned_to,created_by,idempotency_key,request_fingerprint
 ) VALUES(p_organization_id,p_activity_id,p_lead_id,p_activity_type,btrim(p_title),
  coalesce(p_description,''),p_due_at,v_assignee,v_uid,btrim(p_idempotency_key),v_fp)
 RETURNING id INTO v_id;
 INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
 VALUES(p_organization_id,v_uid,'crm.activity_created','crm_activity',v_id::text,
  jsonb_build_object('lead_id',p_lead_id,'activity_type',p_activity_type,'assigned_to',v_assignee));
 RETURN jsonb_build_object('id',v_id,'status','open','version',1,'replayed',false);
END;
$function$;
REVOKE ALL ON FUNCTION public.commercial_crm_activity_create(uuid,uuid,uuid,text,text,text,timestamptz,uuid,text) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_crm_activity_create(uuid,uuid,uuid,text,text,text,timestamptz,uuid,text) TO authenticated;

CREATE OR REPLACE FUNCTION public.commercial_crm_activity_list(
 p_organization_id uuid,p_activity_id uuid,p_limit integer DEFAULT 100
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE v_uid uuid:=auth.uid(); v_role text; v_result jsonb;
BEGIN
 v_role:=public.commercial_crm_context_role(p_organization_id,p_activity_id);
 IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 200 THEN RAISE EXCEPTION 'invalid_crm_list_limit' USING ERRCODE='22023'; END IF;
 SELECT coalesce(jsonb_agg(jsonb_build_object(
  'id',a.id,'lead_id',a.lead_id,'activity_type',a.activity_type,'title',a.title,
  'description',a.description,'due_at',a.due_at,'status',a.status,'assigned_to',a.assigned_to,
  'version',a.version,'completed_at',a.completed_at,'created_at',a.created_at
 ) ORDER BY coalesce(a.due_at,a.created_at),a.id),'[]'::jsonb) INTO v_result
 FROM (
  SELECT a.* FROM public.commercial_crm_activities a
  JOIN public.commercial_crm_leads l ON l.id=a.lead_id
   AND l.organization_id=a.organization_id AND l.activity_id=a.activity_id
  WHERE a.organization_id=p_organization_id AND a.activity_id=p_activity_id
   AND (v_role<>'staff' OR a.assigned_to=v_uid OR l.assigned_to=v_uid)
  ORDER BY coalesce(a.due_at,a.created_at),a.id LIMIT p_limit
 ) a;
 RETURN v_result;
END;
$function$;
REVOKE ALL ON FUNCTION public.commercial_crm_activity_list(uuid,uuid,integer) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_crm_activity_list(uuid,uuid,integer) TO authenticated;

CREATE OR REPLACE FUNCTION public.commercial_crm_activity_update(
 p_activity_record_id uuid,p_expected_version integer,p_status text,p_description text
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ''
AS $function$
DECLARE v_uid uuid:=auth.uid(); v_row public.commercial_crm_activities%ROWTYPE;
 v_role text; v_lead_owner uuid;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 SELECT * INTO v_row FROM public.commercial_crm_activities a
 WHERE a.id=p_activity_record_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'crm_activity_not_found' USING ERRCODE='P0002'; END IF;
 v_role:=public.commercial_crm_context_role(v_row.organization_id,v_row.activity_id);
 SELECT l.assigned_to INTO v_lead_owner FROM public.commercial_crm_leads l WHERE l.id=v_row.lead_id;
 IF v_role='viewer' OR (v_role='staff' AND v_row.assigned_to<>v_uid AND v_lead_owner<>v_uid) THEN
  RAISE EXCEPTION 'crm_activity_update_forbidden' USING ERRCODE='42501';
 END IF;
 IF p_expected_version IS NULL OR p_expected_version<>v_row.version THEN
  RAISE EXCEPTION 'crm_activity_version_conflict' USING ERRCODE='40001';
 END IF;
 IF p_status IS NULL OR p_status NOT IN ('open','completed','cancelled') OR length(coalesce(p_description,''))>4000 THEN
  RAISE EXCEPTION 'invalid_crm_activity_update' USING ERRCODE='22023';
 END IF;
 UPDATE public.commercial_crm_activities SET status=p_status,
  description=coalesce(p_description,description),
  completed_at=CASE WHEN p_status='completed' THEN coalesce(completed_at,now()) ELSE NULL END,
  version=version+1,updated_at=now()
 WHERE id=p_activity_record_id AND version=p_expected_version;
 IF NOT FOUND THEN RAISE EXCEPTION 'crm_activity_version_conflict' USING ERRCODE='40001'; END IF;
 INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
 VALUES(v_row.organization_id,v_uid,'crm.activity_updated','crm_activity',p_activity_record_id::text,
  jsonb_build_object('status',p_status,'version',v_row.version+1));
 RETURN jsonb_build_object('id',p_activity_record_id,'status',p_status,'version',v_row.version+1);
END;
$function$;
REVOKE ALL ON FUNCTION public.commercial_crm_activity_update(uuid,integer,text,text) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_crm_activity_update(uuid,integer,text,text) TO authenticated;

COMMIT;
