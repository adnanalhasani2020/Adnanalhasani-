-- Account-scoped custom units and transactional catalog authoring APIs.
-- Distinguish manually supplied translations from reviewed or official translations.
ALTER TABLE public.commercial_global_entity_translations
  DROP CONSTRAINT IF EXISTS commercial_global_entity_translations_translation_status_check;
ALTER TABLE public.commercial_global_entity_translations
  ADD CONSTRAINT commercial_global_entity_translations_translation_status_check
  CHECK (translation_status IN ('machine_draft','human_authored','human_reviewed','official'));


ALTER TABLE public.commercial_global_units
  ADD COLUMN IF NOT EXISTS owner_organization_id uuid REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
  ADD COLUMN IF NOT EXISTS created_by uuid REFERENCES auth.users(id);

DROP POLICY IF EXISTS commercial_global_units_read_active ON public.commercial_global_units;
DROP POLICY IF EXISTS commercial_global_units_public_read ON public.commercial_global_units;
DROP POLICY IF EXISTS commercial_global_units_authenticated_read ON public.commercial_global_units;
CREATE POLICY commercial_global_units_public_read
ON public.commercial_global_units FOR SELECT TO anon
USING (is_active AND owner_organization_id IS NULL);
CREATE POLICY commercial_global_units_authenticated_read
ON public.commercial_global_units FOR SELECT TO authenticated
USING (
  is_active AND (
    owner_organization_id IS NULL
    OR public.commercial_catalog_member_of(owner_organization_id, ARRAY['owner','admin','manager','staff']::text[])
  )
);
CREATE INDEX IF NOT EXISTS ix_commercial_global_units_owner
  ON public.commercial_global_units(owner_organization_id);

CREATE OR REPLACE FUNCTION public.commercial_catalog_create_category(
  p_organization_id uuid,
  p_code text,
  p_parent_code text,
  p_name_ar text,
  p_name_en text,
  p_description_ar text DEFAULT NULL,
  p_description_en text DEFAULT NULL,
  p_sort_order integer DEFAULT 100,
  p_translations jsonb DEFAULT '[]'::jsonb
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE v_code text; v_translation jsonb;
BEGIN
  IF auth.uid() IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF NOT public.commercial_catalog_member_of(p_organization_id, ARRAY['owner','admin']::text[]) THEN
    RAISE EXCEPTION 'catalog_category_create_forbidden' USING ERRCODE='42501';
  END IF;
  v_code := upper(regexp_replace(btrim(COALESCE(p_code,'')), '[^A-Za-z0-9_-]+', '-', 'g'));
  IF length(v_code) NOT BETWEEN 2 AND 80 OR p_name_ar IS NULL OR btrim(p_name_ar)='' OR p_name_en IS NULL OR btrim(p_name_en)='' THEN
    RAISE EXCEPTION 'invalid_category_fields' USING ERRCODE='22023';
  END IF;
  IF p_parent_code IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM public.commercial_global_catalog_categories c
    WHERE c.code=p_parent_code AND c.is_active
      AND (c.owner_organization_id IS NULL OR c.owner_organization_id=p_organization_id)
  ) THEN RAISE EXCEPTION 'invalid_category_parent' USING ERRCODE='22023'; END IF;

  INSERT INTO public.commercial_global_catalog_categories(
    code,parent_code,name_ar,name_en,description_ar,description_en,sort_order,is_active,version,
    owner_organization_id,created_by,updated_by
  ) VALUES (
    v_code,p_parent_code,btrim(p_name_ar),btrim(p_name_en),nullif(btrim(p_description_ar),''),nullif(btrim(p_description_en),''),
    COALESCE(p_sort_order,100),true,1,p_organization_id,auth.uid(),auth.uid()
  );
  INSERT INTO public.commercial_global_entity_translations(
    entity_type,entity_key,locale_code,field_key,translated_value,translation_status,source_language,owner_organization_id,created_by
  ) VALUES
    ('catalog_category',v_code,'ar','name',btrim(p_name_ar),'human_authored','ar',p_organization_id,auth.uid()),
    ('catalog_category',v_code,'en','name',btrim(p_name_en),'human_authored','en',p_organization_id,auth.uid())
  ON CONFLICT (entity_type,entity_key,locale_code,field_key) DO UPDATE
    SET translated_value=EXCLUDED.translated_value,owner_organization_id=EXCLUDED.owner_organization_id;
  IF jsonb_typeof(COALESCE(p_translations,'[]'::jsonb))<>'array' THEN
    RAISE EXCEPTION 'invalid_category_translations' USING ERRCODE='22023';
  END IF;
  FOR v_translation IN SELECT value FROM jsonb_array_elements(COALESCE(p_translations,'[]'::jsonb)) LOOP
    IF nullif(btrim(v_translation->>'locale_code'),'') IS NULL
       OR nullif(btrim(v_translation->>'field_key'),'') IS NULL
       OR nullif(btrim(v_translation->>'translated_value'),'') IS NULL THEN
      RAISE EXCEPTION 'invalid_category_translation_fields' USING ERRCODE='22023';
    END IF;
    INSERT INTO public.commercial_global_entity_translations(
      entity_type,entity_key,locale_code,field_key,translated_value,translation_status,source_language,owner_organization_id,created_by
    ) VALUES ('catalog_category',v_code,lower(btrim(v_translation->>'locale_code')),lower(btrim(v_translation->>'field_key')),
      btrim(v_translation->>'translated_value'),'human_authored',nullif(v_translation->>'source_language',''),p_organization_id,auth.uid())
    ON CONFLICT (entity_type,entity_key,locale_code,field_key) DO UPDATE
      SET translated_value=EXCLUDED.translated_value,translation_status=EXCLUDED.translation_status,
          source_language=EXCLUDED.source_language,owner_organization_id=EXCLUDED.owner_organization_id;
  END LOOP;
  RETURN jsonb_build_object('category_code',v_code,'parent_code',p_parent_code);
END $$;
REVOKE ALL ON FUNCTION public.commercial_catalog_create_category(uuid,text,text,text,text,text,text,integer,jsonb) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_catalog_create_category(uuid,text,text,text,text,text,text,integer,jsonb) TO authenticated;

