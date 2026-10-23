-- Secure catalog discovery endpoint used by activity-aware product pickers and recommendations.
CREATE OR REPLACE FUNCTION public.commercial_catalog_discover_products(
  p_organization_id uuid,
  p_sector_code text DEFAULT NULL,
  p_business_level_code text DEFAULT NULL,
  p_country_code text DEFAULT NULL,
  p_region_code text DEFAULT NULL,
  p_city text DEFAULT NULL,
  p_locale_code text DEFAULT 'ar',
  p_query text DEFAULT NULL,
  p_limit integer DEFAULT 50
) RETURNS TABLE (
  product_id uuid,
  product_name text,
  short_description text,
  category_id uuid,
  category_name text,
  product_code text,
  brand text,
  manufacturer_name text,
  country_of_manufacture text,
  image_url text,
  is_prescription_only boolean,
  recommendation_score integer
)
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, public
AS $$
BEGIN
  IF auth.uid() IS NULL THEN
    RAISE EXCEPTION 'authentication_required' USING ERRCODE = '42501';
  END IF;
  IF NOT public.commercial_catalog_member_of(
    p_organization_id, ARRAY['owner','admin','manager','staff']::text[]
  ) THEN
    RAISE EXCEPTION 'catalog_discovery_forbidden' USING ERRCODE = '42501';
  END IF;
  IF p_locale_code IS NULL OR length(btrim(p_locale_code)) NOT BETWEEN 2 AND 35 THEN
    RAISE EXCEPTION 'invalid_locale_code' USING ERRCODE = '22023';
  END IF;

  RETURN QUERY
  SELECT
    p.id,
    COALESCE(pt.name, p.default_name),
    COALESCE(pt.short_description, p.short_description),
    p.category_id,
    COALESCE(ct.name, c.default_name),
    p.product_code,
    p.brand,
    p.manufacturer_name,
    p.country_of_manufacture,
    p.image_url,
    p.is_prescription_only,
    COALESCE((
      SELECT max(bl.recommendation_weight)::integer
      FROM public.commercial_catalog_product_business_levels bl
      WHERE bl.product_id = p.id
        AND p_business_level_code IS NOT NULL
        AND bl.business_level_code = p_business_level_code
        AND NOT bl.is_excluded
    ), 0)
    + CASE WHEN p_sector_code IS NOT NULL AND EXISTS (
        SELECT 1 FROM public.commercial_catalog_product_sectors s
        WHERE s.product_id=p.id AND NOT s.is_excluded
          AND s.sector_code IN ('all', p_sector_code)
      ) THEN 20 ELSE 0 END
  FROM public.commercial_catalog_products p
  LEFT JOIN public.commercial_catalog_product_translations pt
    ON pt.product_id=p.id AND pt.locale_code=p_locale_code
  LEFT JOIN public.commercial_catalog_categories c ON c.id=p.category_id
  LEFT JOIN public.commercial_catalog_category_translations ct
    ON ct.category_id=c.id AND ct.locale_code=p_locale_code
  WHERE p.is_active
    AND NOT EXISTS (
      SELECT 1 FROM public.commercial_organization_catalog_items disabled_item
      WHERE disabled_item.organization_id=p_organization_id
        AND disabled_item.product_id=p.id AND NOT disabled_item.is_enabled
    )
    AND (p.owner_organization_id IS NULL OR p.owner_organization_id=p_organization_id
      OR EXISTS (
        SELECT 1 FROM public.commercial_organization_catalog_items oi
        WHERE oi.organization_id=p_organization_id AND oi.product_id=p.id AND oi.is_enabled
      ))
    AND (
      p_query IS NULL OR btrim(p_query)='' OR
      COALESCE(pt.name,p.default_name) ILIKE '%' || btrim(p_query) || '%' OR
      COALESCE(pt.short_description,p.short_description,'') ILIKE '%' || btrim(p_query) || '%' OR
      COALESCE(p.product_code,'') ILIKE '%' || btrim(p_query) || '%' OR
      COALESCE(p.brand,'') ILIKE '%' || btrim(p_query) || '%' OR
      EXISTS (
        SELECT 1 FROM public.commercial_catalog_product_units pu
        WHERE pu.product_id=p.id AND pu.is_active AND pu.barcode=btrim(p_query)
      )
    )
    AND NOT EXISTS (
      SELECT 1 FROM public.commercial_catalog_product_sectors sx
      WHERE sx.product_id=p.id AND sx.is_excluded
        AND (sx.sector_code='all' OR (p_sector_code IS NOT NULL AND sx.sector_code=p_sector_code))
    )
    AND (
      NOT EXISTS (
        SELECT 1 FROM public.commercial_catalog_product_sectors si
        WHERE si.product_id=p.id AND NOT si.is_excluded
      )
      OR EXISTS (
        SELECT 1 FROM public.commercial_catalog_product_sectors si
        WHERE si.product_id=p.id AND NOT si.is_excluded
          AND (si.sector_code='all' OR (p_sector_code IS NOT NULL AND si.sector_code=p_sector_code))
      )
    )
    AND NOT EXISTS (
      SELECT 1 FROM public.commercial_catalog_product_business_levels bx
      WHERE bx.product_id=p.id AND bx.is_excluded
        AND p_business_level_code IS NOT NULL AND bx.business_level_code=p_business_level_code
    )
    AND (
      NOT EXISTS (
        SELECT 1 FROM public.commercial_catalog_product_business_levels bi
        WHERE bi.product_id=p.id AND NOT bi.is_excluded
      )
      OR EXISTS (
        SELECT 1 FROM public.commercial_catalog_product_business_levels bi
        WHERE bi.product_id=p.id AND NOT bi.is_excluded
          AND p_business_level_code IS NOT NULL
          AND bi.business_level_code=p_business_level_code
      )
    )
    AND NOT EXISTS (
      SELECT 1 FROM public.commercial_catalog_product_regions rx
      WHERE rx.product_id=p.id AND rx.is_excluded
        AND (rx.country_code IS NULL OR rx.country_code=upper(p_country_code))
        AND (rx.region_code IS NULL OR rx.region_code=p_region_code)
        AND (rx.city IS NULL OR lower(rx.city)=lower(p_city))
    )
    AND (
      NOT EXISTS (
        SELECT 1 FROM public.commercial_catalog_product_regions ri
        WHERE ri.product_id=p.id AND NOT ri.is_excluded
      )
      OR EXISTS (
        SELECT 1 FROM public.commercial_catalog_product_regions ri
        WHERE ri.product_id=p.id AND NOT ri.is_excluded
          AND (ri.country_code IS NULL OR ri.country_code=upper(p_country_code))
          AND (ri.region_code IS NULL OR ri.region_code=p_region_code)
          AND (ri.city IS NULL OR lower(ri.city)=lower(p_city))
      )
    )
  ORDER BY 12 DESC, COALESCE(pt.name,p.default_name), p.id
  LIMIT greatest(1, least(COALESCE(p_limit,50),100));
END;
$$;

REVOKE ALL ON FUNCTION public.commercial_catalog_discover_products(uuid,text,text,text,text,text,text,text,integer) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_catalog_discover_products(uuid,text,text,text,text,text,text,text,integer) TO authenticated;

COMMENT ON FUNCTION public.commercial_catalog_discover_products(uuid,text,text,text,text,text,text,text,integer) IS
'Searches shared and organization-linked products by text/barcode, locale, sector, business level and region; requires active organization membership and returns relevance-ranked candidates.';
