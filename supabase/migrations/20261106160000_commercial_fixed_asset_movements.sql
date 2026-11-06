BEGIN;

CREATE TABLE public.commercial_fixed_asset_movements (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 organization_id uuid NOT NULL,
 activity_id uuid NOT NULL,
 asset_id uuid NOT NULL,
 from_location_id uuid REFERENCES public.commercial_organization_locations(id) ON DELETE RESTRICT,
 to_location_id uuid REFERENCES public.commercial_organization_locations(id) ON DELETE RESTRICT,
 from_custodian_id uuid REFERENCES auth.users(id) ON DELETE RESTRICT,
 to_custodian_id uuid REFERENCES auth.users(id) ON DELETE RESTRICT,
 reason text NOT NULL CHECK(length(btrim(reason)) BETWEEN 1 AND 500),
 moved_by uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
 idempotency_key text NOT NULL CHECK(length(btrim(idempotency_key)) BETWEEN 8 AND 200),
 request_fingerprint text NOT NULL CHECK(length(request_fingerprint)=32),
 moved_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT commercial_fixed_asset_movements_asset_fk
  FOREIGN KEY(asset_id,organization_id,activity_id)
  REFERENCES public.commercial_fixed_assets(id,organization_id,activity_id) ON DELETE RESTRICT,
 CONSTRAINT commercial_fixed_asset_movements_idempotency_uq
  UNIQUE(organization_id,activity_id,moved_by,idempotency_key)
);
CREATE INDEX commercial_fixed_asset_movements_asset_recent_idx
 ON public.commercial_fixed_asset_movements(organization_id,activity_id,asset_id,moved_at DESC,id DESC);
ALTER TABLE public.commercial_fixed_asset_movements ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.commercial_fixed_asset_movements FROM PUBLIC,anon,authenticated;

