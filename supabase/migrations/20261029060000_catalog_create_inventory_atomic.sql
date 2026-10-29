BEGIN;
CREATE TABLE IF NOT EXISTS public.commercial_catalog_inventory_creation_requests (
 organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
 idempotency_key text NOT NULL CHECK(length(btrim(idempotency_key)) BETWEEN 8 AND 200),
 request_fingerprint text NOT NULL,
 global_item_id uuid NOT NULL REFERENCES public.commercial_global_catalog_items(id) ON DELETE RESTRICT,
 location_id uuid NOT NULL REFERENCES public.commercial_organization_locations(id) ON DELETE RESTRICT,
 offering_id uuid NOT NULL REFERENCES public.commercial_offerings(id) ON DELETE RESTRICT,
 response jsonb NOT NULL CHECK(jsonb_typeof(response)='object'),
 created_by uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(organization_id,idempotency_key)
);
ALTER TABLE public.commercial_catalog_inventory_creation_requests ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.commercial_catalog_inventory_creation_requests FROM PUBLIC,anon,authenticated;

CREATE OR REPLACE FUNCTION public.commercial_catalog_create_inventory_item_from_global(
 p_organization_id uuid,p_activity_id uuid,p_activity_node_code text,p_location_id uuid,p_warehouse_id uuid,
 p_global_item_id uuid,p_base_unit_code text,p_sale_unit_code text,p_unit_code text,p_display_name text,
 p_price_minor bigint,p_currency_code text,p_initial_quantity integer,p_reorder_point numeric,p_category_id uuid,p_idempotency_key text
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,commercial_private AS $$
DECLARE v_uid uuid:=auth.uid(); v_node uuid; v_global public.commercial_global_catalog_items%ROWTYPE;
 v_factor numeric(30,12); v_unit_name text; v_result jsonb; v_offering uuid; v_fp text;
 v_saved public.commercial_catalog_inventory_creation_requests%ROWTYPE; v_existing uuid;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_organization_id IS NULL OR p_activity_id IS NULL OR p_location_id IS NULL OR p_warehouse_id IS NULL
  OR p_global_item_id IS NULL OR p_price_minor IS NULL OR p_price_minor<=0 OR p_currency_code IS NULL
  OR p_initial_quantity IS NULL OR p_initial_quantity<0 OR p_initial_quantity>100000000
  OR p_reorder_point IS NULL OR p_reorder_point<0 OR p_currency_code !~ '^[A-Z]{3}$'
  OR p_unit_code IS NULL OR p_base_unit_code IS NULL OR p_sale_unit_code IS NULL
  OR nullif(btrim(p_display_name),'') IS NULL OR length(btrim(p_display_name))>180
  OR length(btrim(coalesce(p_idempotency_key,''))) NOT BETWEEN 8 AND 200
  OR nullif(btrim(p_activity_node_code),'') IS NULL THEN
  RAISE EXCEPTION 'invalid_catalog_inventory_creation_request' USING ERRCODE='22023';
 END IF;
 IF NOT public.commercial_catalog_member_of(p_organization_id,ARRAY['owner','admin','manager']::text[]) THEN
  RAISE EXCEPTION 'catalog_inventory_creation_forbidden' USING ERRCODE='42501';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM public.commercial_activities WHERE id=p_activity_id AND organization_id=p_organization_id AND state='active' AND deleted_at IS NULL) THEN
  RAISE EXCEPTION 'activity_not_in_active_organization' USING ERRCODE='42501';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM public.commercial_organization_locations WHERE id=p_location_id AND organization_id=p_organization_id AND state='active') THEN
  RAISE EXCEPTION 'branch_not_in_organization' USING ERRCODE='22023';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM public.commercial_warehouses WHERE id=p_warehouse_id AND organization_id=p_organization_id AND branch_location_id=p_location_id AND state='active') THEN
  RAISE EXCEPTION 'warehouse_not_in_branch' USING ERRCODE='22023';
 END IF;
 SELECT id INTO v_node FROM public.commercial_global_business_activity_nodes WHERE code=upper(btrim(p_activity_node_code)) AND status='active' AND is_selectable;
 IF v_node IS NULL THEN RAISE EXCEPTION 'active_activity_not_found' USING ERRCODE='22023'; END IF;
 SELECT * INTO v_global FROM public.commercial_global_catalog_items WHERE id=p_global_item_id AND is_active AND (owner_organization_id IS NULL OR owner_organization_id=p_organization_id);
 IF NOT FOUND THEN RAISE EXCEPTION 'catalog_item_not_active_or_accessible' USING ERRCODE='22023'; END IF;
 IF NOT EXISTS(SELECT 1 FROM public.commercial_catalog_product_units WHERE product_id=p_global_item_id AND unit_code=upper(btrim(p_base_unit_code)) AND is_active AND unit_role='base' AND quantity_in_base=1) THEN
  RAISE EXCEPTION 'base_unit_not_defined_by_catalog' USING ERRCODE='22023';
 END IF;
 SELECT quantity_in_base INTO v_factor FROM public.commercial_catalog_product_units WHERE product_id=p_global_item_id AND unit_code=upper(btrim(p_sale_unit_code)) AND is_active ORDER BY is_default DESC,created_at LIMIT 1;
 IF v_factor IS NULL THEN RAISE EXCEPTION 'sale_unit_not_defined_by_catalog' USING ERRCODE='22023'; END IF;
 SELECT display_name INTO v_unit_name FROM public.commercial_unit_of_measure WHERE code=btrim(p_unit_code) AND is_active;
 IF v_unit_name IS NULL THEN RAISE EXCEPTION 'active_unit_of_measure_not_found' USING ERRCODE='22023'; END IF;
 v_fp:=md5(jsonb_build_object('activity_id',p_activity_id,'activity_node_code',upper(btrim(p_activity_node_code)),
  'location_id',p_location_id,'warehouse_id',p_warehouse_id,'global_item_id',p_global_item_id,
  'base_unit_code',upper(btrim(p_base_unit_code)),'sale_unit_code',upper(btrim(p_sale_unit_code)),
  'unit_code',btrim(p_unit_code),'display_name',btrim(p_display_name),'price_minor',p_price_minor,
  'currency_code',upper(p_currency_code),'initial_quantity',p_initial_quantity,'reorder_point',p_reorder_point,
  'category_id',p_category_id)::text);
 PERFORM pg_advisory_xact_lock(hashtext(p_organization_id::text),hashtext('catalog-inventory:'||btrim(p_idempotency_key)));
 SELECT * INTO v_saved FROM public.commercial_catalog_inventory_creation_requests WHERE organization_id=p_organization_id AND idempotency_key=btrim(p_idempotency_key);
 IF FOUND THEN
  IF v_saved.request_fingerprint IS DISTINCT FROM v_fp THEN RAISE EXCEPTION 'catalog_creation_key_reused_with_different_request' USING ERRCODE='23505'; END IF;
  RETURN v_saved.response||jsonb_build_object('idempotent_replay',true);
 END IF;
 SELECT m.offering_id INTO v_existing FROM public.commercial_catalog_item_offerings m
 JOIN public.commercial_offerings o ON o.id=m.offering_id AND o.activity_id=p_activity_id AND o.location_ref=p_location_id::text AND o.state='active'
 WHERE m.organization_id=p_organization_id AND m.global_item_id=p_global_item_id AND m.location_id=p_location_id LIMIT 1;
 IF v_existing IS NOT NULL THEN
  v_result:=jsonb_build_object('global_item_id',p_global_item_id,'offering_id',v_existing,'location_id',p_location_id,
   'warehouse_id',p_warehouse_id,'created_new_offering',false,'already_linked',true,'idempotent_replay',false);
  INSERT INTO public.commercial_catalog_inventory_creation_requests(organization_id,idempotency_key,request_fingerprint,global_item_id,location_id,offering_id,response,created_by)
  VALUES(p_organization_id,btrim(p_idempotency_key),v_fp,p_global_item_id,p_location_id,v_existing,v_result,v_uid);
  RETURN v_result;
 END IF;
 INSERT INTO public.commercial_organization_catalog_items(organization_id,global_item_id,merchant_display_name,merchant_description,price_minor,currency_code,is_available,metadata,created_by,is_enabled,imported_at,pricing_status)
 VALUES(p_organization_id,p_global_item_id,v_global.name_ar,v_global.description_ar,0,upper(p_currency_code),false,
  jsonb_build_object('linked_without_pricing',true,'pricing_required',true,'source','global_catalog'),v_uid,true,now(),'unpriced')
 ON CONFLICT(organization_id,global_item_id) DO NOTHING;
 INSERT INTO public.commercial_organization_catalog_item_activities(organization_id,global_item_id,activity_node_id,created_by)
 VALUES(p_organization_id,p_global_item_id,v_node,v_uid) ON CONFLICT DO NOTHING;
 INSERT INTO public.commercial_organization_catalog_item_setup(organization_id,global_item_id,activity_node_id,branch_location_id,warehouse_id,base_unit_code,sale_unit_code,sale_quantity_in_base,created_by,updated_at)
 VALUES(p_organization_id,p_global_item_id,v_node,p_location_id,p_warehouse_id,upper(btrim(p_base_unit_code)),upper(btrim(p_sale_unit_code)),v_factor,v_uid,now())
 ON CONFLICT(organization_id,global_item_id,activity_node_id,branch_location_id,warehouse_id)
 DO UPDATE SET base_unit_code=EXCLUDED.base_unit_code,sale_unit_code=EXCLUDED.sale_unit_code,sale_quantity_in_base=EXCLUDED.sale_quantity_in_base,updated_at=now();
 v_result:=public.commercial_create_inventory_item_with_category_and_unit(p_organization_id,p_activity_id,p_location_id,btrim(p_display_name),v_unit_name,p_price_minor,upper(p_currency_code),p_initial_quantity,p_reorder_point,p_category_id,btrim(p_unit_code));
 v_offering:=(v_result->>'offering_id')::uuid;
 INSERT INTO public.commercial_catalog_item_offerings(organization_id,global_item_id,offering_id,location_id,linked_by)
 VALUES(p_organization_id,p_global_item_id,v_offering,p_location_id,v_uid);
 v_result:=v_result||jsonb_build_object('global_item_id',p_global_item_id,'warehouse_id',p_warehouse_id,
  'activity_node_code',upper(btrim(p_activity_node_code)),'created_new_offering',true,'already_linked',false,'idempotent_replay',false);
 INSERT INTO public.commercial_catalog_inventory_creation_requests(organization_id,idempotency_key,request_fingerprint,global_item_id,location_id,offering_id,response,created_by)
 VALUES(p_organization_id,btrim(p_idempotency_key),v_fp,p_global_item_id,p_location_id,v_offering,v_result,v_uid);
 INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
 VALUES(p_organization_id,v_uid,'catalog_inventory_item.created','offering',v_offering::text,jsonb_build_object('global_item_id',p_global_item_id,'activity_node_code',upper(btrim(p_activity_node_code)),'location_id',p_location_id,'warehouse_id',p_warehouse_id,'price_minor',p_price_minor,'currency_code',upper(p_currency_code),'initial_quantity',p_initial_quantity));
 RETURN v_result;
END; $$;
REVOKE ALL ON FUNCTION public.commercial_catalog_create_inventory_item_from_global(uuid,uuid,text,uuid,uuid,uuid,text,text,text,text,bigint,text,integer,numeric,uuid,text) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_catalog_create_inventory_item_from_global(uuid,uuid,text,uuid,uuid,uuid,text,text,text,text,bigint,text,integer,numeric,uuid,text) TO authenticated;
COMMIT;
