CREATE EXTENSION IF NOT EXISTS pgcrypto;
DO $$ BEGIN CREATE ROLE anon NOLOGIN; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE ROLE authenticated NOLOGIN; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
CREATE SCHEMA IF NOT EXISTS auth;
CREATE TABLE auth.users(id uuid PRIMARY KEY);
CREATE OR REPLACE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE AS $$
 SELECT nullif(current_setting('request.jwt.claim.sub',true),'')::uuid
$$;
CREATE TABLE public.commercial_organizations(
 id uuid PRIMARY KEY,name text NOT NULL,default_currency_code text NOT NULL DEFAULT 'YER',
 state text NOT NULL DEFAULT 'active',deleted_at timestamptz
);
CREATE TABLE public.commercial_organization_members(
 organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id),
 user_id uuid NOT NULL REFERENCES auth.users(id),role text NOT NULL,state text NOT NULL,
 PRIMARY KEY(organization_id,user_id)
);
CREATE TABLE public.commercial_activities(
 id uuid PRIMARY KEY,organization_id uuid REFERENCES public.commercial_organizations(id),
 name text NOT NULL DEFAULT 'نشاط',state text NOT NULL DEFAULT 'active',deleted_at timestamptz
);
CREATE TABLE public.commercial_audit_events(
 id bigserial PRIMARY KEY,organization_id uuid,actor_user_id uuid,action text NOT NULL,
 entity_type text NOT NULL,entity_id text,metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
 created_at timestamptz NOT NULL DEFAULT now()
);
