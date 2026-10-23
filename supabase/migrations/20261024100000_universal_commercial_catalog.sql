-- Universal, multilingual commercial catalog foundation.
-- Product images are external HTTPS URLs only; no Supabase Storage uploads.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS public.commercial_catalog_categories (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_organization_id uuid REFERENCES public.commercial_organizations(id) ON DELETE CASCADE,
  parent_id uuid REFERENCES public.commercial_catalog_categories(id) ON DELETE RESTRICT,
  code text NOT NULL,
  slug text NOT NULL,
  default_name text NOT NULL CHECK (length(btrim(default_name)) BETWEEN 1 AND 160),
  description text,
  sort_order integer NOT NULL DEFAULT 0,
  is_active boolean NOT NULL DEFAULT true,
  created_by uuid REFERENCES auth.users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (owner_organization_id, code),
  CHECK (parent_id IS NULL OR parent_id <> id)
);
CREATE INDEX IF NOT EXISTS ix_commercial_catalog_categories_parent ON public.commercial_catalog_categories(parent_id);
CREATE INDEX IF NOT EXISTS ix_commercial_catalog_categories_owner ON public.commercial_catalog_categories(owner_organization_id);

CREATE TABLE IF NOT EXISTS public.commercial_catalog_category_translations (
  category_id uuid NOT NULL REFERENCES public.commercial_catalog_categories(id) ON DELETE CASCADE,
  locale_code text NOT NULL,
  name text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 160),
  description text,
  PRIMARY KEY (category_id, locale_code)
);

CREATE TABLE IF NOT EXISTS public.commercial_catalog_products (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_organization_id uuid REFERENCES public.commercial_organizations(id) ON DELETE CASCADE,
  category_id uuid REFERENCES public.commercial_catalog_categories(id) ON DELETE SET NULL,
  product_code text,
  default_name text NOT NULL CHECK (length(btrim(default_name)) BETWEEN 1 AND 240),
  short_description text,
  full_description text,
  brand text,
  manufacturer_name text,
  country_of_manufacture text,
  model_number text,
  part_number text,
  material_composition jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(material_composition) = 'array'),
  ingredients jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(ingredients) = 'array'),
  recipe jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(recipe) = 'object'),
  specifications jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(specifications) = 'object'),
  storage_instructions text,
  usage_instructions text,
  warnings text,
  shelf_life_days integer CHECK (shelf_life_days IS NULL OR shelf_life_days >= 0),
  requires_expiry_date boolean NOT NULL DEFAULT false,
  is_batch_tracked boolean NOT NULL DEFAULT false,
  is_serial_tracked boolean NOT NULL DEFAULT false,
  is_prescription_only boolean NOT NULL DEFAULT false,
  is_active boolean NOT NULL DEFAULT true,
  image_url text CHECK (image_url IS NULL OR (length(image_url) <= 1000 AND image_url ~* '^https://[^[:space:]]+$')),
  image_alt text,
  metadata jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(metadata) = 'object'),
  created_by uuid REFERENCES auth.users(id),
  updated_by uuid REFERENCES auth.users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK (owner_organization_id IS NOT NULL OR created_by IS NULL),
  CHECK (product_code IS NULL OR length(product_code) <= 100)
);
CREATE INDEX IF NOT EXISTS ix_commercial_catalog_products_category ON public.commercial_catalog_products(category_id);
CREATE INDEX IF NOT EXISTS ix_commercial_catalog_products_owner ON public.commercial_catalog_products(owner_organization_id);
CREATE INDEX IF NOT EXISTS ix_commercial_catalog_products_name ON public.commercial_catalog_products USING gin (to_tsvector('simple', default_name));

CREATE TABLE IF NOT EXISTS public.commercial_catalog_product_translations (
  product_id uuid NOT NULL REFERENCES public.commercial_catalog_products(id) ON DELETE CASCADE,
  locale_code text NOT NULL,
  name text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 240),
  short_description text,
  full_description text,
  usage_instructions text,
  warnings text,
  image_alt text,
  PRIMARY KEY (product_id, locale_code)
);

