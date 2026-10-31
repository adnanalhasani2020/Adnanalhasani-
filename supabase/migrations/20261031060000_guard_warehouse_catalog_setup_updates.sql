-- Prevent later warehouse edits from invalidating previously configured catalog item scope.
-- Additive guard: existing warehouse and catalog setup rows are never rewritten.
BEGIN;

CREATE OR REPLACE FUNCTION public.commercial_catalog_guard_warehouse_update()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM public.commercial_organization_catalog_item_setup s
    WHERE s.organization_id = OLD.organization_id
      AND s.warehouse_id = OLD.id
      AND (
        s.organization_id IS DISTINCT FROM NEW.organization_id
        OR s.branch_location_id IS DISTINCT FROM NEW.branch_location_id
        OR (
          NEW.activity_id IS NOT NULL
          AND NOT EXISTS (
            SELECT 1
            FROM public.commercial_activities a
            JOIN public.commercial_global_business_activity_nodes n
              ON n.id = s.activity_node_id
            WHERE a.id = NEW.activity_id
              AND a.organization_id = NEW.organization_id
              AND a.state = 'active'
              AND a.deleted_at IS NULL
              AND n.status = 'active'
              AND n.is_selectable
              AND (
                (a.business_activity_node_id IS NOT NULL
                  AND a.business_activity_node_id = s.activity_node_id)
                OR
                (a.business_activity_node_id IS NULL
                  AND a.activity_code = n.code)
              )
          )
        )
      )
  ) THEN
    RAISE EXCEPTION 'warehouse_update_would_invalidate_catalog_setup'
      USING ERRCODE = '23514';
  END IF;

  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS commercial_catalog_guard_warehouse_update
  ON public.commercial_warehouses;
CREATE TRIGGER commercial_catalog_guard_warehouse_update
BEFORE UPDATE OF organization_id, branch_location_id, activity_id
ON public.commercial_warehouses
FOR EACH ROW
WHEN (
  OLD.organization_id IS DISTINCT FROM NEW.organization_id
  OR OLD.branch_location_id IS DISTINCT FROM NEW.branch_location_id
  OR OLD.activity_id IS DISTINCT FROM NEW.activity_id
)
EXECUTE FUNCTION public.commercial_catalog_guard_warehouse_update();

REVOKE ALL ON FUNCTION public.commercial_catalog_guard_warehouse_update()
  FROM PUBLIC, anon, authenticated;

COMMENT ON FUNCTION public.commercial_catalog_guard_warehouse_update() IS
'Prevents warehouse organization, branch, or activity edits from silently invalidating existing catalog item setup scope.';

COMMIT;