CREATE OR REPLACE FUNCTION public.commercial_fixed_asset_move(
 p_asset_id uuid,p_expected_version integer,p_to_location_id uuid,p_to_custodian_id uuid,
 p_reason text,p_idempotency_key text
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $function$
DECLARE
 v_uid uuid:=auth.uid();
 v_asset public.commercial_fixed_assets%ROWTYPE;
 v_role text;
 v_fp text;
 v_existing public.commercial_fixed_asset_movements%ROWTYPE;
 v_movement_id uuid;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_asset_id IS NULL OR p_expected_version IS NULL OR p_expected_version<1
  OR length(btrim(coalesce(p_reason,''))) NOT BETWEEN 1 AND 500
  OR p_idempotency_key IS NULL OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200 THEN
  RAISE EXCEPTION 'invalid_asset_movement' USING ERRCODE='22023';
 END IF;
 SELECT a.* INTO v_asset FROM public.commercial_fixed_assets a
 JOIN public.commercial_organization_members m ON m.organization_id=a.organization_id
  AND m.user_id=v_uid AND m.state='active'
 WHERE a.id=p_asset_id FOR UPDATE OF a;
 IF NOT FOUND THEN RAISE EXCEPTION 'asset_not_found_or_forbidden' USING ERRCODE='42501'; END IF;
 v_role:=public.commercial_fixed_asset_context_role(v_asset.organization_id,v_asset.activity_id);
 IF v_role NOT IN('owner','admin','manager') THEN RAISE EXCEPTION 'asset_write_permission_required' USING ERRCODE='42501'; END IF;
 IF v_asset.status='disposed' THEN RAISE EXCEPTION 'disposed_asset_cannot_move' USING ERRCODE='22023'; END IF;
 IF p_to_location_id IS NOT NULL AND NOT EXISTS(
  SELECT 1 FROM public.commercial_organization_locations l
  WHERE l.id=p_to_location_id AND l.organization_id=v_asset.organization_id AND l.state='active'
 ) THEN RAISE EXCEPTION 'asset_location_not_in_organization' USING ERRCODE='42501'; END IF;
 IF p_to_custodian_id IS NOT NULL AND NOT EXISTS(
  SELECT 1 FROM public.commercial_organization_members m
  WHERE m.organization_id=v_asset.organization_id AND m.user_id=p_to_custodian_id AND m.state='active'
 ) THEN RAISE EXCEPTION 'asset_custodian_not_active_member' USING ERRCODE='22023'; END IF;
 IF p_to_location_id IS NOT DISTINCT FROM v_asset.location_id
  AND p_to_custodian_id IS NOT DISTINCT FROM v_asset.custodian_id THEN
  RAISE EXCEPTION 'asset_movement_has_no_changes' USING ERRCODE='22023';
 END IF;
 v_fp:=md5(jsonb_build_object('asset_id',p_asset_id,'from_location',v_asset.location_id,
  'to_location',p_to_location_id,'from_custodian',v_asset.custodian_id,
  'to_custodian',p_to_custodian_id,'reason',btrim(p_reason))::text);
 PERFORM pg_advisory_xact_lock(hashtextextended(v_asset.organization_id::text||':'||
  v_asset.activity_id::text||':'||v_uid::text||':'||btrim(p_idempotency_key),0));
 SELECT * INTO v_existing FROM public.commercial_fixed_asset_movements m
 WHERE m.organization_id=v_asset.organization_id AND m.activity_id=v_asset.activity_id
  AND m.moved_by=v_uid AND m.idempotency_key=btrim(p_idempotency_key) FOR UPDATE;
 IF FOUND THEN
  IF v_existing.request_fingerprint IS DISTINCT FROM v_fp THEN
   RAISE EXCEPTION 'asset_movement_idempotency_conflict' USING ERRCODE='23505';
  END IF;
  RETURN jsonb_build_object('id',v_existing.id,'asset_id',p_asset_id,'version',v_asset.version,'replayed',true);
 END IF;
 IF v_asset.version<>p_expected_version THEN RAISE EXCEPTION 'asset_version_conflict' USING ERRCODE='40001'; END IF;
 INSERT INTO public.commercial_fixed_asset_movements(
  organization_id,activity_id,asset_id,from_location_id,to_location_id,
  from_custodian_id,to_custodian_id,reason,moved_by,idempotency_key,request_fingerprint
 ) VALUES (
  v_asset.organization_id,v_asset.activity_id,v_asset.id,v_asset.location_id,p_to_location_id,
  v_asset.custodian_id,p_to_custodian_id,btrim(p_reason),v_uid,btrim(p_idempotency_key),v_fp
 ) RETURNING id INTO v_movement_id;
 UPDATE public.commercial_fixed_assets SET location_id=p_to_location_id,custodian_id=p_to_custodian_id,
  updated_by=v_uid,version=version+1,updated_at=now() WHERE id=p_asset_id AND version=p_expected_version;
 IF NOT FOUND THEN RAISE EXCEPTION 'asset_version_conflict' USING ERRCODE='40001'; END IF;
 INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
 VALUES(v_asset.organization_id,v_uid,'fixed_asset.moved','fixed_asset_movement',v_movement_id::text,
  jsonb_build_object('asset_id',p_asset_id,'from_location_id',v_asset.location_id,
   'to_location_id',p_to_location_id,'from_custodian_id',v_asset.custodian_id,
   'to_custodian_id',p_to_custodian_id,'version',v_asset.version+1));
 RETURN jsonb_build_object('id',v_movement_id,'asset_id',p_asset_id,'version',v_asset.version+1,'replayed',false);
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_fixed_asset_movement_list(
 p_organization_id uuid,p_activity_id uuid,p_asset_id uuid,p_limit integer DEFAULT 100
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $function$
DECLARE v_role text;v_result jsonb;
BEGIN
 v_role:=public.commercial_fixed_asset_context_role(p_organization_id,p_activity_id);
 IF p_asset_id IS NULL OR p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 200 THEN
  RAISE EXCEPTION 'invalid_asset_movement_query' USING ERRCODE='22023';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM public.commercial_fixed_assets a
  WHERE a.id=p_asset_id AND a.organization_id=p_organization_id AND a.activity_id=p_activity_id) THEN
  RAISE EXCEPTION 'asset_not_found' USING ERRCODE='P0002';
 END IF;
 SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.moved_at DESC,x.id),'[]'::jsonb) INTO v_result
 FROM(
  SELECT m.id,m.asset_id,m.from_location_id,fl.name AS from_location_name,
   m.to_location_id,tl.name AS to_location_name,m.from_custodian_id,m.to_custodian_id,
   m.reason,m.moved_by,m.moved_at
  FROM public.commercial_fixed_asset_movements m
  LEFT JOIN public.commercial_organization_locations fl ON fl.id=m.from_location_id
   AND fl.organization_id=m.organization_id
  LEFT JOIN public.commercial_organization_locations tl ON tl.id=m.to_location_id
   AND tl.organization_id=m.organization_id
  WHERE m.organization_id=p_organization_id AND m.activity_id=p_activity_id AND m.asset_id=p_asset_id
  ORDER BY m.moved_at DESC,m.id DESC LIMIT p_limit
 ) x;
 RETURN v_result;
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_fixed_asset_update(
 p_asset_id uuid,p_expected_version integer,p_status text,p_location_id uuid,p_custodian_id uuid,
 p_notes text,p_disposed_at date,p_disposal_proceeds_minor bigint
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $function$
DECLARE v_uid uuid:=auth.uid();v_asset public.commercial_fixed_assets%ROWTYPE;v_role text;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_status NOT IN('draft','active','disposed') OR p_expected_version IS NULL OR p_expected_version<1
  OR length(coalesce(p_notes,''))>4000 OR (p_disposal_proceeds_minor IS NOT NULL AND p_disposal_proceeds_minor<0) THEN
  RAISE EXCEPTION 'invalid_asset_update' USING ERRCODE='22023';
 END IF;
 SELECT a.* INTO v_asset FROM public.commercial_fixed_assets a
 JOIN public.commercial_organization_members m ON m.organization_id=a.organization_id
  AND m.user_id=v_uid AND m.state='active'
 WHERE a.id=p_asset_id FOR UPDATE OF a;
 IF NOT FOUND THEN RAISE EXCEPTION 'asset_not_found_or_forbidden' USING ERRCODE='42501'; END IF;
 v_role:=public.commercial_fixed_asset_context_role(v_asset.organization_id,v_asset.activity_id);
 IF v_role NOT IN('owner','admin','manager') THEN RAISE EXCEPTION 'asset_write_permission_required' USING ERRCODE='42501'; END IF;
 IF v_asset.version<>p_expected_version THEN RAISE EXCEPTION 'asset_version_conflict' USING ERRCODE='40001'; END IF;
 IF p_location_id IS DISTINCT FROM v_asset.location_id OR p_custodian_id IS DISTINCT FROM v_asset.custodian_id THEN
  RAISE EXCEPTION 'asset_movement_requires_movement_rpc' USING ERRCODE='22023';
 END IF;
 IF v_asset.status='disposed' AND p_status<>'disposed' THEN RAISE EXCEPTION 'disposed_asset_is_final' USING ERRCODE='22023'; END IF;
 IF v_asset.status='active' AND p_status='draft' THEN RAISE EXCEPTION 'active_asset_cannot_return_to_draft' USING ERRCODE='22023'; END IF;
 IF p_location_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM public.commercial_organization_locations l
  WHERE l.id=p_location_id AND l.organization_id=v_asset.organization_id AND l.state='active') THEN
  RAISE EXCEPTION 'asset_location_not_in_organization' USING ERRCODE='42501';
 END IF;
 IF p_custodian_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM public.commercial_organization_members m
  WHERE m.organization_id=v_asset.organization_id AND m.user_id=p_custodian_id AND m.state='active') THEN
  RAISE EXCEPTION 'asset_custodian_not_active_member' USING ERRCODE='22023';
 END IF;
 IF p_status='active' AND v_asset.depreciation_start_date IS NULL THEN
  UPDATE public.commercial_fixed_assets SET depreciation_start_date=available_for_use_date
  WHERE id=p_asset_id;
 END IF;
 IF p_status='disposed' AND v_asset.status<>'active' THEN
  RAISE EXCEPTION 'only_active_assets_can_be_disposed' USING ERRCODE='22023';
 END IF;
 IF p_status='disposed' AND (p_disposed_at IS NULL OR p_disposed_at<v_asset.available_for_use_date OR p_disposal_proceeds_minor IS NULL) THEN
  RAISE EXCEPTION 'asset_disposal_details_required' USING ERRCODE='22023';
 END IF;
 UPDATE public.commercial_fixed_assets SET status=p_status,location_id=p_location_id,custodian_id=p_custodian_id,
  notes=coalesce(p_notes,''),disposed_at=CASE WHEN p_status='disposed' THEN p_disposed_at ELSE NULL END,
  disposal_proceeds_minor=CASE WHEN p_status='disposed' THEN p_disposal_proceeds_minor ELSE NULL END,
  updated_by=v_uid,version=version+1,updated_at=now() WHERE id=p_asset_id;
 INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
 VALUES(v_asset.organization_id,v_uid,'fixed_asset.updated','fixed_asset',p_asset_id::text,
  jsonb_build_object('from_status',v_asset.status,'to_status',p_status,'version',v_asset.version+1));
 RETURN jsonb_build_object('id',p_asset_id,'status',p_status,'version',v_asset.version+1);
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_fixed_asset_move(uuid,integer,uuid,uuid,text,text) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_fixed_asset_movement_list(uuid,uuid,uuid,integer) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_fixed_asset_move(uuid,integer,uuid,uuid,text,text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_fixed_asset_movement_list(uuid,uuid,uuid,integer) TO authenticated;

COMMIT;
