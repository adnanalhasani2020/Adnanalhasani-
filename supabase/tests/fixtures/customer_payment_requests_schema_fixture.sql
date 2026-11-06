CREATE EXTENSION IF NOT EXISTS pgcrypto;
DO $$ BEGIN CREATE ROLE anon NOLOGIN; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE ROLE authenticated NOLOGIN; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
CREATE SCHEMA IF NOT EXISTS auth;
CREATE TABLE auth.users(id uuid PRIMARY KEY,email text);
CREATE OR REPLACE FUNCTION auth.uid() RETURNS uuid
LANGUAGE sql STABLE AS $$
 SELECT nullif(current_setting('request.jwt.claim.sub',true),'')::uuid
$$;

CREATE TABLE public.commercial_organizations(
 id uuid PRIMARY KEY,name text NOT NULL,state text NOT NULL DEFAULT 'active',deleted_at timestamptz
);
CREATE TABLE public.commercial_customers(
 id uuid PRIMARY KEY,organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id),
 auth_user_id uuid REFERENCES auth.users(id),state text NOT NULL DEFAULT 'active',display_name text NOT NULL
);
CREATE TABLE public.commercial_party_accounts(
 id uuid PRIMARY KEY,organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id),
 account_type text NOT NULL,status text NOT NULL
);
CREATE TABLE public.commercial_customer_party_accounts(
 customer_id uuid NOT NULL REFERENCES public.commercial_customers(id),
 organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id),
 party_account_id uuid NOT NULL REFERENCES public.commercial_party_accounts(id),
 PRIMARY KEY(customer_id,organization_id)
);
CREATE TABLE public.commercial_organization_members(
 organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id),
 user_id uuid NOT NULL REFERENCES auth.users(id),role text NOT NULL,state text NOT NULL,
 PRIMARY KEY(organization_id,user_id)
);
CREATE TABLE public.commercial_sales(
 id uuid PRIMARY KEY,organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id),
 customer_id uuid NOT NULL REFERENCES public.commercial_customers(id),
 status text NOT NULL,total_minor bigint NOT NULL,currency_code text NOT NULL,
 receipt_number text NOT NULL,created_at timestamptz NOT NULL DEFAULT now()
);
CREATE SEQUENCE public.commercial_payment_receipt_seq START 9000000000;
CREATE TABLE public.commercial_payments(
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id),
 sale_id uuid REFERENCES public.commercial_sales(id),
 provider text NOT NULL DEFAULT 'internal',
 idempotency_key text NOT NULL UNIQUE,
 amount_minor bigint NOT NULL,
 currency_code text NOT NULL,
 status text NOT NULL,
 payment_method_type text,
 receipt_number bigint NOT NULL DEFAULT nextval('public.commercial_payment_receipt_seq'),
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE public.commercial_sales_returns(
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),sale_id uuid NOT NULL,
 organization_id uuid NOT NULL,refund_status text NOT NULL DEFAULT 'none',
 approved_refund_minor bigint NOT NULL DEFAULT 0,status text NOT NULL DEFAULT 'pending'
);
CREATE TABLE public.commercial_user_notifications(
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),user_id uuid NOT NULL,organization_id uuid NOT NULL,
 notification_type text NOT NULL,title text NOT NULL,body text NOT NULL,entity_type text NOT NULL,
 entity_id uuid NOT NULL,priority text NOT NULL,metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
 created_at timestamptz NOT NULL DEFAULT now()
);

CREATE OR REPLACE FUNCTION public.commercial_customer_portal_link_verified_email()
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $$ BEGIN
 IF auth.uid() IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
END $$;

CREATE OR REPLACE FUNCTION public.commercial_record_customer_payment(
 p_sale_id uuid,p_amount_minor bigint,p_payment_method_type text,p_idempotency_key text
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=''
AS $$
DECLARE v_sale public.commercial_sales%ROWTYPE; v_id uuid; v_existing public.commercial_payments%ROWTYPE;
 v_due numeric;
BEGIN
 SELECT * INTO v_sale FROM public.commercial_sales WHERE id=p_sale_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'sale_not_found'; END IF;
 SELECT * INTO v_existing FROM public.commercial_payments WHERE idempotency_key=p_idempotency_key;
 IF FOUND THEN RETURN jsonb_build_object('payment_id',v_existing.id,'replayed',true); END IF;
 SELECT v_sale.total_minor::numeric-COALESCE(sum(amount_minor),0)
 INTO v_due FROM public.commercial_payments
 WHERE sale_id=p_sale_id AND status IN ('succeeded','partially_refunded','refunded');
 IF p_amount_minor<=0 OR p_amount_minor::numeric>v_due THEN
  RAISE EXCEPTION 'payment_exceeds_due' USING ERRCODE='22023';
 END IF;
 INSERT INTO public.commercial_payments(
  organization_id,sale_id,provider,idempotency_key,amount_minor,currency_code,status,payment_method_type
 ) VALUES(
  v_sale.organization_id,p_sale_id,'internal',p_idempotency_key,p_amount_minor,
  v_sale.currency_code,'succeeded',p_payment_method_type
 ) RETURNING id INTO v_id;
 RETURN jsonb_build_object('payment_id',v_id,'replayed',false);
END $$;

INSERT INTO auth.users(id,email) VALUES
 ('10000000-0000-0000-0000-000000000001','manager@test.invalid'),
 ('10000000-0000-0000-0000-000000000002','customer@test.invalid'),
 ('10000000-0000-0000-0000-000000000003','staff@test.invalid'),
 ('10000000-0000-0000-0000-000000000004','viewer@test.invalid'),
 ('10000000-0000-0000-0000-000000000005','outsider@test.invalid');
INSERT INTO public.commercial_organizations(id,name,state) VALUES
 ('20000000-0000-0000-0000-000000000001','Payment request test','active'),
 ('20000000-0000-0000-0000-000000000002','Other organization','active');
INSERT INTO public.commercial_customers(id,organization_id,auth_user_id,state,display_name) VALUES
 ('30000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000002','active','Test customer');
INSERT INTO public.commercial_party_accounts(id,organization_id,account_type,status) VALUES
 ('40000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000001','business','active');
INSERT INTO public.commercial_customer_party_accounts(customer_id,organization_id,party_account_id) VALUES
 ('30000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000001','40000000-0000-0000-0000-000000000001');
INSERT INTO public.commercial_organization_members(organization_id,user_id,role,state) VALUES
 ('20000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001','manager','active'),
 ('20000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000003','staff','active'),
 ('20000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000004','viewer','active'),
 ('20000000-0000-0000-0000-000000000002','10000000-0000-0000-0000-000000000005','manager','active');
INSERT INTO public.commercial_sales(id,organization_id,customer_id,status,total_minor,currency_code,receipt_number) VALUES
 ('50000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000001','30000000-0000-0000-0000-000000000001','completed',10000,'SAR','INV-TEST-001');
INSERT INTO public.commercial_payments(
 organization_id,sale_id,provider,idempotency_key,amount_minor,currency_code,status,receipt_number
) VALUES
 ('20000000-0000-0000-0000-000000000001','50000000-0000-0000-0000-000000000001',
  'internal','initial-payment',2000,'SAR','succeeded',9000000001);
