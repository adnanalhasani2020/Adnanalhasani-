-- Prevent nearby discovery from exposing names of catalog items the caller cannot read.
-- SECURITY DEFINER bypasses table RLS, so this RPC must apply the same item visibility rule explicitly.
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
 IF p_latitude IS NULL OR p_longitude IS NULL OR p_latitude NOT BETWEEN -90 AND 90 OR p_longitude NOT BETWEEN -180 AND 180
  OR p_radius_m NOT IN(5000,10000,25000,50000,100000) OR p_offset<0 OR p_limit NOT BETWEEN 1 AND 100 THEN
  RAISE EXCEPTION 'invalid_nearby_discovery_request' USING ERRCODE='22023';
 END IF;
 RETURN QUERY WITH sold AS (
  SELECT m.global_item_id,s.organization_id,count(DISTINCT s.id)::numeric transaction_count,
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
  SELECT global_item_id,count(DISTINCT organization_id)::integer merchants,sum(transaction_count)::numeric transactions
  FROM sold WHERE distance_m<=p_radius_m GROUP BY global_item_id HAVING count(DISTINCT organization_id)>=5
 )
 SELECT p.id,coalesce(p.name_ar,p.name_en),
  CASE WHEN ag.transactions>=100 THEN 'high' WHEN ag.transactions>=25 THEN 'medium' ELSE 'emerging' END,
  CASE WHEN ag.merchants>=20 THEN '20+' WHEN ag.merchants>=10 THEN '10-19' ELSE '5-9' END,
  least(100000,p_radius_m*2)
 FROM ag JOIN public.commercial_global_catalog_items p ON p.id=ag.global_item_id
 WHERE p.is_active
  AND (
   p.owner_organization_id IS NULL
   OR public.commercial_catalog_member_of(p.owner_organization_id,ARRAY['owner','admin','manager','staff']::text[])
   OR EXISTS (
    SELECT 1 FROM public.commercial_organization_catalog_items oi
    WHERE oi.organization_id=p_organization_id AND oi.global_item_id=p.id
   )
  )
 ORDER BY ag.transactions DESC,p.id OFFSET p_offset LIMIT p_limit;
END; $$;
REVOKE ALL ON FUNCTION public.commercial_catalog_nearby_popularity(uuid,numeric,numeric,integer,integer,integer) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_catalog_nearby_popularity(uuid,numeric,numeric,integer,integer,integer) TO authenticated;
COMMENT ON FUNCTION public.commercial_catalog_nearby_popularity(uuid,numeric,numeric,integer,integer,integer) IS
'Privacy-preserving nearby popularity aggregates. Returns only catalog items visible to the requesting authenticated user; merchant identities and per-merchant rows are never returned.';
