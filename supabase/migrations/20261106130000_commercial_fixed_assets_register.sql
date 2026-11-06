BEGIN;

CREATE TABLE public.commercial_fixed_assets (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
 activity_id uuid NOT NULL REFERENCES public.commercial_activities(id) ON DELETE RESTRICT,
 asset_code text NOT NULL CHECK(length(btrim(asset_code)) BETWEEN 1 AND 64),
 asset_name text NOT NULL CHECK(length(btrim(asset_name)) BETWEEN 1 AND 180),
 category text NOT NULL CHECK(length(btrim(category)) BETWEEN 1 AND 100),
 serial_number text NOT NULL DEFAULT '' CHECK(length(serial_number)<=120),
 description text NOT NULL DEFAULT '' CHECK(length(description)<=4000),
 acquisition_date date NOT NULL,
 available_for_use_date date NOT NULL,
 acquisition_cost_minor bigint NOT NULL CHECK(acquisition_cost_minor>0),
 salvage_value_minor bigint NOT NULL DEFAULT 0 CHECK(salvage_value_minor>=0),
 currency_code text NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),
 useful_life_months integer NOT NULL CHECK(useful_life_months BETWEEN 1 AND 600),
 depreciation_method text NOT NULL DEFAULT 'straight_line'
  CHECK(depreciation_method IN('straight_line','double_declining')),
 status text NOT NULL DEFAULT 'draft' CHECK(status IN('draft','active','disposed')),
 location_id uuid REFERENCES public.commercial_organization_locations(id) ON DELETE RESTRICT,
 custodian_id uuid REFERENCES auth.users(id) ON DELETE RESTRICT,
 depreciation_start_date date,
 disposed_at date,
 disposal_proceeds_minor bigint CHECK(disposal_proceeds_minor>=0),
 notes text NOT NULL DEFAULT '' CHECK(length(notes)<=4000),
 created_by uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
 updated_by uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
 idempotency_key text NOT NULL CHECK(length(btrim(idempotency_key)) BETWEEN 8 AND 200),
 request_fingerprint text NOT NULL CHECK(length(request_fingerprint)=32),
 version integer NOT NULL DEFAULT 1 CHECK(version>0),
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT commercial_fixed_assets_org_activity_id_uq UNIQUE(organization_id,activity_id,id),
 CONSTRAINT commercial_fixed_assets_code_uq UNIQUE(organization_id,activity_id,asset_code),
 CONSTRAINT commercial_fixed_assets_idempotency_uq UNIQUE(organization_id,activity_id,created_by,idempotency_key),
 CONSTRAINT commercial_fixed_assets_dates_ck CHECK(acquisition_date<=available_for_use_date),
 CONSTRAINT commercial_fixed_assets_salvage_ck CHECK(salvage_value_minor<acquisition_cost_minor),
 CONSTRAINT commercial_fixed_assets_disposal_ck CHECK(
  (status<>'disposed' AND disposed_at IS NULL AND disposal_proceeds_minor IS NULL)
  OR (status='disposed' AND disposed_at IS NOT NULL AND disposed_at>=available_for_use_date AND disposal_proceeds_minor IS NOT NULL)
 ),
 CONSTRAINT commercial_fixed_assets_active_ck CHECK(status<>'active' OR depreciation_start_date IS NOT NULL)
);
CREATE INDEX commercial_fixed_assets_activity_status_idx
 ON public.commercial_fixed_assets(organization_id,activity_id,status,asset_name);
CREATE INDEX commercial_fixed_assets_custodian_idx
 ON public.commercial_fixed_assets(organization_id,activity_id,custodian_id)
 WHERE custodian_id IS NOT NULL;
ALTER TABLE public.commercial_fixed_assets ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.commercial_fixed_assets FROM PUBLIC,anon,authenticated;

