-- OWNER-ONLY FOLLOW-UP: harden future public tables created by supabase_admin.
-- The connected project SQL role cannot alter this role's default ACL (permission denied).
-- Execute from a Supabase administrative session authorized to change defaults for
-- supabase_admin. Do not treat this migration as applied until pg_default_acl is verified.
ALTER DEFAULT PRIVILEGES FOR ROLE supabase_admin IN SCHEMA public
  REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER, MAINTAIN
  ON TABLES FROM anon, authenticated;

-- Verification after execution:
-- SELECT pg_get_userbyid(d.defaclrole) AS grantor, d.defaclacl
-- FROM pg_default_acl d
-- JOIN pg_namespace n ON n.oid = d.defaclnamespace
-- WHERE n.nspname = 'public' AND d.defaclobjtype = 'r'
--   AND pg_get_userbyid(d.defaclrole) IN ('postgres', 'supabase_admin');
