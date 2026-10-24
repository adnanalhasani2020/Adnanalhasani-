-- Phase 1: additive master data for warehouses, storage locations, categories, and departments.
-- Does not move existing balances or change current checkout paths.

CREATE TABLE IF NOT EXISTS public.commercial_warehouses (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
  activity_id uuid REFERENCES public.commercial_activities(id) ON DELETE RESTRICT,
  branch_location_id uuid NOT NULL REFERENCES public.commercial_organization_locations(id) ON DELETE RESTRICT,
  code text NOT NULL CHECK (length(btrim(code)) BETWEEN 1 AND 64),
  name text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 160),
  warehouse_type text NOT NULL DEFAULT 'main'
    CHECK (warehouse_type IN ('main','retail','distribution','production','returns','quarantine','virtual','transit')),
  state text NOT NULL DEFAULT 'active' CHECK (state IN ('active','inactive','archived')),
  metadata jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(metadata) = 'object'),
  created_by uuid REFERENCES auth.users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (organization_id, code)
  -- activity_id is optional; its organization scope is enforced by the trigger.
);
CREATE INDEX IF NOT EXISTS ix_commercial_warehouses_branch
  ON public.commercial_warehouses(organization_id, branch_location_id, state);

CREATE TABLE IF NOT EXISTS public.commercial_storage_locations (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
  warehouse_id uuid NOT NULL REFERENCES public.commercial_warehouses(id) ON DELETE RESTRICT,
  parent_location_id uuid REFERENCES public.commercial_storage_locations(id) ON DELETE RESTRICT,
  code text NOT NULL CHECK (length(btrim(code)) BETWEEN 1 AND 64),
  name text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 160),
  location_type text NOT NULL DEFAULT 'internal'
    CHECK (location_type IN ('view','internal','receiving','packing','quality','quarantine','transit','customer','supplier','scrap')),
  state text NOT NULL DEFAULT 'active' CHECK (state IN ('active','inactive','archived')),
  allow_negative_stock boolean NOT NULL DEFAULT false,
  picking_sequence integer NOT NULL DEFAULT 10 CHECK (picking_sequence >= 0),
  metadata jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(metadata) = 'object'),
  created_by uuid REFERENCES auth.users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (warehouse_id, code),
  CHECK (parent_location_id IS NULL OR parent_location_id <> id)
);
CREATE INDEX IF NOT EXISTS ix_commercial_storage_locations_parent
  ON public.commercial_storage_locations(warehouse_id, parent_location_id, state);

CREATE TABLE IF NOT EXISTS public.commercial_product_categories (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
  parent_id uuid REFERENCES public.commercial_product_categories(id) ON DELETE RESTRICT,
  code text NOT NULL CHECK (length(btrim(code)) BETWEEN 1 AND 64),
  name_ar text NOT NULL CHECK (length(btrim(name_ar)) BETWEEN 1 AND 160),
  name_en text CHECK (name_en IS NULL OR length(btrim(name_en)) BETWEEN 1 AND 160),
  description text,
  state text NOT NULL DEFAULT 'active' CHECK (state IN ('active','inactive','archived')),
  sort_order integer NOT NULL DEFAULT 0,
  created_by uuid REFERENCES auth.users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (organization_id, code),
  CHECK (parent_id IS NULL OR parent_id <> id)
);
CREATE INDEX IF NOT EXISTS ix_commercial_product_categories_parent
  ON public.commercial_product_categories(organization_id, parent_id, state);

CREATE TABLE IF NOT EXISTS public.commercial_departments (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
  parent_id uuid REFERENCES public.commercial_departments(id) ON DELETE RESTRICT,
  code text NOT NULL CHECK (length(btrim(code)) BETWEEN 1 AND 64),
  name_ar text NOT NULL CHECK (length(btrim(name_ar)) BETWEEN 1 AND 160),
  name_en text CHECK (name_en IS NULL OR length(btrim(name_en)) BETWEEN 1 AND 160),
  cost_center_code text,
  state text NOT NULL DEFAULT 'active' CHECK (state IN ('active','inactive','archived')),
  metadata jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(metadata) = 'object'),
  created_by uuid REFERENCES auth.users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (organization_id, code),
  CHECK (parent_id IS NULL OR parent_id <> id)
);
CREATE INDEX IF NOT EXISTS ix_commercial_departments_parent
  ON public.commercial_departments(organization_id, parent_id, state);

