-- The membership helper is an internal authorization primitive, not a client RPC.
-- SECURITY DEFINER callers execute as their owner and retain internal access.
REVOKE ALL ON FUNCTION public.commercial_catalog_member_of(uuid, text[]) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.commercial_catalog_member_of(uuid, text[]) FROM anon;
REVOKE ALL ON FUNCTION public.commercial_catalog_member_of(uuid, text[]) FROM authenticated;
