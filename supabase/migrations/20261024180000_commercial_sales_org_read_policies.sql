-- Allow organization managers to review organization sales while preserving
-- the existing own-sale policy for staff and all users.
-- Mutations remain exclusively in trusted, guarded RPCs.

DROP POLICY IF EXISTS "organization managers read sales" ON public.commercial_sales;
CREATE POLICY "organization managers read sales"
  ON public.commercial_sales
  FOR SELECT
  TO authenticated
  USING (
    EXISTS (
      SELECT 1
      FROM public.commercial_organization_members m
      WHERE m.organization_id = commercial_sales.organization_id
        AND m.user_id = (SELECT auth.uid())
        AND m.state = 'active'
        AND m.role IN ('owner', 'admin', 'manager')
    )
  );

DROP POLICY IF EXISTS "organization managers read sale items" ON public.commercial_sale_items;
CREATE POLICY "organization managers read sale items"
  ON public.commercial_sale_items
  FOR SELECT
  TO authenticated
  USING (
    EXISTS (
      SELECT 1
      FROM public.commercial_sales s
      JOIN public.commercial_organization_members m
        ON m.organization_id = s.organization_id
      WHERE s.id = commercial_sale_items.sale_id
        AND m.user_id = (SELECT auth.uid())
        AND m.state = 'active'
        AND m.role IN ('owner', 'admin', 'manager')
    )
  );

COMMENT ON POLICY "organization managers read sales" ON public.commercial_sales
  IS 'Active organization owners/admins/managers can review organization sales; staff retain own-sale access.';
COMMENT ON POLICY "organization managers read sale items" ON public.commercial_sale_items
  IS 'Active organization owners/admins/managers can review invoice lines for organization sales.';
