-- Extend the existing global catalog rather than creating parallel product/category/unit masters.
-- Product images are HTTPS URLs only; image bytes are hosted outside Supabase Storage.

ALTER TABLE public.commercial_global_catalog_categories
  ADD COLUMN IF NOT EXISTS owner_organization_id uuid REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
  ADD COLUMN IF NOT EXISTS created_by uuid REFERENCES auth.users(id),
  ADD COLUMN IF NOT EXISTS updated_by uuid REFERENCES auth.users(id);

ALTER TABLE public.commercial_global_catalog_items
  ADD COLUMN IF NOT EXISTS owner_organization_id uuid REFERENCES public.commercial_organizations(id) ON DELETE CASCADE,
  ADD COLUMN IF NOT EXISTS brand text,
  ADD COLUMN IF NOT EXISTS manufacturer_name text,
  ADD COLUMN IF NOT EXISTS country_of_manufacture text,
  ADD COLUMN IF NOT EXISTS model_number text,
  ADD COLUMN IF NOT EXISTS part_number text,
  ADD COLUMN IF NOT EXISTS material_composition jsonb NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN IF NOT EXISTS ingredients jsonb NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN IF NOT EXISTS recipe jsonb NOT NULL DEFAULT '{}'::jsonb,
  ADD COLUMN IF NOT EXISTS specifications jsonb NOT NULL DEFAULT '{}'::jsonb,
  ADD COLUMN IF NOT EXISTS allergens jsonb NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN IF NOT EXISTS storage_instructions text,
  ADD COLUMN IF NOT EXISTS usage_instructions text,
  ADD COLUMN IF NOT EXISTS warnings text,
  ADD COLUMN IF NOT EXISTS shelf_life_days integer,
  ADD COLUMN IF NOT EXISTS requires_expiry_date boolean NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS is_batch_tracked boolean NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS is_serial_tracked boolean NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS is_prescription_only boolean NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS image_url text,
  ADD COLUMN IF NOT EXISTS image_alt text,
  ADD COLUMN IF NOT EXISTS created_by uuid REFERENCES auth.users(id),
  ADD COLUMN IF NOT EXISTS updated_by uuid REFERENCES auth.users(id);

ALTER TABLE public.commercial_global_catalog_items
  ADD CONSTRAINT commercial_global_catalog_items_material_composition_array
    CHECK (jsonb_typeof(material_composition) = 'array'),
  ADD CONSTRAINT commercial_global_catalog_items_ingredients_array
    CHECK (jsonb_typeof(ingredients) = 'array'),
  ADD CONSTRAINT commercial_global_catalog_items_recipe_object
    CHECK (jsonb_typeof(recipe) = 'object'),
  ADD CONSTRAINT commercial_global_catalog_items_specifications_object
    CHECK (jsonb_typeof(specifications) = 'object'),
  ADD CONSTRAINT commercial_global_catalog_items_allergens_array
    CHECK (jsonb_typeof(allergens) = 'array'),
  ADD CONSTRAINT commercial_global_catalog_items_shelf_life_nonnegative
    CHECK (shelf_life_days IS NULL OR shelf_life_days >= 0),
  ADD CONSTRAINT commercial_global_catalog_items_image_https
    CHECK (image_url IS NULL OR (length(image_url) <= 1000 AND image_url ~* '^https://[^[:space:]]+$'));

-- Existing generic translation storage supports arbitrary locales; ownership keeps private category labels private.
ALTER TABLE public.commercial_global_entity_translations
  ADD COLUMN IF NOT EXISTS owner_organization_id uuid REFERENCES public.commercial_organizations(id) ON DELETE CASCADE,
  ADD COLUMN IF NOT EXISTS created_by uuid REFERENCES auth.users(id);

ALTER TABLE public.commercial_organization_catalog_items
  ADD COLUMN IF NOT EXISTS source_organization_id uuid REFERENCES public.commercial_organizations(id) ON DELETE SET NULL,
  ADD COLUMN IF NOT EXISTS imported_at timestamptz,
  ADD COLUMN IF NOT EXISTS is_favorite boolean NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS is_enabled boolean NOT NULL DEFAULT true;

CREATE INDEX IF NOT EXISTS ix_commercial_catalog_items_owner
  ON public.commercial_global_catalog_items(owner_organization_id);