CREATE OR REPLACE FUNCTION public.commercial_fixed_asset_context_role(
 p_organization_id uuid,p_activity_id uuid
) RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $function$
DECLARE v_uid uuid:=auth.uid();v_role text;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_organization_id IS NULL OR p_activity_id IS NULL THEN RAISE EXCEPTION 'asset_context_required' USING ERRCODE='22023'; END IF;
 IF NOT EXISTS(SELECT 1 FROM public.commercial_organizations o WHERE o.id=p_organization_id AND o.state='active' AND o.deleted_at IS NULL)
 THEN RAISE EXCEPTION 'asset_organization_not_available' USING ERRCODE='42501'; END IF;
 SELECT m.role INTO v_role FROM public.commercial_organization_members m
 WHERE m.organization_id=p_organization_id AND m.user_id=v_uid AND m.state='active';
 IF v_role IS NULL THEN RAISE EXCEPTION 'asset_membership_required' USING ERRCODE='42501'; END IF;
 IF NOT EXISTS(SELECT 1 FROM public.commercial_activities a WHERE a.id=p_activity_id
  AND a.organization_id=p_organization_id AND a.state='active' AND a.deleted_at IS NULL)
 THEN RAISE EXCEPTION 'asset_activity_not_available' USING ERRCODE='42501'; END IF;
 RETURN v_role;
END;
$function$;
REVOKE ALL ON FUNCTION public.commercial_fixed_asset_context_role(uuid,uuid) FROM PUBLIC,anon,authenticated;

