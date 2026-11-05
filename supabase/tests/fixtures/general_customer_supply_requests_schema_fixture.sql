CREATE EXTENSION IF NOT EXISTS pgcrypto;
DO $$ BEGIN CREATE ROLE anon NOLOGIN; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE ROLE authenticated NOLOGIN; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
CREATE SCHEMA IF NOT EXISTS auth;
CREATE TABLE auth.users(id uuid PRIMARY KEY,email text,email_confirmed_at timestamptz);
CREATE OR REPLACE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE AS $$
 SELECT nullif(current_setting('request.jwt.claim.sub',true),'')::uuid
$$;
CREATE TABLE public.commercial_organizations(
 id uuid PRIMARY KEY,name text NOT NULL,state text NOT NULL DEFAULT 'active',deleted_at timestamptz,
 default_currency_code text NOT NULL DEFAULT 'YER'
);
CREATE TABLE public.commercial_organization_members(
 organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id),
 user_id uuid NOT NULL REFERENCES auth.users(id),state text NOT NULL,role text NOT NULL,
 PRIMARY KEY(organization_id,user_id)
);
CREATE TABLE public.commercial_organization_locations(
 id uuid PRIMARY KEY,organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id),
 name text NOT NULL,state text NOT NULL DEFAULT 'active'
);
CREATE TABLE public.commercial_customers(
 id uuid PRIMARY KEY,organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id),
 auth_user_id uuid REFERENCES auth.users(id),display_name text NOT NULL,state text NOT NULL DEFAULT 'active'
);
CREATE TABLE public.commercial_party_accounts(
 id uuid PRIMARY KEY,organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id),
 account_type text NOT NULL,status text NOT NULL DEFAULT 'active',allow_credit boolean NOT NULL DEFAULT false,
 currency_code text NOT NULL DEFAULT 'YER'
);
CREATE TABLE public.commercial_customer_party_accounts(
 organization_id uuid NOT NULL,customer_id uuid NOT NULL REFERENCES public.commercial_customers(id),
 party_account_id uuid NOT NULL REFERENCES public.commercial_party_accounts(id),
 PRIMARY KEY(organization_id,customer_id,party_account_id)
);
CREATE TABLE public.commercial_suppliers(
 id uuid PRIMARY KEY,organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id),
 display_name text NOT NULL,state text NOT NULL DEFAULT 'active'
);
CREATE TABLE public.commercial_purchase_orders(
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id),
 supplier_id uuid NOT NULL REFERENCES public.commercial_suppliers(id),order_number text NOT NULL,
 status text NOT NULL,currency_code text NOT NULL,receive_location_id uuid NOT NULL REFERENCES public.commercial_organization_locations(id),
 notes text,created_by uuid NOT NULL REFERENCES auth.users(id),create_idempotency_key text,create_request_fingerprint text,
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE public.commercial_purchase_order_items(
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),organization_id uuid NOT NULL,
 purchase_order_id uuid NOT NULL REFERENCES public.commercial_purchase_orders(id),line_number integer NOT NULL,
 item_description text NOT NULL,quantity_ordered bigint NOT NULL,quantity_received bigint NOT NULL DEFAULT 0,
 unit_cost_minor bigint NOT NULL,offering_id uuid,source_selling_unit_code text,source_selling_unit_name text,
 source_selling_quantity numeric,source_quantity_in_stock_units numeric
);
CREATE TABLE public.commercial_audit_events(
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),organization_id uuid NOT NULL,actor_user_id uuid NOT NULL,
 action text NOT NULL,entity_type text NOT NULL,entity_id text NOT NULL,metadata jsonb NOT NULL DEFAULT '{}'::jsonb
);
CREATE TABLE public.commercial_user_notifications(
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),user_id uuid NOT NULL,organization_id uuid,
 notification_type text NOT NULL,title text NOT NULL,body text NOT NULL DEFAULT '',
 entity_type text,entity_id uuid,priority text NOT NULL DEFAULT 'normal',metadata jsonb NOT NULL DEFAULT '{}'::jsonb
);
CREATE OR REPLACE FUNCTION public.commercial_customer_portal_link_verified_email()
RETURNS void LANGUAGE plpgsql AS $$ BEGIN RETURN; END $$;
