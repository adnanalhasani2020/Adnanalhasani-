-- A distinct business-activity taxonomy. Product categories and units remain in their existing masters.
CREATE TABLE IF NOT EXISTS public.commercial_global_business_activity_nodes (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code text NOT NULL UNIQUE CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{1,79}$'),
  parent_code text REFERENCES public.commercial_global_business_activity_nodes(code) ON UPDATE RESTRICT ON DELETE RESTRICT,
  name_ar text NOT NULL CHECK (length(btrim(name_ar)) BETWEEN 1 AND 200),
  name_en text NOT NULL CHECK (length(btrim(name_en)) BETWEEN 1 AND 200),
  description_ar text,
  description_en text,
  node_kind text NOT NULL CHECK (node_kind IN ('sector','activity','specialization')),
  is_selectable boolean NOT NULL DEFAULT false,
  default_business_level_code text,
  source_system text,
  source_code text,
  source_version text,
  status text NOT NULL DEFAULT 'active' CHECK (status IN ('active','inactive','deprecated')),
  sort_order integer NOT NULL DEFAULT 0,
  version integer NOT NULL DEFAULT 1 CHECK (version > 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK (parent_code IS NULL OR parent_code <> code)
);

CREATE INDEX IF NOT EXISTS ix_business_activity_parent_status
  ON public.commercial_global_business_activity_nodes(parent_code, status, sort_order);
CREATE INDEX IF NOT EXISTS ix_business_activity_name_ar
  ON public.commercial_global_business_activity_nodes(name_ar);
CREATE INDEX IF NOT EXISTS ix_business_activity_name_en
  ON public.commercial_global_business_activity_nodes(name_en);

CREATE TABLE IF NOT EXISTS public.commercial_global_business_activity_aliases (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  activity_node_id uuid NOT NULL REFERENCES public.commercial_global_business_activity_nodes(id) ON DELETE CASCADE,
  locale_code text NOT NULL REFERENCES public.commercial_supported_locales(locale_code),
  alias text NOT NULL CHECK (length(btrim(alias)) BETWEEN 1 AND 200),
  normalized_alias text GENERATED ALWAYS AS (lower(regexp_replace(btrim(alias), '[[:space:]]+', ' ', 'g'))) STORED,
  source text NOT NULL DEFAULT 'curated',
  is_active boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (activity_node_id, locale_code, normalized_alias)
);
CREATE INDEX IF NOT EXISTS ix_business_activity_alias_search
  ON public.commercial_global_business_activity_aliases(normalized_alias) WHERE is_active;

CREATE TABLE IF NOT EXISTS public.commercial_global_business_activity_codes (
  activity_node_id uuid NOT NULL REFERENCES public.commercial_global_business_activity_nodes(id) ON DELETE CASCADE,
  standard_id text NOT NULL CHECK (length(btrim(standard_id)) BETWEEN 1 AND 80),
  external_code text NOT NULL CHECK (length(btrim(external_code)) BETWEEN 1 AND 100),
  external_version text NOT NULL DEFAULT '',
  mapping_type text NOT NULL DEFAULT 'exact' CHECK (mapping_type IN ('exact','broader','narrower','partial','related')),
  valid_from date,
  valid_to date,
  PRIMARY KEY (activity_node_id, standard_id, external_code, external_version),
  CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to >= valid_from)
);
CREATE INDEX IF NOT EXISTS ix_business_activity_external_code
  ON public.commercial_global_business_activity_codes(standard_id, external_code);

-- Reuse the existing normalized global translation directory; do not create a parallel translation master.
ALTER TABLE public.commercial_global_entity_translations
  DROP CONSTRAINT IF EXISTS commercial_global_entity_translations_entity_type_check;
ALTER TABLE public.commercial_global_entity_translations
  ADD CONSTRAINT commercial_global_entity_translations_entity_type_check
  CHECK (entity_type = ANY (ARRAY[
    'catalog_item','catalog_category','unit','chart_account','module','activity_type',
    'business_activity','report_label','planning_template','geographic_location'
  ]::text[]));

-- Existing activity_code is retained for compatibility; the FK gives each activity a canonical node identity.
ALTER TABLE public.commercial_activities
  ADD COLUMN IF NOT EXISTS business_activity_node_id uuid;
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname='commercial_activities_business_activity_node_fkey'
      AND conrelid='public.commercial_activities'::regclass
  ) THEN
    ALTER TABLE public.commercial_activities
      ADD CONSTRAINT commercial_activities_business_activity_node_fkey
      FOREIGN KEY (business_activity_node_id)
      REFERENCES public.commercial_global_business_activity_nodes(id)
      ON UPDATE RESTRICT ON DELETE RESTRICT;
  END IF;