-- Each package/sale/purchase unit can have its own barcode and image URL.
CREATE TABLE IF NOT EXISTS public.commercial_catalog_product_units (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  product_id uuid NOT NULL REFERENCES public.commercial_catalog_products(id) ON DELETE CASCADE,
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
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (product_id, unit_id, unit_role),
  CHECK (barcode IS NULL OR length(btrim(barcode)) BETWEEN 3 AND 128),
  CHECK (sku IS NULL OR length(btrim(sku)) <= 100)
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_commercial_catalog_barcode ON public.commercial_catalog_product_units(barcode) WHERE barcode IS NOT NULL AND is_active;
CREATE INDEX IF NOT EXISTS ix_commercial_catalog_product_units_product ON public.commercial_catalog_product_units(product_id);

-- Sector/region targeting is data-driven; no hard-coded activity list or level limit.
CREATE TABLE IF NOT EXISTS public.commercial_catalog_product_sectors (
  product_id uuid NOT NULL REFERENCES public.commercial_catalog_products(id) ON DELETE CASCADE,
  sector_code text NOT NULL CHECK (sector_code IN ('all','hospital','pharmacy','grocery','supermarket','restaurant','hotel','general_company','mobile_company','automotive','heavy_equipment','manufacturing','wholesale','retail','clinic','laboratory','construction','agriculture','education','office','beauty','electronics','other')),
  is_excluded boolean NOT NULL DEFAULT false,
  PRIMARY KEY (product_id, sector_code)
);
CREATE TABLE IF NOT EXISTS public.commercial_catalog_product_regions (
  product_id uuid NOT NULL REFERENCES public.commercial_catalog_products(id) ON DELETE CASCADE,
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
  product_id uuid NOT NULL REFERENCES public.commercial_catalog_products(id) ON DELETE CASCADE,
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
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS public.commercial_catalog_recipe_ingredients (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  recipe_id uuid NOT NULL REFERENCES public.commercial_catalog_product_recipes(id) ON DELETE CASCADE,
  ingredient_product_id uuid REFERENCES public.commercial_catalog_products(id) ON DELETE RESTRICT,
  ingredient_name text,
  quantity numeric(30,12) NOT NULL CHECK (quantity > 0),
  unit_code text NOT NULL REFERENCES public.commercial_global_units(code) ON DELETE RESTRICT,
  preparation_note text,
  CHECK (ingredient_product_id IS NOT NULL OR nullif(btrim(ingredient_name),'') IS NOT NULL)
);

-- Claims are sourced, dated, and attributable; they are not presented as universal religious/medical rulings.
CREATE TABLE IF NOT EXISTS public.commercial_catalog_product_rulings (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  product_id uuid NOT NULL REFERENCES public.commercial_catalog_products(id) ON DELETE CASCADE,
  ruling_domain text NOT NULL CHECK (ruling_domain IN ('religious','medical','regulatory','dietary')),
  authority_code text NOT NULL,
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
  CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to >= effective_from)
);
CREATE INDEX IF NOT EXISTS ix_commercial_catalog_rulings_product ON public.commercial_catalog_product_rulings(product_id, ruling_domain);

-- A business has its own catalog choices, pricing-independent overrides and availability scope.
CREATE TABLE IF NOT EXISTS public.commercial_organization_catalog_items (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE CASCADE,
  product_id uuid NOT NULL REFERENCES public.commercial_catalog_products(id) ON DELETE RESTRICT,
  local_code text,
  local_name text,
  local_description text,
  is_enabled boolean NOT NULL DEFAULT true,
  is_favorite boolean NOT NULL DEFAULT false,
  source_organization_id uuid REFERENCES public.commercial_organizations(id) ON DELETE SET NULL,
  imported_at timestamptz,
  created_by uuid REFERENCES auth.users(id),
  updated_by uuid REFERENCES auth.users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (organization_id, product_id)
);
CREATE INDEX IF NOT EXISTS ix_commercial_org_catalog_items_org ON public.commercial_organization_catalog_items(organization_id, is_enabled);

CREATE TABLE IF NOT EXISTS public.commercial_catalog_transfers (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  source_organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE CASCADE,
  destination_organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE CASCADE,
  transferred_by uuid NOT NULL REFERENCES auth.users(id),
  item_count integer NOT NULL DEFAULT 0,
  transfer_mode text NOT NULL DEFAULT 'link' CHECK (transfer_mode = 'link'),
  status text NOT NULL DEFAULT 'completed' CHECK (status IN ('completed','failed')),
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (source_organization_id <> destination_organization_id)
);

-- Hierarchy is unbounded. Reject cycles even when a category is moved after creation.
CREATE OR REPLACE FUNCTION public.commercial_catalog_category_cycle_guard()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, public AS $$
BEGIN
  IF NEW.parent_id IS NULL THEN RETURN NEW; END IF;
  IF NEW.parent_id = NEW.id THEN RAISE EXCEPTION 'catalog_category_cycle'; END IF;
  IF EXISTS (
    WITH RECURSIVE ancestors(id, parent_id) AS (
      SELECT c.id, c.parent_id FROM public.commercial_catalog_categories c WHERE c.id = NEW.parent_id
      UNION ALL
      SELECT p.id, p.parent_id FROM public.commercial_catalog_categories p
      JOIN ancestors a ON p.id = a.parent_id
    ) SELECT 1 FROM ancestors WHERE id = NEW.id
  ) THEN RAISE EXCEPTION 'catalog_category_cycle'; END IF;
  IF (SELECT owner_organization_id FROM public.commercial_catalog_categories WHERE id = NEW.parent_id)
     IS DISTINCT FROM NEW.owner_organization_id THEN
    RAISE EXCEPTION 'catalog_category_owner_mismatch';
  END IF;
  RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS commercial_catalog_category_cycle_guard ON public.commercial_catalog_categories;
CREATE TRIGGER commercial_catalog_category_cycle_guard
BEFORE INSERT OR UPDATE OF parent_id, owner_organization_id ON public.commercial_catalog_categories
FOR EACH ROW EXECUTE FUNCTION public.commercial_catalog_category_cycle_guard();

CREATE OR REPLACE FUNCTION public.commercial_catalog_member_of(p_org_id uuid, p_roles text[] DEFAULT ARRAY['owner','admin']::text[])
RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public AS $$
  SELECT EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id = p_org_id AND m.user_id = auth.uid()
      AND m.state = 'active' AND m.role = ANY(p_roles)
  );
