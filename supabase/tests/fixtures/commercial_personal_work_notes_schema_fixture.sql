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
 notification_type text NOT NULL,CONSTRAINT commercial_user_notifications_notification_type_check CHECK (notification_type = ANY (ARRAY['order','payment','booking','account_access','approval','system','security','message']::text[])),title text NOT NULL,CONSTRAINT commercial_user_notifications_title_check CHECK (length(btrim(title)) BETWEEN 1 AND 180),body text NOT NULL DEFAULT '' CONSTRAINT commercial_user_notifications_body_check CHECK (length(body)<=4000),
 entity_type text,entity_id uuid,priority text NOT NULL DEFAULT 'normal' CONSTRAINT commercial_user_notifications_priority_check CHECK (priority = ANY (ARRAY['low','normal','high','urgent']::text[])),metadata jsonb NOT NULL DEFAULT '{}'::jsonb CONSTRAINT commercial_user_notifications_metadata_check CHECK (jsonb_typeof(metadata)='object')
);
