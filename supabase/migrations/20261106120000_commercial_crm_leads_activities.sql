BEGIN;

CREATE TABLE IF NOT EXISTS public.commercial_crm_leads (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
 activity_id uuid REFERENCES public.commercial_activities(id) ON DELETE SET NULL,
 assigned_to uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
 created_by uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
 lead_name text NOT NULL CHECK(length(btrim(lead_name)) BETWEEN 1 AND 180),
 company_name text NOT NULL DEFAULT '' CHECK(length(company_name)<=180),
 phone text NOT NULL DEFAULT '' CHECK(length(phone)<=40),
 email text NOT NULL DEFAULT '' CHECK(length(email)<=254),
 source text NOT NULL DEFAULT 'other' CHECK(source IN ('referral','walk_in','phone','social','website','campaign','other')),
 stage text NOT NULL DEFAULT 'new' CHECK(stage IN ('new','contacted','qualified','proposal','won','lost')),
 expected_value_minor bigint NOT NULL DEFAULT 0 CHECK(expected_value_minor>=0),
 currency_code text NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),
 next_follow_up_at timestamptz,
 notes text NOT NULL DEFAULT '' CHECK(length(notes)<=4000),
 lost_reason text NOT NULL DEFAULT '' CHECK(length(lost_reason)<=1000),
 idempotency_key text NOT NULL CHECK(length(btrim(idempotency_key)) BETWEEN 8 AND 200),
 request_fingerprint text NOT NULL CHECK(length(request_fingerprint)=32),
 version integer NOT NULL DEFAULT 1 CHECK(version>0),
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT commercial_crm_leads_org_id_uq UNIQUE(organization_id,id),
 CONSTRAINT commercial_crm_leads_idempotency_uq UNIQUE(organization_id,created_by,idempotency_key)
);
CREATE INDEX IF NOT EXISTS commercial_crm_leads_org_stage_followup_idx
 ON public.commercial_crm_leads(organization_id,stage,next_follow_up_at,created_at DESC);
CREATE INDEX IF NOT EXISTS commercial_crm_leads_assignee_idx
 ON public.commercial_crm_leads(organization_id,assigned_to,updated_at DESC);

CREATE TABLE IF NOT EXISTS public.commercial_crm_activities (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
 lead_id uuid NOT NULL,
 activity_type text NOT NULL CHECK(activity_type IN ('call','meeting','email','note','task')),
 subject text NOT NULL CHECK(length(btrim(subject)) BETWEEN 1 AND 180),
 body text NOT NULL DEFAULT '' CHECK(length(body)<=4000),
 due_at timestamptz,
 completed_at timestamptz,
 assigned_to uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
 created_by uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
 idempotency_key text NOT NULL CHECK(length(btrim(idempotency_key)) BETWEEN 8 AND 200),
 request_fingerprint text NOT NULL CHECK(length(request_fingerprint)=32),
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT commercial_crm_activities_lead_fk FOREIGN KEY(organization_id,lead_id)
  REFERENCES public.commercial_crm_leads(organization_id,id) ON DELETE RESTRICT,
 CONSTRAINT commercial_crm_activities_idempotency_uq UNIQUE(organization_id,created_by,idempotency_key)
);
CREATE INDEX IF NOT EXISTS commercial_crm_activities_lead_created_idx
 ON public.commercial_crm_activities(organization_id,lead_id,created_at DESC);

ALTER TABLE public.commercial_crm_leads ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.commercial_crm_activities ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.commercial_crm_leads,public.commercial_crm_activities FROM PUBLIC,anon,authenticated;