CREATE INDEX IF NOT EXISTS ix_commercial_catalog_categories_owner
  ON public.commercial_global_catalog_categories(owner_organization_id);
CREATE INDEX IF NOT EXISTS ix_commercial_catalog_items_name_ar
  ON public.commercial_global_catalog_items(name_ar);
CREATE INDEX IF NOT EXISTS ix_commercial_catalog_items_name_en
  ON public.commercial_global_catalog_items(name_en);
CREATE INDEX IF NOT EXISTS ix_commercial_catalog_items_barcode_gtin
  ON public.commercial_global_catalog_items(barcode_gtin) WHERE barcode_gtin IS NOT NULL;

-- Product-specific sale/purchase/stock/recipe/shipping units, package variants, SKU/barcode and package image.
CREATE TABLE IF NOT EXISTS public.commercial_catalog_product_units (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  product_id uuid NOT NULL REFERENCES public.commercial_global_catalog_items(id) ON DELETE CASCADE,
  unit_code text NOT NULL REFERENCES public.commercial_global_units(code) ON DELETE RESTRICT,
  unit_role text NOT NULL DEFAULT 'sale' CHECK (unit_role IN ('base','purchase','sale','stock','recipe','shipping','consumption','other')),
  quantity_in_base numeric(30,12) NOT NULL DEFAULT 1 CHECK (quantity_in_base > 0),
  barcode text,
  sku text,
  package_name text,
  net_quantity numeric(30,12) CHECK (net_quantity IS NULL OR net_quantity > 0),
  net_unit_code text REFERENCES public.commercial_global_units(code) ON DELETE RESTRICT,
  image_url text CHECK (image_url IS NULL OR (length(image_url) <= 1000 AND image_url ~* '^https://[^[:space:]]+$')),
  is_default boolean NOT NULL DEFAULT false,
  is_active boolean NOT NULL DEFAULT true,
  created_by uuid REFERENCES auth.users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE NULLS NOT DISTINCT (product_id, unit_code, unit_role, package_name),
  CHECK (barcode IS NULL OR length(btrim(barcode)) BETWEEN 3 AND 128),
  CHECK (sku IS NULL OR length(btrim(sku)) <= 100)
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_commercial_catalog_product_unit_barcode
  ON public.commercial_catalog_product_units(barcode) WHERE barcode IS NOT NULL AND is_active;
CREATE INDEX IF NOT EXISTS ix_commercial_catalog_product_units_product
  ON public.commercial_catalog_product_units(product_id);

CREATE TABLE IF NOT EXISTS public.commercial_catalog_product_sectors (
  product_id uuid NOT NULL REFERENCES public.commercial_global_catalog_items(id) ON DELETE CASCADE,
  sector_code text NOT NULL CHECK (length(btrim(sector_code)) BETWEEN 1 AND 80),
  is_excluded boolean NOT NULL DEFAULT false,
  PRIMARY KEY (product_id, sector_code)
);
CREATE TABLE IF NOT EXISTS public.commercial_catalog_product_business_levels (
  product_id uuid NOT NULL REFERENCES public.commercial_global_catalog_items(id) ON DELETE CASCADE,
  business_level_code text NOT NULL CHECK (length(btrim(business_level_code)) BETWEEN 1 AND 80),
  is_excluded boolean NOT NULL DEFAULT false,
  recommendation_weight smallint NOT NULL DEFAULT 0 CHECK (recommendation_weight BETWEEN -100 AND 100),
  PRIMARY KEY (product_id, business_level_code)
);
CREATE TABLE IF NOT EXISTS public.commercial_catalog_product_regions (
  product_id uuid NOT NULL REFERENCES public.commercial_global_catalog_items(id) ON DELETE CASCADE,
  country_code text,
  region_code text,
  city text,
  is_excluded boolean NOT NULL DEFAULT false,
  CHECK (country_code IS NULL OR country_code ~ '^[A-Z]{2}$'),
  CHECK (country_code IS NOT NULL OR region_code IS NOT NULL OR city IS NOT NULL),
  UNIQUE NULLS NOT DISTINCT (product_id, country_code, region_code, city)
);

CREATE TABLE IF NOT EXISTS public.commercial_catalog_product_recipes (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  product_id uuid NOT NULL REFERENCES public.commercial_global_catalog_items(id) ON DELETE CASCADE,
  locale_code text NOT NULL DEFAULT 'ar',
  recipe_name text NOT NULL,
  instructions text,
  yield_quantity numeric(30,12) CHECK (yield_quantity IS NULL OR yield_quantity > 0),
  yield_unit_code text REFERENCES public.commercial_global_units(code),
  preparation_minutes integer CHECK (preparation_minutes IS NULL OR preparation_minutes >= 0),
  cooking_minutes integer CHECK (cooking_minutes IS NULL OR cooking_minutes >= 0),
  allergen_notes text,
  source_reference text,
  reviewed_by uuid REFERENCES auth.users(id),
  reviewed_at timestamptz,
  created_by uuid REFERENCES auth.users(id),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS public.commercial_catalog_recipe_ingredients (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  recipe_id uuid NOT NULL REFERENCES public.commercial_catalog_product_recipes(id) ON DELETE CASCADE,
  ingredient_product_id uuid REFERENCES public.commercial_global_catalog_items(id) ON DELETE RESTRICT,
  ingredient_name text,
  quantity numeric(30,12) NOT NULL CHECK (quantity > 0),
  unit_code text NOT NULL REFERENCES public.commercial_global_units(code) ON DELETE RESTRICT,
  preparation_note text,
  CHECK (ingredient_product_id IS NOT NULL OR nullif(btrim(ingredient_name),'') IS NOT NULL)
);

-- Claims are attributed, jurisdiction-aware and reviewable; no universal ruling is inferred.
CREATE TABLE IF NOT EXISTS public.commercial_catalog_product_rulings (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  product_id uuid NOT NULL REFERENCES public.commercial_global_catalog_items(id) ON DELETE CASCADE,
  ruling_domain text NOT NULL CHECK (ruling_domain IN ('religious','medical','regulatory','dietary')),
  authority_code text NOT NULL,
  religion_code text,
  jurisdiction_code text,
  outcome text NOT NULL CHECK (outcome IN ('permitted','prohibited','conditional','unknown','safe_with_conditions','caution','contraindicated','not_assessed')),
  rationale text,
  source_title text,
  source_url text CHECK (source_url IS NULL OR (length(source_url) <= 1000 AND source_url ~* '^https://[^[:space:]]+$')),
  effective_from date,
  effective_to date,
  confidence text NOT NULL DEFAULT 'unverified' CHECK (confidence IN ('unverified','reported','reviewed','official')),
  reviewed_by uuid REFERENCES auth.users(id),
  reviewed_at timestamptz,
  created_by uuid REFERENCES auth.users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to >= effective_from),
  CHECK (ruling_domain <> 'religious' OR nullif(btrim(religion_code),'') IS NOT NULL)
);
CREATE INDEX IF NOT EXISTS ix_commercial_catalog_rulings_product
  ON public.commercial_catalog_product_rulings(product_id, ruling_domain);

CREATE TABLE IF NOT EXISTS public.commercial_catalog_transfers (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  source_organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE CASCADE,
  destination_organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE CASCADE,
  transferred_by uuid NOT NULL REFERENCES auth.users(id),
  item_count integer NOT NULL DEFAULT 0,
  status text NOT NULL DEFAULT 'completed' CHECK (status IN ('completed','failed')),
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (source_organization_id <> destination_organization_id)
);

CREATE OR REPLACE FUNCTION public.commercial_catalog_member_of(
  p_org_id uuid, p_roles text[] DEFAULT ARRAY['owner','admin']::text[]
) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
  SELECT auth.uid() IS NOT NULL AND EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id=p_org_id AND m.user_id=auth.uid()
      AND m.state='active' AND m.role=ANY(p_roles)
  );