END $$;
CREATE INDEX IF NOT EXISTS ix_commercial_activities_business_activity_node
  ON public.commercial_activities(business_activity_node_id)
  WHERE business_activity_node_id IS NOT NULL;

CREATE OR REPLACE FUNCTION public.commercial_business_activity_cycle_guard()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
  IF NEW.parent_code IS NULL THEN RETURN NEW; END IF;
  IF NEW.parent_code=NEW.code THEN RAISE EXCEPTION 'business_activity_cycle'; END IF;
  IF EXISTS (
    WITH RECURSIVE ancestors(code,parent_code) AS (
      SELECT n.code,n.parent_code FROM public.commercial_global_business_activity_nodes n
      WHERE n.code=NEW.parent_code
      UNION ALL
      SELECT p.code,p.parent_code
      FROM public.commercial_global_business_activity_nodes p
      JOIN ancestors a ON p.code=a.parent_code
    )
    SELECT 1 FROM ancestors WHERE code=NEW.code
  ) THEN RAISE EXCEPTION 'business_activity_cycle'; END IF;
  RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS commercial_business_activity_cycle_guard ON public.commercial_global_business_activity_nodes;
CREATE TRIGGER commercial_business_activity_cycle_guard
BEFORE INSERT OR UPDATE OF parent_code,code
ON public.commercial_global_business_activity_nodes
FOR EACH ROW EXECUTE FUNCTION public.commercial_business_activity_cycle_guard();

CREATE OR REPLACE FUNCTION public.commercial_business_activity_audit()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
  NEW.updated_at := now();
  RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS commercial_business_activity_audit ON public.commercial_global_business_activity_nodes;
CREATE TRIGGER commercial_business_activity_audit
BEFORE UPDATE ON public.commercial_global_business_activity_nodes
FOR EACH ROW EXECUTE FUNCTION public.commercial_business_activity_audit();

CREATE OR REPLACE FUNCTION public.commercial_link_activity_taxonomy()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE v_id uuid; v_code text;
BEGIN
  SELECT n.id,n.code INTO v_id,v_code
  FROM public.commercial_global_business_activity_nodes n
  LEFT JOIN public.commercial_global_business_activity_aliases a
    ON a.activity_node_id=n.id AND a.is_active
  WHERE n.status='active' AND n.is_selectable
    AND (
      (NEW.business_activity_node_id IS NOT NULL AND n.id=NEW.business_activity_node_id)
      OR (NEW.activity_code IS NOT NULL AND n.code=NEW.activity_code)
      OR (NEW.name IS NOT NULL AND (
        lower(btrim(n.name_ar))=lower(btrim(NEW.name))
        OR lower(btrim(n.name_en))=lower(btrim(NEW.name))
        OR a.normalized_alias=lower(regexp_replace(btrim(NEW.name),'[[:space:]]+',' ','g'))
      ))
    )
  ORDER BY CASE
    WHEN NEW.business_activity_node_id IS NOT NULL AND n.id=NEW.business_activity_node_id THEN 0
    WHEN NEW.activity_code IS NOT NULL AND n.code=NEW.activity_code THEN 1
    WHEN lower(btrim(n.name_ar))=lower(btrim(NEW.name)) OR lower(btrim(n.name_en))=lower(btrim(NEW.name)) THEN 2
    ELSE 3 END
  LIMIT 1;
  IF v_id IS NOT NULL THEN
    NEW.business_activity_node_id := v_id;
    NEW.activity_code := v_code;
  END IF;
  RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS commercial_activities_taxonomy_link ON public.commercial_activities;
CREATE TRIGGER commercial_activities_taxonomy_link
BEFORE INSERT OR UPDATE ON public.commercial_activities
FOR EACH ROW EXECUTE FUNCTION public.commercial_link_activity_taxonomy();

-- Public read-only directory. Writes are performed only by trusted migrations/admin tooling.
ALTER TABLE public.commercial_global_business_activity_nodes ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.commercial_global_business_activity_aliases ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.commercial_global_business_activity_codes ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS commercial_business_activity_nodes_read ON public.commercial_global_business_activity_nodes;
CREATE POLICY commercial_business_activity_nodes_read
  ON public.commercial_global_business_activity_nodes FOR SELECT TO anon, authenticated
  USING (status='active');
