-- Break the self-referential RLS policy on organization membership.
-- The SECURITY DEFINER helper is isolated in commercial_private and returns
-- only whether the current authenticated user is an active owner/admin.
CREATE OR REPLACE FUNCTION commercial_private.commercial_can_manage_organization_members(p_organization_id uuid)
RETURNS boolean
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $function$
  SELECT EXISTS (
    SELECT 1
    FROM public.commercial_organization_members AS m
    WHERE m.organization_id = p_organization_id
      AND m.user_id = (SELECT auth.uid())
      AND m.state = 'active'
      AND m.role IN ('owner', 'admin')
  );
$function$;

REVOKE ALL ON FUNCTION commercial_private.commercial_can_manage_organization_members(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION commercial_private.commercial_can_manage_organization_members(uuid) TO authenticated;

DROP POLICY IF EXISTS "members read organization membership" ON public.commercial_organization_members;
CREATE POLICY "members read organization membership"
  ON public.commercial_organization_members
  FOR SELECT
  TO authenticated
  USING (
    user_id = (SELECT auth.uid())
    OR commercial_private.commercial_can_manage_organization_members(organization_id)
  );