-- Optional explicit links avoid conflating global product identity with an organization's classification.
CREATE TABLE IF NOT EXISTS public.commercial_offering_categories (
  organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
  offering_id uuid NOT NULL REFERENCES public.commercial_offerings(id) ON DELETE CASCADE,
  category_id uuid NOT NULL REFERENCES public.commercial_product_categories(id) ON DELETE RESTRICT,
  is_primary boolean NOT NULL DEFAULT false,
  created_by uuid REFERENCES auth.users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (offering_id, category_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_commercial_offering_primary_category
  ON public.commercial_offering_categories(offering_id) WHERE is_primary;
CREATE INDEX IF NOT EXISTS ix_commercial_offering_categories_org_category
  ON public.commercial_offering_categories(organization_id, category_id);

CREATE TABLE IF NOT EXISTS public.commercial_department_warehouse_assignments (
  organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
  department_id uuid NOT NULL REFERENCES public.commercial_departments(id) ON DELETE CASCADE,
  warehouse_id uuid NOT NULL REFERENCES public.commercial_warehouses(id) ON DELETE CASCADE,
  created_by uuid REFERENCES auth.users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (department_id, warehouse_id)
);

-- Validate organization boundaries and prevent hierarchy cycles.
CREATE OR REPLACE FUNCTION public.commercial_validate_master_data_parent()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $function$
DECLARE
  v_parent_org uuid;
  v_parent_warehouse uuid;
  v_cursor uuid;
  v_parent uuid;
BEGIN
  IF TG_TABLE_NAME = 'commercial_storage_locations' THEN
    SELECT organization_id INTO v_parent_org
      FROM public.commercial_warehouses WHERE id = NEW.warehouse_id;
    IF v_parent_org IS NULL OR v_parent_org <> NEW.organization_id THEN
      RAISE EXCEPTION 'warehouse_organization_mismatch' USING ERRCODE = '23514';
    END IF;
    IF NEW.parent_location_id IS NOT NULL THEN
      SELECT organization_id, warehouse_id INTO v_parent_org, v_parent_warehouse
        FROM public.commercial_storage_locations WHERE id = NEW.parent_location_id;
      IF v_parent_org IS DISTINCT FROM NEW.organization_id OR v_parent_warehouse IS DISTINCT FROM NEW.warehouse_id THEN
        RAISE EXCEPTION 'storage_parent_scope_mismatch' USING ERRCODE = '23514';
      END IF;
      v_cursor := NEW.parent_location_id;
      WHILE v_cursor IS NOT NULL LOOP
        IF v_cursor = NEW.id THEN
          RAISE EXCEPTION 'storage_location_hierarchy_cycle' USING ERRCODE = '23514';
        END IF;
        SELECT parent_location_id INTO v_parent
          FROM public.commercial_storage_locations WHERE id = v_cursor;
        v_cursor := v_parent;
      END LOOP;
    END IF;
  ELSIF TG_TABLE_NAME = 'commercial_warehouses' THEN
    IF NOT EXISTS (
      SELECT 1 FROM public.commercial_organization_locations l
      WHERE l.id = NEW.branch_location_id AND l.organization_id = NEW.organization_id
    ) THEN RAISE EXCEPTION 'warehouse_branch_scope_mismatch' USING ERRCODE = '23514'; END IF;
    IF NEW.activity_id IS NOT NULL AND NOT EXISTS (
      SELECT 1 FROM public.commercial_activities a
      WHERE a.id = NEW.activity_id AND a.organization_id = NEW.organization_id
    ) THEN RAISE EXCEPTION 'warehouse_activity_scope_mismatch' USING ERRCODE = '23514'; END IF;
  ELSIF TG_TABLE_NAME = 'commercial_product_categories' THEN
    IF NEW.parent_id IS NOT NULL THEN
      SELECT organization_id INTO v_parent_org
        FROM public.commercial_product_categories WHERE id = NEW.parent_id;
      IF v_parent_org IS DISTINCT FROM NEW.organization_id THEN
        RAISE EXCEPTION 'category_parent_scope_mismatch' USING ERRCODE = '23514';
      END IF;
      v_cursor := NEW.parent_id;
      WHILE v_cursor IS NOT NULL LOOP
        IF v_cursor = NEW.id THEN RAISE EXCEPTION 'category_hierarchy_cycle' USING ERRCODE = '23514'; END IF;
        SELECT parent_id INTO v_parent FROM public.commercial_product_categories WHERE id = v_cursor;
        v_cursor := v_parent;
      END LOOP;
    END IF;
  ELSIF TG_TABLE_NAME = 'commercial_departments' THEN
    IF NEW.parent_id IS NOT NULL THEN
      SELECT organization_id INTO v_parent_org
        FROM public.commercial_departments WHERE id = NEW.parent_id;
      IF v_parent_org IS DISTINCT FROM NEW.organization_id THEN
        RAISE EXCEPTION 'department_parent_scope_mismatch' USING ERRCODE = '23514';
      END IF;
      v_cursor := NEW.parent_id;
      WHILE v_cursor IS NOT NULL LOOP
        IF v_cursor = NEW.id THEN RAISE EXCEPTION 'department_hierarchy_cycle' USING ERRCODE = '23514'; END IF;
        SELECT parent_id INTO v_parent FROM public.commercial_departments WHERE id = v_cursor;
        v_cursor := v_parent;
      END LOOP;
    END IF;
  END IF;
  RETURN NEW;
END;
$function$;

DROP TRIGGER IF EXISTS commercial_warehouse_validate_scope ON public.commercial_warehouses;
CREATE TRIGGER commercial_warehouse_validate_scope BEFORE INSERT OR UPDATE ON public.commercial_warehouses
FOR EACH ROW EXECUTE FUNCTION public.commercial_validate_master_data_parent();
DROP TRIGGER IF EXISTS commercial_storage_location_validate_scope ON public.commercial_storage_locations;
CREATE TRIGGER commercial_storage_location_validate_scope BEFORE INSERT OR UPDATE ON public.commercial_storage_locations
FOR EACH ROW EXECUTE FUNCTION public.commercial_validate_master_data_parent();
DROP TRIGGER IF EXISTS commercial_product_category_validate_scope ON public.commercial_product_categories;
CREATE TRIGGER commercial_product_category_validate_scope BEFORE INSERT OR UPDATE ON public.commercial_product_categories
FOR EACH ROW EXECUTE FUNCTION public.commercial_validate_master_data_parent();
DROP TRIGGER IF EXISTS commercial_department_validate_scope ON public.commercial_departments;
CREATE TRIGGER commercial_department_validate_scope BEFORE INSERT OR UPDATE ON public.commercial_departments
FOR EACH ROW EXECUTE FUNCTION public.commercial_validate_master_data_parent();


CREATE OR REPLACE FUNCTION public.commercial_validate_master_data_assignments()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $function$
BEGIN
  IF TG_TABLE_NAME = 'commercial_offering_categories' THEN
    IF NOT EXISTS (
      SELECT 1 FROM public.commercial_offerings o
      JOIN public.commercial_product_categories c ON c.id = NEW.category_id
      WHERE o.id = NEW.offering_id
        AND o.activity_id IS NOT NULL
        AND c.organization_id = NEW.organization_id
        AND EXISTS (
          SELECT 1 FROM public.commercial_activities a
          WHERE a.id = o.activity_id AND a.organization_id = NEW.organization_id
        )
    ) THEN
      RAISE EXCEPTION 'offering_category_scope_mismatch' USING ERRCODE = '23514';
    END IF;
  ELSIF TG_TABLE_NAME = 'commercial_department_warehouse_assignments' THEN
    IF NOT EXISTS (
      SELECT 1 FROM public.commercial_departments d
      JOIN public.commercial_warehouses w ON w.id = NEW.warehouse_id
      WHERE d.id = NEW.department_id
        AND d.organization_id = NEW.organization_id
        AND w.organization_id = NEW.organization_id
    ) THEN
      RAISE EXCEPTION 'department_warehouse_scope_mismatch' USING ERRCODE = '23514';
    END IF;
  END IF;
  RETURN NEW;
END;
$function$;

DROP TRIGGER IF EXISTS commercial_offering_category_validate_scope ON public.commercial_offering_categories;
CREATE TRIGGER commercial_offering_category_validate_scope
BEFORE INSERT OR UPDATE ON public.commercial_offering_categories
FOR EACH ROW EXECUTE FUNCTION public.commercial_validate_master_data_assignments();

DROP TRIGGER IF EXISTS commercial_department_warehouse_validate_scope ON public.commercial_department_warehouse_assignments;
CREATE TRIGGER commercial_department_warehouse_validate_scope
BEFORE INSERT OR UPDATE ON public.commercial_department_warehouse_assignments
FOR EACH ROW EXECUTE FUNCTION public.commercial_validate_master_data_assignments();

-- Explicit tenant access: members may read; only owner/admin/manager may write.
DO $policies$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY[
    'commercial_warehouses','commercial_storage_locations','commercial_product_categories',
    'commercial_departments','commercial_offering_categories','commercial_department_warehouse_assignments'
  ] LOOP
    EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('DROP POLICY IF EXISTS %I ON public.%I', t || '_member_read', t);
    EXECUTE format(
      'CREATE POLICY %I ON public.%I FOR SELECT TO authenticated USING (
        EXISTS (SELECT 1 FROM public.commercial_organization_members m
          WHERE m.organization_id = %I.organization_id AND m.user_id = auth.uid() AND m.state = ''active'')
      )', t || '_member_read', t, t
    );
    EXECUTE format('DROP POLICY IF EXISTS %I ON public.%I', t || '_manager_insert', t);
    EXECUTE format(
      'CREATE POLICY %I ON public.%I FOR INSERT TO authenticated WITH CHECK (
        EXISTS (SELECT 1 FROM public.commercial_organization_members m
          WHERE m.organization_id = %I.organization_id AND m.user_id = auth.uid()
            AND m.state = ''active'' AND m.role IN (''owner'',''admin'',''manager''))
      )', t || '_manager_insert', t, t
    );
    EXECUTE format('DROP POLICY IF EXISTS %I ON public.%I', t || '_manager_update', t);
    EXECUTE format(
      'CREATE POLICY %I ON public.%I FOR UPDATE TO authenticated USING (
        EXISTS (SELECT 1 FROM public.commercial_organization_members m
          WHERE m.organization_id = %I.organization_id AND m.user_id = auth.uid()
            AND m.state = ''active'' AND m.role IN (''owner'',''admin'',''manager''))
      ) WITH CHECK (
        EXISTS (SELECT 1 FROM public.commercial_organization_members m
          WHERE m.organization_id = %I.organization_id AND m.user_id = auth.uid()
            AND m.state = ''active'' AND m.role IN (''owner'',''admin'',''manager''))
      )', t || '_manager_update', t, t, t
    );
  END LOOP;
END;
$policies$;

REVOKE ALL ON public.commercial_warehouses, public.commercial_storage_locations,
  public.commercial_product_categories, public.commercial_departments,
  public.commercial_offering_categories, public.commercial_department_warehouse_assignments
FROM anon;
GRANT SELECT, INSERT, UPDATE ON public.commercial_warehouses, public.commercial_storage_locations,
  public.commercial_product_categories, public.commercial_departments,
  public.commercial_offering_categories, public.commercial_department_warehouse_assignments TO authenticated;

COMMENT ON TABLE public.commercial_warehouses IS 'Organization-scoped warehouse master; branch/site is referenced rather than duplicated.';
COMMENT ON TABLE public.commercial_storage_locations IS 'Nested warehouse storage locations; stock posting is intentionally not wired in this additive phase.';
COMMENT ON TABLE public.commercial_product_categories IS 'Organization-specific category tree, independent from global business activity taxonomy.';
COMMENT ON TABLE public.commercial_departments IS 'Organization-specific department tree for responsibility and reporting.';