CREATE OR REPLACE FUNCTION public.commercial_catalog_create_unit(
  p_organization_id uuid,
  p_code text,
  p_name_ar text,
  p_name_en text,
  p_dimension text,
  p_factor_to_base numeric,
  p_base_unit_code text,
  p_decimal_places smallint DEFAULT 3,
  p_translations jsonb DEFAULT '[]'::jsonb
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE v_code text; v_dimension text; v_owner uuid; v_translation jsonb;
BEGIN
  IF auth.uid() IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF NOT public.commercial_catalog_member_of(p_organization_id, ARRAY['owner','admin']::text[]) THEN
    RAISE EXCEPTION 'catalog_unit_create_forbidden' USING ERRCODE='42501';
  END IF;
  v_code := 'O' || upper(substr(replace(p_organization_id::text,'-',''),1,8)) || '_' ||
    substr(upper(regexp_replace(btrim(COALESCE(p_code,'')), '[^A-Za-z0-9_]+', '_', 'g')),1,6);
  IF length(v_code) > 80 OR length(btrim(COALESCE(p_name_ar,'')))=0 OR length(btrim(COALESCE(p_name_en,'')))=0
     OR length(btrim(COALESCE(p_dimension,'')))=0 OR COALESCE(p_factor_to_base,0)<=0
     OR COALESCE(p_decimal_places,0)<0 OR COALESCE(p_decimal_places,0)>6
     OR p_dimension NOT IN ('count','mass','volume','length','area','time','service','other')
     OR nullif(btrim(COALESCE(p_code,'')),'') IS NULL THEN
    RAISE EXCEPTION 'invalid_unit_fields' USING ERRCODE='22023';
  END IF;
  SELECT u.dimension,u.owner_organization_id INTO v_dimension,v_owner
  FROM public.commercial_global_units u
  WHERE u.code=p_base_unit_code AND u.is_active AND u.base_unit_code IS NULL;
  IF NOT FOUND OR v_dimension<>p_dimension OR (v_owner IS NOT NULL AND v_owner<>p_organization_id) THEN
    RAISE EXCEPTION 'invalid_unit_base_or_dimension' USING ERRCODE='22023';
  END IF;
  INSERT INTO public.commercial_global_units(
    code,name_ar,name_en,dimension,base_unit_code,factor_to_base,decimal_places,is_active,version,
    owner_organization_id,created_by
  ) VALUES (
    v_code,btrim(p_name_ar),btrim(p_name_en),p_dimension,p_base_unit_code,p_factor_to_base,
    p_decimal_places,true,1,p_organization_id,auth.uid()
  );
  INSERT INTO public.commercial_global_entity_translations(
    entity_type,entity_key,locale_code,field_key,translated_value,translation_status,source_language,owner_organization_id,created_by
  ) VALUES
    ('unit',v_code,'ar','name',btrim(p_name_ar),'human_authored','ar',p_organization_id,auth.uid()),
    ('unit',v_code,'en','name',btrim(p_name_en),'human_authored','en',p_organization_id,auth.uid())
  ON CONFLICT (entity_type,entity_key,locale_code,field_key) DO UPDATE
    SET translated_value=EXCLUDED.translated_value,owner_organization_id=EXCLUDED.owner_organization_id;
  IF jsonb_typeof(COALESCE(p_translations,'[]'::jsonb))<>'array' THEN
    RAISE EXCEPTION 'invalid_unit_translations' USING ERRCODE='22023';
  END IF;
  FOR v_translation IN SELECT value FROM jsonb_array_elements(COALESCE(p_translations,'[]'::jsonb)) LOOP
    IF nullif(btrim(v_translation->>'locale_code'),'') IS NULL
       OR nullif(btrim(v_translation->>'field_key'),'') IS NULL
       OR nullif(btrim(v_translation->>'translated_value'),'') IS NULL THEN
      RAISE EXCEPTION 'invalid_unit_translation_fields' USING ERRCODE='22023';
    END IF;
    INSERT INTO public.commercial_global_entity_translations(
      entity_type,entity_key,locale_code,field_key,translated_value,translation_status,source_language,owner_organization_id,created_by
    ) VALUES ('unit',v_code,lower(btrim(v_translation->>'locale_code')),lower(btrim(v_translation->>'field_key')),
      btrim(v_translation->>'translated_value'),'human_authored',nullif(v_translation->>'source_language',''),p_organization_id,auth.uid())
    ON CONFLICT (entity_type,entity_key,locale_code,field_key) DO UPDATE
      SET translated_value=EXCLUDED.translated_value,translation_status=EXCLUDED.translation_status,
          source_language=EXCLUDED.source_language,owner_organization_id=EXCLUDED.owner_organization_id;
  END LOOP;
  RETURN jsonb_build_object('unit_code',v_code,'base_unit_code',p_base_unit_code,'dimension',p_dimension);
END $$;
REVOKE ALL ON FUNCTION public.commercial_catalog_create_unit(uuid,text,text,text,text,numeric,text,smallint,jsonb) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_catalog_create_unit(uuid,text,text,text,text,numeric,text,smallint,jsonb) TO authenticated;

CREATE OR REPLACE FUNCTION public.commercial_catalog_create_product(
  p_organization_id uuid,
  p_product jsonb,
  p_units jsonb DEFAULT '[]'::jsonb,
  p_sectors text[] DEFAULT ARRAY[]::text[],
  p_business_levels jsonb DEFAULT '[]'::jsonb,
  p_regions jsonb DEFAULT '[]'::jsonb,
  p_rulings jsonb DEFAULT '[]'::jsonb
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE
  v_id uuid; v_code text; v_unit_code text; v_category text; v_type text;
  v_barcode text; v_image text; v_unit jsonb; v_level jsonb; v_region jsonb; v_ruling jsonb; v_translation jsonb; v_ingredient jsonb; v_unit_dimension text; v_recipe_id uuid;
BEGIN
  IF auth.uid() IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF NOT public.commercial_catalog_member_of(p_organization_id, ARRAY['owner','admin']::text[]) THEN
    RAISE EXCEPTION 'catalog_product_create_forbidden' USING ERRCODE='42501';
  END IF;
  IF jsonb_typeof(COALESCE(p_product,'{}'::jsonb))<>'object'
     OR jsonb_typeof(COALESCE(p_units,'[]'::jsonb))<>'array'
     OR jsonb_typeof(COALESCE(p_business_levels,'[]'::jsonb))<>'array'
     OR jsonb_typeof(COALESCE(p_regions,'[]'::jsonb))<>'array'
     OR jsonb_typeof(COALESCE(p_rulings,'[]'::jsonb))<>'array' THEN
    RAISE EXCEPTION 'invalid_catalog_payload' USING ERRCODE='22023';
  END IF;
  v_category := nullif(btrim(p_product->>'category_code'),'');
  v_unit_code := nullif(btrim(p_product->>'unit_code'),'');
  v_type := COALESCE(nullif(p_product->>'item_type',''),'product');
  v_barcode := nullif(btrim(p_product->>'barcode_gtin'),'');
  v_image := nullif(btrim(p_product->>'image_url'),'');
  IF v_category IS NULL OR v_unit_code IS NULL
     OR nullif(btrim(p_product->>'name_ar'),'') IS NULL
     OR nullif(btrim(p_product->>'name_en'),'') IS NULL
     OR v_type NOT IN ('product','service','digital','material','asset') THEN
    RAISE EXCEPTION 'invalid_required_product_fields' USING ERRCODE='22023';
  END IF;
  IF v_barcode IS NOT NULL AND v_barcode !~ '^[0-9]{8,14}$' THEN
    RAISE EXCEPTION 'invalid_gtin_barcode' USING ERRCODE='22023';
  END IF;
  IF v_image IS NOT NULL AND (length(v_image)>1000 OR v_image !~* '^https://[^[:space:]]+$') THEN
    RAISE EXCEPTION 'image_url_must_be_https' USING ERRCODE='22023';
  END IF;
  SELECT u.dimension INTO v_unit_dimension FROM public.commercial_global_units u
    WHERE u.code=v_unit_code AND u.is_active
      AND (u.owner_organization_id IS NULL OR u.owner_organization_id=p_organization_id);
  IF NOT FOUND THEN RAISE EXCEPTION 'invalid_product_unit' USING ERRCODE='22023'; END IF;

  v_id := gen_random_uuid();
  v_code := COALESCE(nullif(btrim(p_product->>'catalog_code'),''), 'ORG-'||upper(substr(replace(p_organization_id::text,'-',''),1,8))||'-'||upper(substr(replace(v_id::text,'-',''),1,12)));
  INSERT INTO public.commercial_global_catalog_items(
    id,catalog_code,name_ar,name_en,description_ar,description_en,category_code,item_type,unit_code,
    barcode_gtin,attributes_schema,is_active,version,owner_organization_id,brand,manufacturer_name,
    country_of_manufacture,model_number,part_number,material_composition,ingredients,recipe,specifications,
    allergens,storage_instructions,usage_instructions,warnings,shelf_life_days,requires_expiry_date,
    is_batch_tracked,is_serial_tracked,is_prescription_only,image_url,image_alt,created_by,updated_by
  ) VALUES (
    v_id,v_code,btrim(p_product->>'name_ar'),btrim(p_product->>'name_en'),
    nullif(btrim(p_product->>'description_ar'),''),nullif(btrim(p_product->>'description_en'),''),v_category,v_type,v_unit_code,
    v_barcode,COALESCE(p_product->'attributes_schema','{}'::jsonb),true,1,p_organization_id,
    nullif(btrim(p_product->>'brand'),''),nullif(btrim(p_product->>'manufacturer_name'),''),
    nullif(btrim(p_product->>'country_of_manufacture'),''),nullif(btrim(p_product->>'model_number'),''),
    nullif(btrim(p_product->>'part_number'),''),COALESCE(p_product->'material_composition','[]'::jsonb),
    COALESCE(p_product->'ingredients','[]'::jsonb),COALESCE(p_product->'recipe','{}'::jsonb),
    COALESCE(p_product->'specifications','{}'::jsonb),COALESCE(p_product->'allergens','[]'::jsonb),
    nullif(btrim(p_product->>'storage_instructions'),''),nullif(btrim(p_product->>'usage_instructions'),''),
    nullif(btrim(p_product->>'warnings'),''),nullif(p_product->>'shelf_life_days','')::integer,
    COALESCE((p_product->>'requires_expiry_date')::boolean,false),
    COALESCE((p_product->>'is_batch_tracked')::boolean,false),COALESCE((p_product->>'is_serial_tracked')::boolean,false),
    COALESCE((p_product->>'is_prescription_only')::boolean,false),v_image,nullif(btrim(p_product->>'image_alt'),''),
    auth.uid(),auth.uid()
  );

  INSERT INTO public.commercial_global_entity_translations(
    entity_type,entity_key,locale_code,field_key,translated_value,translation_status,source_language,owner_organization_id,created_by
  ) VALUES
    ('catalog_item',v_code,'ar','name',btrim(p_product->>'name_ar'),'human_authored','ar',p_organization_id,auth.uid()),
    ('catalog_item',v_code,'en','name',btrim(p_product->>'name_en'),'human_authored','en',p_organization_id,auth.uid())
  ON CONFLICT (entity_type,entity_key,locale_code,field_key) DO UPDATE
    SET translated_value=EXCLUDED.translated_value,owner_organization_id=EXCLUDED.owner_organization_id;

  INSERT INTO public.commercial_global_entity_translations(
    entity_type,entity_key,locale_code,field_key,translated_value,translation_status,source_language,owner_organization_id,created_by
  )
  SELECT 'catalog_item',v_code,x.locale_code,'description',x.translated_value,'human_authored',x.locale_code,p_organization_id,auth.uid()
  FROM (VALUES ('ar',nullif(btrim(p_product->>'description_ar'),'')),('en',nullif(btrim(p_product->>'description_en'),''))) x(locale_code,translated_value)
  WHERE x.translated_value IS NOT NULL
  ON CONFLICT (entity_type,entity_key,locale_code,field_key) DO UPDATE
    SET translated_value=EXCLUDED.translated_value,translation_status=EXCLUDED.translation_status,
        source_language=EXCLUDED.source_language,owner_organization_id=EXCLUDED.owner_organization_id;

  IF jsonb_typeof(COALESCE(p_product->'translations','[]'::jsonb))<>'array' THEN
    RAISE EXCEPTION 'invalid_product_translations' USING ERRCODE='22023';
  END IF;
  FOR v_translation IN SELECT value FROM jsonb_array_elements(COALESCE(p_product->'translations','[]'::jsonb)) LOOP
    IF nullif(btrim(v_translation->>'locale_code'),'') IS NULL
       OR nullif(btrim(v_translation->>'field_key'),'') IS NULL
       OR nullif(btrim(v_translation->>'translated_value'),'') IS NULL THEN
      RAISE EXCEPTION 'invalid_translation_fields' USING ERRCODE='22023';
    END IF;
    INSERT INTO public.commercial_global_entity_translations(
      entity_type,entity_key,locale_code,field_key,translated_value,translation_status,source_language,owner_organization_id,created_by
    ) VALUES ('catalog_item',v_code,lower(btrim(v_translation->>'locale_code')),lower(btrim(v_translation->>'field_key')),
      btrim(v_translation->>'translated_value'),COALESCE(nullif(v_translation->>'translation_status',''),'human_authored'),
      nullif(v_translation->>'source_language',''),p_organization_id,auth.uid())
    ON CONFLICT (entity_type,entity_key,locale_code,field_key) DO UPDATE
      SET translated_value=EXCLUDED.translated_value,translation_status=EXCLUDED.translation_status,
          source_language=EXCLUDED.source_language,owner_organization_id=EXCLUDED.owner_organization_id;
  END LOOP;

  IF jsonb_array_length(COALESCE(p_units,'[]'::jsonb))=0 THEN
    INSERT INTO public.commercial_catalog_product_units(product_id,unit_code,unit_role,quantity_in_base,is_default,created_by)
    VALUES(v_id,v_unit_code,'base',1,true,auth.uid());
  ELSE
    FOR v_unit IN SELECT value FROM jsonb_array_elements(p_units) LOOP
      IF nullif(v_unit->>'unit_code','') IS NULL
         OR NOT EXISTS (SELECT 1 FROM public.commercial_global_units u WHERE u.code=v_unit->>'unit_code' AND u.is_active
           AND (u.owner_organization_id IS NULL OR u.owner_organization_id=p_organization_id)) THEN
        RAISE EXCEPTION 'invalid_product_unit_variant' USING ERRCODE='22023';
      END IF;
      IF NOT EXISTS (
        SELECT 1 FROM public.commercial_global_units u
        WHERE u.code=v_unit->>'unit_code' AND u.dimension=v_unit_dimension
          AND (u.owner_organization_id IS NULL OR u.owner_organization_id=p_organization_id)
      ) THEN RAISE EXCEPTION 'product_unit_dimension_mismatch' USING ERRCODE='22023'; END IF;
      IF nullif(v_unit->>'image_url','') IS NOT NULL AND (length(v_unit->>'image_url')>1000 OR v_unit->>'image_url' !~* '^https://[^[:space:]]+
        RAISE EXCEPTION 'unit_image_url_must_be_https' USING ERRCODE='22023';
      END IF;
      INSERT INTO public.commercial_catalog_product_units(
        product_id,unit_code,unit_role,quantity_in_base,barcode,sku,package_name,net_quantity,net_unit_code,image_url,is_default,created_by
      ) VALUES (
        v_id,v_unit->>'unit_code',COALESCE(nullif(v_unit->>'unit_role',''),'sale'),
        COALESCE(nullif(v_unit->>'quantity_in_base','')::numeric,1),nullif(btrim(v_unit->>'barcode'),''),
        nullif(btrim(v_unit->>'sku'),''),nullif(btrim(v_unit->>'package_name'),''),
        nullif(v_unit->>'net_quantity','')::numeric,nullif(v_unit->>'net_unit_code',''),
        nullif(v_unit->>'image_url',''),COALESCE((v_unit->>'is_default')::boolean,false),auth.uid()
      );
    END LOOP;
  END IF;

  IF nullif(btrim(p_product->'recipe'->>'instructions'),'') IS NOT NULL
     OR jsonb_array_length(COALESCE(p_product->'recipe'->'ingredients','[]'::jsonb))>0 THEN
    INSERT INTO public.commercial_catalog_product_recipes(
      product_id,locale_code,recipe_name,instructions,yield_quantity,yield_unit_code,
      preparation_minutes,cooking_minutes,allergen_notes,source_reference,created_by
    ) VALUES (
      v_id,COALESCE(nullif(p_product->'recipe'->>'locale_code',''),'ar'),
      COALESCE(nullif(btrim(p_product->'recipe'->>'recipe_name'),''),btrim(p_product->>'name_ar')),
      nullif(btrim(p_product->'recipe'->>'instructions'),''),
      nullif(p_product->'recipe'->>'yield_quantity','')::numeric,
      nullif(p_product->'recipe'->>'yield_unit_code',''),
      nullif(p_product->'recipe'->>'preparation_minutes','')::integer,
      nullif(p_product->'recipe'->>'cooking_minutes','')::integer,
      nullif(btrim(p_product->'recipe'->>'allergen_notes'),''),
      nullif(btrim(p_product->'recipe'->>'source_reference'),''),auth.uid()
    ) RETURNING id INTO v_recipe_id;
    FOR v_ingredient IN SELECT value FROM jsonb_array_elements(COALESCE(p_product->'recipe'->'ingredients','[]'::jsonb)) LOOP
      IF jsonb_typeof(v_ingredient)<>'object'
         OR COALESCE(nullif(btrim(v_ingredient->>'ingredient_name'),''),nullif(btrim(v_ingredient->>'ingredient_product_id'),'')) IS NULL
         OR COALESCE(nullif(v_ingredient->>'quantity','')::numeric,0)<=0
         OR nullif(btrim(v_ingredient->>'unit_code'),'') IS NULL
         OR NOT EXISTS (SELECT 1 FROM public.commercial_global_units u WHERE u.code=v_ingredient->>'unit_code' AND u.is_active
           AND (u.owner_organization_id IS NULL OR u.owner_organization_id=p_organization_id))
         OR (nullif(v_ingredient->>'ingredient_product_id','') IS NOT NULL AND NOT EXISTS (
           SELECT 1 FROM public.commercial_global_catalog_items ip
           WHERE ip.id=(v_ingredient->>'ingredient_product_id')::uuid
             AND (ip.owner_organization_id IS NULL OR ip.owner_organization_id=p_organization_id
               OR EXISTS (SELECT 1 FROM public.commercial_organization_catalog_items oi
                 WHERE oi.organization_id=p_organization_id AND oi.global_item_id=ip.id))
         )) THEN
        RAISE EXCEPTION 'invalid_recipe_ingredient' USING ERRCODE='22023';
      END IF;
      INSERT INTO public.commercial_catalog_recipe_ingredients(
        recipe_id,ingredient_product_id,ingredient_name,quantity,unit_code,preparation_note
      ) VALUES (
        v_recipe_id,nullif(v_ingredient->>'ingredient_product_id','')::uuid,
        nullif(btrim(v_ingredient->>'ingredient_name'),''),(v_ingredient->>'quantity')::numeric,
        v_ingredient->>'unit_code',nullif(btrim(v_ingredient->>'preparation_note'),'')
      );
    END LOOP;
  END IF;

  INSERT INTO public.commercial_catalog_product_sectors(product_id,sector_code)
  SELECT v_id,lower(btrim(s)) FROM unnest(COALESCE(p_sectors,ARRAY[]::text[])) s
  WHERE nullif(btrim(s),'') IS NOT NULL
  ON CONFLICT DO NOTHING;

  FOR v_level IN SELECT value FROM jsonb_array_elements(COALESCE(p_business_levels,'[]'::jsonb)) LOOP
    INSERT INTO public.commercial_catalog_product_business_levels(product_id,business_level_code,recommendation_weight,is_excluded)
    VALUES(v_id,lower(btrim(v_level->>'code')),COALESCE(nullif(v_level->>'weight','')::smallint,0),COALESCE((v_level->>'excluded')::boolean,false))
    ON CONFLICT (product_id,business_level_code) DO UPDATE SET recommendation_weight=EXCLUDED.recommendation_weight,is_excluded=EXCLUDED.is_excluded;
  END LOOP;

  FOR v_region IN SELECT value FROM jsonb_array_elements(COALESCE(p_regions,'[]'::jsonb)) LOOP
    INSERT INTO public.commercial_catalog_product_regions(product_id,country_code,region_code,city,is_excluded)
    VALUES(v_id,upper(nullif(btrim(v_region->>'country_code'),'')),nullif(btrim(v_region->>'region_code'),''),nullif(btrim(v_region->>'city'),''),COALESCE((v_region->>'excluded')::boolean,false))
    ON CONFLICT DO NOTHING;
  END LOOP;

  FOR v_ruling IN SELECT value FROM jsonb_array_elements(COALESCE(p_rulings,'[]'::jsonb)) LOOP
    INSERT INTO public.commercial_catalog_product_rulings(
      product_id,ruling_domain,authority_code,religion_code,jurisdiction_code,outcome,rationale,
      source_title,source_url,effective_from,effective_to,confidence,reviewed_by,reviewed_at,created_by
    ) VALUES (
      v_id,v_ruling->>'ruling_domain',btrim(v_ruling->>'authority_code'),nullif(btrim(v_ruling->>'religion_code'),''),
      nullif(btrim(v_ruling->>'jurisdiction_code'),''),v_ruling->>'outcome',nullif(btrim(v_ruling->>'rationale'),''),
      nullif(btrim(v_ruling->>'source_title'),''),nullif(btrim(v_ruling->>'source_url'),''),
      nullif(v_ruling->>'effective_from','')::date,nullif(v_ruling->>'effective_to','')::date,
      COALESCE(nullif(v_ruling->>'confidence',''),'unverified'),NULL,NULL,auth.uid()
    );
  END LOOP;

  INSERT INTO public.commercial_organization_catalog_items(
    organization_id,global_item_id,merchant_display_name,merchant_description,
    price_minor,currency_code,is_available,metadata,created_by
  )
  SELECT p_organization_id,v_id,btrim(p_product->>'name_ar'),
    nullif(btrim(p_product->>'description_ar'),''),0,COALESCE(nullif(p_product->>'currency_code',''),'YER'),true,
    jsonb_build_object('created_from_catalog_manager',true),auth.uid()
  FROM public.commercial_organizations o WHERE o.id=p_organization_id
  ON CONFLICT (organization_id,global_item_id) DO NOTHING;

  RETURN jsonb_build_object('product_id',v_id,'catalog_code',v_code,'created_by',auth.uid());
END $$;
REVOKE ALL ON FUNCTION public.commercial_catalog_create_product(uuid,jsonb,jsonb,text[],jsonb,jsonb,jsonb) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_catalog_create_product(uuid,jsonb,jsonb,text[],jsonb,jsonb,jsonb) TO authenticated;

CREATE OR REPLACE FUNCTION public.commercial_catalog_transfer(
  p_source_organization_id uuid,
  p_destination_organization_id uuid,
  p_global_item_ids uuid[] DEFAULT NULL
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE v_transfer_id uuid; v_count integer;
BEGIN
  IF auth.uid() IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_source_organization_id=p_destination_organization_id THEN RAISE EXCEPTION 'same_organization'; END IF;
  IF NOT public.commercial_catalog_member_of(p_source_organization_id, ARRAY['owner','admin']::text[])
     OR NOT public.commercial_catalog_member_of(p_destination_organization_id, ARRAY['owner','admin']::text[]) THEN
    RAISE EXCEPTION 'catalog_transfer_forbidden' USING ERRCODE='42501';
  END IF;
  INSERT INTO public.commercial_catalog_transfers(source_organization_id,destination_organization_id,transferred_by)
  VALUES(p_source_organization_id,p_destination_organization_id,auth.uid()) RETURNING id INTO v_transfer_id;
  INSERT INTO public.commercial_organization_catalog_items(
    organization_id,global_item_id,merchant_sku,merchant_display_name,merchant_description,
    price_minor,currency_code,is_available,metadata,created_by,source_organization_id,imported_at,is_favorite,is_enabled
  )
  SELECT p_destination_organization_id, i.global_item_id,
    CASE WHEN i.merchant_sku IS NULL OR NOT EXISTS (
      SELECT 1 FROM public.commercial_organization_catalog_items d
      WHERE d.organization_id=p_destination_organization_id AND d.merchant_sku=i.merchant_sku
    ) THEN i.merchant_sku ELSE NULL END,
    i.merchant_display_name, i.merchant_description, i.price_minor, i.currency_code, i.is_available, i.metadata,
    auth.uid(), p_source_organization_id, now(), i.is_favorite, i.is_enabled
  FROM public.commercial_organization_catalog_items i
  WHERE i.organization_id=p_source_organization_id
    AND (p_global_item_ids IS NULL OR i.global_item_id=ANY(p_global_item_ids))
  ON CONFLICT (organization_id,global_item_id) DO NOTHING;
  GET DIAGNOSTICS v_count=ROW_COUNT;
  INSERT INTO public.commercial_organization_entity_translations(
    organization_id,entity_type,entity_id,locale_code,field_key,translated_value,created_by
  )
  SELECT p_destination_organization_id,t.entity_type,t.entity_id,t.locale_code,t.field_key,t.translated_value,auth.uid()
  FROM public.commercial_organization_entity_translations t
  WHERE t.organization_id=p_source_organization_id AND t.entity_type='catalog_item'
    AND EXISTS (
      SELECT 1 FROM public.commercial_organization_catalog_items src
      WHERE src.organization_id=p_source_organization_id AND src.global_item_id=t.entity_id
        AND (p_global_item_ids IS NULL OR src.global_item_id=ANY(p_global_item_ids))
    )
  ON CONFLICT DO NOTHING;
  UPDATE public.commercial_catalog_transfers SET item_count=v_count WHERE id=v_transfer_id;
  RETURN jsonb_build_object('transfer_id',v_transfer_id,'items_transferred',v_count);
END $$;
REVOKE ALL ON FUNCTION public.commercial_catalog_transfer(uuid,uuid,uuid[]) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_catalog_transfer(uuid,uuid,uuid[]) TO authenticated;

COMMENT ON FUNCTION public.commercial_catalog_create_product(uuid,jsonb,jsonb,text[],jsonb,jsonb,jsonb) IS
'Creates an organization-owned catalog product and its unit variants, sector/business-level/region targeting and sourced rulings in one transaction. Religious and medical outcomes are caller-supplied, source-attributed and unverified by default.';


-- Cross-account transfers are two-phase: source sends, destination owner explicitly accepts.
ALTER TABLE public.commercial_catalog_transfers
  ADD COLUMN IF NOT EXISTS global_item_ids uuid[],
  ADD COLUMN IF NOT EXISTS accepted_by uuid REFERENCES auth.users(id),
  ADD COLUMN IF NOT EXISTS accepted_at timestamptz;
ALTER TABLE public.commercial_catalog_transfers
  DROP CONSTRAINT IF EXISTS commercial_catalog_transfers_status_check;
ALTER TABLE public.commercial_catalog_transfers
  ADD CONSTRAINT commercial_catalog_transfers_status_check
  CHECK (status IN ('pending','completed','failed','rejected'));

CREATE OR REPLACE FUNCTION public.commercial_catalog_send_transfer(
  p_source_organization_id uuid,
  p_destination_organization_id uuid,
  p_global_item_ids uuid[] DEFAULT NULL
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE v_transfer_id uuid; v_ids uuid[]; v_requested integer;
BEGIN
  IF auth.uid() IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_source_organization_id=p_destination_organization_id THEN RAISE EXCEPTION 'same_organization' USING ERRCODE='22023'; END IF;
  IF NOT public.commercial_catalog_member_of(p_source_organization_id,ARRAY['owner','admin']::text[]) THEN
    RAISE EXCEPTION 'catalog_transfer_source_forbidden' USING ERRCODE='42501';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM public.commercial_organizations o WHERE o.id=p_destination_organization_id) THEN
    RAISE EXCEPTION 'catalog_transfer_destination_not_found' USING ERRCODE='22023';
  END IF;
  IF p_global_item_ids IS NULL THEN
    SELECT array_agg(DISTINCT i.global_item_id) INTO v_ids
    FROM public.commercial_organization_catalog_items i
    WHERE i.organization_id=p_source_organization_id;
  ELSE
    SELECT array_agg(DISTINCT x) INTO v_ids FROM unnest(p_global_item_ids) x;
    IF EXISTS (
      SELECT x FROM unnest(COALESCE(v_ids,ARRAY[]::uuid[])) x
      EXCEPT SELECT i.global_item_id FROM public.commercial_organization_catalog_items i
      WHERE i.organization_id=p_source_organization_id
    ) THEN RAISE EXCEPTION 'transfer_contains_unowned_catalog_items' USING ERRCODE='42501'; END IF;
  END IF;
  v_requested := COALESCE(cardinality(v_ids),0);
  IF v_requested=0 THEN RAISE EXCEPTION 'catalog_transfer_empty' USING ERRCODE='22023'; END IF;
  INSERT INTO public.commercial_catalog_transfers(
    source_organization_id,destination_organization_id,transferred_by,item_count,status,global_item_ids
  ) VALUES(p_source_organization_id,p_destination_organization_id,auth.uid(),v_requested,'pending',v_ids)
  RETURNING id INTO v_transfer_id;
  RETURN jsonb_build_object('transfer_id',v_transfer_id,'status','pending','items_requested',v_requested);
END $$;
REVOKE ALL ON FUNCTION public.commercial_catalog_send_transfer(uuid,uuid,uuid[]) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_catalog_send_transfer(uuid,uuid,uuid[]) TO authenticated;

CREATE OR REPLACE FUNCTION public.commercial_catalog_accept_transfer(p_transfer_id uuid)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE v_transfer public.commercial_catalog_transfers%ROWTYPE; v_count integer;
BEGIN
  IF auth.uid() IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  SELECT * INTO v_transfer FROM public.commercial_catalog_transfers WHERE id=p_transfer_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'catalog_transfer_not_found' USING ERRCODE='22023'; END IF;
  IF NOT public.commercial_catalog_member_of(v_transfer.destination_organization_id,ARRAY['owner','admin']::text[]) THEN
    RAISE EXCEPTION 'catalog_transfer_accept_forbidden' USING ERRCODE='42501';
  END IF;
  IF v_transfer.status<>'pending' THEN RAISE EXCEPTION 'catalog_transfer_not_pending' USING ERRCODE='22023'; END IF;

  INSERT INTO public.commercial_organization_catalog_items(
    organization_id,global_item_id,merchant_sku,merchant_display_name,merchant_description,
    price_minor,currency_code,is_available,metadata,created_by,source_organization_id,imported_at,is_favorite,is_enabled
  )
  SELECT v_transfer.destination_organization_id,src.global_item_id,
    CASE WHEN src.merchant_sku IS NULL OR NOT EXISTS (
      SELECT 1 FROM public.commercial_organization_catalog_items d
      WHERE d.organization_id=v_transfer.destination_organization_id AND d.merchant_sku=src.merchant_sku
    ) THEN src.merchant_sku ELSE NULL END,
    src.merchant_display_name,src.merchant_description,src.price_minor,src.currency_code,src.is_available,
    src.metadata,auth.uid(),v_transfer.source_organization_id,now(),src.is_favorite,src.is_enabled
  FROM public.commercial_organization_catalog_items src
  WHERE src.organization_id=v_transfer.source_organization_id
    AND src.global_item_id=ANY(COALESCE(v_transfer.global_item_ids,ARRAY[]::uuid[]))
  ON CONFLICT (organization_id,global_item_id) DO NOTHING;
  GET DIAGNOSTICS v_count=ROW_COUNT;

  INSERT INTO public.commercial_organization_entity_translations(
    organization_id,entity_type,entity_id,locale_code,field_key,translated_value,created_by
  )
  SELECT v_transfer.destination_organization_id,t.entity_type,t.entity_id,t.locale_code,t.field_key,t.translated_value,auth.uid()
  FROM public.commercial_organization_entity_translations t
  WHERE t.organization_id=v_transfer.source_organization_id AND t.entity_type='catalog_item'
    AND t.entity_id=ANY(COALESCE(v_transfer.global_item_ids,ARRAY[]::uuid[]))
  ON CONFLICT DO NOTHING;

  UPDATE public.commercial_catalog_transfers
  SET status='completed',accepted_by=auth.uid(),accepted_at=now(),item_count=v_count
  WHERE id=v_transfer.id;
  RETURN jsonb_build_object('transfer_id',v_transfer.id,'status','completed','items_transferred',v_count);
END $$;
REVOKE ALL ON FUNCTION public.commercial_catalog_accept_transfer(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_catalog_accept_transfer(uuid) TO authenticated;

CREATE OR REPLACE FUNCTION public.commercial_catalog_reject_transfer(p_transfer_id uuid)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE v_transfer public.commercial_catalog_transfers%ROWTYPE;
BEGIN
  IF auth.uid() IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  SELECT * INTO v_transfer FROM public.commercial_catalog_transfers WHERE id=p_transfer_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'catalog_transfer_not_found' USING ERRCODE='22023'; END IF;
  IF NOT public.commercial_catalog_member_of(v_transfer.destination_organization_id,ARRAY['owner','admin']::text[]) THEN
    RAISE EXCEPTION 'catalog_transfer_reject_forbidden' USING ERRCODE='42501';
  END IF;
  IF v_transfer.status<>'pending' THEN RAISE EXCEPTION 'catalog_transfer_not_pending' USING ERRCODE='22023'; END IF;
  UPDATE public.commercial_catalog_transfers SET status='rejected',accepted_by=auth.uid(),accepted_at=now() WHERE id=v_transfer.id;
  RETURN jsonb_build_object('transfer_id',v_transfer.id,'status','rejected');
END $$;
REVOKE ALL ON FUNCTION public.commercial_catalog_reject_transfer(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_catalog_reject_transfer(uuid) TO authenticated;
) THEN
        RAISE EXCEPTION 'unit_image_url_must_be_https' USING ERRCODE='22023';
      END IF;
      INSERT INTO public.commercial_catalog_product_units(
        product_id,unit_code,unit_role,quantity_in_base,barcode,sku,package_name,net_quantity,net_unit_code,image_url,is_default,created_by
      ) VALUES (
        v_id,v_unit->>'unit_code',COALESCE(nullif(v_unit->>'unit_role',''),'sale'),
        COALESCE(nullif(v_unit->>'quantity_in_base','')::numeric,1),nullif(btrim(v_unit->>'barcode'),''),
        nullif(btrim(v_unit->>'sku'),''),nullif(btrim(v_unit->>'package_name'),''),
        nullif(v_unit->>'net_quantity','')::numeric,nullif(v_unit->>'net_unit_code',''),
        nullif(v_unit->>'image_url',''),COALESCE((v_unit->>'is_default')::boolean,false),auth.uid()
      );
    END LOOP;
  END IF;

  INSERT INTO public.commercial_catalog_product_sectors(product_id,sector_code)
  SELECT v_id,lower(btrim(s)) FROM unnest(COALESCE(p_sectors,ARRAY[]::text[])) s
  WHERE nullif(btrim(s),'') IS NOT NULL
  ON CONFLICT DO NOTHING;

  FOR v_level IN SELECT value FROM jsonb_array_elements(COALESCE(p_business_levels,'[]'::jsonb)) LOOP
    INSERT INTO public.commercial_catalog_product_business_levels(product_id,business_level_code,recommendation_weight,is_excluded)
    VALUES(v_id,lower(btrim(v_level->>'code')),COALESCE(nullif(v_level->>'weight','')::smallint,0),COALESCE((v_level->>'excluded')::boolean,false))
    ON CONFLICT (product_id,business_level_code) DO UPDATE SET recommendation_weight=EXCLUDED.recommendation_weight,is_excluded=EXCLUDED.is_excluded;
  END LOOP;

  FOR v_region IN SELECT value FROM jsonb_array_elements(COALESCE(p_regions,'[]'::jsonb)) LOOP
    INSERT INTO public.commercial_catalog_product_regions(product_id,country_code,region_code,city,is_excluded)
    VALUES(v_id,upper(nullif(btrim(v_region->>'country_code'),'')),nullif(btrim(v_region->>'region_code'),''),nullif(btrim(v_region->>'city'),''),COALESCE((v_region->>'excluded')::boolean,false))
    ON CONFLICT DO NOTHING;
  END LOOP;

  FOR v_ruling IN SELECT value FROM jsonb_array_elements(COALESCE(p_rulings,'[]'::jsonb)) LOOP
    INSERT INTO public.commercial_catalog_product_rulings(
      product_id,ruling_domain,authority_code,religion_code,jurisdiction_code,outcome,rationale,
      source_title,source_url,effective_from,effective_to,confidence,reviewed_by,reviewed_at,created_by
    ) VALUES (
      v_id,v_ruling->>'ruling_domain',btrim(v_ruling->>'authority_code'),nullif(btrim(v_ruling->>'religion_code'),''),
      nullif(btrim(v_ruling->>'jurisdiction_code'),''),v_ruling->>'outcome',nullif(btrim(v_ruling->>'rationale'),''),
      nullif(btrim(v_ruling->>'source_title'),''),nullif(btrim(v_ruling->>'source_url'),''),
      nullif(v_ruling->>'effective_from','')::date,nullif(v_ruling->>'effective_to','')::date,
      COALESCE(nullif(v_ruling->>'confidence',''),'unverified'),NULL,NULL,auth.uid()
    );
  END LOOP;

  INSERT INTO public.commercial_organization_catalog_items(
    organization_id,global_item_id,merchant_display_name,merchant_description,
    price_minor,currency_code,is_available,metadata,created_by
  )
  SELECT p_organization_id,v_id,btrim(p_product->>'name_ar'),
    nullif(btrim(p_product->>'description_ar'),''),0,COALESCE(nullif(p_product->>'currency_code',''),'YER'),true,
    jsonb_build_object('created_from_catalog_manager',true),auth.uid()
  FROM public.commercial_organizations o WHERE o.id=p_organization_id
  ON CONFLICT (organization_id,global_item_id) DO NOTHING;

  RETURN jsonb_build_object('product_id',v_id,'catalog_code',v_code,'created_by',auth.uid());
END $$;
REVOKE ALL ON FUNCTION public.commercial_catalog_create_product(uuid,jsonb,jsonb,text[],jsonb,jsonb,jsonb) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_catalog_create_product(uuid,jsonb,jsonb,text[],jsonb,jsonb,jsonb) TO authenticated;

CREATE OR REPLACE FUNCTION public.commercial_catalog_transfer(
  p_source_organization_id uuid,
  p_destination_organization_id uuid,
  p_global_item_ids uuid[] DEFAULT NULL
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE v_transfer_id uuid; v_count integer;
BEGIN
  IF auth.uid() IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_source_organization_id=p_destination_organization_id THEN RAISE EXCEPTION 'same_organization'; END IF;
  IF NOT public.commercial_catalog_member_of(p_source_organization_id, ARRAY['owner','admin']::text[])
     OR NOT public.commercial_catalog_member_of(p_destination_organization_id, ARRAY['owner','admin']::text[]) THEN
    RAISE EXCEPTION 'catalog_transfer_forbidden' USING ERRCODE='42501';
  END IF;
  INSERT INTO public.commercial_catalog_transfers(source_organization_id,destination_organization_id,transferred_by)
  VALUES(p_source_organization_id,p_destination_organization_id,auth.uid()) RETURNING id INTO v_transfer_id;
  INSERT INTO public.commercial_organization_catalog_items(
    organization_id,global_item_id,merchant_sku,merchant_display_name,merchant_description,
    price_minor,currency_code,is_available,metadata,created_by,source_organization_id,imported_at,is_favorite,is_enabled
  )
  SELECT p_destination_organization_id, i.global_item_id,
    CASE WHEN i.merchant_sku IS NULL OR NOT EXISTS (
      SELECT 1 FROM public.commercial_organization_catalog_items d
      WHERE d.organization_id=p_destination_organization_id AND d.merchant_sku=i.merchant_sku
    ) THEN i.merchant_sku ELSE NULL END,
    i.merchant_display_name, i.merchant_description, i.price_minor, i.currency_code, i.is_available, i.metadata,
    auth.uid(), p_source_organization_id, now(), i.is_favorite, i.is_enabled
  FROM public.commercial_organization_catalog_items i
  WHERE i.organization_id=p_source_organization_id
    AND (p_global_item_ids IS NULL OR i.global_item_id=ANY(p_global_item_ids))
  ON CONFLICT (organization_id,global_item_id) DO NOTHING;
  GET DIAGNOSTICS v_count=ROW_COUNT;
  INSERT INTO public.commercial_organization_entity_translations(
    organization_id,entity_type,entity_id,locale_code,field_key,translated_value,created_by
  )
  SELECT p_destination_organization_id,t.entity_type,t.entity_id,t.locale_code,t.field_key,t.translated_value,auth.uid()
  FROM public.commercial_organization_entity_translations t
  WHERE t.organization_id=p_source_organization_id AND t.entity_type='catalog_item'
    AND EXISTS (
      SELECT 1 FROM public.commercial_organization_catalog_items src
      WHERE src.organization_id=p_source_organization_id AND src.global_item_id=t.entity_id
        AND (p_global_item_ids IS NULL OR src.global_item_id=ANY(p_global_item_ids))
    )
  ON CONFLICT DO NOTHING;
  UPDATE public.commercial_catalog_transfers SET item_count=v_count WHERE id=v_transfer_id;
  RETURN jsonb_build_object('transfer_id',v_transfer_id,'items_transferred',v_count);
END $$;
REVOKE ALL ON FUNCTION public.commercial_catalog_transfer(uuid,uuid,uuid[]) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_catalog_transfer(uuid,uuid,uuid[]) TO authenticated;

COMMENT ON FUNCTION public.commercial_catalog_create_product(uuid,jsonb,jsonb,text[],jsonb,jsonb,jsonb) IS
'Creates an organization-owned catalog product and its unit variants, sector/business-level/region targeting and sourced rulings in one transaction. Religious and medical outcomes are caller-supplied, source-attributed and unverified by default.';


-- Cross-account transfers are two-phase: source sends, destination owner explicitly accepts.
ALTER TABLE public.commercial_catalog_transfers
  ADD COLUMN IF NOT EXISTS global_item_ids uuid[],
  ADD COLUMN IF NOT EXISTS accepted_by uuid REFERENCES auth.users(id),
  ADD COLUMN IF NOT EXISTS accepted_at timestamptz;
ALTER TABLE public.commercial_catalog_transfers
  DROP CONSTRAINT IF EXISTS commercial_catalog_transfers_status_check;
ALTER TABLE public.commercial_catalog_transfers
  ADD CONSTRAINT commercial_catalog_transfers_status_check
  CHECK (status IN ('pending','completed','failed','rejected'));

CREATE OR REPLACE FUNCTION public.commercial_catalog_send_transfer(
  p_source_organization_id uuid,
  p_destination_organization_id uuid,
  p_global_item_ids uuid[] DEFAULT NULL
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE v_transfer_id uuid; v_ids uuid[]; v_requested integer;
BEGIN
  IF auth.uid() IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_source_organization_id=p_destination_organization_id THEN RAISE EXCEPTION 'same_organization' USING ERRCODE='22023'; END IF;
  IF NOT public.commercial_catalog_member_of(p_source_organization_id,ARRAY['owner','admin']::text[]) THEN
    RAISE EXCEPTION 'catalog_transfer_source_forbidden' USING ERRCODE='42501';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM public.commercial_organizations o WHERE o.id=p_destination_organization_id) THEN
    RAISE EXCEPTION 'catalog_transfer_destination_not_found' USING ERRCODE='22023';
  END IF;
  IF p_global_item_ids IS NULL THEN
    SELECT array_agg(DISTINCT i.global_item_id) INTO v_ids
    FROM public.commercial_organization_catalog_items i
    WHERE i.organization_id=p_source_organization_id;
  ELSE
    SELECT array_agg(DISTINCT x) INTO v_ids FROM unnest(p_global_item_ids) x;
    IF EXISTS (
      SELECT x FROM unnest(COALESCE(v_ids,ARRAY[]::uuid[])) x
      EXCEPT SELECT i.global_item_id FROM public.commercial_organization_catalog_items i
      WHERE i.organization_id=p_source_organization_id
    ) THEN RAISE EXCEPTION 'transfer_contains_unowned_catalog_items' USING ERRCODE='42501'; END IF;
  END IF;
  v_requested := COALESCE(cardinality(v_ids),0);
  IF v_requested=0 THEN RAISE EXCEPTION 'catalog_transfer_empty' USING ERRCODE='22023'; END IF;
  INSERT INTO public.commercial_catalog_transfers(
    source_organization_id,destination_organization_id,transferred_by,item_count,status,global_item_ids
  ) VALUES(p_source_organization_id,p_destination_organization_id,auth.uid(),v_requested,'pending',v_ids)
  RETURNING id INTO v_transfer_id;
  RETURN jsonb_build_object('transfer_id',v_transfer_id,'status','pending','items_requested',v_requested);
END $$;
REVOKE ALL ON FUNCTION public.commercial_catalog_send_transfer(uuid,uuid,uuid[]) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_catalog_send_transfer(uuid,uuid,uuid[]) TO authenticated;

CREATE OR REPLACE FUNCTION public.commercial_catalog_accept_transfer(p_transfer_id uuid)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE v_transfer public.commercial_catalog_transfers%ROWTYPE; v_count integer;
BEGIN
  IF auth.uid() IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  SELECT * INTO v_transfer FROM public.commercial_catalog_transfers WHERE id=p_transfer_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'catalog_transfer_not_found' USING ERRCODE='22023'; END IF;
  IF NOT public.commercial_catalog_member_of(v_transfer.destination_organization_id,ARRAY['owner','admin']::text[]) THEN
    RAISE EXCEPTION 'catalog_transfer_accept_forbidden' USING ERRCODE='42501';
  END IF;
  IF v_transfer.status<>'pending' THEN RAISE EXCEPTION 'catalog_transfer_not_pending' USING ERRCODE='22023'; END IF;

  INSERT INTO public.commercial_organization_catalog_items(
    organization_id,global_item_id,merchant_sku,merchant_display_name,merchant_description,
    price_minor,currency_code,is_available,metadata,created_by,source_organization_id,imported_at,is_favorite,is_enabled
  )
  SELECT v_transfer.destination_organization_id,src.global_item_id,
    CASE WHEN src.merchant_sku IS NULL OR NOT EXISTS (
      SELECT 1 FROM public.commercial_organization_catalog_items d
      WHERE d.organization_id=v_transfer.destination_organization_id AND d.merchant_sku=src.merchant_sku
    ) THEN src.merchant_sku ELSE NULL END,
    src.merchant_display_name,src.merchant_description,src.price_minor,src.currency_code,src.is_available,
    src.metadata,auth.uid(),v_transfer.source_organization_id,now(),src.is_favorite,src.is_enabled
  FROM public.commercial_organization_catalog_items src
  WHERE src.organization_id=v_transfer.source_organization_id
    AND src.global_item_id=ANY(COALESCE(v_transfer.global_item_ids,ARRAY[]::uuid[]))
  ON CONFLICT (organization_id,global_item_id) DO NOTHING;
  GET DIAGNOSTICS v_count=ROW_COUNT;

  INSERT INTO public.commercial_organization_entity_translations(
    organization_id,entity_type,entity_id,locale_code,field_key,translated_value,created_by
  )
  SELECT v_transfer.destination_organization_id,t.entity_type,t.entity_id,t.locale_code,t.field_key,t.translated_value,auth.uid()
  FROM public.commercial_organization_entity_translations t
  WHERE t.organization_id=v_transfer.source_organization_id AND t.entity_type='catalog_item'
    AND t.entity_id=ANY(COALESCE(v_transfer.global_item_ids,ARRAY[]::uuid[]))
  ON CONFLICT DO NOTHING;

  UPDATE public.commercial_catalog_transfers
  SET status='completed',accepted_by=auth.uid(),accepted_at=now(),item_count=v_count
  WHERE id=v_transfer.id;
  RETURN jsonb_build_object('transfer_id',v_transfer.id,'status','completed','items_transferred',v_count);
END $$;
REVOKE ALL ON FUNCTION public.commercial_catalog_accept_transfer(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_catalog_accept_transfer(uuid) TO authenticated;

CREATE OR REPLACE FUNCTION public.commercial_catalog_reject_transfer(p_transfer_id uuid)
RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE v_transfer public.commercial_catalog_transfers%ROWTYPE;
BEGIN
  IF auth.uid() IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  SELECT * INTO v_transfer FROM public.commercial_catalog_transfers WHERE id=p_transfer_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'catalog_transfer_not_found' USING ERRCODE='22023'; END IF;
  IF NOT public.commercial_catalog_member_of(v_transfer.destination_organization_id,ARRAY['owner','admin']::text[]) THEN
    RAISE EXCEPTION 'catalog_transfer_reject_forbidden' USING ERRCODE='42501';
  END IF;
  IF v_transfer.status<>'pending' THEN RAISE EXCEPTION 'catalog_transfer_not_pending' USING ERRCODE='22023'; END IF;
  UPDATE public.commercial_catalog_transfers SET status='rejected',accepted_by=auth.uid(),accepted_at=now() WHERE id=v_transfer.id;
  RETURN jsonb_build_object('transfer_id',v_transfer.id,'status','rejected');
END $$;
REVOKE ALL ON FUNCTION public.commercial_catalog_reject_transfer(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_catalog_reject_transfer(uuid) TO authenticated;