DROP POLICY IF EXISTS commercial_business_activity_aliases_read ON public.commercial_global_business_activity_aliases;
CREATE POLICY commercial_business_activity_aliases_read
  ON public.commercial_global_business_activity_aliases FOR SELECT TO anon, authenticated
  USING (is_active AND EXISTS (
    SELECT 1 FROM public.commercial_global_business_activity_nodes n
    WHERE n.id=activity_node_id AND n.status='active'
  ));
DROP POLICY IF EXISTS commercial_business_activity_codes_read ON public.commercial_global_business_activity_codes;
CREATE POLICY commercial_business_activity_codes_read
  ON public.commercial_global_business_activity_codes FOR SELECT TO anon, authenticated
  USING (EXISTS (
    SELECT 1 FROM public.commercial_global_business_activity_nodes n
    WHERE n.id=activity_node_id AND n.status='active'
  ));
GRANT SELECT ON public.commercial_global_business_activity_nodes,
  public.commercial_global_business_activity_aliases,
  public.commercial_global_business_activity_codes TO anon, authenticated;

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
    WHERE c.status='active' AND t.depth < 30
  )
  SELECT jsonb_build_object('nodes',COALESCE(jsonb_agg(jsonb_build_object(
    'id',id,'code',code,'parent_code',parent_code,'name_ar',name_ar,'name_en',name_en,
    'description_ar',description_ar,'description_en',description_en,'node_kind',node_kind,
    'is_selectable',is_selectable,'default_business_level_code',default_business_level_code,
    'depth',depth,'path_ar',path_ar,'path_en',path_en
  ) ORDER BY path_ar),'[]'::jsonb))
  FROM tree WHERE is_selectable;
$$;
REVOKE ALL ON FUNCTION public.commercial_list_business_activity_nodes() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.commercial_list_business_activity_nodes() TO anon, authenticated;

-- Seed global sectors and common activity paths. Codes are stable and distinct from product-category codes.
INSERT INTO public.commercial_global_business_activity_nodes
(code,parent_code,name_ar,name_en,description_ar,description_en,node_kind,is_selectable,default_business_level_code,sort_order,source_system,source_code,source_version)
VALUES
('AGRICULTURE',NULL,'الزراعة والإنتاج الحيواني','Agriculture, forestry and fishing','أنشطة الإنتاج الزراعي والحيواني والموارد البحرية.','Agriculture, animal production and related primary activities.','sector',false,NULL,10,'ISIC',NULL,'Rev.4'),
('MINING',NULL,'التعدين واستخراج الموارد','Mining and quarrying','استخراج الموارد الطبيعية.','Extraction of natural resources.','sector',false,NULL,20,'ISIC',NULL,'Rev.4'),
('MANUFACTURING',NULL,'الصناعة والتصنيع','Manufacturing','تحويل المواد والمنتجات إلى سلع.','Manufacturing and processing activities.','sector',false,NULL,30,'ISIC',NULL,'Rev.4'),
('UTILITIES',NULL,'الطاقة والمياه والمرافق','Utilities','أنشطة الطاقة والمياه والمرافق.','Electricity, water and utility activities.','sector',false,NULL,40,'ISIC',NULL,'Rev.4'),
('CONSTRUCTION',NULL,'البناء والتشييد','Construction','أعمال البناء والتركيب والتشييد.','Construction and installation activities.','sector',false,NULL,50,'ISIC',NULL,'Rev.4'),
('TRADE',NULL,'التجارة','Trade','تجارة الجملة والتجزئة وإصلاح المركبات.','Wholesale, retail and vehicle trade.','sector',false,NULL,60,'ISIC',NULL,'Rev.4'),
('TRANSPORT',NULL,'النقل والتخزين','Transportation and storage','النقل والتخزين والخدمات اللوجستية.','Transport, storage and logistics.','sector',false,NULL,70,'ISIC',NULL,'Rev.4'),
('HOSPITALITY',NULL,'الإقامة والطعام والضيافة','Accommodation and food services','الإقامة والمطاعم وخدمات الطعام.','Accommodation and food service activities.','sector',false,NULL,80,'ISIC',NULL,'Rev.4'),
('INFORMATION',NULL,'المعلومات والاتصالات','Information and communication','الاتصالات والبرمجيات والمعلومات.','Information and communication activities.','sector',false,NULL,90,'ISIC',NULL,'Rev.4'),
('FINANCE',NULL,'المال والتأمين','Financial and insurance activities','الخدمات المالية والتأمين.','Financial and insurance activities.','sector',false,NULL,100,'ISIC',NULL,'Rev.4'),
('REAL_ESTATE',NULL,'العقارات','Real estate activities','بيع وتأجير وإدارة العقارات.','Real estate activities.','sector',false,NULL,110,'ISIC',NULL,'Rev.4'),
('PROFESSIONAL',NULL,'الخدمات المهنية والإدارية','Professional and administrative services','الخدمات المهنية والفنية والإدارية.','Professional, scientific and administrative services.','sector',false,NULL,120,'ISIC',NULL,'Rev.4'),
('EDUCATION',NULL,'التعليم والتدريب','Education','التعليم والتدريب.','Education and training.','sector',false,NULL,130,'ISIC',NULL,'Rev.4'),
('HEALTH',NULL,'الصحة والرعاية','Human health and social work','الصحة والرعاية والخدمات الاجتماعية.','Health and social care.','sector',false,NULL,140,'ISIC',NULL,'Rev.4'),
('ARTS',NULL,'الفنون والترفيه','Arts, entertainment and recreation','الفنون والترفيه والرياضة.','Arts, entertainment, sports and recreation.','sector',false,NULL,150,'ISIC',NULL,'Rev.4'),
('PERSONAL_SERVICES',NULL,'الخدمات الشخصية والمنزلية','Other service activities','الخدمات الشخصية والمنزلية والإصلاحات.','Personal, household and repair services.','sector',false,NULL,160,'ISIC',NULL,'Rev.4')
ON CONFLICT (code) DO NOTHING;