CREATE OR REPLACE FUNCTION public.commercial_fixed_asset_create(
 p_organization_id uuid,p_activity_id uuid,p_asset_code text,p_asset_name text,p_category text,
 p_serial_number text,p_description text,p_acquisition_date date,p_available_for_use_date date,
 p_acquisition_cost_minor bigint,p_salvage_value_minor bigint,p_currency_code text,
 p_useful_life_months integer,p_depreciation_method text,p_location_id uuid,p_custodian_id uuid,
 p_notes text,p_idempotency_key text
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $function$
DECLARE v_uid uuid:=auth.uid();v_role text;v_currency text;v_fp text;v_existing public.commercial_fixed_assets%ROWTYPE;v_id uuid;
BEGIN
 v_role:=public.commercial_fixed_asset_context_role(p_organization_id,p_activity_id);
 IF v_role NOT IN('owner','admin','manager') THEN RAISE EXCEPTION 'asset_write_permission_required' USING ERRCODE='42501'; END IF;
 IF length(btrim(coalesce(p_asset_code,''))) NOT BETWEEN 1 AND 64
  OR length(btrim(coalesce(p_asset_name,''))) NOT BETWEEN 1 AND 180
  OR length(btrim(coalesce(p_category,''))) NOT BETWEEN 1 AND 100
  OR length(coalesce(p_serial_number,''))>120 OR length(coalesce(p_description,''))>4000
  OR p_acquisition_date IS NULL OR p_available_for_use_date IS NULL OR p_acquisition_date>p_available_for_use_date
  OR p_acquisition_cost_minor IS NULL OR p_acquisition_cost_minor<=0
  OR p_salvage_value_minor IS NULL OR p_salvage_value_minor<0 OR p_salvage_value_minor>=p_acquisition_cost_minor
  OR (p_currency_code IS NOT NULL AND p_currency_code !~ '^[A-Z]{3}$')
  OR p_useful_life_months IS NULL OR p_useful_life_months NOT BETWEEN 1 AND 600
  OR p_depreciation_method NOT IN('straight_line','double_declining')
  OR length(coalesce(p_notes,''))>4000
  OR p_idempotency_key IS NULL OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200 THEN
  RAISE EXCEPTION 'invalid_fixed_asset' USING ERRCODE='22023';
 END IF;
 IF p_location_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM public.commercial_organization_locations l
  WHERE l.id=p_location_id AND l.organization_id=p_organization_id AND l.state='active') THEN
  RAISE EXCEPTION 'asset_location_not_in_organization' USING ERRCODE='42501';
 END IF;
 IF p_custodian_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM public.commercial_organization_members m
  WHERE m.organization_id=p_organization_id AND m.user_id=p_custodian_id AND m.state='active') THEN
  RAISE EXCEPTION 'asset_custodian_not_active_member' USING ERRCODE='22023';
 END IF;
 SELECT default_currency_code INTO v_currency FROM public.commercial_organizations
 WHERE id=p_organization_id AND state='active' AND deleted_at IS NULL;
 v_currency:=coalesce(p_currency_code,v_currency);
 IF v_currency IS NULL THEN RAISE EXCEPTION 'asset_organization_not_available' USING ERRCODE='42501'; END IF;
 v_fp:=md5(jsonb_build_object('code',upper(btrim(p_asset_code)),'name',btrim(p_asset_name),
  'category',btrim(p_category),'serial',coalesce(p_serial_number,''),'description',coalesce(p_description,''),
  'acquisition',p_acquisition_date,'available',p_available_for_use_date,'cost',p_acquisition_cost_minor,
  'salvage',p_salvage_value_minor,'currency',v_currency,'life',p_useful_life_months,
  'method',p_depreciation_method,'location',p_location_id,'custodian',p_custodian_id,'notes',coalesce(p_notes,''))::text);
 PERFORM pg_advisory_xact_lock(hashtextextended(p_organization_id::text||':'||p_activity_id::text||':'||v_uid::text||':'||btrim(p_idempotency_key),0));
 SELECT * INTO v_existing FROM public.commercial_fixed_assets a WHERE a.organization_id=p_organization_id
  AND a.activity_id=p_activity_id AND a.created_by=v_uid AND a.idempotency_key=btrim(p_idempotency_key) FOR UPDATE;
 IF FOUND THEN
  IF v_existing.request_fingerprint IS DISTINCT FROM v_fp THEN RAISE EXCEPTION 'asset_idempotency_key_reused' USING ERRCODE='23505'; END IF;
  RETURN jsonb_build_object('id',v_existing.id,'status',v_existing.status,'version',v_existing.version,'replayed',true);
 END IF;
 INSERT INTO public.commercial_fixed_assets(organization_id,activity_id,asset_code,asset_name,category,
  serial_number,description,acquisition_date,available_for_use_date,acquisition_cost_minor,salvage_value_minor,
  currency_code,useful_life_months,depreciation_method,location_id,custodian_id,notes,created_by,updated_by,
  idempotency_key,request_fingerprint)
 VALUES(p_organization_id,p_activity_id,upper(btrim(p_asset_code)),btrim(p_asset_name),btrim(p_category),
  coalesce(btrim(p_serial_number),''),coalesce(p_description,''),p_acquisition_date,p_available_for_use_date,
  p_acquisition_cost_minor,p_salvage_value_minor,v_currency,p_useful_life_months,p_depreciation_method,
  p_location_id,p_custodian_id,coalesce(p_notes,''),v_uid,v_uid,btrim(p_idempotency_key),v_fp)
 RETURNING id INTO v_id;
 INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
 VALUES(p_organization_id,v_uid,'fixed_asset.created','fixed_asset',v_id::text,
  jsonb_build_object('activity_id',p_activity_id,'asset_code',upper(btrim(p_asset_code)),'cost_minor',p_acquisition_cost_minor));
 RETURN jsonb_build_object('id',v_id,'status','draft','version',1,'replayed',false);
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_fixed_asset_list(
 p_organization_id uuid,p_activity_id uuid,p_status text DEFAULT NULL,p_limit integer DEFAULT 100
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $function$
DECLARE v_role text;v_result jsonb;
BEGIN
 v_role:=public.commercial_fixed_asset_context_role(p_organization_id,p_activity_id);
 IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 200
  OR (p_status IS NOT NULL AND p_status NOT IN('draft','active','disposed')) THEN
  RAISE EXCEPTION 'invalid_asset_query' USING ERRCODE='22023';
 END IF;
 SELECT coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.asset_name,x.id),'[]'::jsonb) INTO v_result
 FROM(
  SELECT a.id,a.asset_code,a.asset_name,a.category,a.serial_number,a.description,a.acquisition_date,
   a.available_for_use_date,a.acquisition_cost_minor,a.salvage_value_minor,a.currency_code,
   a.useful_life_months,a.depreciation_method,a.status,a.location_id,l.name AS location_name,
   a.custodian_id,a.depreciation_start_date,a.disposed_at,a.disposal_proceeds_minor,a.notes,a.version,
   a.created_at,a.updated_at
  FROM public.commercial_fixed_assets a
  LEFT JOIN public.commercial_organization_locations l ON l.id=a.location_id AND l.organization_id=a.organization_id
  WHERE a.organization_id=p_organization_id AND a.activity_id=p_activity_id
   AND (p_status IS NULL OR a.status=p_status)
  ORDER BY a.asset_name,a.id LIMIT p_limit
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
 SELECT * INTO v_asset FROM public.commercial_fixed_assets WHERE id=p_asset_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'asset_not_found' USING ERRCODE='P0002'; END IF;
 v_role:=public.commercial_fixed_asset_context_role(v_asset.organization_id,v_asset.activity_id);
 IF v_role NOT IN('owner','admin','manager') THEN RAISE EXCEPTION 'asset_write_permission_required' USING ERRCODE='42501'; END IF;
 IF v_asset.version<>p_expected_version THEN RAISE EXCEPTION 'asset_version_conflict' USING ERRCODE='40001'; END IF;
 IF v_asset.status='disposed' AND p_status<>'disposed' THEN RAISE EXCEPTION 'disposed_asset_is_final' USING ERRCODE='22023'; END IF;
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

CREATE OR REPLACE FUNCTION public.commercial_fixed_asset_depreciation_preview(
 p_organization_id uuid,p_activity_id uuid,p_asset_id uuid,p_limit integer DEFAULT 600
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $function$
DECLARE v_role text;v_asset public.commercial_fixed_assets%ROWTYPE;v_book bigint;v_base bigint;
 v_month integer:=0;v_amount bigint;v_total bigint:=0;v_rows jsonb:='[]'::jsonb;v_period date;v_rate numeric;
BEGIN
 v_role:=public.commercial_fixed_asset_context_role(p_organization_id,p_activity_id);
 IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 600 THEN RAISE EXCEPTION 'invalid_depreciation_preview_limit' USING ERRCODE='22023'; END IF;
 SELECT * INTO v_asset FROM public.commercial_fixed_assets a
 WHERE a.id=p_asset_id AND a.organization_id=p_organization_id AND a.activity_id=p_activity_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'asset_not_found' USING ERRCODE='P0002'; END IF;
 IF v_asset.status='draft' THEN RAISE EXCEPTION 'asset_must_be_active_before_depreciation_preview' USING ERRCODE='22023'; END IF;
 v_book:=v_asset.acquisition_cost_minor;v_base:=v_asset.acquisition_cost_minor-v_asset.salvage_value_minor;
 v_rate:=2.0/v_asset.useful_life_months;
 v_period:=date_trunc('month',v_asset.depreciation_start_date)::date;
 WHILE v_month<v_asset.useful_life_months AND v_month<p_limit AND v_book>v_asset.salvage_value_minor LOOP
  v_month:=v_month+1;
  IF v_asset.depreciation_method='straight_line' THEN
   v_amount:=CASE WHEN v_month=v_asset.useful_life_months THEN v_book-v_asset.salvage_value_minor
    ELSE least(v_book-v_asset.salvage_value_minor,round(v_base::numeric/v_asset.useful_life_months)::bigint) END;
  ELSE
   v_amount:=least(v_book-v_asset.salvage_value_minor,round(v_book::numeric*v_rate)::bigint);
   IF v_month=v_asset.useful_life_months THEN v_amount:=v_book-v_asset.salvage_value_minor; END IF;
  END IF;
  v_rows:=v_rows||jsonb_build_array(jsonb_build_object('period',to_char(v_period,'YYYY-MM'),
   'opening_book_value_minor',v_book,'depreciation_minor',v_amount,'closing_book_value_minor',v_book-v_amount));
  v_book:=v_book-v_amount;v_total:=v_total+v_amount;v_period:=(v_period+interval '1 month')::date;
 END LOOP;
 RETURN jsonb_build_object('asset_id',v_asset.id,'currency_code',v_asset.currency_code,
  'method',v_asset.depreciation_method,'total_preview_depreciation_minor',v_total,
  'closing_book_value_minor',v_book,'periods',v_rows);
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_fixed_asset_create(uuid,uuid,text,text,text,text,text,date,date,bigint,bigint,text,integer,text,uuid,uuid,text,text) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_fixed_asset_list(uuid,uuid,text,integer) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_fixed_asset_update(uuid,integer,text,uuid,uuid,text,date,bigint) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_fixed_asset_depreciation_preview(uuid,uuid,uuid,integer) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_fixed_asset_create(uuid,uuid,text,text,text,text,text,date,date,bigint,bigint,text,integer,text,uuid,uuid,text,text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_fixed_asset_list(uuid,uuid,text,integer) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_fixed_asset_update(uuid,integer,text,uuid,uuid,text,date,bigint) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_fixed_asset_depreciation_preview(uuid,uuid,uuid,integer) TO authenticated;

COMMIT;
