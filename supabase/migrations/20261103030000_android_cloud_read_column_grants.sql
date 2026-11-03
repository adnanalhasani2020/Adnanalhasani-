-- Restore the narrowly scoped authenticated reads required by existing Android cloud screens.
-- Row-level security remains enabled and enforced on both tables.
-- No table-wide SELECT grant is added, and anon receives no additional privilege.

GRANT SELECT (
    id,
    default_currency_code,
    default_timezone
) ON TABLE public.commercial_organizations TO authenticated;

GRANT SELECT (
    id,
    organization_id,
    name,
    location_type,
    latitude,
    longitude,
    state
) ON TABLE public.commercial_organization_locations TO authenticated;
