BEGIN;

CREATE TABLE public.commercial_crm_opportunities (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
 activity_id uuid NOT NULL REFERENCES public.commercial_activities(id) ON DELETE RESTRICT,
 lead_id uuid NOT NULL,
 title text NOT NULL CHECK(length(btrim(title)) BETWEEN 1 AND 180),
 stage text NOT NULL DEFAULT 'qualification'
  CHECK(stage IN('qualification','needs_analysis','proposal','negotiation','won','lost')),
 probability smallint NOT NULL DEFAULT 10 CHECK(probability BETWEEN 0 AND 100),
 expected_value_minor bigint NOT NULL DEFAULT 0 CHECK(expected_value_minor>=0),
 currency_code text NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),
 expected_close_date date,
 assigned_to uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
 created_by uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
 notes text NOT NULL DEFAULT '' CHECK(length(notes)<=4000),
 lost_reason text NOT NULL DEFAULT '' CHECK(length(lost_reason)<=1000),
 idempotency_key text NOT NULL CHECK(length(btrim(idempotency_key)) BETWEEN 8 AND 200),
 request_fingerprint text NOT NULL CHECK(length(request_fingerprint)=32),
 version integer NOT NULL DEFAULT 1 CHECK(version>0),
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT commercial_crm_opportunities_lead_fk
  FOREIGN KEY(lead_id,organization_id,activity_id)
  REFERENCES public.commercial_crm_leads(id,organization_id,activity_id) ON DELETE RESTRICT,
 CONSTRAINT commercial_crm_opportunities_id_org_activity_uq UNIQUE(id,organization_id,activity_id),
 CONSTRAINT commercial_crm_opportunities_idempotency_uq
  UNIQUE(organization_id,activity_id,created_by,idempotency_key)
);
CREATE INDEX commercial_crm_opportunities_pipeline_idx
 ON public.commercial_crm_opportunities(organization_id,activity_id,stage,expected_close_date,updated_at DESC);
CREATE INDEX commercial_crm_opportunities_assignee_idx
 ON public.commercial_crm_opportunities(organization_id,activity_id,assigned_to,updated_at DESC);

ALTER TABLE public.commercial_crm_opportunities ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.commercial_crm_opportunities FROM PUBLIC,anon,authenticated;

