-- Keep anonymous public-directory reads while avoiding overlapping SELECT policies
-- for authenticated users. The authenticated policy includes both public listing
-- visibility and the existing active-membership check.
DROP POLICY IF EXISTS commercial_organizations_public_directory_read ON public.commercial_organizations;
CREATE POLICY commercial_organizations_public_directory_read
  ON public.commercial_organizations FOR SELECT TO anon
  USING (public_listing = true AND state = 'active' AND deleted_at IS NULL);

DROP POLICY IF EXISTS "members read their organizations" ON public.commercial_organizations;
CREATE POLICY "members read their organizations"
  ON public.commercial_organizations FOR SELECT TO authenticated
  USING (
    (public_listing = true AND state = 'active' AND deleted_at IS NULL)
    OR EXISTS (
      SELECT 1 FROM public.commercial_organization_members m
      WHERE m.organization_id = commercial_organizations.id
        AND m.user_id = (SELECT auth.uid())
        AND m.state = 'active'
    )
  );

DROP POLICY IF EXISTS commercial_locations_public_directory_read ON public.commercial_organization_locations;
CREATE POLICY commercial_locations_public_directory_read
  ON public.commercial_organization_locations FOR SELECT TO anon
  USING (
    state = 'active'
    AND EXISTS (
      SELECT 1 FROM public.commercial_organizations o
      WHERE o.id = commercial_organization_locations.organization_id
        AND o.public_listing = true
        AND o.state = 'active'
        AND o.deleted_at IS NULL
    )
  );

DROP POLICY IF EXISTS "organization members read locations" ON public.commercial_organization_locations;
CREATE POLICY "organization members read locations"
  ON public.commercial_organization_locations FOR SELECT TO authenticated
  USING (
    EXISTS (
      SELECT 1 FROM public.commercial_organizations o
      WHERE o.id = commercial_organization_locations.organization_id
        AND o.public_listing = true
        AND o.state = 'active'
        AND o.deleted_at IS NULL
    )
    OR EXISTS (
      SELECT 1 FROM public.commercial_organization_members m
      WHERE m.organization_id = commercial_organization_locations.organization_id
        AND m.user_id = (SELECT auth.uid())
        AND m.state = 'active'
    )
  );
