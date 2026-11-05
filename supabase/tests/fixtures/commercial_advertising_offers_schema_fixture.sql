CREATE EXTENSION IF NOT EXISTS pgcrypto;
DO $$ BEGIN CREATE ROLE anon NOLOGIN; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE ROLE authenticated NOLOGIN; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
CREATE SCHEMA IF NOT EXISTS auth;
CREATE TABLE auth.users(id uuid PRIMARY KEY,email text);
CREATE OR REPLACE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE AS $$
 SELECT nullif(current_setting('request.jwt.claim.sub',true),'')::uuid
$$;
CREATE TABLE public.commercial_organizations(id uuid PRIMARY KEY,name text NOT NULL);
CREATE TABLE public.commercial_organization_locations(
 id uuid PRIMARY KEY,organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id),
 state text NOT NULL,latitude numeric,longitude numeric
);
CREATE TABLE public.commercial_organization_members(
 organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id),
 user_id uuid NOT NULL REFERENCES auth.users(id),role text NOT NULL,state text NOT NULL,
 PRIMARY KEY(organization_id,user_id)
);