CREATE OR REPLACE FUNCTION public.commercial_crm_opportunity_create(
 p_organization_id uuid,p_activity_id uuid,p_lead_id uuid,p_title text,p_stage text,
 p_probability smallint,p_expected_value_minor bigint,p_currency_code text,
 p_expected_close_date date,p_notes text,p_assigned_to uuid,p_idempotency_key text
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $function$
DECLARE v_uid uuid:=auth.uid(); v_role text; v_assignee uuid; v_currency text;
 v_lead public.commercial_crm_leads%ROWTYPE; v_fp text; v_existing public.commercial_crm_opportunities%ROWTYPE; v_id uuid;
BEGIN
 v_role:=public.commercial_crm_context_role(p_organization_id,p_activity_id);
 IF v_role='viewer' THEN RAISE EXCEPTION 'crm_opportunity_write_forbidden' USING ERRCODE='42501'; END IF;
 IF p_lead_id IS NULL OR length(btrim(coalesce(p_title,''))) NOT BETWEEN 1 AND 180
  OR p_stage NOT IN('qualification','needs_analysis','proposal','negotiation','won','lost')
  OR p_probability IS NULL OR p_probability NOT BETWEEN 0 AND 100
  OR p_expected_value_minor IS NULL OR p_expected_value_minor<0
  OR (p_currency_code IS NOT NULL AND p_currency_code !~ '^[A-Z]{3}$')
  OR length(coalesce(p_notes,''))>4000
  OR p_idempotency_key IS NULL OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200 THEN
  RAISE EXCEPTION 'invalid_crm_opportunity' USING ERRCODE='22023';
 END IF;
 SELECT * INTO v_lead FROM public.commercial_crm_leads l
 WHERE l.id=p_lead_id AND l.organization_id=p_organization_id AND l.activity_id=p_activity_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'crm_opportunity_lead_not_found' USING ERRCODE='P0002'; END IF;
 IF v_role='staff' AND v_lead.assigned_to<>v_uid AND v_lead.created_by<>v_uid THEN
  RAISE EXCEPTION 'crm_opportunity_lead_access_denied' USING ERRCODE='42501';
 END IF;
 v_assignee:=coalesce(p_assigned_to,v_uid);
 IF v_role='staff' AND v_assignee<>v_uid THEN RAISE EXCEPTION 'crm_staff_cannot_assign_opportunity' USING ERRCODE='42501'; END IF;
 IF NOT EXISTS(SELECT 1 FROM public.commercial_organization_members m
  WHERE m.organization_id=p_organization_id AND m.user_id=v_assignee AND m.state='active') THEN
  RAISE EXCEPTION 'crm_opportunity_assignee_not_active' USING ERRCODE='22023';
 END IF;
 SELECT default_currency_code INTO v_currency FROM public.commercial_organizations
 WHERE id=p_organization_id AND state='active' AND deleted_at IS NULL;
 IF v_currency IS NULL THEN RAISE EXCEPTION 'crm_organization_not_available' USING ERRCODE='42501'; END IF;
 v_currency:=coalesce(p_currency_code,v_currency);
 v_fp:=md5(jsonb_build_object('lead_id',p_lead_id,'title',btrim(p_title),'stage',p_stage,
  'probability',p_probability,'value',p_expected_value_minor,'currency',v_currency,
  'close_date',p_expected_close_date,'notes',coalesce(p_notes,''),'assignee',v_assignee)::text);
 PERFORM pg_advisory_xact_lock(hashtextextended(p_organization_id::text||':'||p_activity_id::text||':'||v_uid::text||':'||btrim(p_idempotency_key),0));
 SELECT * INTO v_existing FROM public.commercial_crm_opportunities
 WHERE organization_id=p_organization_id AND activity_id=p_activity_id
  AND created_by=v_uid AND idempotency_key=btrim(p_idempotency_key) FOR UPDATE;
 IF FOUND THEN
  IF v_existing.request_fingerprint IS DISTINCT FROM v_fp THEN
   RAISE EXCEPTION 'crm_opportunity_idempotency_key_reused' USING ERRCODE='23505';
  END IF;
  RETURN jsonb_build_object('id',v_existing.id,'stage',v_existing.stage,'version',v_existing.version,'replayed',true);
 END IF;
 INSERT INTO public.commercial_crm_opportunities(organization_id,activity_id,lead_id,title,stage,probability,
  expected_value_minor,currency_code,expected_close_date,assigned_to,created_by,notes,idempotency_key,request_fingerprint)
 VALUES(p_organization_id,p_activity_id,p_lead_id,btrim(p_title),p_stage,p_probability,p_expected_value_minor,
  v_currency,p_expected_close_date,v_assignee,v_uid,coalesce(p_notes,''),btrim(p_idempotency_key),v_fp)
 RETURNING id INTO v_id;
 INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
 VALUES(p_organization_id,v_uid,'crm.opportunity.created','crm_opportunity',v_id::text,
  jsonb_build_object('lead_id',p_lead_id,'stage',p_stage,'assigned_to',v_assignee,'value_minor',p_expected_value_minor));
 RETURN jsonb_build_object('id',v_id,'stage',p_stage,'version',1,'replayed',false);
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_crm_opportunity_list(
 p_organization_id uuid,p_activity_id uuid,p_stage text DEFAULT NULL,p_limit integer DEFAULT 100
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $function$
DECLARE v_role text; v_uid uuid:=auth.uid(); v_result jsonb;
BEGIN
 v_role:=public.commercial_crm_context_role(p_organization_id,p_activity_id);
 IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 200
  OR (p_stage IS NOT NULL AND p_stage NOT IN('qualification','needs_analysis','proposal','negotiation','won','lost')) THEN
  RAISE EXCEPTION 'invalid_crm_opportunity_query' USING ERRCODE='22023';
 END IF;
 SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.updated_at DESC,x.id),'[]'::jsonb) INTO v_result
 FROM (
  SELECT o.id,o.lead_id,l.name AS lead_name,o.title,o.stage,o.probability,o.expected_value_minor,
   o.currency_code,o.expected_close_date,o.assigned_to,o.created_by,o.notes,o.lost_reason,
   o.version,o.created_at,o.updated_at
  FROM public.commercial_crm_opportunities o
  JOIN public.commercial_crm_leads l ON l.id=o.lead_id AND l.organization_id=o.organization_id
   AND l.activity_id=o.activity_id
  WHERE o.organization_id=p_organization_id AND o.activity_id=p_activity_id
   AND (p_stage IS NULL OR o.stage=p_stage)
   AND (v_role IN('owner','admin','manager','viewer') OR o.assigned_to=v_uid OR o.created_by=v_uid)
  ORDER BY o.updated_at DESC,o.id LIMIT p_limit
 ) x;
 RETURN v_result;
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_crm_opportunity_update(
 p_opportunity_id uuid,p_expected_version integer,p_stage text,p_probability smallint,
 p_expected_value_minor bigint,p_expected_close_date date,p_notes text,p_lost_reason text
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $function$
DECLARE v_uid uuid:=auth.uid(); v_op public.commercial_crm_opportunities%ROWTYPE; v_role text;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_stage NOT IN('qualification','needs_analysis','proposal','negotiation','won','lost')
  OR p_probability IS NULL OR p_probability NOT BETWEEN 0 AND 100
  OR p_expected_value_minor IS NULL OR p_expected_value_minor<0
  OR length(coalesce(p_notes,''))>4000 OR length(coalesce(p_lost_reason,''))>1000
  OR p_expected_version IS NULL OR p_expected_version<1 THEN
  RAISE EXCEPTION 'invalid_crm_opportunity_update' USING ERRCODE='22023';
 END IF;
 SELECT * INTO v_op FROM public.commercial_crm_opportunities WHERE id=p_opportunity_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'crm_opportunity_not_found' USING ERRCODE='P0002'; END IF;
 v_role:=public.commercial_crm_context_role(v_op.organization_id,v_op.activity_id);
 IF v_role='viewer' OR (v_role='staff' AND v_op.assigned_to<>v_uid AND v_op.created_by<>v_uid) THEN
  RAISE EXCEPTION 'crm_opportunity_update_forbidden' USING ERRCODE='42501';
 END IF;
 IF v_op.version<>p_expected_version THEN RAISE EXCEPTION 'crm_opportunity_version_conflict' USING ERRCODE='40001'; END IF;
 IF p_stage='lost' AND length(btrim(coalesce(p_lost_reason,'')))=0 THEN
  RAISE EXCEPTION 'crm_opportunity_lost_reason_required' USING ERRCODE='22023';
 END IF;
 UPDATE public.commercial_crm_opportunities SET stage=p_stage,probability=p_probability,
  expected_value_minor=p_expected_value_minor,expected_close_date=p_expected_close_date,
  notes=coalesce(p_notes,''),lost_reason=CASE WHEN p_stage='lost' THEN btrim(coalesce(p_lost_reason,'')) ELSE '' END,
  version=version+1,updated_at=now() WHERE id=p_opportunity_id;
 INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
 VALUES(v_op.organization_id,v_uid,'crm.opportunity.updated','crm_opportunity',p_opportunity_id::text,
  jsonb_build_object('from_stage',v_op.stage,'to_stage',p_stage,'version',v_op.version+1));
 RETURN jsonb_build_object('id',p_opportunity_id,'stage',p_stage,'version',v_op.version+1);
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_crm_opportunity_create(uuid,uuid,uuid,text,text,smallint,bigint,text,date,text,uuid,text) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_crm_opportunity_list(uuid,uuid,text,integer) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_crm_opportunity_update(uuid,integer,text,smallint,bigint,date,text,text) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_crm_opportunity_create(uuid,uuid,uuid,text,text,smallint,bigint,text,date,text,uuid,text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_crm_opportunity_list(uuid,uuid,text,integer) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_crm_opportunity_update(uuid,integer,text,smallint,bigint,date,text,text) TO authenticated;

COMMIT;