$$;
REVOKE ALL ON FUNCTION public.commercial_catalog_member_of(uuid,text[]) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_catalog_member_of(uuid,text[]) TO authenticated;

CREATE OR REPLACE FUNCTION public.commercial_catalog_transfer(
  p_source_organization_id uuid,
  p_destination_organization_id uuid,
  p_product_ids uuid[] DEFAULT NULL,
  p_transfer_mode text DEFAULT 'link'
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE v_transfer_id uuid; v_count integer;
BEGIN
  IF auth.uid() IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_source_organization_id = p_destination_organization_id THEN RAISE EXCEPTION 'same_organization'; END IF;
  IF p_transfer_mode <> 'link' THEN RAISE EXCEPTION 'invalid_transfer_mode'; END IF;
  IF NOT public.commercial_catalog_member_of(p_source_organization_id, ARRAY['owner','admin'])
     OR NOT public.commercial_catalog_member_of(p_destination_organization_id, ARRAY['owner','admin']) THEN
    RAISE EXCEPTION 'catalog_transfer_forbidden' USING ERRCODE='42501';
  END IF;
  INSERT INTO public.commercial_catalog_transfers(source_organization_id,destination_organization_id,transferred_by,transfer_mode)
  VALUES(p_source_organization_id,p_destination_organization_id,auth.uid(),p_transfer_mode)
  RETURNING id INTO v_transfer_id;

  INSERT INTO public.commercial_organization_catalog_items(
    organization_id,product_id,local_code,local_name,local_description,is_enabled,is_favorite,
    source_organization_id,imported_at,created_by,updated_by
  )
  SELECT p_destination_organization_id, i.product_id, i.local_code, i.local_name, i.local_description,
         i.is_enabled, i.is_favorite, p_source_organization_id, now(), auth.uid(), auth.uid()
  FROM public.commercial_organization_catalog_items i
  WHERE i.organization_id=p_source_organization_id
    AND (p_product_ids IS NULL OR i.product_id=ANY(p_product_ids))
  ON CONFLICT (organization_id,product_id) DO NOTHING;
  GET DIAGNOSTICS v_count = ROW_COUNT;
  UPDATE public.commercial_catalog_transfers SET item_count=v_count WHERE id=v_transfer_id;
  RETURN jsonb_build_object('transfer_id',v_transfer_id,'items_transferred',v_count,'mode',p_transfer_mode);
END $$;
REVOKE ALL ON FUNCTION public.commercial_catalog_transfer(uuid,uuid,uuid[],text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_catalog_transfer(uuid,uuid,uuid[],text) TO authenticated;

-- Enable RLS on every new table. Global reference rows are readable; org-owned rows are member-scoped.
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY[
        'commercial_catalog_categories','commercial_catalog_category_translations',
    'commercial_catalog_products','commercial_catalog_product_translations',
    'commercial_catalog_product_units','commercial_catalog_product_sectors',
    'commercial_catalog_product_regions','commercial_catalog_product_recipes',
    'commercial_catalog_recipe_ingredients','commercial_catalog_product_rulings',
    'commercial_organization_catalog_items','commercial_catalog_transfers'
  ] LOOP
    EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY',t);
    EXECUTE format('REVOKE ALL ON public.%I FROM anon',t);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON public.%I TO authenticated',t);
  END LOOP;
END $$;

CREATE POLICY catalog_categories_read ON public.commercial_catalog_categories FOR SELECT TO authenticated
USING (owner_organization_id IS NULL OR public.commercial_catalog_member_of(owner_organization_id, ARRAY['owner','admin','manager','staff']::text[]));
CREATE POLICY catalog_categories_write ON public.commercial_catalog_categories FOR ALL TO authenticated
USING (owner_organization_id IS NOT NULL AND public.commercial_catalog_member_of(owner_organization_id, ARRAY['owner','admin']::text[]))
WITH CHECK (owner_organization_id IS NOT NULL AND public.commercial_catalog_member_of(owner_organization_id, ARRAY['owner','admin']::text[]));
CREATE POLICY catalog_category_translations_read ON public.commercial_catalog_category_translations FOR SELECT TO authenticated
USING (EXISTS (SELECT 1 FROM public.commercial_catalog_categories c WHERE c.id=category_id AND (c.owner_organization_id IS NULL OR public.commercial_catalog_member_of(c.owner_organization_id, ARRAY['owner','admin','manager','staff']::text[]))));
CREATE POLICY catalog_category_translations_write ON public.commercial_catalog_category_translations FOR ALL TO authenticated
USING (EXISTS (SELECT 1 FROM public.commercial_catalog_categories c WHERE c.id=category_id AND c.owner_organization_id IS NOT NULL AND public.commercial_catalog_member_of(c.owner_organization_id, ARRAY['owner','admin']::text[])))
WITH CHECK (EXISTS (SELECT 1 FROM public.commercial_catalog_categories c WHERE c.id=category_id AND c.owner_organization_id IS NOT NULL AND public.commercial_catalog_member_of(c.owner_organization_id, ARRAY['owner','admin']::text[])));

CREATE POLICY catalog_products_read ON public.commercial_catalog_products FOR SELECT TO authenticated
USING (owner_organization_id IS NULL OR public.commercial_catalog_member_of(owner_organization_id, ARRAY['owner','admin','manager','staff']::text[]) OR EXISTS (SELECT 1 FROM public.commercial_organization_catalog_items oi WHERE oi.product_id=id AND public.commercial_catalog_member_of(oi.organization_id, ARRAY['owner','admin','manager','staff']::text[])));
CREATE POLICY catalog_products_write ON public.commercial_catalog_products FOR ALL TO authenticated
USING (owner_organization_id IS NOT NULL AND public.commercial_catalog_member_of(owner_organization_id, ARRAY['owner','admin']::text[]))
WITH CHECK (owner_organization_id IS NOT NULL AND public.commercial_catalog_member_of(owner_organization_id, ARRAY['owner','admin']::text[]));

CREATE POLICY catalog_product_translations_read ON public.commercial_catalog_product_translations FOR SELECT TO authenticated
USING (EXISTS (SELECT 1 FROM public.commercial_catalog_products p WHERE p.id=product_id AND (p.owner_organization_id IS NULL OR public.commercial_catalog_member_of(p.owner_organization_id, ARRAY['owner','admin','manager','staff']::text[]) OR EXISTS (SELECT 1 FROM public.commercial_organization_catalog_items oi WHERE oi.product_id=p.id AND public.commercial_catalog_member_of(oi.organization_id, ARRAY['owner','admin','manager','staff']::text[])))));
CREATE POLICY catalog_product_translations_write ON public.commercial_catalog_product_translations FOR ALL TO authenticated
USING (EXISTS (SELECT 1 FROM public.commercial_catalog_products p WHERE p.id=product_id AND p.owner_organization_id IS NOT NULL AND public.commercial_catalog_member_of(p.owner_organization_id, ARRAY['owner','admin']::text[])))
WITH CHECK (EXISTS (SELECT 1 FROM public.commercial_catalog_products p WHERE p.id=product_id AND p.owner_organization_id IS NOT NULL AND public.commercial_catalog_member_of(p.owner_organization_id, ARRAY['owner','admin']::text[])));

-- Product child records inherit product-level visibility and write authorization.
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['commercial_catalog_product_units','commercial_catalog_product_sectors','commercial_catalog_product_regions','commercial_catalog_product_recipes','commercial_catalog_product_rulings'] LOOP
    EXECUTE format('CREATE POLICY %I ON public.%I FOR SELECT TO authenticated USING (EXISTS (SELECT 1 FROM public.commercial_catalog_products p WHERE p.id=product_id AND (p.owner_organization_id IS NULL OR public.commercial_catalog_member_of(p.owner_organization_id, ARRAY[''owner'',''admin'',''manager'',''staff'']::text[]) OR EXISTS (SELECT 1 FROM public.commercial_organization_catalog_items oi WHERE oi.product_id=p.id AND public.commercial_catalog_member_of(oi.organization_id, ARRAY[''owner'',''admin'',''manager'',''staff'']::text[])))))',t||'_read',t);
    EXECUTE format('CREATE POLICY %I ON public.%I FOR ALL TO authenticated USING (EXISTS (SELECT 1 FROM public.commercial_catalog_products p WHERE p.id=product_id AND p.owner_organization_id IS NOT NULL AND public.commercial_catalog_member_of(p.owner_organization_id, ARRAY[''owner'',''admin'']::text[]))) WITH CHECK (EXISTS (SELECT 1 FROM public.commercial_catalog_products p WHERE p.id=product_id AND p.owner_organization_id IS NOT NULL AND public.commercial_catalog_member_of(p.owner_organization_id, ARRAY[''owner'',''admin'']::text[])))',t||'_write',t);
  END LOOP;
END $$;
CREATE POLICY catalog_recipe_ingredients_read ON public.commercial_catalog_recipe_ingredients FOR SELECT TO authenticated
USING (EXISTS (SELECT 1 FROM public.commercial_catalog_product_recipes r JOIN public.commercial_catalog_products p ON p.id=r.product_id WHERE r.id=recipe_id AND (p.owner_organization_id IS NULL OR public.commercial_catalog_member_of(p.owner_organization_id, ARRAY['owner','admin','manager','staff']::text[]) OR EXISTS (SELECT 1 FROM public.commercial_organization_catalog_items oi WHERE oi.product_id=p.id AND public.commercial_catalog_member_of(oi.organization_id, ARRAY['owner','admin','manager','staff']::text[])))));
CREATE POLICY catalog_recipe_ingredients_write ON public.commercial_catalog_recipe_ingredients FOR ALL TO authenticated
USING (EXISTS (SELECT 1 FROM public.commercial_catalog_product_recipes r JOIN public.commercial_catalog_products p ON p.id=r.product_id WHERE r.id=recipe_id AND p.owner_organization_id IS NOT NULL AND public.commercial_catalog_member_of(p.owner_organization_id, ARRAY['owner','admin']::text[])))
WITH CHECK (EXISTS (SELECT 1 FROM public.commercial_catalog_product_recipes r JOIN public.commercial_catalog_products p ON p.id=r.product_id WHERE r.id=recipe_id AND p.owner_organization_id IS NOT NULL AND public.commercial_catalog_member_of(p.owner_organization_id, ARRAY['owner','admin']::text[])));

CREATE POLICY catalog_org_items_read ON public.commercial_organization_catalog_items FOR SELECT TO authenticated
USING (public.commercial_catalog_member_of(organization_id, ARRAY['owner','admin','manager','staff']::text[]));
CREATE POLICY catalog_org_items_write ON public.commercial_organization_catalog_items FOR ALL TO authenticated
USING (public.commercial_catalog_member_of(organization_id, ARRAY['owner','admin']::text[]))
WITH CHECK (public.commercial_catalog_member_of(organization_id, ARRAY['owner','admin']::text[]));
CREATE POLICY catalog_transfers_read ON public.commercial_catalog_transfers FOR SELECT TO authenticated
USING (public.commercial_catalog_member_of(source_organization_id, ARRAY['owner','admin']::text[]) OR public.commercial_catalog_member_of(destination_organization_id, ARRAY['owner','admin']::text[]));

-- Seed broad, cross-industry units in the existing canonical global unit directory.
-- Existing unit tables are reused; this migration intentionally does not create a duplicate unit catalog.
INSERT INTO public.commercial_global_units
  (code, name_ar, name_en, dimension, factor_to_base, base_unit_code, decimal_places, is_active, version)
VALUES
 ('EA','قطعة','Each','count',1,'EA',0,true,1),
 ('DOZ','دزينة','Dozen','count',12,'EA',0,true,1),
 ('PAIR','زوج','Pair','count',2,'EA',0,true,1),
 ('SET','طقم','Set','count',1,'EA',0,true,1),
 ('MG','مليغرام','Milligram','mass',0.000001,'KG',3,true,1),
 ('G','غرام','Gram','mass',0.001,'KG',3,true,1),
 ('KG','كيلوغرام','Kilogram','mass',1,'KG',3,true,1),
 ('TONNE','طن متري','Metric tonne','mass',1000,'KG',3,true,1),
 ('ML','مليلتر','Millilitre','volume',0.001,'L',3,true,1),
 ('L','لتر','Litre','volume',1,'L',3,true,1),
 ('M3','متر مكعب','Cubic metre','volume',1000,'L',3,true,1),
 ('MM','مليمتر','Millimetre','length',0.001,'M',3,true,1),
 ('CM','سنتيمتر','Centimetre','length',0.01,'M',3,true,1),
 ('M','متر','Metre','length',1,'M',3,true,1),
 ('KM','كيلومتر','Kilometre','length',1000,'M',3,true,1),
 ('CM2','سنتيمتر مربع','Square centimetre','area',0.0001,'M2',3,true,1),
 ('M2','متر مربع','Square metre','area',1,'M2',3,true,1),
 ('HA','هكتار','Hectare','area',10000,'M2',3,true,1),
 ('SEC','ثانية','Second','time',1,'SEC',0,true,1),
 ('MIN','دقيقة','Minute','time',60,'SEC',2,true,1),
 ('HOUR','ساعة','Hour','time',3600,'SEC',2,true,1),
 ('DAY','يوم','Day','time',86400,'SEC',2,true,1),
 ('PACK','عبوة','Pack','package',1,'PACK',0,true,1),
 ('BOX','صندوق','Box','package',1,'BOX',0,true,1),
 ('CARTON','كرتون','Carton','package',1,'CARTON',0,true,1),
 ('PALLET','منصة تحميل','Pallet','package',1,'PALLET',0,true,1),
 ('KWH','كيلوواط ساعة','Kilowatt-hour','energy',1,'KWH',3,true,1),
 ('GB','غيغابايت','Gigabyte','data',1,'GB',2,true,1)
ON CONFLICT (code) DO NOTHING;

COMMENT ON TABLE public.commercial_catalog_categories IS 'Unlimited-depth shared or organization-owned product category tree; localized labels are stored separately.';
COMMENT ON TABLE public.commercial_catalog_products IS 'Universal product master with multilingual descriptions, ingredients, specifications, expiry/lot controls and external image URLs.';
COMMENT ON TABLE public.commercial_catalog_product_units IS 'Product packaging and selling/purchasing units; barcode and image may vary by package.';
COMMENT ON TABLE public.commercial_catalog_product_rulings IS 'Attributed, time-scoped religious/medical/regulatory claims; confidence and sources are mandatory context, not universal rulings.';
COMMENT ON FUNCTION public.commercial_catalog_transfer(uuid,uuid,uuid[],text) IS 'Copies catalog links between organizations only when the caller is owner/admin of both organizations.';