$$;
REVOKE ALL ON FUNCTION public.commercial_catalog_member_of(uuid,text[]) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_catalog_member_of(uuid,text[]) TO authenticated;

CREATE OR REPLACE FUNCTION public.commercial_catalog_audit_fields()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, public AS $
BEGIN
  IF auth.uid() IS NOT NULL THEN
    IF TG_OP='INSERT' THEN NEW.created_by := auth.uid(); END IF;
    NEW.updated_by := auth.uid();
  END IF;
  NEW.updated_at := now();
  RETURN NEW;
END $;
DROP TRIGGER IF EXISTS commercial_catalog_items_audit ON public.commercial_global_catalog_items;
CREATE TRIGGER commercial_catalog_items_audit
BEFORE INSERT OR UPDATE ON public.commercial_global_catalog_items
FOR EACH ROW EXECUTE FUNCTION public.commercial_catalog_audit_fields();
DROP TRIGGER IF EXISTS commercial_catalog_categories_audit ON public.commercial_global_catalog_categories;
CREATE TRIGGER commercial_catalog_categories_audit
BEFORE INSERT OR UPDATE ON public.commercial_global_catalog_categories
FOR EACH ROW EXECUTE FUNCTION public.commercial_catalog_audit_fields();

-- Category parent_code already exists; guard it against cycles and cross-tenant parent changes.
CREATE OR REPLACE FUNCTION public.commercial_catalog_category_cycle_guard()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, public AS $$
DECLARE v_parent_owner uuid;
BEGIN
  IF NEW.parent_code IS NULL THEN RETURN NEW; END IF;
  IF NEW.parent_code=NEW.code THEN RAISE EXCEPTION 'catalog_category_cycle'; END IF;
  IF EXISTS (
    WITH RECURSIVE ancestors(code,parent_code) AS (
      SELECT c.code,c.parent_code FROM public.commercial_global_catalog_categories c WHERE c.code=NEW.parent_code
      UNION ALL
      SELECT p.code,p.parent_code FROM public.commercial_global_catalog_categories p
      JOIN ancestors a ON p.code=a.parent_code
    ) SELECT 1 FROM ancestors WHERE code=NEW.code
  ) THEN RAISE EXCEPTION 'catalog_category_cycle'; END IF;
  SELECT owner_organization_id INTO v_parent_owner
    FROM public.commercial_global_catalog_categories WHERE code=NEW.parent_code;
  IF v_parent_owner IS NOT NULL AND v_parent_owner IS DISTINCT FROM NEW.owner_organization_id THEN
    RAISE EXCEPTION 'catalog_category_owner_mismatch';
  END IF;
  RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS commercial_catalog_category_cycle_guard ON public.commercial_global_catalog_categories;
