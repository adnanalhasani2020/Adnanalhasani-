-- The cloud expense/report screens read the organization's timezone directly.
-- Keep the existing membership/public-listing RLS policy; expose only this one
-- setting to authenticated clients. Do not grant it to anon or grant table-wide SELECT.

GRANT SELECT (default_timezone)
ON TABLE public.commercial_organizations
TO authenticated;
