BEGIN;

-- Stock movements do not carry organization_id directly. The offering-to-catalog
-- link is unique by offering_id, so use it as the tenant boundary and grant only
-- active organization managers read access. Preserve the existing own-row policy.
DROP POLICY IF EXISTS "organization managers read stock movements"
  ON public.commercial_stock_movements;

CREATE POLICY "organization managers read stock movements"
  ON public.commercial_stock_movements
  FOR SELECT TO authenticated
  USING (
    EXISTS (
      SELECT 1
      FROM public.commercial_catalog_item_offerings cio
      JOIN public.commercial_organization_members m
        ON m.organization_id = cio.organization_id
      WHERE cio.offering_id = commercial_stock_movements.offering_id
        AND m.user_id = (SELECT auth.uid())
        AND m.state = 'active'
        AND m.role IN ('owner', 'admin', 'manager')
    )
  );

COMMIT;
