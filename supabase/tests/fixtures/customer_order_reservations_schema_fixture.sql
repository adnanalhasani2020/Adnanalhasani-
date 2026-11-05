CREATE EXTENSION IF NOT EXISTS pgcrypto;
DO $$ BEGIN CREATE ROLE anon NOLOGIN; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE ROLE authenticated NOLOGIN; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
CREATE SCHEMA IF NOT EXISTS auth;
CREATE TABLE auth.users(id uuid PRIMARY KEY);
CREATE OR REPLACE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE AS $$
  SELECT nullif(current_setting('request.jwt.claim.sub',true),'')::uuid
$$;

CREATE TABLE public.commercial_organizations(id uuid PRIMARY KEY);
CREATE TABLE public.commercial_organization_locations(
 id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id),
 name text NOT NULL, state text NOT NULL DEFAULT 'active'
);
CREATE TABLE public.commercial_organization_members(
 organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id),
 user_id uuid NOT NULL REFERENCES auth.users(id), state text NOT NULL, role text NOT NULL,
 PRIMARY KEY(organization_id,user_id)
);
CREATE TABLE public.commercial_activities(
 id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id)
);
CREATE TABLE public.commercial_offerings(
 id uuid PRIMARY KEY, activity_id uuid NOT NULL REFERENCES public.commercial_activities(id)
);
CREATE TABLE public.commercial_customer_orders(
 id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id),
 order_type text NOT NULL CHECK(order_type IN ('purchase','booking','service')),
 status text NOT NULL CHECK(status IN ('pending','confirmed','in_progress','fulfilled','cancelled','rejected')),
 payment_status text NOT NULL CHECK(payment_status IN ('unpaid','partially_paid','paid','credit','refunded')),
 reserved_from timestamptz, reserved_until timestamptz,
 merchant_reviewed_at timestamptz, merchant_reviewed_by uuid REFERENCES auth.users(id),
 merchant_decision_reason text, updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE public.commercial_customer_order_items(
 id uuid PRIMARY KEY, order_id uuid NOT NULL REFERENCES public.commercial_customer_orders(id),
 offering_id uuid NOT NULL REFERENCES public.commercial_offerings(id),
 item_type text NOT NULL, quantity numeric NOT NULL
);
CREATE TABLE public.commercial_inventory_balances(
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id),
 location_id uuid NOT NULL REFERENCES public.commercial_organization_locations(id),
 offering_id uuid NOT NULL REFERENCES public.commercial_offerings(id),
 quantity_on_hand numeric NOT NULL CHECK(quantity_on_hand>=0),
 quantity_reserved numeric NOT NULL DEFAULT 0 CHECK(quantity_reserved>=0 AND quantity_reserved<=quantity_on_hand),
 updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(organization_id,location_id,offering_id)
);
CREATE TABLE public.commercial_audit_events(
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL,
 actor_user_id uuid NOT NULL, action text NOT NULL, entity_type text NOT NULL,
 entity_id text NOT NULL, metadata jsonb NOT NULL DEFAULT '{}'::jsonb
);
CREATE TABLE public.commercial_user_notifications(
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), user_id uuid NOT NULL,
 organization_id uuid, notification_type text NOT NULL, title text NOT NULL,
 body text NOT NULL DEFAULT '', entity_type text, entity_id uuid, priority text NOT NULL DEFAULT 'normal',
 metadata jsonb NOT NULL DEFAULT '{}'::jsonb
);
CREATE TABLE public.commercial_customer_order_fulfillments(
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), organization_id uuid NOT NULL,
 order_id uuid NOT NULL UNIQUE, sale_id uuid NOT NULL UNIQUE,
 location_id uuid NOT NULL, idempotency_key text NOT NULL, request_fingerprint text NOT NULL,
 result jsonb NOT NULL, fulfilled_by uuid NOT NULL
);

CREATE OR REPLACE FUNCTION public.commercial_customer_portal_fulfill_order(
 p_organization_id uuid,p_order_id uuid,p_location_id uuid,p_idempotency_key text
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $$
DECLARE v_existing public.commercial_customer_order_fulfillments%ROWTYPE; v_result jsonb;
BEGIN
 SELECT * INTO v_existing FROM public.commercial_customer_order_fulfillments WHERE order_id=p_order_id;
 IF FOUND THEN RETURN v_existing.result || jsonb_build_object('idempotent_replay',true); END IF;
 UPDATE public.commercial_inventory_balances b
 SET quantity_on_hand=b.quantity_on_hand-q.qty,updated_at=now()
 FROM (SELECT offering_id,sum(quantity) qty FROM public.commercial_customer_order_items WHERE order_id=p_order_id GROUP BY offering_id) q
 WHERE b.organization_id=p_organization_id AND b.location_id=p_location_id AND b.offering_id=q.offering_id
   AND b.quantity_on_hand>=q.qty;
 IF NOT FOUND THEN RAISE EXCEPTION 'stub_checkout_stock_unavailable'; END IF;
 UPDATE public.commercial_customer_orders SET status='fulfilled',updated_at=now() WHERE id=p_order_id;
 v_result:=jsonb_build_object('order_id',p_order_id,'state','completed','status','fulfilled','idempotent_replay',false);
 INSERT INTO public.commercial_customer_order_fulfillments(
  organization_id,order_id,sale_id,location_id,idempotency_key,request_fingerprint,result,fulfilled_by
 ) VALUES(p_organization_id,p_order_id,gen_random_uuid(),p_location_id,p_idempotency_key,
  md5(jsonb_build_object('organization_id',p_organization_id,'order_id',p_order_id,'location_id',p_location_id)::text),
  v_result,auth.uid());
 RETURN v_result;
END;
$$;