CREATE TRIGGER commercial_catalog_category_cycle_guard
BEFORE INSERT OR UPDATE OF parent_code, owner_organization_id ON public.commercial_global_catalog_categories
FOR EACH ROW EXECUTE FUNCTION public.commercial_catalog_category_cycle_guard();

CREATE OR REPLACE FUNCTION public.commercial_catalog_item_category_owner_guard()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, public AS $
DECLARE v_category_owner uuid;
BEGIN
  SELECT owner_organization_id INTO v_category_owner
  FROM public.commercial_global_catalog_categories WHERE code=NEW.category_code;
  IF v_category_owner IS NOT NULL AND v_category_owner IS DISTINCT FROM NEW.owner_organization_id THEN
    RAISE EXCEPTION 'catalog_item_category_owner_mismatch';
  END IF;
  RETURN NEW;
END $;
DROP TRIGGER IF EXISTS commercial_catalog_item_category_owner_guard ON public.commercial_global_catalog_items;
CREATE TRIGGER commercial_catalog_item_category_owner_guard
BEFORE INSERT OR UPDATE OF category_code, owner_organization_id
ON public.commercial_global_catalog_items
FOR EACH ROW EXECUTE FUNCTION public.commercial_catalog_item_category_owner_guard();

-- Restrictive tenant guards are ANDed with existing catalog policies, so active global templates remain public to authenticated users.
CREATE POLICY commercial_catalog_items_tenant_guard
ON public.commercial_global_catalog_items AS RESTRICTIVE FOR ALL TO authenticated
USING (
  owner_organization_id IS NULL
  OR public.commercial_catalog_member_of(owner_organization_id, ARRAY['owner','admin','manager','staff']::text[])
  OR EXISTS (
    SELECT 1 FROM public.commercial_organization_catalog_items oi
    WHERE oi.global_item_id=id
      AND public.commercial_catalog_member_of(oi.organization_id, ARRAY['owner','admin','manager','staff']::text[])
  )
)
WITH CHECK (
  owner_organization_id IS NULL
  OR public.commercial_catalog_member_of(owner_organization_id, ARRAY['owner','admin']::text[])
);

