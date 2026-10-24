-- Allow active organization members to read organization-scoped sales and their child rows.
-- Preserve personal-sale visibility and do not grant write access.
BEGIN;

DROP POLICY IF EXISTS "users read own sales" ON public.commercial_sales;
CREATE POLICY "users read own or organization sales"
ON public.commercial_sales
FOR SELECT
TO authenticated
USING (
  user_id = (SELECT auth.uid())
  OR (
    organization_id IS NOT NULL
    AND EXISTS (
      SELECT 1
      FROM public.commercial_organization_members m
      WHERE m.organization_id = commercial_sales.organization_id
        AND m.user_id = (SELECT auth.uid())
        AND m.state = 'active'
    )
  )
);

DROP POLICY IF EXISTS "users read own sale items" ON public.commercial_sale_items;
CREATE POLICY "users read own or organization sale items"
ON public.commercial_sale_items
FOR SELECT
TO authenticated
USING (
  EXISTS (
    SELECT 1
    FROM public.commercial_sales s
    WHERE s.id = commercial_sale_items.sale_id
      AND (
        s.user_id = (SELECT auth.uid())
        OR (
          s.organization_id IS NOT NULL
          AND EXISTS (
            SELECT 1
            FROM public.commercial_organization_members m
            WHERE m.organization_id = s.organization_id
              AND m.user_id = (SELECT auth.uid())
              AND m.state = 'active'
          )
        )
      )
  )
);

DROP POLICY IF EXISTS "users read own stock movements" ON public.commercial_stock_movements;
CREATE POLICY "users read own or organization stock movements"
ON public.commercial_stock_movements
FOR SELECT
TO authenticated
USING (
  created_by = (SELECT auth.uid())
  OR EXISTS (
    SELECT 1
    FROM public.commercial_sales s
    WHERE s.id = commercial_stock_movements.sale_id
      AND (
        s.user_id = (SELECT auth.uid())
        OR (
          s.organization_id IS NOT NULL
          AND EXISTS (
            SELECT 1
            FROM public.commercial_organization_members m
            WHERE m.organization_id = s.organization_id
              AND m.user_id = (SELECT auth.uid())
              AND m.state = 'active'
          )
        )
      )
  )
);

COMMIT;
