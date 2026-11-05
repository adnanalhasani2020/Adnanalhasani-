CREATE EXTENSION IF NOT EXISTS pgcrypto;
DO $$ BEGIN CREATE ROLE anon NOLOGIN; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE ROLE authenticated NOLOGIN; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
CREATE SCHEMA IF NOT EXISTS auth;
CREATE TABLE auth.users(id uuid PRIMARY KEY,email text);
CREATE OR REPLACE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE AS $$
 SELECT nullif(current_setting('request.jwt.claim.sub',true),'')::uuid
$$;
CREATE TABLE public.commercial_organizations(id uuid PRIMARY KEY,name text NOT NULL);
CREATE TABLE public.commercial_organization_members(
 organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id),
 user_id uuid NOT NULL REFERENCES auth.users(id),state text NOT NULL,role text NOT NULL,
 PRIMARY KEY(organization_id,user_id)
);
CREATE TABLE public.commercial_user_notifications(
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),user_id uuid NOT NULL,organization_id uuid,
 notification_type text NOT NULL,title text NOT NULL,body text NOT NULL DEFAULT '',
 entity_type text,entity_id uuid,priority text NOT NULL DEFAULT 'normal',metadata jsonb NOT NULL DEFAULT '{}'::jsonb
);