CREATE POLICY commercial_catalog_categories_tenant_guard
ON public.commercial_global_catalog_categories AS RESTRICTIVE FOR ALL TO authenticated
USING (
  owner_organization_id IS NULL
  OR public.commercial_catalog_member_of(owner_organization_id, ARRAY['owner','admin','manager','staff']::text[])
  OR EXISTS (SELECT 1 FROM public.commercial_global_catalog_items i WHERE i.category_code=code)
)
WITH CHECK (
  owner_organization_id IS NULL
  OR public.commercial_catalog_member_of(owner_organization_id, ARRAY['owner','admin']::text[])
);

CREATE POLICY commercial_catalog_global_translations_tenant_guard
ON public.commercial_global_entity_translations AS RESTRICTIVE FOR ALL TO authenticated
USING (
  owner_organization_id IS NULL
  OR public.commercial_catalog_member_of(owner_organization_id, ARRAY['owner','admin','manager','staff']::text[])
  OR (
    entity_type='catalog_item' AND EXISTS (
      SELECT 1 FROM public.commercial_global_catalog_items p
      JOIN public.commercial_organization_catalog_items oi ON oi.global_item_id=p.id
      WHERE p.catalog_code=entity_key
        AND public.commercial_catalog_member_of(oi.organization_id, ARRAY['owner','admin','manager','staff']::text[])
    )
  )
  OR (
    entity_type='catalog_category' AND EXISTS (
      SELECT 1 FROM public.commercial_global_catalog_categories c
      JOIN public.commercial_global_catalog_items p ON p.category_code=c.code
      JOIN public.commercial_organization_catalog_items oi ON oi.global_item_id=p.id
      WHERE c.code=entity_key
        AND public.commercial_catalog_member_of(oi.organization_id, ARRAY['owner','admin','manager','staff']::text[])
    )
  )
)
WITH CHECK (
  owner_organization_id IS NULL
  OR public.commercial_catalog_member_of(owner_organization_id, ARRAY['owner','admin']::text[])
);

CREATE POLICY commercial_catalog_items_org_insert
ON public.commercial_global_catalog_items FOR INSERT TO authenticated
WITH CHECK (owner_organization_id IS NOT NULL AND public.commercial_catalog_member_of(owner_organization_id, ARRAY['owner','admin']::text[]));
CREATE POLICY commercial_catalog_items_org_update
ON public.commercial_global_catalog_items FOR UPDATE TO authenticated
USING (owner_organization_id IS NOT NULL AND public.commercial_catalog_member_of(owner_organization_id, ARRAY['owner','admin']::text[]))
WITH CHECK (owner_organization_id IS NOT NULL AND public.commercial_catalog_member_of(owner_organization_id, ARRAY['owner','admin']::text[]));
CREATE POLICY commercial_catalog_items_org_delete
ON public.commercial_global_catalog_items FOR DELETE TO authenticated
USING (owner_organization_id IS NOT NULL AND public.commercial_catalog_member_of(owner_organization_id, ARRAY['owner','admin']::text[]));

CREATE POLICY commercial_catalog_categories_org_insert
ON public.commercial_global_catalog_categories FOR INSERT TO authenticated
WITH CHECK (owner_organization_id IS NOT NULL AND public.commercial_catalog_member_of(owner_organization_id, ARRAY['owner','admin']::text[]));
CREATE POLICY commercial_catalog_categories_org_update
ON public.commercial_global_catalog_categories FOR UPDATE TO authenticated
USING (owner_organization_id IS NOT NULL AND public.commercial_catalog_member_of(owner_organization_id, ARRAY['owner','admin']::text[]))
WITH CHECK (owner_organization_id IS NOT NULL AND public.commercial_catalog_member_of(owner_organization_id, ARRAY['owner','admin']::text[]));
CREATE POLICY commercial_catalog_categories_org_delete
ON public.commercial_global_catalog_categories FOR DELETE TO authenticated
USING (owner_organization_id IS NOT NULL AND public.commercial_catalog_member_of(owner_organization_id, ARRAY['owner','admin']::text[]));