INSERT INTO public.commercial_global_business_activity_nodes
(code,parent_code,name_ar,name_en,description_ar,description_en,node_kind,is_selectable,default_business_level_code,sort_order,source_system,source_code,source_version)
VALUES
('WHOLESALE','TRADE','تجارة الجملة','Wholesale trade','بيع السلع بكميات تجارية للموزعين والمنشآت.','Resale of goods in commercial quantities to businesses.','activity',true,'wholesale',10,'ISIC',NULL,'Rev.4'),
('RETAIL','TRADE','تجارة التجزئة','Retail trade','بيع السلع للمستهلكين النهائيين.','Sale of goods to final consumers.','activity',false,NULL,20,'ISIC',NULL,'Rev.4'),
('VEHICLE_TRADE','TRADE','تجارة المركبات وإصلاحها','Motor vehicle trade and repair','بيع المركبات وصيانتها وإصلاحها.','Motor vehicle trade, maintenance and repair.','activity',false,NULL,30,'ISIC',NULL,'Rev.4'),
('FOOD_PRODUCTION','AGRICULTURE','إنتاج الأغذية والزراعة','Food production and farming','إنتاج المحاصيل والأغذية الأولية.','Farming and primary food production.','activity',true,'producer',10,'ISIC',NULL,'Rev.4'),
('MANUFACTURING_GENERAL','MANUFACTURING','أنشطة التصنيع','Manufacturing activities','أنشطة التصنيع العامة والمتخصصة.','General and specialized manufacturing.','activity',true,'manufacturer',10,'ISIC',NULL,'Rev.4'),
('CONSTRUCTION_GENERAL','CONSTRUCTION','مقاولات البناء والتشييد','Building and construction contracting','تنفيذ أعمال البناء والتشييد.','Building and construction works.','activity',true,'contractor',10,'ISIC',NULL,'Rev.4'),
('TRANSPORT_LOGISTICS','TRANSPORT','النقل والخدمات اللوجستية','Transport and logistics','نقل البضائع والتخزين والتوزيع.','Transport, storage and distribution.','activity',true,'service',10,'ISIC',NULL,'Rev.4'),
('RESTAURANT','HOSPITALITY','المطاعم وخدمات الطعام','Restaurants and food service','تحضير وتقديم الطعام والشراب.','Preparation and service of food and beverages.','activity',true,'restaurant',10,'ISIC',NULL,'Rev.4'),
('HOTEL','HOSPITALITY','الفنادق والإقامة','Hotels and accommodation','الإقامة الفندقية وخدمات الضيافة.','Hotels and accommodation services.','activity',true,'hotel',20,'ISIC',NULL,'Rev.4'),
('SOFTWARE_SERVICES','INFORMATION','البرمجيات وخدمات التقنية','Software and technology services','تطوير البرمجيات والخدمات التقنية.','Software development and technology services.','activity',true,'service',10,'ISIC',NULL,'Rev.4'),
('HEALTHCARE_SERVICES','HEALTH','الخدمات الصحية','Healthcare services','خدمات الرعاية الصحية وفق التراخيص المحلية.','Healthcare services subject to local licensing.','activity',true,'clinic',10,'ISIC',NULL,'Rev.4'),
('EDUCATION_SERVICES','EDUCATION','التعليم والتدريب','Education and training services','خدمات التعليم والتدريب.','Education and training services.','activity',true,'service',10,'ISIC',NULL,'Rev.4'),
('PERSONAL_REPAIR','PERSONAL_SERVICES','الإصلاح والخدمات الشخصية','Repair and personal services','خدمات إصلاح وخدمات شخصية.','Repair and personal services.','activity',true,'service',10,'ISIC',NULL,'Rev.4'),
('RETAIL_NON_SPECIALIZED','RETAIL','تجارة التجزئة غير المتخصصة','Non-specialized retail stores','متاجر تبيع تشكيلة واسعة من المنتجات.','Stores selling a broad assortment of goods.','specialization',false,NULL,10,'ISIC',NULL,'Rev.4'),
('RETAIL_SPECIALIZED','RETAIL','تجارة التجزئة المتخصصة','Specialized retail stores','متاجر تركز على مجموعة منتجات محددة.','Stores specializing in selected product groups.','specialization',false,NULL,20,'ISIC',NULL,'Rev.4')
ON CONFLICT (code) DO NOTHING;

