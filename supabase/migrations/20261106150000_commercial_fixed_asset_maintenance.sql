BEGIN;

CREATE TABLE public.commercial_fixed_asset_maintenance (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
 activity_id uuid NOT NULL REFERENCES public.commercial_activities(id) ON DELETE RESTRICT,
 asset_id uuid NOT NULL,
 maintenance_type text NOT NULL CHECK(maintenance_type IN('preventive','corrective','inspection')),
 title text NOT NULL CHECK(length(btrim(title)) BETWEEN 1 AND 180),
 description text NOT NULL DEFAULT '' CHECK(length(description)<=4000),
 scheduled_date date NOT NULL,
 due_date date,
 status text NOT NULL DEFAULT 'scheduled' CHECK(status IN('scheduled','in_progress','completed','cancelled')),
 assigned_to uuid REFERENCES auth.users(id) ON DELETE RESTRICT,
 vendor_name text NOT NULL DEFAULT '' CHECK(length(vendor_name)<=180),
 completed_at date,
 cost_minor bigint NOT NULL DEFAULT 0 CHECK(cost_minor>=0),
 currency_code text NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),
 notes text NOT NULL DEFAULT '' CHECK(length(notes)<=4000),
 created_by uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
 updated_by uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
 idempotency_key text NOT NULL CHECK(length(btrim(idempotency_key)) BETWEEN 8 AND 200),
 request_fingerprint text NOT NULL CHECK(length(request_fingerprint)=32),
 version integer NOT NULL DEFAULT 1 CHECK(version>0),
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT commercial_fixed_asset_maintenance_asset_fk
  FOREIGN KEY(organization_id,activity_id,asset_id)
  REFERENCES public.commercial_fixed_assets(organization_id,activity_id,id) ON DELETE RESTRICT,
 CONSTRAINT commercial_fixed_asset_maintenance_idempotency_uq
  UNIQUE(organization_id,activity_id,created_by,idempotency_key),
 CONSTRAINT commercial_fixed_asset_maintenance_due_ck CHECK(due_date IS NULL OR due_date>=scheduled_date),
 CONSTRAINT commercial_fixed_asset_maintenance_completed_ck CHECK(
  (status='completed' AND completed_at IS NOT NULL AND completed_at>=scheduled_date)
  OR (status<>'completed' AND completed_at IS NULL)
 )
);
CREATE INDEX commercial_fixed_asset_maintenance_schedule_idx
 ON public.commercial_fixed_asset_maintenance(organization_id,activity_id,status,scheduled_date,due_date);
CREATE INDEX commercial_fixed_asset_maintenance_assignee_idx
 ON public.commercial_fixed_asset_maintenance(organization_id,activity_id,assigned_to,scheduled_date)
 WHERE assigned_to IS NOT NULL;
ALTER TABLE public.commercial_fixed_asset_maintenance ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.commercial_fixed_asset_maintenance FROM PUBLIC,anon,authenticated;