CREATE POLICY commercial_catalog_global_translations_org_insert
ON public.commercial_global_entity_translations FOR INSERT TO authenticated
WITH CHECK (owner_organization_id IS NOT NULL AND public.commercial_catalog_member_of(owner_organization_id, ARRAY['owner','admin']::text[]));
CREATE POLICY commercial_catalog_global_translations_org_update
ON public.commercial_global_entity_translations FOR UPDATE TO authenticated
USING (owner_organization_id IS NOT NULL AND public.commercial_catalog_member_of(owner_organization_id, ARRAY['owner','admin']::text[]))
WITH CHECK (owner_organization_id IS NOT NULL AND public.commercial_catalog_member_of(owner_organization_id, ARRAY['owner','admin']::text[]));
CREATE POLICY commercial_catalog_global_translations_org_delete
ON public.commercial_global_entity_translations FOR DELETE TO authenticated
USING (owner_organization_id IS NOT NULL AND public.commercial_catalog_member_of(owner_organization_id, ARRAY['owner','admin']::text[]));

GRANT SELECT, INSERT, UPDATE, DELETE ON public.commercial_global_catalog_items TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.commercial_global_catalog_categories TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.commercial_global_entity_translations TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.commercial_catalog_product_units TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.commercial_catalog_product_sectors TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.commercial_catalog_product_business_levels TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.commercial_catalog_product_regions TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.commercial_catalog_product_recipes TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.commercial_catalog_recipe_ingredients TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.commercial_catalog_product_rulings TO authenticated;
GRANT SELECT ON public.commercial_catalog_transfers TO authenticated;
GRANT INSERT ON public.commercial_catalog_transfers TO authenticated;

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY[
    'commercial_catalog_product_units','commercial_catalog_product_sectors',
    'commercial_catalog_product_business_levels','commercial_catalog_product_regions',
    'commercial_catalog_product_recipes','commercial_catalog_recipe_ingredients',
    'commercial_catalog_product_rulings','commercial_catalog_transfers'
  ] LOOP
    EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY',t);
    EXECUTE format('REVOKE ALL ON public.%I FROM anon',t);
  END LOOP;
END $$;

-- Child rows inherit product visibility; writes are limited to the owning organization.
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY[
    'commercial_catalog_product_units','commercial_catalog_product_sectors',
    'commercial_catalog_product_business_levels','commercial_catalog_product_regions',
    'commercial_catalog_product_recipes','commercial_catalog_product_rulings'
  ] LOOP
    EXECUTE format(
      'CREATE POLICY %I ON public.%I FOR SELECT TO authenticated USING (
        EXISTS (SELECT 1 FROM public.commercial_global_catalog_items p
          WHERE p.id=product_id AND (p.owner_organization_id IS NULL
            OR public.commercial_catalog_member_of(p.owner_organization_id, ARRAY[''owner'',''admin'',''manager'',''staff'']::text[])
            OR EXISTS (SELECT 1 FROM public.commercial_organization_catalog_items oi
              WHERE oi.global_item_id=p.id
                AND public.commercial_catalog_member_of(oi.organization_id, ARRAY[''owner'',''admin'',''manager'',''staff'']::text[])))
        ))', t||'_read',t);
    EXECUTE format(
      'CREATE POLICY %I ON public.%I FOR ALL TO authenticated USING (
        EXISTS (SELECT 1 FROM public.commercial_global_catalog_items p
          WHERE p.id=product_id AND p.owner_organization_id IS NOT NULL
            AND public.commercial_catalog_member_of(p.owner_organization_id, ARRAY[''owner'',''admin'']::text[]))
        ) WITH CHECK (
        EXISTS (SELECT 1 FROM public.commercial_global_catalog_items p
          WHERE p.id=product_id AND p.owner_organization_id IS NOT NULL
            AND public.commercial_catalog_member_of(p.owner_organization_id, ARRAY[''owner'',''admin'']::text[]))
        )', t||'_write',t);
  END LOOP;
END $$;

