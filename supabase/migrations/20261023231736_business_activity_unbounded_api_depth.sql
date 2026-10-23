-- Remove the arbitrary depth cap while retaining cycle-safe recursive traversal.
CREATE OR REPLACE FUNCTION public.commercial_list_business_activity_nodes()
RETURNS jsonb
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = pg_catalog, public
AS $$
  WITH RECURSIVE tree AS (
    SELECT n.id,n.code,n.parent_code,n.name_ar,n.name_en,n.description_ar,n.description_en,
      n.node_kind,n.is_selectable,n.default_business_level_code,n.sort_order,0 AS depth,
      n.name_ar::text AS path_ar,n.name_en::text AS path_en
    FROM public.commercial_global_business_activity_nodes n
    WHERE n.parent_code IS NULL AND n.status='active'
    UNION ALL
    SELECT c.id,c.code,c.parent_code,c.name_ar,c.name_en,c.description_ar,c.description_en,
      c.node_kind,c.is_selectable,c.default_business_level_code,c.sort_order,t.depth+1,
      t.path_ar || ' ← ' || c.name_ar,t.path_en || ' > ' || c.name_en
    FROM public.commercial_global_business_activity_nodes c
    JOIN tree t ON c.parent_code=t.code
    WHERE c.status='active'
  )
  CYCLE code SET is_cycle USING cycle_path
  SELECT jsonb_build_object('nodes',COALESCE(jsonb_agg(jsonb_build_object(
    'id',id,'code',code,'parent_code',parent_code,'name_ar',name_ar,'name_en',name_en,
    'description_ar',description_ar,'description_en',description_en,'node_kind',node_kind,
    'is_selectable',is_selectable,'default_business_level_code',default_business_level_code,
    'depth',depth,'path_ar',path_ar,'path_en',path_en
  ) ORDER BY path_ar),'[]'::jsonb))
  FROM tree WHERE is_selectable AND NOT is_cycle;
$$;
REVOKE ALL ON FUNCTION public.commercial_list_business_activity_nodes() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.commercial_list_business_activity_nodes() TO anon, authenticated;
