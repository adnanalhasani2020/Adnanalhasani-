-- Defense in depth: RLS is not a substitute for least-privilege table grants.
-- Public/anonymous callers should never have write or TRUNCATE privileges on app tables.
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON ALL TABLES IN SCHEMA public FROM anon;

-- TRUNCATE bypasses row-level security. Keep it unavailable to client roles.
REVOKE TRUNCATE ON ALL TABLES IN SCHEMA public FROM authenticated;

-- These tables are read-only to authenticated clients; mutations belong in trusted RPCs.
REVOKE INSERT, UPDATE, DELETE ON TABLE public.commercial_module_catalog FROM authenticated;
REVOKE INSERT, UPDATE, DELETE ON TABLE public.commercial_sales_returns FROM authenticated;
REVOKE INSERT, UPDATE, DELETE ON TABLE public.commercial_sale_return_items FROM authenticated;
REVOKE DELETE ON TABLE public.commercial_profiles FROM authenticated;
