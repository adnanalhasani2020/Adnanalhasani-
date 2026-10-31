-- Keep catalog setup, warehouse activity ownership, and the linked operational offering on the same activity taxonomy node.
-- Additive validation only: no existing catalog, offering, or stock rows are rewritten.
BEGIN;

CREATE OR REPLACE FUNCTION public.commercial_catalog_validate_activity_scope()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
  v_warehouse_activity_id uuid;
  v_activity_node_id uuid;
  v_activity_code text;
  v_setup_node_code text;
  v_activity_org_id uuid;
  v_offering_location_ref text;
BEGIN
  IF TG_TABLE_NAME = 'commercial_organization_catalog_item_setup' THEN
    SELECT w.activity_id
      INTO v_warehouse_activity_id
    FROM public.commercial_warehouses w
    WHERE w.id = NEW.warehouse_id
      AND w.organization_id = NEW.organization_id
      AND w.branch_location_id = NEW.branch_location_id
      AND w.state = 'active';

    IF NOT FOUND THEN
      RAISE EXCEPTION 'catalog_setup_warehouse_scope_mismatch'
        USING ERRCODE = '23514';
    END IF;

    IF v_warehouse_activity_id IS NOT NULL THEN
      SELECT a.organization_id, a.business_activity_node_id, a.activity_code
        INTO v_activity_org_id, v_activity_node_id, v_activity_code
      FROM public.commercial_activities a
      WHERE a.id = v_warehouse_activity_id
        AND a.state = 'active'
        AND a.deleted_at IS NULL;

      SELECT n.code INTO v_setup_node_code
      FROM public.commercial_global_business_activity_nodes n
      WHERE n.id = NEW.activity_node_id
        AND n.status = 'active'
        AND n.is_selectable;

      IF v_activity_org_id IS DISTINCT FROM NEW.organization_id
         OR v_setup_node_code IS NULL
         OR (v_activity_node_id IS NOT NULL AND v_activity_node_id IS DISTINCT FROM NEW.activity_node_id)
         OR (v_activity_node_id IS NULL AND v_activity_code IS DISTINCT FROM v_setup_node_code) THEN
        RAISE EXCEPTION 'catalog_setup_warehouse_activity_mismatch'
          USING ERRCODE = '23514';
      END IF;
    END IF;

  ELSIF TG_TABLE_NAME = 'commercial_catalog_item_offerings' THEN
    SELECT a.organization_id, a.business_activity_node_id, a.activity_code, o.location_ref
      INTO v_activity_org_id, v_activity_node_id, v_activity_code, v_offering_location_ref
    FROM public.commercial_offerings o
    JOIN public.commercial_activities a ON a.id = o.activity_id
    WHERE o.id = NEW.offering_id
      AND o.state = 'active';

    IF NOT FOUND
       OR v_activity_org_id IS DISTINCT FROM NEW.organization_id
       OR v_offering_location_ref IS DISTINCT FROM NEW.location_id::text
       OR NOT EXISTS (
         SELECT 1
         FROM public.commercial_organization_locations l
         WHERE l.id = NEW.location_id
           AND l.organization_id = NEW.organization_id
           AND l.state = 'active'
       ) THEN
      RAISE EXCEPTION 'catalog_offering_location_or_organization_mismatch'
        USING ERRCODE = '23514';
    END IF;

    -- A catalog item may legitimately be configured for several activity nodes
    -- at the same branch. Match the offering against any exact node mapping;
    -- never choose an arbitrary "latest" mapping.
    IF EXISTS (
      SELECT 1
      FROM public.commercial_organization_catalog_item_setup s
      WHERE s.organization_id = NEW.organization_id
        AND s.global_item_id = NEW.global_item_id
        AND s.branch_location_id = NEW.location_id
    ) AND NOT EXISTS (
      SELECT 1
      FROM public.commercial_organization_catalog_item_setup s
      JOIN public.commercial_global_business_activity_nodes n
        ON n.id = s.activity_node_id
      WHERE s.organization_id = NEW.organization_id
        AND s.global_item_id = NEW.global_item_id
        AND s.branch_location_id = NEW.location_id
        AND n.status = 'active'
        AND n.is_selectable
        AND (
          (v_activity_node_id IS NOT NULL AND s.activity_node_id = v_activity_node_id)
          OR (v_activity_node_id IS NULL AND n.code = v_activity_code)
        )
    ) THEN
      RAISE EXCEPTION 'catalog_offering_activity_node_mismatch'
        USING ERRCODE = '23514';
    END IF;
  END IF;

  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS commercial_catalog_setup_activity_scope ON public.commercial_organization_catalog_item_setup;
CREATE TRIGGER commercial_catalog_setup_activity_scope
BEFORE INSERT OR UPDATE OF organization_id, activity_node_id, branch_location_id, warehouse_id
ON public.commercial_organization_catalog_item_setup
FOR EACH ROW EXECUTE FUNCTION public.commercial_catalog_validate_activity_scope();

DROP TRIGGER IF EXISTS commercial_catalog_offering_activity_scope ON public.commercial_catalog_item_offerings;
CREATE TRIGGER commercial_catalog_offering_activity_scope
BEFORE INSERT OR UPDATE OF organization_id, global_item_id, offering_id, location_id
ON public.commercial_catalog_item_offerings
FOR EACH ROW EXECUTE FUNCTION public.commercial_catalog_validate_activity_scope();

REVOKE ALL ON FUNCTION public.commercial_catalog_validate_activity_scope() FROM PUBLIC, anon, authenticated;

COMMENT ON FUNCTION public.commercial_catalog_validate_activity_scope() IS
'Validates activity taxonomy consistency between catalog setup, activity-scoped warehouses, and linked active offerings without changing inventory or pricing.';

COMMIT;
