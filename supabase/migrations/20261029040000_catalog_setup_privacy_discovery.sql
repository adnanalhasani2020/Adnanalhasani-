-- Explicit catalog-to-inventory identity bridge and privacy-preserving market aggregates.
BEGIN;
CREATE TABLE IF NOT EXISTS public.commercial_organization_catalog_item_setup (
 organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE CASCADE,
 global_item_id uuid NOT NULL REFERENCES public.commercial_global_catalog_items(id) ON DELETE RESTRICT,
 activity_node_id uuid NOT NULL REFERENCES public.commercial_global_business_activity_nodes(id) ON DELETE RESTRICT,
 branch_location_id uuid NOT NULL REFERENCES public.commercial_organization_locations(id) ON DELETE RESTRICT,
 warehouse_id uuid NOT NULL REFERENCES public.commercial_warehouses(id) ON DELETE RESTRICT,
 base_unit_code text NOT NULL REFERENCES public.commercial_global_units(code) ON DELETE RESTRICT,
 sale_unit_code text NOT NULL REFERENCES public.commercial_global_units(code) ON DELETE RESTRICT,
 sale_quantity_in_base numeric(30,12) NOT NULL CHECK(sale_quantity_in_base>0),
 created_by uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
 updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(organization_id,global_item_id,activity_node_id),
 FOREIGN KEY(organization_id,global_item_id)
  REFERENCES public.commercial_organization_catalog_items(organization_id,global_item_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS ix_org_catalog_setup_branch
 ON public.commercial_organization_catalog_item_setup(organization_id,branch_location_id,warehouse_id);
CREATE TABLE IF NOT EXISTS public.commercial_catalog_item_offerings (
 organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE CASCADE,
 global_item_id uuid NOT NULL REFERENCES public.commercial_global_catalog_items(id) ON DELETE RESTRICT,
 offering_id uuid NOT NULL UNIQUE REFERENCES public.commercial_offerings(id) ON DELETE CASCADE,
 location_id uuid NOT NULL REFERENCES public.commercial_organization_locations(id) ON DELETE RESTRICT,
 linked_by uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
 linked_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(organization_id,global_item_id,offering_id),
 FOREIGN KEY(organization_id,global_item_id)
  REFERENCES public.commercial_organization_catalog_items(organization_id,global_item_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS ix_catalog_item_offerings_item_location
 ON public.commercial_catalog_item_offerings(global_item_id,location_id);
ALTER TABLE public.commercial_organization_catalog_item_setup ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.commercial_catalog_item_offerings ENABLE ROW LEVEL SECURITY;
GRANT SELECT ON public.commercial_organization_catalog_item_setup,public.commercial_catalog_item_offerings TO authenticated;
DROP POLICY IF EXISTS commercial_catalog_item_setup_member_read ON public.commercial_organization_catalog_item_setup;
CREATE POLICY commercial_catalog_item_setup_member_read ON public.commercial_organization_catalog_item_setup
 FOR SELECT TO authenticated USING(public.commercial_catalog_member_of(organization_id,ARRAY['owner','admin','manager','staff']::text[]));
DROP POLICY IF EXISTS commercial_catalog_item_offerings_member_read ON public.commercial_catalog_item_offerings;
CREATE POLICY commercial_catalog_item_offerings_member_read ON public.commercial_catalog_item_offerings
 FOR SELECT TO authenticated USING(public.commercial_catalog_member_of(organization_id,ARRAY['owner','admin','manager','staff']::text[]));

CREATE OR REPLACE FUNCTION public.commercial_catalog_configure_items(
 p_organization_id uuid,p_activity_node_code text,p_branch_location_id uuid,p_warehouse_id uuid,p_items jsonb
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE v_uid uuid:=auth.uid(); v_node uuid; v_item jsonb; v_gid uuid; v_base text; v_sale text;
 v_factor numeric(30,12); v_count integer; v_added integer:=0; v_done integer:=0; v_rows_added integer:=0;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_organization_id IS NULL OR p_branch_location_id IS NULL OR p_warehouse_id IS NULL
  OR nullif(btrim(p_activity_node_code),'') IS NULL OR jsonb_typeof(p_items) IS DISTINCT FROM 'array' THEN
  RAISE EXCEPTION 'invalid_catalog_setup_request' USING ERRCODE='22023';
 END IF;
 IF jsonb_array_length(p_items) NOT BETWEEN 1 AND 100 THEN
  RAISE EXCEPTION 'invalid_catalog_setup_request' USING ERRCODE='22023';
 END IF;
 IF NOT public.commercial_catalog_member_of(p_organization_id,ARRAY['owner','admin','manager']::text[]) THEN
  RAISE EXCEPTION 'catalog_setup_forbidden' USING ERRCODE='42501';
 END IF;
 SELECT id INTO v_node FROM public.commercial_global_business_activity_nodes
  WHERE code=upper(btrim(p_activity_node_code)) AND status='active' AND is_selectable;
 IF v_node IS NULL THEN RAISE EXCEPTION 'active_activity_not_found' USING ERRCODE='22023'; END IF;
 IF NOT EXISTS(SELECT 1 FROM public.commercial_organization_locations
  WHERE id=p_branch_location_id AND organization_id=p_organization_id AND state='active') THEN
  RAISE EXCEPTION 'branch_not_in_organization' USING ERRCODE='22023';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM public.commercial_warehouses
  WHERE id=p_warehouse_id AND organization_id=p_organization_id AND branch_location_id=p_branch_location_id AND state='active') THEN
  RAISE EXCEPTION 'warehouse_not_in_branch' USING ERRCODE='22023';
 END IF;
 SELECT count(DISTINCT x->>'global_item_id') INTO v_count FROM jsonb_array_elements(p_items) x;
 IF v_count<>jsonb_array_length(p_items) OR EXISTS(SELECT 1 FROM jsonb_array_elements(p_items) x
  WHERE nullif(x->>'global_item_id','') IS NULL OR nullif(x->>'base_unit_code','') IS NULL OR nullif(x->>'sale_unit_code','') IS NULL) THEN
  RAISE EXCEPTION 'duplicate_or_incomplete_catalog_item_selection' USING ERRCODE='22023';
 END IF;
 FOR v_item IN SELECT value FROM jsonb_array_elements(p_items) LOOP
  v_gid:=(v_item->>'global_item_id')::uuid; v_base:=upper(btrim(v_item->>'base_unit_code')); v_sale:=upper(btrim(v_item->>'sale_unit_code'));
  IF NOT EXISTS(SELECT 1 FROM public.commercial_global_catalog_items
   WHERE id=v_gid AND is_active AND (owner_organization_id IS NULL OR owner_organization_id=p_organization_id)) THEN
   RAISE EXCEPTION 'catalog_item_not_active_or_accessible' USING ERRCODE='22023';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM public.commercial_catalog_product_units
   WHERE product_id=v_gid AND unit_code=v_base AND is_active AND unit_role='base' AND quantity_in_base=1) THEN
   RAISE EXCEPTION 'base_unit_not_defined_by_catalog' USING ERRCODE='22023';
  END IF;
  SELECT quantity_in_base INTO v_factor FROM public.commercial_catalog_product_units
   WHERE product_id=v_gid AND unit_code=v_sale AND is_active ORDER BY is_default DESC,created_at LIMIT 1;
  IF v_factor IS NULL THEN RAISE EXCEPTION 'sale_unit_not_defined_by_catalog' USING ERRCODE='22023'; END IF;
  INSERT INTO public.commercial_organization_catalog_items(organization_id,global_item_id,merchant_display_name,merchant_description,
   price_minor,currency_code,is_available,metadata,created_by,is_enabled,imported_at,pricing_status)
  SELECT p_organization_id,p.id,p.name_ar,p.description_ar,0,'YER',false,
   jsonb_build_object('linked_without_pricing',true,'pricing_required',true,'source','global_catalog'),v_uid,true,now(),'unpriced'
  FROM public.commercial_global_catalog_items p WHERE p.id=v_gid
  ON CONFLICT(organization_id,global_item_id) DO NOTHING;
  GET DIAGNOSTICS v_rows_added=ROW_COUNT;
  v_added:=v_added+v_rows_added;
  INSERT INTO public.commercial_organization_catalog_item_activities(organization_id,global_item_id,activity_node_id,created_by)
   VALUES(p_organization_id,v_gid,v_node,v_uid) ON CONFLICT DO NOTHING;
  INSERT INTO public.commercial_organization_catalog_item_setup(organization_id,global_item_id,activity_node_id,
   branch_location_id,warehouse_id,base_unit_code,sale_unit_code,sale_quantity_in_base,created_by,updated_at)
  VALUES(p_organization_id,v_gid,v_node,p_branch_location_id,p_warehouse_id,v_base,v_sale,v_factor,v_uid,now())
  ON CONFLICT(organization_id,global_item_id,activity_node_id) DO UPDATE SET
   branch_location_id=EXCLUDED.branch_location_id,warehouse_id=EXCLUDED.warehouse_id,
   base_unit_code=EXCLUDED.base_unit_code,sale_unit_code=EXCLUDED.sale_unit_code,
   sale_quantity_in_base=EXCLUDED.sale_quantity_in_base,updated_at=now();
  v_done:=v_done+1;
 END LOOP;
 RETURN jsonb_build_object('requested_count',v_count,'newly_linked_count',v_added,'configured_count',v_done,
  'pricing_required',true,'stock_created',false);
END; $$;
REVOKE ALL ON FUNCTION public.commercial_catalog_configure_items(uuid,text,uuid,uuid,jsonb) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_catalog_configure_items(uuid,text,uuid,uuid,jsonb) TO authenticated;

CREATE OR REPLACE FUNCTION public.commercial_catalog_bind_existing_offering(
 p_organization_id uuid,p_global_item_id uuid,p_offering_id uuid,p_location_id uuid
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE v_uid uuid:=auth.uid(); v_org uuid; v_loc text;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF NOT public.commercial_catalog_member_of(p_organization_id,ARRAY['owner','admin','manager']::text[]) THEN
  RAISE EXCEPTION 'catalog_binding_forbidden' USING ERRCODE='42501';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM public.commercial_organization_catalog_items
  WHERE organization_id=p_organization_id AND global_item_id=p_global_item_id AND is_enabled) THEN
  RAISE EXCEPTION 'catalog_item_must_be_linked_first' USING ERRCODE='22023';
 END IF;
 SELECT a.organization_id,o.location_ref INTO v_org,v_loc FROM public.commercial_offerings o
  JOIN public.commercial_activities a ON a.id=o.activity_id WHERE o.id=p_offering_id AND o.state='active';
 IF v_org IS DISTINCT FROM p_organization_id OR v_loc IS DISTINCT FROM p_location_id::text
  OR NOT EXISTS(SELECT 1 FROM public.commercial_organization_locations
   WHERE id=p_location_id AND organization_id=p_organization_id AND state='active') THEN
  RAISE EXCEPTION 'offering_location_or_organization_mismatch' USING ERRCODE='42501';
 END IF;
 INSERT INTO public.commercial_catalog_item_offerings(organization_id,global_item_id,offering_id,location_id,linked_by)
 VALUES(p_organization_id,p_global_item_id,p_offering_id,p_location_id,v_uid)
 ON CONFLICT(organization_id,global_item_id,offering_id) DO NOTHING;
 RETURN jsonb_build_object('linked',true,'global_item_id',p_global_item_id,'offering_id',p_offering_id,'location_id',p_location_id);
END; $$;
REVOKE ALL ON FUNCTION public.commercial_catalog_bind_existing_offering(uuid,uuid,uuid,uuid) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_catalog_bind_existing_offering(uuid,uuid,uuid,uuid) TO authenticated;

CREATE OR REPLACE FUNCTION public.commercial_catalog_price_suggestions(
 p_organization_id uuid,p_global_item_ids uuid[],p_latitude numeric,p_longitude numeric,
 p_radius_m integer DEFAULT 25000,p_currency_code text DEFAULT 'YER'
) RETURNS TABLE(global_item_id uuid,currency_code text,suggested_median_minor bigint,
 lower_quartile_minor bigint,upper_quartile_minor bigint,merchant_count_band text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
BEGIN
 IF auth.uid() IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF NOT public.commercial_catalog_member_of(p_organization_id,ARRAY['owner','admin','manager','staff']::text[]) THEN
  RAISE EXCEPTION 'price_suggestions_forbidden' USING ERRCODE='42501';
 END IF;
 IF p_latitude NOT BETWEEN -90 AND 90 OR p_longitude NOT BETWEEN -180 AND 180
  OR p_radius_m NOT BETWEEN 1000 AND 100000 OR upper(p_currency_code) !~ '^[A-Z]{3}$'
  OR p_global_item_ids IS NULL OR cardinality(p_global_item_ids) NOT BETWEEN 1 AND 100 THEN
  RAISE EXCEPTION 'invalid_price_suggestion_request' USING ERRCODE='22023';
 END IF;
 RETURN QUERY WITH prices AS (
  SELECT m.global_item_id,upper(o.currency_code) currency_code,o.price_minor,m.organization_id,
   6371000*2*asin(sqrt(power(sin(radians((l.latitude-p_latitude)::double precision)/2),2)+
   cos(radians(p_latitude::double precision))*cos(radians(l.latitude::double precision))*
   power(sin(radians((l.longitude-p_longitude)::double precision)/2),2))) distance_m
  FROM public.commercial_catalog_item_offerings m JOIN public.commercial_offerings o ON o.id=m.offering_id AND o.state='active'
  JOIN public.commercial_organization_locations l ON l.id=m.location_id AND l.state='active'
  WHERE m.global_item_id=ANY(p_global_item_ids) AND m.organization_id<>p_organization_id
   AND o.price_minor>0 AND upper(o.currency_code)=upper(p_currency_code)
   AND l.latitude IS NOT NULL AND l.longitude IS NOT NULL
 ), eligible AS(SELECT * FROM prices WHERE distance_m<=p_radius_m)
 SELECT e.global_item_id,e.currency_code,percentile_cont(0.5) WITHIN GROUP(ORDER BY e.price_minor)::bigint,
  percentile_cont(0.25) WITHIN GROUP(ORDER BY e.price_minor)::bigint,
  percentile_cont(0.75) WITHIN GROUP(ORDER BY e.price_minor)::bigint,
  CASE WHEN count(DISTINCT e.organization_id)>=20 THEN '20+' WHEN count(DISTINCT e.organization_id)>=10 THEN '10-19' ELSE '5-9' END
 FROM eligible e GROUP BY e.global_item_id,e.currency_code HAVING count(DISTINCT e.organization_id)>=5;
END; $$;
REVOKE ALL ON FUNCTION public.commercial_catalog_price_suggestions(uuid,uuid[],numeric,numeric,integer,text) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_catalog_price_suggestions(uuid,uuid[],numeric,numeric,integer,text) TO authenticated;

CREATE OR REPLACE FUNCTION public.commercial_catalog_nearby_popularity(
 p_organization_id uuid,p_latitude numeric,p_longitude numeric,p_radius_m integer DEFAULT 5000,
 p_offset integer DEFAULT 0,p_limit integer DEFAULT 30
) RETURNS TABLE(global_item_id uuid,product_name text,popularity_band text,merchant_count_band text,next_radius_m integer)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
BEGIN
 IF auth.uid() IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF NOT public.commercial_catalog_member_of(p_organization_id,ARRAY['owner','admin','manager','staff']::text[]) THEN
  RAISE EXCEPTION 'nearby_catalog_forbidden' USING ERRCODE='42501';
 END IF;
 IF p_latitude NOT BETWEEN -90 AND 90 OR p_longitude NOT BETWEEN -180 AND 180
  OR p_radius_m NOT IN(5000,10000,25000,50000,100000) OR p_offset<0 OR p_limit NOT BETWEEN 1 AND 100 THEN
  RAISE EXCEPTION 'invalid_nearby_discovery_request' USING ERRCODE='22023';
 END IF;
 RETURN QUERY WITH sold AS (
  SELECT m.global_item_id,s.organization_id,sum(si.quantity::numeric) sold_qty,
   6371000*2*asin(sqrt(power(sin(radians((l.latitude-p_latitude)::double precision)/2),2)+
   cos(radians(p_latitude::double precision))*cos(radians(l.latitude::double precision))*
   power(sin(radians((l.longitude-p_longitude)::double precision)/2),2))) distance_m
  FROM public.commercial_catalog_item_offerings m JOIN public.commercial_sale_items si ON si.offering_id=m.offering_id
  JOIN public.commercial_sales s ON s.id=si.sale_id AND s.organization_id=m.organization_id
   AND s.status='completed' AND s.created_at>=now()-interval '30 days'
  JOIN public.commercial_organization_locations l ON l.id=m.location_id AND l.organization_id=m.organization_id AND l.state='active'
  WHERE m.organization_id<>p_organization_id AND l.latitude IS NOT NULL AND l.longitude IS NOT NULL
  GROUP BY m.global_item_id,s.organization_id,l.latitude,l.longitude
 ), ag AS (
  SELECT global_item_id,count(DISTINCT organization_id)::integer merchants,sum(sold_qty)::numeric qty
  FROM sold WHERE distance_m<=p_radius_m GROUP BY global_item_id HAVING count(DISTINCT organization_id)>=5
 )
 SELECT p.id,coalesce(p.name_ar,p.name_en),
  CASE WHEN ag.qty>=1000 THEN 'high' WHEN ag.qty>=100 THEN 'medium' ELSE 'emerging' END,
  CASE WHEN ag.merchants>=20 THEN '20+' WHEN ag.merchants>=10 THEN '10-19' ELSE '5-9' END,
  least(100000,p_radius_m*2)
 FROM ag JOIN public.commercial_global_catalog_items p ON p.id=ag.global_item_id
 WHERE p.is_active ORDER BY ag.qty DESC,p.id OFFSET p_offset LIMIT p_limit;
END; $$;
REVOKE ALL ON FUNCTION public.commercial_catalog_nearby_popularity(uuid,numeric,numeric,integer,integer,integer) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_catalog_nearby_popularity(uuid,numeric,numeric,integer,integer,integer) TO authenticated;
COMMIT;
