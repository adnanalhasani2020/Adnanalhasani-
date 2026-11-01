-- Least-privilege default table grants for objects created by postgres in public.
-- Applied directly to the live project on 2026-10-23.
-- Existing tables are handled by 20261023_harden_public_table_write_grants.sql.
-- The supabase_admin grantor defaults remain pending owner/admin execution because
-- this project connection is not permitted to alter that role's default ACLs.

ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
  REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER, MAINTAIN
  ON TABLES FROM anon, authenticated;

-- Pending owner/admin execution:
-- ALTER DEFAULT PRIVILEGES FOR ROLE supabase_admin IN SCHEMA public
--   REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER, MAINTAIN
--   ON TABLES FROM anon, authenticated;