CREATE POLICY commercial_catalog_recipe_ingredients_read
ON public.commercial_catalog_recipe_ingredients FOR SELECT TO authenticated
USING (
  EXISTS (
    SELECT 1 FROM public.commercial_catalog_product_recipes r
    JOIN public.commercial_global_catalog_items p ON p.id=r.product_id
    WHERE r.id=recipe_id AND (
      p.owner_organization_id IS NULL
      OR public.commercial_catalog_member_of(p.owner_organization_id, ARRAY['owner','admin','manager','staff']::text[])
      OR EXISTS (SELECT 1 FROM public.commercial_organization_catalog_items oi
        WHERE oi.global_item_id=p.id
          AND public.commercial_catalog_member_of(oi.organization_id, ARRAY['owner','admin','manager','staff']::text[]))
    )
  )
);
CREATE POLICY commercial_catalog_recipe_ingredients_write
ON public.commercial_catalog_recipe_ingredients FOR ALL TO authenticated
USING (
  EXISTS (
    SELECT 1 FROM public.commercial_catalog_product_recipes r
    JOIN public.commercial_global_catalog_items p ON p.id=r.product_id
    WHERE r.id=recipe_id AND p.owner_organization_id IS NOT NULL
      AND public.commercial_catalog_member_of(p.owner_organization_id, ARRAY['owner','admin']::text[])
  )
)
WITH CHECK (
  EXISTS (
    SELECT 1 FROM public.commercial_catalog_product_recipes r
    JOIN public.commercial_global_catalog_items p ON p.id=r.product_id
    WHERE r.id=recipe_id AND p.owner_organization_id IS NOT NULL
      AND public.commercial_catalog_member_of(p.owner_organization_id, ARRAY['owner','admin']::text[])
  )
);

CREATE POLICY commercial_catalog_transfers_read
ON public.commercial_catalog_transfers FOR SELECT TO authenticated
USING (
  public.commercial_catalog_member_of(source_organization_id, ARRAY['owner','admin']::text[])
  OR public.commercial_catalog_member_of(destination_organization_id, ARRAY['owner','admin']::text[])
);

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
    price_minor,currency_code,is_available,metadata,created_by,source_organization_id,imported_at,is_favorite
  )
  SELECT p_destination_organization_id, i.global_item_id,
    CASE WHEN i.merchant_sku IS NULL OR NOT EXISTS (
      SELECT 1 FROM public.commercial_organization_catalog_items d
      WHERE d.organization_id=p_destination_organization_id AND d.merchant_sku=i.merchant_sku
    ) THEN i.merchant_sku ELSE NULL END,
    i.merchant_display_name, i.merchant_description, i.price_minor, i.currency_code, i.is_available, i.metadata,
    auth.uid(), p_source_organization_id, now(), i.is_favorite
  FROM public.commercial_organization_catalog_items i
  WHERE i.organization_id=p_source_organization_id
    AND (p_global_item_ids IS NULL OR i.global_item_id=ANY(p_global_item_ids))
  ON CONFLICT (organization_id,global_item_id) DO NOTHING;
  GET DIAGNOSTICS v_count=ROW_COUNT;

  -- Preserve per-organization translations for the catalog items that were linked.
  INSERT INTO public.commercial_organization_entity_translations(
    organization_id,entity_type,entity_id,locale_code,field_key,translated_value,created_by
  )
  SELECT p_destination_organization_id,t.entity_type,t.entity_id,t.locale_code,t.field_key,t.translated_value,auth.uid()
  FROM public.commercial_organization_entity_translations t
  WHERE t.organization_id=p_source_organization_id
    AND t.entity_type='catalog_item'
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

-- Seed missing measurement units into the existing canonical unit directory; existing rows are preserved.
INSERT INTO public.commercial_global_units
  (code,name_ar,name_en,dimension,factor_to_base,base_unit_code,decimal_places,is_active,version)
VALUES
 ('PAIR','زوج','Pair','count',2,'EA',0,true,1),
 ('SET','طقم','Set','count',1,'EA',0,true,1),
 ('M3','متر مكعب','Cubic metre','volume',1000,'L',3,true,1),
 ('CM2','سنتيمتر مربع','Square centimetre','area',0.0001,'M2',3,true,1),
 ('HA','هكتار','Hectare','area',10000,'M2',3,true,1),
 ('SEC','ثانية','Second','time',0.000277777778,'HOUR',3,true,1),
 ('MIN','دقيقة','Minute','time',0.016666666667,'HOUR',3,true,1),
 ('CARTON','كرتون','Carton','count',1,'EA',0,true,1),
 ('PALLET','منصة تحميل','Pallet','count',1,'EA',0,true,1),
 ('KWH','كيلوواط ساعة','Kilowatt-hour','other',1,NULL,3,true,1),
 ('GB','غيغابايت','Gigabyte','other',1,NULL,2,true,1)
ON CONFLICT (code) DO NOTHING;

INSERT INTO public.commercial_global_entity_translations
  (entity_type,entity_key,locale_code,field_key,translated_value,source_language)
