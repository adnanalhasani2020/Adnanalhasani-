\set ON_ERROR_STOP on
CREATE EXTENSION IF NOT EXISTS pgcrypto;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='anon') THEN CREATE ROLE anon NOLOGIN; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='authenticated') THEN CREATE ROLE authenticated NOLOGIN; END IF;
END $$;
CREATE SCHEMA IF NOT EXISTS auth;
CREATE TABLE auth.users(id uuid PRIMARY KEY,email text UNIQUE);
CREATE OR REPLACE FUNCTION auth.uid() RETURNS uuid
LANGUAGE sql STABLE AS $$
 SELECT nullif(current_setting('request.jwt.claim.sub',true),'')::uuid
$$;
CREATE TABLE public.commercial_organizations(
 id uuid PRIMARY KEY,name text NOT NULL,state text NOT NULL DEFAULT 'active',deleted_at timestamptz,default_currency_code text NOT NULL DEFAULT 'YER'
);
CREATE TABLE public.commercial_organization_members(
 organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id),
 user_id uuid NOT NULL REFERENCES auth.users(id),
 role text NOT NULL CHECK(role IN ('owner','admin','manager','staff','viewer')),
 state text NOT NULL CHECK(state IN ('invited','active','suspended','removed')),
 PRIMARY KEY(organization_id,user_id)
);
CREATE TABLE public.commercial_activities(
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 owner_user_id uuid NOT NULL REFERENCES auth.users(id),
 name text NOT NULL,
 state text NOT NULL DEFAULT 'active',
 organization_id uuid REFERENCES public.commercial_organizations(id),
 activity_type text NOT NULL DEFAULT 'general',
 activity_code text,
 description text,
 metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
 deleted_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE public.commercial_customers(
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id),
 display_name text NOT NULL CHECK(length(btrim(display_name)) BETWEEN 1 AND 200),
 email text,
 phone text,
 external_reference text,
 notes text,
 state text NOT NULL DEFAULT 'active' CHECK(state IN('active','inactive')),
 created_by uuid NOT NULL REFERENCES auth.users(id),
 auth_user_id uuid REFERENCES auth.users(id),
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(organization_id,external_reference)
);
CREATE TABLE public.commercial_audit_events(
 id bigserial PRIMARY KEY,
 organization_id uuid,
 actor_user_id uuid,
 action text NOT NULL,
 entity_type text NOT NULL,
 entity_id text,
 request_id text,
 metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
 created_at timestamptz NOT NULL DEFAULT now()
);
