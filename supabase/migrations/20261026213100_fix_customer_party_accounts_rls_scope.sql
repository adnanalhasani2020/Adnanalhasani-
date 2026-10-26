-- Correct tenant scoping for customer-to-ledger party-account mappings.
-- The old predicates accidentally compared m.organization_id to itself, allowing
-- any active organization member (and any manager/admin) to match every row.
ALTER TABLE public.commercial_customer_party_accounts ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS commercial_customer_party_accounts_admin_write
  ON public.commercial_customer_party_accounts;
DROP POLICY IF EXISTS commercial_customer_party_accounts_member_read
  ON public.commercial_customer_party_accounts;

CREATE POLICY commercial_customer_party_accounts_member_read
  ON public.commercial_customer_party_accounts
  FOR SELECT
  TO authenticated
  USING (
    EXISTS (
      SELECT 1
      FROM public.commercial_organization_members m
      WHERE m.organization_id = commercial_customer_party_accounts.organization_id
        AND m.user_id = (SELECT auth.uid())
        AND m.state = 'active'
    )
  );

CREATE POLICY commercial_customer_party_accounts_admin_write
  ON public.commercial_customer_party_accounts
  FOR ALL
  TO authenticated
  USING (
    EXISTS (
      SELECT 1
      FROM public.commercial_organization_members m
      WHERE m.organization_id = commercial_customer_party_accounts.organization_id
        AND m.user_id = (SELECT auth.uid())
        AND m.state = 'active'
        AND m.role IN ('owner', 'admin', 'manager')
    )
  )
  WITH CHECK (
    EXISTS (
      SELECT 1
      FROM public.commercial_organization_members m
      WHERE m.organization_id = commercial_customer_party_accounts.organization_id
        AND m.user_id = (SELECT auth.uid())
        AND m.state = 'active'
        AND m.role IN ('owner', 'admin', 'manager')
    )
  );

COMMENT ON POLICY commercial_customer_party_accounts_member_read
  ON public.commercial_customer_party_accounts IS
  'Active members may read customer party-account links only inside their own organization.';
COMMENT ON POLICY commercial_customer_party_accounts_admin_write
  ON public.commercial_customer_party_accounts IS
  'Only active owners/admins/managers may mutate customer party-account links inside their own organization.';