INSERT INTO public.commercial_global_business_activity_nodes
(code,parent_code,name_ar,name_en,description_ar,description_en,node_kind,is_selectable,default_business_level_code,sort_order,source_system,source_code,source_version)
VALUES
('GROCERY','RETAIL_NON_SPECIALIZED','بقالة وتموينات','Grocery and convenience store','متجر تجزئة صغير أو محلي لبيع الأغذية والسلع اليومية.','Small/local retail store selling food and everyday essentials.','specialization',true,'grocery',10,'ISIC',NULL,'Rev.4'),
('SUPERMARKET','RETAIL_NON_SPECIALIZED','سوبر ماركت','Supermarket','متجر تجزئة واسع التشكيلة يركز على الأغذية والسلع المنزلية اليومية.','Broad-assortment retail store focused on food and household essentials.','specialization',true,'supermarket',20,'ISIC',NULL,'Rev.4'),
('HYPERMARKET','RETAIL_NON_SPECIALIZED','هايبر ماركت','Hypermarket','متجر تجزئة كبير واسع التشكيلة.','Large-format broad-assortment retail store.','specialization',true,'supermarket',30,'ISIC',NULL,'Rev.4'),
('CONVENIENCE_STORE','RETAIL_NON_SPECIALIZED','متجر صغير للمواد اليومية','Convenience store','متجر صغير يركز على المنتجات اليومية السريعة.','Small store focused on everyday convenience goods.','specialization',true,'shop',40,'ISIC',NULL,'Rev.4'),
('FOOD_RETAIL','RETAIL_SPECIALIZED','تجارة التجزئة للمواد الغذائية','Specialized food retail','بيع مجموعة غذائية محددة أو متخصصة.','Retail of a specialized food product group.','specialization',false,NULL,10,'ISIC',NULL,'Rev.4'),
('ELECTRONICS_RETAIL','RETAIL_SPECIALIZED','تجارة الإلكترونيات','Electronics retail','بيع أجهزة ومعدات الإلكترونيات بالتجزئة.','Retail sale of electronics and devices.','specialization',true,'shop',20,'ISIC',NULL,'Rev.4'),
('CLOTHING_RETAIL','RETAIL_SPECIALIZED','تجارة الملابس والأحذية','Clothing and footwear retail','بيع الملابس والأحذية بالتجزئة.','Retail sale of clothing and footwear.','specialization',true,'shop',30,'ISIC',NULL,'Rev.4'),
('PHARMACY_RETAIL','RETAIL_SPECIALIZED','الصيدليات','Pharmacy retail','بيع الأدوية والمنتجات الصيدلانية ضمن الترخيص المحلي.','Pharmacy retail subject to local licensing.','specialization',true,'pharmacy',40,'ISIC',NULL,'Rev.4'),
('BUILDING_MATERIALS_RETAIL','RETAIL_SPECIALIZED','تجارة مواد البناء','Building materials retail','بيع مواد ومستلزمات البناء.','Retail sale of building materials.','specialization',true,'shop',50,'ISIC',NULL,'Rev.4'),
('AUTO_PARTS_RETAIL','RETAIL_SPECIALIZED','تجارة قطع غيار المركبات','Motor vehicle parts retail','بيع قطع غيار المركبات.','Retail sale of motor vehicle parts.','specialization',true,'shop',60,'ISIC',NULL,'Rev.4'),
('FURNITURE_RETAIL','RETAIL_SPECIALIZED','تجارة الأثاث','Furniture retail','بيع الأثاث بالتجزئة.','Retail sale of furniture.','specialization',true,'shop',70,'ISIC',NULL,'Rev.4'),
('COSMETICS_RETAIL','RETAIL_SPECIALIZED','تجارة مستحضرات التجميل','Cosmetics retail','بيع مستحضرات التجميل والعناية الشخصية.','Retail sale of cosmetics and personal care products.','specialization',true,'shop',80,'ISIC',NULL,'Rev.4')
ON CONFLICT (code) DO NOTHING;