SELECT 'unit',u.code,'ar','name',u.name_ar,'ar'
FROM public.commercial_global_units u WHERE u.code IN ('PAIR','SET','M3','CM2','HA','SEC','MIN','CARTON','PALLET','KWH','GB')
ON CONFLICT DO NOTHING;
INSERT INTO public.commercial_global_entity_translations
  (entity_type,entity_key,locale_code,field_key,translated_value,source_language)
SELECT 'unit',u.code,'en','name',u.name_en,'en'
FROM public.commercial_global_units u WHERE u.code IN ('PAIR','SET','M3','CM2','HA','SEC','MIN','CARTON','PALLET','KWH','GB')
ON CONFLICT DO NOTHING;

-- Seed activity targeting for the existing starter catalog; future activity codes remain unrestricted.
INSERT INTO public.commercial_catalog_product_sectors(product_id,sector_code)
SELECT i.id, s.sector_code
FROM public.commercial_global_catalog_items i
JOIN (VALUES
 ('FOOD-GRAINS','retail'),('FOOD-GRAINS','healthcare'),('FOOD-GRAINS','general'),('FOOD-GRAINS','manufacturing'),
 ('FOOD-SWEETENERS','retail'),('FOOD-SWEETENERS','healthcare'),('FOOD-SWEETENERS','general'),('FOOD-SWEETENERS','manufacturing'),
 ('FOOD-OILS','retail'),('FOOD-OILS','healthcare'),('FOOD-OILS','general'),('FOOD-OILS','manufacturing'),
 ('FOOD-DRINKS','retail'),('FOOD-DRINKS','healthcare'),('FOOD-DRINKS','general'),('FOOD-DRINKS','services'),
 ('FOOD-DAIRY','retail'),('FOOD-DAIRY','healthcare'),('FOOD-DAIRY','general'),('FOOD-DAIRY','manufacturing'),
 ('FOOD-BAKERY','retail'),('FOOD-BAKERY','healthcare'),('FOOD-BAKERY','general'),('FOOD-BAKERY','services'),
 ('HOUSEHOLD-CLEANING','retail'),('HOUSEHOLD-CLEANING','healthcare'),('HOUSEHOLD-CLEANING','general'),('HOUSEHOLD-CLEANING','services'),
 ('OFFICE-SUPPLIES','retail'),('OFFICE-SUPPLIES','education'),('OFFICE-SUPPLIES','general'),('OFFICE-SUPPLIES','services'),
 ('CONSTRUCTION','manufacturing'),('CONSTRUCTION','logistics'),('CONSTRUCTION','general'),
 ('ELECTRICAL','manufacturing'),('ELECTRICAL','logistics'),('ELECTRICAL','retail'),('ELECTRICAL','services'),
 ('TEXTILES','retail'),('TEXTILES','manufacturing'),('TEXTILES','healthcare'),('TEXTILES','services'),
 ('ASSETS-EQUIPMENT','logistics'),('ASSETS-EQUIPMENT','manufacturing'),('ASSETS-EQUIPMENT','general'),('ASSETS-EQUIPMENT','healthcare'),
 ('DIGITAL-SERVICES','services'),('DIGITAL-SERVICES','general'),('DIGITAL-SERVICES','retail'),
 ('PROFESSIONAL-SERVICES','services'),('PROFESSIONAL-SERVICES','general'),('PROFESSIONAL-SERVICES','healthcare'),
 ('MAINTENANCE','services'),('MAINTENANCE','general'),('MAINTENANCE','manufacturing'),('MAINTENANCE','logistics')
) AS s(category_code,sector_code) ON s.category_code=i.category_code
WHERE i.owner_organization_id IS NULL AND i.is_active
ON CONFLICT (product_id,sector_code) DO NOTHING;

COMMENT ON TABLE public.commercial_global_catalog_items IS
'Canonical universal product master, extended additively with manufacturing, recipe, safety, tracking and external image metadata.';
COMMENT ON TABLE public.commercial_global_catalog_categories IS
'Canonical unlimited-depth catalog category tree, extended with organization ownership while preserving existing global category codes.';
COMMENT ON TABLE public.commercial_catalog_product_rulings IS
'Source-attributed, jurisdiction-aware religious/medical/regulatory/dietary claims; not universal rulings or clinical advice.';
COMMENT ON FUNCTION public.commercial_catalog_transfer(uuid,uuid,uuid[]) IS
'Copies organization catalog links between accounts only when the caller is owner/admin of both organizations; preserves destination selections.';