CREATE OR REPLACE FUNCTION public.commercial_fixed_asset_maintenance_create(
 p_organization_id uuid,p_activity_id uuid,p_asset_id uuid,p_maintenance_type text,
 p_title text,p_description text,p_scheduled_date date,p_due_date date,p_assigned_to uuid,
 p_vendor_name text,p_cost_minor bigint,p_currency_code text,p_notes text,p_idempotency_key text
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $function$
DECLARE v_uid uuid:=auth.uid();v_role text;v_currency text;v_assignee uuid;
 v_fp text;v_existing public.commercial_fixed_asset_maintenance%ROWTYPE;v_id uuid;
BEGIN
 v_role:=public.commercial_fixed_asset_context_role(p_organization_id,p_activity_id);
 IF v_role NOT IN('owner','admin','manager') THEN RAISE EXCEPTION 'asset_maintenance_write_permission_required' USING ERRCODE='42501'; END IF;
 IF p_maintenance_type NOT IN('preventive','corrective','inspection')
  OR length(btrim(coalesce(p_title,''))) NOT BETWEEN 1 AND 180
  OR length(coalesce(p_description,''))>4000 OR p_scheduled_date IS NULL
  OR (p_due_date IS NOT NULL AND p_due_date<p_scheduled_date)
  OR length(coalesce(p_vendor_name,''))>180 OR p_cost_minor IS NULL OR p_cost_minor<0
  OR (p_currency_code IS NOT NULL AND p_currency_code !~ '^[A-Z]{3}$')
  OR length(coalesce(p_notes,''))>4000
  OR p_idempotency_key IS NULL OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200 THEN
  RAISE EXCEPTION 'invalid_asset_maintenance' USING ERRCODE='22023';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM public.commercial_fixed_assets a
  WHERE a.id=p_asset_id AND a.organization_id=p_organization_id AND a.activity_id=p_activity_id AND a.status='active') THEN
  RAISE EXCEPTION 'maintenance_asset_not_active_or_available' USING ERRCODE='42501';
 END IF;
 v_assignee:=coalesce(p_assigned_to,v_uid);
 IF NOT EXISTS(SELECT 1 FROM public.commercial_organization_members m
  WHERE m.organization_id=p_organization_id AND m.user_id=v_assignee AND m.state='active') THEN
  RAISE EXCEPTION 'maintenance_assignee_not_active_member' USING ERRCODE='22023';
 END IF;
 SELECT default_currency_code INTO v_currency FROM public.commercial_organizations
 WHERE id=p_organization_id AND state='active' AND deleted_at IS NULL;
 v_currency:=coalesce(p_currency_code,v_currency);
 IF v_currency IS NULL THEN RAISE EXCEPTION 'maintenance_organization_not_available' USING ERRCODE='42501'; END IF;
 v_fp:=md5(jsonb_build_object('asset',p_asset_id,'type',p_maintenance_type,'title',btrim(p_title),
  'description',coalesce(p_description,''),'scheduled',p_scheduled_date,'due',p_due_date,
  'assignee',v_assignee,'vendor',coalesce(p_vendor_name,''),'cost',p_cost_minor,
  'currency',v_currency,'notes',coalesce(p_notes,''))::text);
 PERFORM pg_advisory_xact_lock(hashtextextended(p_organization_id::text||':'||p_activity_id::text||':'||v_uid::text||':'||btrim(p_idempotency_key),0));
 SELECT * INTO v_existing FROM public.commercial_fixed_asset_maintenance m
 WHERE m.organization_id=p_organization_id AND m.activity_id=p_activity_id
  AND m.created_by=v_uid AND m.idempotency_key=btrim(p_idempotency_key) FOR UPDATE;
 IF FOUND THEN
  IF v_existing.request_fingerprint IS DISTINCT FROM v_fp THEN
   RAISE EXCEPTION 'asset_maintenance_idempotency_key_reused' USING ERRCODE='23505';
  END IF;
  RETURN jsonb_build_object('id',v_existing.id,'status',v_existing.status,'version',v_existing.version,'replayed',true);
 END IF;
 INSERT INTO public.commercial_fixed_asset_maintenance(organization_id,activity_id,asset_id,maintenance_type,
  title,description,scheduled_date,due_date,status,assigned_to,vendor_name,cost_minor,currency_code,notes,
  created_by,updated_by,idempotency_key,request_fingerprint)
 VALUES(p_organization_id,p_activity_id,p_asset_id,p_maintenance_type,btrim(p_title),coalesce(p_description,''),
  p_scheduled_date,p_due_date,'scheduled',v_assignee,coalesce(p_vendor_name,''),p_cost_minor,v_currency,
  coalesce(p_notes,''),v_uid,v_uid,btrim(p_idempotency_key),v_fp)
 RETURNING id INTO v_id;
 INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
 VALUES(p_organization_id,v_uid,'fixed_asset.maintenance.created','fixed_asset_maintenance',v_id::text,
  jsonb_build_object('asset_id',p_asset_id,'type',p_maintenance_type,'scheduled_date',p_scheduled_date,'assigned_to',v_assignee));
 RETURN jsonb_build_object('id',v_id,'status','scheduled','version',1,'replayed',false);
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_fixed_asset_maintenance_list(
 p_organization_id uuid,p_activity_id uuid,p_status text DEFAULT NULL,p_limit integer DEFAULT 100
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $function$
DECLARE v_uid uuid:=auth.uid();v_role text;v_result jsonb;
BEGIN
 v_role:=public.commercial_fixed_asset_context_role(p_organization_id,p_activity_id);
 IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 200
  OR (p_status IS NOT NULL AND p_status NOT IN('scheduled','in_progress','completed','cancelled')) THEN
  RAISE EXCEPTION 'invalid_asset_maintenance_query' USING ERRCODE='22023';
 END IF;
 SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.scheduled_date,x.id),'[]'::jsonb) INTO v_result
 FROM(
  SELECT m.id,m.asset_id,a.asset_code,a.asset_name,m.maintenance_type,m.title,m.description,
   m.scheduled_date,m.due_date,m.status,m.assigned_to,m.vendor_name,m.completed_at,m.cost_minor,
   m.currency_code,m.notes,m.version,m.created_at,m.updated_at
  FROM public.commercial_fixed_asset_maintenance m
  JOIN public.commercial_fixed_assets a ON a.id=m.asset_id AND a.organization_id=m.organization_id
   AND a.activity_id=m.activity_id
  WHERE m.organization_id=p_organization_id AND m.activity_id=p_activity_id
   AND (p_status IS NULL OR m.status=p_status)
   AND (v_role IN('owner','admin','manager','viewer') OR m.assigned_to=v_uid OR m.created_by=v_uid)
  ORDER BY m.scheduled_date,m.id LIMIT p_limit
 ) x;
 RETURN v_result;
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_fixed_asset_maintenance_update(
 p_maintenance_id uuid,p_expected_version integer,p_status text,p_completed_at date,
 p_vendor_name text,p_cost_minor bigint,p_notes text
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $function$
DECLARE v_uid uuid:=auth.uid();v_item public.commercial_fixed_asset_maintenance%ROWTYPE;v_role text;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_status NOT IN('scheduled','in_progress','completed','cancelled')
  OR p_completed_at IS NOT NULL AND p_status<>'completed'
  OR length(coalesce(p_vendor_name,''))>180 OR p_cost_minor IS NULL OR p_cost_minor<0
  OR length(coalesce(p_notes,''))>4000 OR p_expected_version IS NULL OR p_expected_version<1 THEN
  RAISE EXCEPTION 'invalid_asset_maintenance_update' USING ERRCODE='22023';
 END IF;
 SELECT m.* INTO v_item FROM public.commercial_fixed_asset_maintenance m
 JOIN public.commercial_organization_members member ON member.organization_id=m.organization_id
  AND member.user_id=v_uid AND member.state='active'
 WHERE m.id=p_maintenance_id FOR UPDATE OF m;
 IF NOT FOUND THEN RAISE EXCEPTION 'asset_maintenance_not_found_or_forbidden' USING ERRCODE='42501'; END IF;
 v_role:=public.commercial_fixed_asset_context_role(v_item.organization_id,v_item.activity_id);
 IF v_role NOT IN('owner','admin','manager') AND v_item.assigned_to<>v_uid THEN
  RAISE EXCEPTION 'asset_maintenance_update_forbidden' USING ERRCODE='42501';
 END IF;
 IF v_item.status IN('completed','cancelled') AND p_status<>v_item.status THEN
  RAISE EXCEPTION 'asset_maintenance_final_status' USING ERRCODE='22023';
 END IF;
 IF p_status='completed' AND p_completed_at IS NULL THEN
  RAISE EXCEPTION 'asset_maintenance_completion_date_required' USING ERRCODE='22023';
 END IF;
 IF p_status='completed' AND p_completed_at<v_item.scheduled_date THEN
  RAISE EXCEPTION 'asset_maintenance_completion_before_schedule' USING ERRCODE='22023';
 END IF;
 IF v_item.version<>p_expected_version THEN RAISE EXCEPTION 'asset_maintenance_version_conflict' USING ERRCODE='40001'; END IF;
 UPDATE public.commercial_fixed_asset_maintenance SET status=p_status,
  completed_at=CASE WHEN p_status='completed' THEN p_completed_at ELSE NULL END,
  vendor_name=coalesce(p_vendor_name,''),cost_minor=p_cost_minor,notes=coalesce(p_notes,''),
  updated_by=v_uid,version=version+1,updated_at=now()
 WHERE id=p_maintenance_id;
 INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
 VALUES(v_item.organization_id,v_uid,'fixed_asset.maintenance.updated','fixed_asset_maintenance',p_maintenance_id::text,
  jsonb_build_object('from_status',v_item.status,'to_status',p_status,'version',v_item.version+1,'cost_minor',p_cost_minor));
 RETURN jsonb_build_object('id',p_maintenance_id,'status',p_status,'version',v_item.version+1);
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_fixed_asset_maintenance_create(uuid,uuid,uuid,text,text,text,date,date,uuid,text,bigint,text,text,text) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_fixed_asset_maintenance_list(uuid,uuid,text,integer) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_fixed_asset_maintenance_update(uuid,integer,text,date,text,bigint,text) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_fixed_asset_maintenance_create(uuid,uuid,uuid,text,text,text,date,date,uuid,text,bigint,text,text,text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_fixed_asset_maintenance_list(uuid,uuid,text,integer) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_fixed_asset_maintenance_update(uuid,integer,text,date,text,bigint,text) TO authenticated;

COMMIT;
