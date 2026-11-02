BEGIN;

-- Keep the valuation report under caller privileges so its underlying RLS
-- policies are applied instead of running with the view owner's privileges.
ALTER VIEW public.commercial_inventory_cost_audit_report
  SET (security_invoker = true);

-- Cost-layer details expose acquisition costs and cost allocations. Keep ordinary
-- active members able to operate stock and sales, but restrict these detailed
-- valuation records to organization managers.
DROP POLICY IF EXISTS commercial_inventory_cost_layers_member_read
  ON public.commercial_inventory_cost_layers;
CREATE POLICY commercial_inventory_cost_layers_manager_read
  ON public.commercial_inventory_cost_layers
  FOR SELECT TO authenticated
  USING (
    EXISTS (
      SELECT 1
      FROM public.commercial_organization_members m
      WHERE m.organization_id = commercial_inventory_cost_layers.organization_id
        AND m.user_id = (SELECT auth.uid())
        AND m.state = 'active'
        AND m.role IN ('owner', 'admin', 'manager')
    )
  );

DROP POLICY IF EXISTS commercial_inventory_cost_allocations_member_read
  ON public.commercial_inventory_cost_allocations;
CREATE POLICY commercial_inventory_cost_allocations_manager_read
  ON public.commercial_inventory_cost_allocations
  FOR SELECT TO authenticated
  USING (
    EXISTS (
      SELECT 1
      FROM public.commercial_organization_members m
      WHERE m.organization_id = commercial_inventory_cost_allocations.organization_id
        AND m.user_id = (SELECT auth.uid())
        AND m.state = 'active'
        AND m.role IN ('owner', 'admin', 'manager')
    )
  );

DROP POLICY IF EXISTS commercial_inventory_cost_reversals_member_read
  ON public.commercial_inventory_cost_reversals;
CREATE POLICY commercial_inventory_cost_reversals_manager_read
  ON public.commercial_inventory_cost_reversals
  FOR SELECT TO authenticated
  USING (
    EXISTS (
      SELECT 1
      FROM public.commercial_organization_members m
      WHERE m.organization_id = commercial_inventory_cost_reversals.organization_id
        AND m.user_id = (SELECT auth.uid())
        AND m.state = 'active'
        AND m.role IN ('owner', 'admin', 'manager')
    )
  );

DROP POLICY IF EXISTS commercial_inventory_return_cost_allocations_member_read
  ON public.commercial_inventory_return_cost_allocations;
CREATE POLICY commercial_inventory_return_cost_allocations_manager_read
  ON public.commercial_inventory_return_cost_allocations
  FOR SELECT TO authenticated
  USING (
    EXISTS (
      SELECT 1
      FROM public.commercial_organization_members m
      WHERE m.organization_id = commercial_inventory_return_cost_allocations.organization_id
        AND m.user_id = (SELECT auth.uid())
        AND m.state = 'active'
        AND m.role IN ('owner', 'admin', 'manager')
    )
  );

COMMIT;
