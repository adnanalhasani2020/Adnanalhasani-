-- Fix two row-level security policies whose membership predicates compared
-- the membership table to itself instead of the protected row's organization.
ALTER TABLE public.commercial_inventory_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.commercial_return_restock_postings ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS commercial_inventory_receipts_member_read
  ON public.commercial_inventory_receipts;
CREATE POLICY commercial_inventory_receipts_member_read
  ON public.commercial_inventory_receipts
  FOR SELECT
  TO authenticated
  USING (
    EXISTS (
      SELECT 1
      FROM public.commercial_organization_members m
      WHERE m.organization_id = commercial_inventory_receipts.organization_id
        AND m.user_id = (SELECT auth.uid())
        AND m.state = 'active'
    )
  );

DROP POLICY IF EXISTS commercial_return_restock_postings_member_read
  ON public.commercial_return_restock_postings;
CREATE POLICY commercial_return_restock_postings_member_read
  ON public.commercial_return_restock_postings
  FOR SELECT
  TO authenticated
  USING (
    EXISTS (
      SELECT 1
      FROM public.commercial_organization_members m
      WHERE m.organization_id = commercial_return_restock_postings.organization_id
        AND m.user_id = (SELECT auth.uid())
        AND m.state = 'active'
    )
  );

COMMENT ON POLICY commercial_inventory_receipts_member_read
  ON public.commercial_inventory_receipts IS
  'Active members may read inventory receipt records only for their own organization.';
COMMENT ON POLICY commercial_return_restock_postings_member_read
  ON public.commercial_return_restock_postings IS
  'Active members may read return restock postings only for their own organization.';
