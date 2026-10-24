-- Trigger functions are invoked by PostgreSQL triggers, not by API clients.
-- Remove inherited PUBLIC/anon/authenticated EXECUTE from the SECURITY DEFINER trigger.
REVOKE ALL ON FUNCTION public.commercial_link_activity_taxonomy() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.commercial_link_activity_taxonomy() FROM anon;
REVOKE ALL ON FUNCTION public.commercial_link_activity_taxonomy() FROM authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_link_activity_taxonomy() TO postgres, service_role;
