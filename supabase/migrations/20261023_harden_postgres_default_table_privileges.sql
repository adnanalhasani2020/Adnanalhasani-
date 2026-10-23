-- Harden default table privileges for objects created by postgres in public.
-- Applied directly to the live project on 2026-10-23.
-- The supabase_admin grantor defaults are intentionally handled separately because
-- this project connection cannot alter that role's default ACLs.

ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
  REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON TABLES FROM anon;

ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
  REVOKE TRUNCATE ON TABLES FROM authenticated;

-- Pending owner/admin execution (not executable by the current project connection):
-- ALTER DEFAULT PRIVILEGES FOR ROLE supabase_admin IN SCHEMA public
--   REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON TABLES FROM anon;
-- ALTER DEFAULT PRIVILEGES FOR ROLE supabase_admin IN SCHEMA public
--   REVOKE TRUNCATE ON TABLES FROM authenticated;