CREATE OR REPLACE FUNCTION public.commercial_crm_list_leads(
 p_organization_id uuid,p_stage text DEFAULT NULL,p_limit integer DEFAULT 100
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $function$
DECLARE v_uid uuid:=auth.uid(); v_role text; v_result jsonb;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_organization_id IS NULL OR p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 200
  OR (p_stage IS NOT NULL AND p_stage NOT IN('new','contacted','qualified','proposal','won','lost')) THEN
  RAISE EXCEPTION 'invalid_crm_lead_query' USING ERRCODE='22023';
 END IF;
 SELECT m.role INTO v_role FROM public.commercial_organization_members m
 WHERE m.organization_id=p_organization_id AND m.user_id=v_uid AND m.state='active';
 IF v_role IS NULL THEN RAISE EXCEPTION 'crm_organization_access_denied' USING ERRCODE='42501'; END IF;
 SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.updated_at DESC,x.id),'[]'::jsonb) INTO v_result
 FROM (
  SELECT l.id,l.activity_id,l.lead_name,l.company_name,l.phone,l.email,l.source,l.stage,
   l.expected_value_minor,l.currency_code,l.next_follow_up_at,l.notes,l.lost_reason,
   l.assigned_to,l.created_by,l.version,l.created_at,l.updated_at
  FROM public.commercial_crm_leads l
  WHERE l.organization_id=p_organization_id AND (p_stage IS NULL OR l.stage=p_stage)
   AND (v_role IN('owner','admin','manager') OR l.assigned_to=v_uid OR l.created_by=v_uid)
  ORDER BY l.updated_at DESC,l.id LIMIT p_limit
 ) x;
 RETURN v_result;
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_crm_create_lead(
 p_organization_id uuid,p_activity_id uuid,p_lead_name text,p_company_name text,p_phone text,p_email text,
 p_source text,p_expected_value_minor bigint,p_currency_code text,p_next_follow_up_at timestamptz,
 p_notes text,p_assigned_to uuid,p_idempotency_key text
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $function$
DECLARE v_uid uuid:=auth.uid(); v_role text; v_assignee uuid; v_currency text;
 v_fingerprint text; v_existing public.commercial_crm_leads%ROWTYPE; v_id uuid;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_organization_id IS NULL OR length(btrim(coalesce(p_lead_name,''))) NOT BETWEEN 1 AND 180
  OR length(coalesce(p_company_name,''))>180 OR length(coalesce(p_phone,''))>40
  OR length(coalesce(p_email,''))>254 OR (coalesce(p_email,'')<>'' AND p_email !~* '^[^@[:space:]]+@[^@[:space:]]+[.][^@[:space:]]+$')
  OR p_source NOT IN('referral','walk_in','phone','social','website','campaign','other')
  OR p_expected_value_minor IS NULL OR p_expected_value_minor<0
  OR (p_currency_code IS NOT NULL AND p_currency_code !~ '^[A-Z]{3}$')
  OR length(coalesce(p_notes,''))>4000 OR p_idempotency_key IS NULL OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200 THEN
  RAISE EXCEPTION 'invalid_crm_lead' USING ERRCODE='22023';
 END IF;
 SELECT m.role INTO v_role FROM public.commercial_organization_members m
 WHERE m.organization_id=p_organization_id AND m.user_id=v_uid AND m.state='active';
 IF v_role IS NULL OR v_role NOT IN('owner','admin','manager','staff') THEN
  RAISE EXCEPTION 'crm_lead_write_forbidden' USING ERRCODE='42501';
 END IF;
 v_assignee:=coalesce(p_assigned_to,v_uid);
 IF v_assignee<>v_uid AND v_role NOT IN('owner','admin','manager') THEN
  RAISE EXCEPTION 'crm_assignment_forbidden' USING ERRCODE='42501';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM public.commercial_organization_members m
  WHERE m.organization_id=p_organization_id AND m.user_id=v_assignee AND m.state='active') THEN
  RAISE EXCEPTION 'crm_assignee_not_active_member' USING ERRCODE='22023';
 END IF;
 IF p_activity_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM public.commercial_activities a
  WHERE a.id=p_activity_id AND a.organization_id=p_organization_id AND a.state='active' AND a.deleted_at IS NULL) THEN
  RAISE EXCEPTION 'crm_activity_not_in_organization' USING ERRCODE='42501';
 END IF;
 SELECT default_currency_code INTO v_currency FROM public.commercial_organizations
 WHERE id=p_organization_id AND state='active' AND deleted_at IS NULL;
 IF v_currency IS NULL THEN RAISE EXCEPTION 'crm_organization_not_active' USING ERRCODE='42501'; END IF;
 v_currency:=coalesce(p_currency_code,v_currency);
 v_fingerprint:=md5(jsonb_build_object('activity_id',p_activity_id,'lead_name',btrim(p_lead_name),
  'company_name',coalesce(p_company_name,''),'phone',coalesce(p_phone,''),'email',lower(btrim(coalesce(p_email,''))),
  'source',p_source,'value',p_expected_value_minor,'currency',v_currency,'follow_up',p_next_follow_up_at,
  'notes',coalesce(p_notes,''),'assignee',v_assignee)::text);
 PERFORM pg_advisory_xact_lock(hashtextextended(p_organization_id::text||':'||v_uid::text||':'||btrim(p_idempotency_key),0));
 SELECT * INTO v_existing FROM public.commercial_crm_leads
 WHERE organization_id=p_organization_id AND created_by=v_uid AND idempotency_key=btrim(p_idempotency_key) FOR UPDATE;
 IF FOUND THEN
  IF v_existing.request_fingerprint IS DISTINCT FROM v_fingerprint THEN
   RAISE EXCEPTION 'crm_idempotency_key_reused' USING ERRCODE='23505';
  END IF;
  RETURN jsonb_build_object('id',v_existing.id,'stage',v_existing.stage,'version',v_existing.version,'replayed',true);
 END IF;
 INSERT INTO public.commercial_crm_leads(organization_id,activity_id,assigned_to,created_by,lead_name,
  company_name,phone,email,source,expected_value_minor,currency_code,next_follow_up_at,notes,
  idempotency_key,request_fingerprint)
 VALUES(p_organization_id,p_activity_id,v_assignee,v_uid,btrim(p_lead_name),btrim(coalesce(p_company_name,'')),
  btrim(coalesce(p_phone,'')),lower(btrim(coalesce(p_email,''))),p_source,p_expected_value_minor,v_currency,
  p_next_follow_up_at,btrim(coalesce(p_notes,'')),btrim(p_idempotency_key),v_fingerprint)
 RETURNING id INTO v_id;
 INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
 VALUES(p_organization_id,v_uid,'crm.lead.created','crm_lead',v_id::text,
  jsonb_build_object('stage','new','assigned_to',v_assignee,'source',p_source));
 RETURN jsonb_build_object('id',v_id,'stage','new','version',1,'replayed',false);
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_crm_update_lead(
 p_organization_id uuid,p_lead_id uuid,p_stage text,p_notes text,p_next_follow_up_at timestamptz,
 p_lost_reason text,p_expected_value_minor bigint,p_version integer
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $function$
DECLARE v_uid uuid:=auth.uid(); v_role text; v_lead public.commercial_crm_leads%ROWTYPE;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_stage NOT IN('new','contacted','qualified','proposal','won','lost') OR length(coalesce(p_notes,''))>4000
  OR length(coalesce(p_lost_reason,''))>1000 OR p_expected_value_minor IS NULL OR p_expected_value_minor<0
  OR p_version IS NULL OR p_version<1 THEN RAISE EXCEPTION 'invalid_crm_lead_update' USING ERRCODE='22023'; END IF;
 SELECT m.role INTO v_role FROM public.commercial_organization_members m
 WHERE m.organization_id=p_organization_id AND m.user_id=v_uid AND m.state='active';
 IF v_role IS NULL OR v_role NOT IN('owner','admin','manager','staff') THEN
  RAISE EXCEPTION 'crm_lead_write_forbidden' USING ERRCODE='42501';
 END IF;
 SELECT * INTO v_lead FROM public.commercial_crm_leads l
 WHERE l.organization_id=p_organization_id AND l.id=p_lead_id
  AND (v_role IN('owner','admin','manager') OR l.assigned_to=v_uid OR l.created_by=v_uid)
 FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'crm_lead_not_found_or_forbidden' USING ERRCODE='42501'; END IF;
 IF v_lead.version<>p_version THEN RAISE EXCEPTION 'crm_lead_version_conflict' USING ERRCODE='40001'; END IF;
 IF p_stage='lost' AND length(btrim(coalesce(p_lost_reason,'')))=0 THEN
  RAISE EXCEPTION 'crm_lost_reason_required' USING ERRCODE='22023';
 END IF;
 UPDATE public.commercial_crm_leads SET stage=p_stage,notes=coalesce(p_notes,''),
  next_follow_up_at=p_next_follow_up_at,lost_reason=CASE WHEN p_stage='lost' THEN btrim(coalesce(p_lost_reason,'')) ELSE '' END,
  expected_value_minor=p_expected_value_minor,version=version+1,updated_at=now()
 WHERE id=p_lead_id AND organization_id=p_organization_id;
 INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
 VALUES(p_organization_id,v_uid,'crm.lead.updated','crm_lead',p_lead_id::text,
  jsonb_build_object('from_stage',v_lead.stage,'to_stage',p_stage,'version',v_lead.version+1));
 RETURN jsonb_build_object('id',p_lead_id,'stage',p_stage,'version',v_lead.version+1);
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_crm_add_activity(
 p_organization_id uuid,p_lead_id uuid,p_activity_type text,p_subject text,p_body text,
 p_due_at timestamptz,p_assigned_to uuid,p_idempotency_key text
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $function$
DECLARE v_uid uuid:=auth.uid(); v_role text; v_lead public.commercial_crm_leads%ROWTYPE;
 v_assignee uuid; v_fingerprint text; v_existing public.commercial_crm_activities%ROWTYPE; v_id uuid;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_activity_type NOT IN('call','meeting','email','note','task')
  OR length(btrim(coalesce(p_subject,''))) NOT BETWEEN 1 AND 180 OR length(coalesce(p_body,''))>4000
  OR p_idempotency_key IS NULL OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200 THEN
  RAISE EXCEPTION 'invalid_crm_activity' USING ERRCODE='22023';
 END IF;
 SELECT m.role INTO v_role FROM public.commercial_organization_members m
 WHERE m.organization_id=p_organization_id AND m.user_id=v_uid AND m.state='active';
 IF v_role IS NULL OR v_role NOT IN('owner','admin','manager','staff') THEN
  RAISE EXCEPTION 'crm_activity_write_forbidden' USING ERRCODE='42501';
 END IF;
 SELECT * INTO v_lead FROM public.commercial_crm_leads l
 WHERE l.organization_id=p_organization_id AND l.id=p_lead_id
  AND (v_role IN('owner','admin','manager') OR l.assigned_to=v_uid OR l.created_by=v_uid);
 IF NOT FOUND THEN RAISE EXCEPTION 'crm_lead_not_found_or_forbidden' USING ERRCODE='42501'; END IF;
 v_assignee:=coalesce(p_assigned_to,v_uid);
 IF v_assignee<>v_uid AND v_role NOT IN('owner','admin','manager') THEN
  RAISE EXCEPTION 'crm_assignment_forbidden' USING ERRCODE='42501';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM public.commercial_organization_members m
  WHERE m.organization_id=p_organization_id AND m.user_id=v_assignee AND m.state='active') THEN
  RAISE EXCEPTION 'crm_assignee_not_active_member' USING ERRCODE='22023';
 END IF;
 v_fingerprint:=md5(jsonb_build_object('lead_id',p_lead_id,'type',p_activity_type,'subject',btrim(p_subject),
  'body',coalesce(p_body,''),'due_at',p_due_at,'assignee',v_assignee)::text);
 PERFORM pg_advisory_xact_lock(hashtextextended(p_organization_id::text||':'||v_uid::text||':'||btrim(p_idempotency_key),0));
 SELECT * INTO v_existing FROM public.commercial_crm_activities
 WHERE organization_id=p_organization_id AND created_by=v_uid AND idempotency_key=btrim(p_idempotency_key) FOR UPDATE;
 IF FOUND THEN
  IF v_existing.request_fingerprint IS DISTINCT FROM v_fingerprint THEN
   RAISE EXCEPTION 'crm_activity_idempotency_key_reused' USING ERRCODE='23505';
  END IF;
  RETURN jsonb_build_object('id',v_existing.id,'replayed',true);
 END IF;
 INSERT INTO public.commercial_crm_activities(organization_id,lead_id,activity_type,subject,body,due_at,
  assigned_to,created_by,idempotency_key,request_fingerprint)
 VALUES(p_organization_id,p_lead_id,p_activity_type,btrim(p_subject),coalesce(p_body,''),p_due_at,
  v_assignee,v_uid,btrim(p_idempotency_key),v_fingerprint) RETURNING id INTO v_id;
 INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
 VALUES(p_organization_id,v_uid,'crm.activity.created','crm_activity',v_id::text,
  jsonb_build_object('lead_id',p_lead_id,'activity_type',p_activity_type,'assigned_to',v_assignee));
 RETURN jsonb_build_object('id',v_id,'replayed',false);
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_crm_list_activities(
 p_organization_id uuid,p_lead_id uuid,p_limit integer DEFAULT 100
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $function$
DECLARE v_uid uuid:=auth.uid(); v_role text; v_result jsonb;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_organization_id IS NULL OR p_lead_id IS NULL OR p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 200 THEN
  RAISE EXCEPTION 'invalid_crm_activity_query' USING ERRCODE='22023';
 END IF;
 SELECT m.role INTO v_role FROM public.commercial_organization_members m
 WHERE m.organization_id=p_organization_id AND m.user_id=v_uid AND m.state='active';
 IF v_role IS NULL THEN RAISE EXCEPTION 'crm_organization_access_denied' USING ERRCODE='42501'; END IF;
 IF NOT EXISTS(SELECT 1 FROM public.commercial_crm_leads l
  WHERE l.organization_id=p_organization_id AND l.id=p_lead_id
   AND (v_role IN('owner','admin','manager') OR l.assigned_to=v_uid OR l.created_by=v_uid)) THEN
  RAISE EXCEPTION 'crm_lead_not_found_or_forbidden' USING ERRCODE='42501';
 END IF;
 SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.created_at DESC,x.id),'[]'::jsonb) INTO v_result
 FROM (
  SELECT a.id,a.activity_type,a.subject,a.body,a.due_at,a.completed_at,a.assigned_to,a.created_by,a.created_at
  FROM public.commercial_crm_activities a
  WHERE a.organization_id=p_organization_id AND a.lead_id=p_lead_id
  ORDER BY a.created_at DESC,a.id LIMIT p_limit
 ) x;
 RETURN v_result;
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_crm_list_leads(uuid,text,integer) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_crm_create_lead(uuid,uuid,text,text,text,text,text,bigint,text,timestamptz,text,uuid,text) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_crm_update_lead(uuid,uuid,text,text,timestamptz,text,bigint,integer) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_crm_add_activity(uuid,uuid,text,text,text,timestamptz,uuid,text) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_crm_list_activities(uuid,uuid,integer) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_crm_list_leads(uuid,text,integer) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_crm_create_lead(uuid,uuid,text,text,text,text,text,bigint,text,timestamptz,text,uuid,text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_crm_update_lead(uuid,uuid,text,text,timestamptz,text,bigint,integer) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_crm_add_activity(uuid,uuid,text,text,text,timestamptz,uuid,text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_crm_list_activities(uuid,uuid,integer) TO authenticated;

COMMIT;
