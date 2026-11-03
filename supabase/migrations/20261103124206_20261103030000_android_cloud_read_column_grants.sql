-- The cloud expense/report screens read the organization's timezone directly.
-- Expose only this setting to authenticated clients under the existing RLS policy.

GRANT SELECT (default_timezone)
ON TABLE public.commercial_organizations
TO authenticated;
