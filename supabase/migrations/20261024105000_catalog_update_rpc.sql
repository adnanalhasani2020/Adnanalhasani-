-- Update organization-owned catalog details without mutating shared/global templates or existing unit/targeting/ruling links.
CREATE OR REPLACE FUNCTION public.commercial_catalog_update_product_details(
  p_organization_id uuid,
  p_product_id uuid,
  p_product jsonb
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
  v_code text;
  v_image_url text;
BEGIN
  IF auth.uid() IS NULL THEN
    RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501';
  END IF;
  IF NOT public.commercial_catalog_member_of(p_organization_id,ARRAY['owner','admin']::text[]) THEN
    RAISE EXCEPTION 'catalog_product_update_forbidden' USING ERRCODE='42501';
  END IF;
  IF jsonb_typeof(p_product)<>'object'
     OR nullif(btrim(p_product->>'name_ar'),'') IS NULL
     OR nullif(btrim(p_product->>'name_en'),'') IS NULL
     OR (p_product ? 'attributes_schema' AND jsonb_typeof(p_product->'attributes_schema')<>'object')
     OR (p_product ? 'material_composition' AND jsonb_typeof(p_product->'material_composition')<>'array')
     OR (p_product ? 'ingredients' AND jsonb_typeof(p_product->'ingredients')<>'array')
     OR (p_product ? 'allergens' AND jsonb_typeof(p_product->'allergens')<>'array') THEN
    RAISE EXCEPTION 'invalid_catalog_product_update' USING ERRCODE='22023';
  END IF;

  v_image_url := nullif(btrim(p_product->>'image_url'),'');
  IF v_image_url IS NOT NULL AND (left(v_image_url,8)<>'https://' OR v_image_url ~ '[[:space:]]') THEN
    RAISE EXCEPTION 'image_url_must_be_https' USING ERRCODE='22023';
  END IF;
  IF nullif(btrim(p_product->>'barcode_gtin'),'') IS NOT NULL
     AND (btrim(p_product->>'barcode_gtin') !~ '^[0-9]{8,14}$') THEN
    RAISE EXCEPTION 'invalid_gtin_barcode' USING ERRCODE='22023';
  END IF;

  SELECT catalog_code INTO v_code
  FROM public.commercial_global_catalog_items
  WHERE id=p_product_id AND owner_organization_id=p_organization_id
  FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'organization_owned_catalog_product_not_found' USING ERRCODE='22023';
  END IF;

  UPDATE public.commercial_global_catalog_items
  SET name_ar=btrim(p_product->>'name_ar'),
      name_en=btrim(p_product->>'name_en'),
      description_ar=nullif(btrim(p_product->>'description_ar'),''),
      description_en=nullif(btrim(p_product->>'description_en'),''),
      brand=CASE WHEN p_product ? 'brand' THEN nullif(btrim(p_product->>'brand'),'') ELSE brand END,
      manufacturer_name=CASE WHEN p_product ? 'manufacturer_name' THEN nullif(btrim(p_product->>'manufacturer_name'),'') ELSE manufacturer_name END,
      country_of_manufacture=CASE WHEN p_product ? 'country_of_manufacture' THEN nullif(btrim(p_product->>'country_of_manufacture'),'') ELSE country_of_manufacture END,
      model_number=CASE WHEN p_product ? 'model_number' THEN nullif(btrim(p_product->>'model_number'),'') ELSE model_number END,
      part_number=CASE WHEN p_product ? 'part_number' THEN nullif(btrim(p_product->>'part_number'),'') ELSE part_number END,
      barcode_gtin=CASE WHEN p_product ? 'barcode_gtin' THEN nullif(btrim(p_product->>'barcode_gtin'),'') ELSE barcode_gtin END,
      image_url=CASE WHEN p_product ? 'image_url' THEN v_image_url ELSE image_url END,
      image_alt=CASE WHEN p_product ? 'image_alt' THEN nullif(btrim(p_product->>'image_alt'),'') ELSE image_alt END,
      material_composition=CASE WHEN p_product ? 'material_composition' THEN p_product->'material_composition' ELSE material_composition END,
      ingredients=CASE WHEN p_product ? 'ingredients' THEN p_product->'ingredients' ELSE ingredients END,
      specifications=CASE WHEN p_product ? 'specifications' THEN p_product->'specifications' ELSE specifications END,
      allergens=CASE WHEN p_product ? 'allergens' THEN p_product->'allergens' ELSE allergens END,
      storage_instructions=CASE WHEN p_product ? 'storage_instructions' THEN nullif(btrim(p_product->>'storage_instructions'),'') ELSE storage_instructions END,
      usage_instructions=CASE WHEN p_product ? 'usage_instructions' THEN nullif(btrim(p_product->>'usage_instructions'),'') ELSE usage_instructions END,
      warnings=CASE WHEN p_product ? 'warnings' THEN nullif(btrim(p_product->>'warnings'),'') ELSE warnings END,
      shelf_life_days=CASE WHEN p_product ? 'shelf_life_days' THEN nullif(p_product->>'shelf_life_days','')::integer ELSE shelf_life_days END,
      requires_expiry_date=CASE WHEN p_product ? 'requires_expiry_date' THEN COALESCE((p_product->>'requires_expiry_date')::boolean,false) ELSE requires_expiry_date END,
      is_batch_tracked=CASE WHEN p_product ? 'is_batch_tracked' THEN COALESCE((p_product->>'is_batch_tracked')::boolean,false) ELSE is_batch_tracked END,
      is_serial_tracked=CASE WHEN p_product ? 'is_serial_tracked' THEN COALESCE((p_product->>'is_serial_tracked')::boolean,false) ELSE is_serial_tracked END,
      is_prescription_only=CASE WHEN p_product ? 'is_prescription_only' THEN COALESCE((p_product->>'is_prescription_only')::boolean,false) ELSE is_prescription_only END,
      attributes_schema=CASE WHEN p_product ? 'attributes_schema' THEN p_product->'attributes_schema' ELSE attributes_schema END
  WHERE id=p_product_id;

  DELETE FROM public.commercial_global_entity_translations
  WHERE entity_type='catalog_item' AND entity_key=v_code
    AND owner_organization_id=p_organization_id
    AND field_key='description' AND locale_code IN ('ar','en');

  INSERT INTO public.commercial_global_entity_translations(
    entity_type,entity_key,locale_code,field_key,translated_value,source_language,translation_status,owner_organization_id,created_by
  ) VALUES
    ('catalog_item',v_code,'ar','name',btrim(p_product->>'name_ar'),'ar','human_authored',p_organization_id,auth.uid()),
    ('catalog_item',v_code,'en','name',btrim(p_product->>'name_en'),'en','human_authored',p_organization_id,auth.uid())
  ON CONFLICT (entity_type,entity_key,locale_code,field_key) DO UPDATE
    SET translated_value=EXCLUDED.translated_value,source_language=EXCLUDED.source_language,
        translation_status='human_authored',owner_organization_id=EXCLUDED.owner_organization_id;

  IF nullif(btrim(p_product->>'description_ar'),'') IS NOT NULL THEN
    INSERT INTO public.commercial_global_entity_translations(
      entity_type,entity_key,locale_code,field_key,translated_value,source_language,translation_status,owner_organization_id,created_by
    ) VALUES ('catalog_item',v_code,'ar','description',btrim(p_product->>'description_ar'),'ar','human_authored',p_organization_id,auth.uid())
    ON CONFLICT (entity_type,entity_key,locale_code,field_key) DO UPDATE
      SET translated_value=EXCLUDED.translated_value,source_language='ar',translation_status='human_authored',owner_organization_id=EXCLUDED.owner_organization_id;
  END IF;
  IF nullif(btrim(p_product->>'description_en'),'') IS NOT NULL THEN
    INSERT INTO public.commercial_global_entity_translations(
      entity_type,entity_key,locale_code,field_key,translated_value,source_language,translation_status,owner_organization_id,created_by
    ) VALUES ('catalog_item',v_code,'en','description',btrim(p_product->>'description_en'),'en','human_authored',p_organization_id,auth.uid())
    ON CONFLICT (entity_type,entity_key,locale_code,field_key) DO UPDATE
      SET translated_value=EXCLUDED.translated_value,source_language='en',translation_status='human_authored',owner_organization_id=EXCLUDED.owner_organization_id;
  END IF;

  RETURN jsonb_build_object('product_id',p_product_id,'catalog_code',v_code,'updated',true);
END;
$$;

REVOKE ALL ON FUNCTION public.commercial_catalog_update_product_details(uuid,uuid,jsonb) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_catalog_update_product_details(uuid,uuid,jsonb) TO authenticated;
COMMENT ON FUNCTION public.commercial_catalog_update_product_details(uuid,uuid,jsonb) IS
'Updates organization-owned product details only. It intentionally preserves product units, targeting rules, recipe rows and rulings; global shared templates cannot be mutated by tenant accounts.';
