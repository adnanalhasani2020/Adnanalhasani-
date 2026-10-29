-- Activity-, business-level-, locale- and region-aware discovery over the existing catalog master.
CREATE OR REPLACE FUNCTION public.commercial_catalog_discover_products_page(
  p_organization_id uuid,
  p_sector_code text DEFAULT NULL,
  p_business_level_code text DEFAULT NULL,
  p_country_code text DEFAULT NULL,
  p_region_code text DEFAULT NULL,
  p_city text DEFAULT NULL,
  p_locale_code text DEFAULT 'ar',
  p_query text DEFAULT NULL,
  p_limit integer DEFAULT 50,
  p_offset integer DEFAULT 0
) RETURNS TABLE (
  product_id uuid,
  product_name text,
  short_description text,
  category_code text,
  category_name text,
  catalog_code text,
  brand text,
  manufacturer_name text,
  country_of_manufacture text,
  image_url text,
  is_prescription_only boolean,
  recommendation_score integer,
  barcode text,
  unit_code text,
  available_units jsonb,
  created_by uuid,
  created_at timestamptz,
  attributes_schema jsonb,
  material_composition jsonb,
  ingredients jsonb,
  recipe jsonb,
  specifications jsonb,
  allergens jsonb,
  storage_instructions text,
  usage_instructions text,
  warnings text,
  shelf_life_days integer,
  requires_expiry_date boolean,
  is_batch_tracked boolean,
  is_serial_tracked boolean,
  rulings jsonb,
  model_number text,
  part_number text,
  image_alt text,
  localized_details jsonb,
  owner_organization_id uuid,
  name_ar text,
  name_en text,
  description_ar text,
  description_en text,
  item_type text,
  barcode_gtin text
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
    COALESCE(oname.translated_value, gtname.translated_value,
      CASE WHEN p_locale_code LIKE 'en%' THEN p.name_en ELSE p.name_ar END),
    COALESCE(odesc.translated_value, gtdesc.translated_value,
      CASE WHEN p_locale_code LIKE 'en%' THEN p.description_en ELSE p.description_ar END),
    p.category_code,
    COALESCE(ct.translated_value,
      CASE WHEN p_locale_code LIKE 'en%' THEN c.name_en ELSE c.name_ar END),
    p.catalog_code,
    p.brand,
    p.manufacturer_name,
    p.country_of_manufacture,
    p.image_url,
    p.is_prescription_only,
    COALESCE((
      SELECT max(bl.recommendation_weight)::integer
      FROM public.commercial_catalog_product_business_levels bl
      WHERE bl.product_id=p.id AND p_business_level_code IS NOT NULL
        AND bl.business_level_code=p_business_level_code AND NOT bl.is_excluded
    ),0)
    + CASE WHEN p_sector_code IS NOT NULL AND EXISTS (
      SELECT 1 FROM public.commercial_catalog_product_sectors s
      WHERE s.product_id=p.id AND NOT s.is_excluded
        AND s.sector_code IN ('all',p_sector_code)
    ) THEN 20 ELSE 0 END,
    p.barcode_gtin,
    COALESCE(default_unit.unit_code,p.unit_code,'EA'),
    COALESCE(unit_list.units,'[]'::jsonb),
    p.created_by,
    p.created_at,
    p.attributes_schema,
    p.material_composition,
    p.ingredients,
    p.recipe,
    p.specifications,
    p.allergens,
    p.storage_instructions,
    p.usage_instructions,
    p.warnings,
    p.shelf_life_days,
    p.requires_expiry_date,
    p.is_batch_tracked,
    p.is_serial_tracked,
    COALESCE(ruling_list.rulings,'[]'::jsonb),
    p.model_number,
    p.part_number,
    p.image_alt,
    COALESCE(localized_details.fields,'{}'::jsonb),
    p.owner_organization_id,
    p.name_ar,
    p.name_en,
    p.description_ar,
    p.description_en,
    p.item_type,
    p.barcode_gtin
  FROM public.commercial_global_catalog_items p
  LEFT JOIN LATERAL (
    SELECT pu.unit_code
    FROM public.commercial_catalog_product_units pu
    WHERE pu.product_id=p.id AND pu.is_active
    ORDER BY pu.is_default DESC, CASE pu.unit_role WHEN 'base' THEN 0 WHEN 'sale' THEN 1 ELSE 2 END, pu.created_at
    LIMIT 1
  ) default_unit ON true
  LEFT JOIN LATERAL (
    SELECT jsonb_agg(jsonb_build_object(
      'unit_code',pu.unit_code,'unit_name',COALESCE(ut.translated_value,CASE WHEN p_locale_code LIKE 'en%' THEN u.name_en ELSE u.name_ar END),
      'unit_role',pu.unit_role,'quantity_in_base',pu.quantity_in_base,
      'barcode',pu.barcode,'sku',pu.sku,'package_name',pu.package_name,
      'net_quantity',pu.net_quantity,'net_unit_code',pu.net_unit_code,'image_url',pu.image_url,
      'is_default',pu.is_default
    ) ORDER BY pu.is_default DESC, pu.unit_role, pu.unit_code) AS units
    FROM public.commercial_catalog_product_units pu
    JOIN public.commercial_global_units u ON u.code=pu.unit_code
    LEFT JOIN public.commercial_global_entity_translations ut
      ON ut.entity_type='unit' AND ut.entity_key=pu.unit_code AND ut.field_key='name'
      AND ut.locale_code=p_locale_code AND ut.owner_organization_id IS NOT DISTINCT FROM u.owner_organization_id
    WHERE pu.product_id=p.id AND pu.is_active
  ) unit_list ON true
  LEFT JOIN LATERAL (
    SELECT jsonb_object_agg(t.field_key,t.translated_value) AS fields
    FROM (
      SELECT DISTINCT ON (candidates.field_key) candidates.field_key,candidates.translated_value
      FROM (
        SELECT ot.field_key,ot.translated_value,1 AS priority
        FROM public.commercial_organization_entity_translations ot
        WHERE ot.organization_id=p_organization_id AND ot.entity_type='catalog_item'
          AND ot.entity_id=p.id AND ot.locale_code=p_locale_code
          AND ot.field_key NOT IN ('name','description')
        UNION ALL
        SELECT gt.field_key,gt.translated_value,2 AS priority
        FROM public.commercial_global_entity_translations gt
        WHERE gt.entity_type='catalog_item' AND gt.entity_key=p.catalog_code
          AND gt.locale_code=p_locale_code AND gt.owner_organization_id IS NOT DISTINCT FROM p.owner_organization_id
          AND gt.field_key NOT IN ('name','description')
      ) candidates
      ORDER BY candidates.field_key,candidates.priority
    ) t
  ) localized_details ON true
  LEFT JOIN LATERAL (
    SELECT jsonb_agg(jsonb_build_object(
      'ruling_id',r.id,'ruling_domain',r.ruling_domain,'authority_code',r.authority_code,
      'religion_code',r.religion_code,'jurisdiction_code',r.jurisdiction_code,'outcome',r.outcome,
      'rationale',r.rationale,'source_title',r.source_title,'source_url',r.source_url,
      'effective_from',r.effective_from,'effective_to',r.effective_to,'confidence',r.confidence,
      'reviewed_by',r.reviewed_by,'reviewed_at',r.reviewed_at
    ) ORDER BY r.ruling_domain,r.religion_code,r.authority_code) AS rulings
    FROM public.commercial_catalog_product_rulings r
    WHERE r.product_id=p.id
  ) ruling_list ON true
  LEFT JOIN public.commercial_global_catalog_categories c ON c.code=p.category_code
  LEFT JOIN public.commercial_global_entity_translations gtname
    ON gtname.entity_type='catalog_item' AND gtname.entity_key=p.catalog_code
    AND gtname.field_key='name' AND gtname.locale_code=p_locale_code
    AND gtname.owner_organization_id IS NOT DISTINCT FROM p.owner_organization_id
  LEFT JOIN public.commercial_global_entity_translations gtdesc
    ON gtdesc.entity_type='catalog_item' AND gtdesc.entity_key=p.catalog_code
    AND gtdesc.field_key='description' AND gtdesc.locale_code=p_locale_code
    AND gtdesc.owner_organization_id IS NOT DISTINCT FROM p.owner_organization_id
  LEFT JOIN public.commercial_organization_entity_translations oname
    ON oname.organization_id=p_organization_id AND oname.entity_type='catalog_item'
    AND oname.entity_id=p.id AND oname.field_key='name' AND oname.locale_code=p_locale_code
  LEFT JOIN public.commercial_organization_entity_translations odesc
    ON odesc.organization_id=p_organization_id AND odesc.entity_type='catalog_item'
    AND odesc.entity_id=p.id AND odesc.field_key='description' AND odesc.locale_code=p_locale_code
  LEFT JOIN public.commercial_global_entity_translations ct
    ON ct.entity_type='catalog_category' AND ct.entity_key=c.code
    AND ct.field_key='name' AND ct.locale_code=p_locale_code
    AND ct.owner_organization_id IS NOT DISTINCT FROM c.owner_organization_id
  WHERE p.is_active
    AND NOT EXISTS (
      SELECT 1 FROM public.commercial_organization_catalog_items disabled_item
      WHERE disabled_item.organization_id=p_organization_id
        AND disabled_item.global_item_id=p.id AND NOT disabled_item.is_enabled
    )
    AND (
      p.owner_organization_id IS NULL
      OR p.owner_organization_id=p_organization_id
      OR EXISTS (
        SELECT 1 FROM public.commercial_organization_catalog_items oi
        WHERE oi.organization_id=p_organization_id AND oi.global_item_id=p.id AND oi.is_enabled
      )
    )
    AND (
      p_query IS NULL OR btrim(p_query)='' OR
      COALESCE(oname.translated_value,gtname.translated_value,
        CASE WHEN p_locale_code LIKE 'en%' THEN p.name_en ELSE p.name_ar END) ILIKE '%'||btrim(p_query)||'%' OR
      COALESCE(odesc.translated_value,gtdesc.translated_value,p.description_ar,'') ILIKE '%'||btrim(p_query)||'%' OR
      p.catalog_code ILIKE '%'||btrim(p_query)||'%' OR
      COALESCE(p.brand,'') ILIKE '%'||btrim(p_query)||'%' OR
      p.barcode_gtin=btrim(p_query) OR
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
      NOT EXISTS (SELECT 1 FROM public.commercial_catalog_product_sectors si
        WHERE si.product_id=p.id AND NOT si.is_excluded)
      OR EXISTS (SELECT 1 FROM public.commercial_catalog_product_sectors si
        WHERE si.product_id=p.id AND NOT si.is_excluded
          AND (si.sector_code='all' OR (p_sector_code IS NOT NULL AND si.sector_code=p_sector_code)))
    )
    AND NOT EXISTS (
      SELECT 1 FROM public.commercial_catalog_product_business_levels bx
      WHERE bx.product_id=p.id AND bx.is_excluded
        AND p_business_level_code IS NOT NULL AND bx.business_level_code=p_business_level_code
    )
    AND (
      NOT EXISTS (SELECT 1 FROM public.commercial_catalog_product_business_levels bi
        WHERE bi.product_id=p.id AND NOT bi.is_excluded)
      OR EXISTS (SELECT 1 FROM public.commercial_catalog_product_business_levels bi
        WHERE bi.product_id=p.id AND NOT bi.is_excluded
          AND p_business_level_code IS NOT NULL AND bi.business_level_code=p_business_level_code)
    )
    AND NOT EXISTS (
      SELECT 1 FROM public.commercial_catalog_product_regions rx
      WHERE rx.product_id=p.id AND rx.is_excluded
        AND (rx.country_code IS NULL OR rx.country_code=upper(p_country_code))
        AND (rx.region_code IS NULL OR rx.region_code=p_region_code)
        AND (rx.city IS NULL OR lower(rx.city)=lower(p_city))
    )
    AND (
      NOT EXISTS (SELECT 1 FROM public.commercial_catalog_product_regions ri
        WHERE ri.product_id=p.id AND NOT ri.is_excluded)
      OR EXISTS (SELECT 1 FROM public.commercial_catalog_product_regions ri
        WHERE ri.product_id=p.id AND NOT ri.is_excluded
          AND (ri.country_code IS NULL OR ri.country_code=upper(p_country_code))
          AND (ri.region_code IS NULL OR ri.region_code=p_region_code)
          AND (ri.city IS NULL OR lower(ri.city)=lower(p_city)))
    )
  ORDER BY 12 DESC, COALESCE(oname.translated_value,gtname.translated_value,p.name_ar), p.id
  LIMIT greatest(1,least(COALESCE(p_limit,50),100))
  OFFSET greatest(0,COALESCE(p_offset,0));
END;
$$;

REVOKE ALL ON FUNCTION public.commercial_catalog_discover_products_page(uuid,text,text,text,text,text,text,text,integer,integer) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_catalog_discover_products_page(uuid,text,text,text,text,text,text,text,integer,integer) TO authenticated;

COMMENT ON FUNCTION public.commercial_catalog_discover_products_page(uuid,text,text,text,text,text,text,text,integer,integer) IS
'Paginated, authenticated discovery over the existing global catalog. Stable ordering plus offset supports incremental loading without recreating catalog masters.';