-- Canonical names and localized aliases use the shared translation store and dedicated synonym rows.
INSERT INTO public.commercial_global_entity_translations
(entity_type,entity_key,locale_code,field_key,translated_value,translation_status,source_language)
SELECT 'business_activity',code,'ar','name',name_ar,'official','ar'
FROM public.commercial_global_business_activity_nodes
ON CONFLICT (entity_type,entity_key,locale_code,field_key) DO NOTHING;
INSERT INTO public.commercial_global_entity_translations
(entity_type,entity_key,locale_code,field_key,translated_value,translation_status,source_language)
SELECT 'business_activity',code,'en','name',name_en,'official','en'
FROM public.commercial_global_business_activity_nodes
ON CONFLICT (entity_type,entity_key,locale_code,field_key) DO NOTHING;

INSERT INTO public.commercial_global_business_activity_aliases(activity_node_id,locale_code,alias,source)
SELECT n.id,v.locale_code,v.alias,'curated'
FROM (VALUES
 ('GROCERY','ar','بقالة'),('GROCERY','ar','بقاله'),('GROCERY','ar','تموينات'),('GROCERY','ar','محل تموينات'),
 ('GROCERY','en','grocery'),('GROCERY','en','grocery store'),('GROCERY','en','corner shop'),
 ('SUPERMARKET','ar','سوبرماركت'),('SUPERMARKET','ar','سوبر ماركت'),('SUPERMARKET','en','supermarket'),
 ('HYPERMARKET','ar','هايبر'),('HYPERMARKET','ar','هايبر ماركت'),('HYPERMARKET','en','hypermarket'),
 ('CONVENIENCE_STORE','en','convenience store'),('CONVENIENCE_STORE','ar','متجر مواد يومية'),
 ('WHOLESALE','ar','جملة'),('WHOLESALE','en','wholesale'),
 ('RETAIL','ar','تجزئة'),('RETAIL','en','retail'),
 ('RESTAURANT','ar','مطعم'),('RESTAURANT','en','restaurant'),
 ('HOTEL','ar','فندق'),('HOTEL','en','hotel'),
 ('PHARMACY_RETAIL','ar','صيدلية'),('PHARMACY_RETAIL','en','pharmacy')
) AS v(code,locale_code,alias)
JOIN public.commercial_global_business_activity_nodes n ON n.code=v.code
ON CONFLICT (activity_node_id,locale_code,normalized_alias) DO NOTHING;

-- Resolve existing free-text activity names to canonical taxonomy nodes when a clear match exists.
UPDATE public.commercial_activities a
SET business_activity_node_id=n.id, activity_code=n.code
FROM public.commercial_global_business_activity_nodes n
WHERE a.business_activity_node_id IS NULL
  AND n.status='active' AND n.is_selectable
  AND (lower(btrim(a.name))=lower(btrim(n.name_ar)) OR lower(btrim(a.name))=lower(btrim(n.name_en)));
