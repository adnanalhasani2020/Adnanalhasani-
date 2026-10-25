-- Complete commercial procurement lifecycle: purchase orders, receiving, supplier payables and disbursements.
-- All writes are authenticated, organization-scoped, idempotent and transactional.
ALTER TABLE public.commercial_purchase_orders
  ADD COLUMN IF NOT EXISTS receive_location_id uuid REFERENCES public.commercial_organization_locations(id) ON DELETE RESTRICT,
  ADD COLUMN IF NOT EXISTS create_idempotency_key text,
  ADD COLUMN IF NOT EXISTS create_request_fingerprint text,
  ADD COLUMN IF NOT EXISTS cancelled_by uuid REFERENCES auth.users(id) ON DELETE SET NULL,
  ADD COLUMN IF NOT EXISTS cancelled_at timestamptz;

ALTER TABLE public.commercial_purchase_order_items
  ADD COLUMN IF NOT EXISTS offering_id uuid REFERENCES public.commercial_offerings(id) ON DELETE RESTRICT;

CREATE UNIQUE INDEX IF NOT EXISTS commercial_purchase_orders_create_idempotency_uq
  ON public.commercial_purchase_orders(organization_id, create_idempotency_key)
  WHERE create_idempotency_key IS NOT NULL;

CREATE TABLE IF NOT EXISTS public.commercial_purchase_receipts (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
  purchase_order_id uuid NOT NULL,
  location_id uuid NOT NULL REFERENCES public.commercial_organization_locations(id) ON DELETE RESTRICT,
  idempotency_key text NOT NULL CHECK (length(btrim(idempotency_key)) BETWEEN 8 AND 200),
  request_fingerprint text NOT NULL,
  received_by uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
  received_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (organization_id, idempotency_key),
  UNIQUE (organization_id, id, purchase_order_id),
  FOREIGN KEY (organization_id, purchase_order_id)
    REFERENCES public.commercial_purchase_orders(organization_id, id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS public.commercial_purchase_receipt_items (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
  receipt_id uuid NOT NULL,
  purchase_order_id uuid NOT NULL,
  purchase_order_item_id uuid NOT NULL,
  offering_id uuid NOT NULL REFERENCES public.commercial_offerings(id) ON DELETE RESTRICT,
  quantity bigint NOT NULL CHECK (quantity > 0),
  unit_cost_minor bigint NOT NULL CHECK (unit_cost_minor >= 0),
  inventory_receipt_id uuid NOT NULL REFERENCES public.commercial_inventory_receipts(id) ON DELETE RESTRICT,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (receipt_id, purchase_order_item_id),
  FOREIGN KEY (organization_id, receipt_id, purchase_order_id)
    REFERENCES public.commercial_purchase_receipts(organization_id, id, purchase_order_id) ON DELETE RESTRICT,
  FOREIGN KEY (organization_id, purchase_order_id)
    REFERENCES public.commercial_purchase_orders(organization_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (purchase_order_item_id)
    REFERENCES public.commercial_purchase_order_items(id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS public.commercial_supplier_gl_accounts (
  organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
  currency_code text NOT NULL CHECK (currency_code ~ '^[A-Z]{3}$'),
  account_role text NOT NULL CHECK (account_role IN ('inventory_asset','accounts_payable')),
  ledger_account_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (organization_id, currency_code, account_role),
  UNIQUE (ledger_account_id),
  FOREIGN KEY (organization_id, ledger_account_id)
    REFERENCES public.commercial_ledger_accounts(organization_id, id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS public.commercial_supplier_party_accounts (
  organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
  supplier_id uuid NOT NULL,
  currency_code text NOT NULL CHECK (currency_code ~ '^[A-Z]{3}$'),
  party_account_id uuid NOT NULL REFERENCES public.commercial_party_accounts(id) ON DELETE RESTRICT,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (organization_id, supplier_id, currency_code),
  UNIQUE (party_account_id),
  FOREIGN KEY (organization_id, supplier_id)
    REFERENCES public.commercial_suppliers(organization_id, id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS public.commercial_supplier_obligations (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
  supplier_invoice_id uuid NOT NULL UNIQUE REFERENCES public.commercial_supplier_invoices(id) ON DELETE RESTRICT,
  supplier_id uuid NOT NULL,
  party_account_id uuid NOT NULL REFERENCES public.commercial_party_accounts(id) ON DELETE RESTRICT,
  journal_entry_id uuid REFERENCES public.commercial_journal_entries(id) ON DELETE RESTRICT,
  currency_code text NOT NULL CHECK (currency_code ~ '^[A-Z]{3}
  amount_due_minor bigint NOT NULL CHECK (amount_due_minor >= 0),
  amount_paid_minor bigint NOT NULL DEFAULT 0 CHECK (amount_paid_minor >= 0 AND amount_paid_minor <= amount_due_minor),
  state text NOT NULL DEFAULT 'open' CHECK (state IN ('open','partially_paid','paid')),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (organization_id, supplier_id)
    REFERENCES public.commercial_suppliers(organization_id, id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS public.commercial_supplier_payments (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
  obligation_id uuid NOT NULL REFERENCES public.commercial_supplier_obligations(id) ON DELETE RESTRICT,
  supplier_invoice_id uuid NOT NULL REFERENCES public.commercial_supplier_invoices(id) ON DELETE RESTRICT,
  amount_minor bigint NOT NULL CHECK (amount_minor > 0),
  currency_code text NOT NULL CHECK (currency_code ~ '^[A-Z]{3}$'),
  payment_method text NOT NULL CHECK (payment_method IN ('cash','bank_transfer','check','other')),
  evidence_reference text NOT NULL CHECK (length(btrim(evidence_reference)) BETWEEN 3 AND 200),
  idempotency_key text NOT NULL CHECK (length(btrim(idempotency_key)) BETWEEN 8 AND 200),
  request_fingerprint text NOT NULL,
  journal_entry_id uuid NOT NULL REFERENCES public.commercial_journal_entries(id) ON DELETE RESTRICT,
  recorded_by uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
  recorded_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (organization_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS commercial_supplier_obligations_supplier_idx
  ON public.commercial_supplier_obligations(organization_id, supplier_id, state, created_at DESC);
CREATE INDEX IF NOT EXISTS commercial_supplier_payments_obligation_idx
  ON public.commercial_supplier_payments(organization_id, obligation_id, recorded_at DESC);

ALTER TABLE public.commercial_purchase_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.commercial_purchase_receipt_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.commercial_supplier_gl_accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.commercial_supplier_party_accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.commercial_supplier_obligations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.commercial_supplier_payments ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS commercial_purchase_receipts_org_read ON public.commercial_purchase_receipts;
CREATE POLICY commercial_purchase_receipts_org_read ON public.commercial_purchase_receipts FOR SELECT TO authenticated
USING (EXISTS (SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=commercial_purchase_receipts.organization_id AND m.user_id=auth.uid() AND m.state='active'));
DROP POLICY IF EXISTS commercial_purchase_receipt_items_org_read ON public.commercial_purchase_receipt_items;
CREATE POLICY commercial_purchase_receipt_items_org_read ON public.commercial_purchase_receipt_items FOR SELECT TO authenticated
USING (EXISTS (SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=commercial_purchase_receipt_items.organization_id AND m.user_id=auth.uid() AND m.state='active'));
DROP POLICY IF EXISTS commercial_supplier_obligations_org_read ON public.commercial_supplier_obligations;
CREATE POLICY commercial_supplier_obligations_org_read ON public.commercial_supplier_obligations FOR SELECT TO authenticated
USING (EXISTS (SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=commercial_supplier_obligations.organization_id AND m.user_id=auth.uid() AND m.state='active'));
DROP POLICY IF EXISTS commercial_supplier_payments_org_read ON public.commercial_supplier_payments;
CREATE POLICY commercial_supplier_payments_org_read ON public.commercial_supplier_payments FOR SELECT TO authenticated
USING (EXISTS (SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=commercial_supplier_payments.organization_id AND m.user_id=auth.uid() AND m.state='active'));
DROP POLICY IF EXISTS commercial_supplier_gl_accounts_org_read ON public.commercial_supplier_gl_accounts;
CREATE POLICY commercial_supplier_gl_accounts_org_read ON public.commercial_supplier_gl_accounts FOR SELECT TO authenticated
USING (EXISTS (SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=commercial_supplier_gl_accounts.organization_id AND m.user_id=auth.uid() AND m.state='active'));
DROP POLICY IF EXISTS commercial_supplier_party_accounts_org_read ON public.commercial_supplier_party_accounts;
CREATE POLICY commercial_supplier_party_accounts_org_read ON public.commercial_supplier_party_accounts FOR SELECT TO authenticated
USING (EXISTS (SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=commercial_supplier_party_accounts.organization_id AND m.user_id=auth.uid() AND m.state='active'));

REVOKE ALL ON public.commercial_purchase_receipts, public.commercial_purchase_receipt_items,
  public.commercial_supplier_gl_accounts, public.commercial_supplier_party_accounts,
  public.commercial_supplier_obligations, public.commercial_supplier_payments FROM anon, authenticated;
GRANT SELECT ON public.commercial_purchase_receipts, public.commercial_purchase_receipt_items,
  public.commercial_supplier_gl_accounts, public.commercial_supplier_party_accounts,
  public.commercial_supplier_obligations, public.commercial_supplier_payments TO authenticated;

CREATE SEQUENCE IF NOT EXISTS public.commercial_supplier_journal_entry_number_seq;
SELECT setval('public.commercial_supplier_journal_entry_number_seq',
  GREATEST(COALESCE((SELECT max(entry_number) FROM public.commercial_journal_entries),0)+1,1), false);

CREATE OR REPLACE FUNCTION commercial_private.commercial_ensure_supplier_ledger_account(
  p_organization_id uuid, p_currency_code text, p_role text
) RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE v_account_id uuid; v_code text; v_name text; v_type text; v_balance text;
BEGIN
  SELECT ledger_account_id INTO v_account_id FROM public.commercial_supplier_gl_accounts
   WHERE organization_id=p_organization_id AND currency_code=p_currency_code AND account_role=p_role;
  IF FOUND THEN
    IF NOT EXISTS (SELECT 1 FROM public.commercial_ledger_accounts WHERE organization_id=p_organization_id AND id=v_account_id AND currency_code=p_currency_code AND is_active) THEN
      RAISE EXCEPTION 'supplier_ledger_account_invalid' USING ERRCODE='55000';
    END IF;
    RETURN v_account_id;
  END IF;
  v_code := CASE WHEN p_role='inventory_asset' THEN 'SUPINV_' ELSE 'SUPAP_' END || p_currency_code;
  v_name := CASE WHEN p_role='inventory_asset' THEN 'مخزون مشتريات الموردين - ' ELSE 'ذمم الموردين - ' END || p_currency_code;
  v_type := CASE WHEN p_role='inventory_asset' THEN 'asset' ELSE 'liability' END;
  v_balance := CASE WHEN p_role='inventory_asset' THEN 'debit' ELSE 'credit' END;
  INSERT INTO public.commercial_ledger_accounts(organization_id,account_code,account_name,account_type,normal_balance,currency_code,is_system,is_active)
  VALUES(p_organization_id,v_code,v_name,v_type,v_balance,p_currency_code,true,true)
  ON CONFLICT (organization_id,account_code) DO UPDATE SET updated_at=now()
  RETURNING id INTO v_account_id;
  IF NOT EXISTS (SELECT 1 FROM public.commercial_ledger_accounts WHERE organization_id=p_organization_id AND id=v_account_id AND currency_code=p_currency_code AND is_active AND account_type=v_type) THEN
    RAISE EXCEPTION 'supplier_ledger_account_code_conflict' USING ERRCODE='23505';
  END IF;
  INSERT INTO public.commercial_supplier_gl_accounts(organization_id,currency_code,account_role,ledger_account_id)
  VALUES(p_organization_id,p_currency_code,p_role,v_account_id);
  RETURN v_account_id;
END;
$function$;

CREATE OR REPLACE FUNCTION commercial_private.commercial_ensure_supplier_party_account(
  p_organization_id uuid, p_supplier_id uuid, p_currency_code text
) RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE v_party_id uuid; v_supplier_name text; v_account_number text;
BEGIN
  SELECT party_account_id INTO v_party_id FROM public.commercial_supplier_party_accounts
  WHERE organization_id=p_organization_id AND supplier_id=p_supplier_id AND currency_code=p_currency_code;
  IF FOUND THEN
    IF NOT EXISTS (SELECT 1 FROM public.commercial_party_accounts WHERE id=v_party_id AND organization_id=p_organization_id AND currency_code=p_currency_code AND status='active') THEN
      RAISE EXCEPTION 'supplier_party_account_invalid' USING ERRCODE='55000';
    END IF;
    RETURN v_party_id;
  END IF;
  SELECT display_name INTO v_supplier_name FROM public.commercial_suppliers
  WHERE organization_id=p_organization_id AND id=p_supplier_id AND state='active';
  IF NOT FOUND THEN RAISE EXCEPTION 'supplier_not_active' USING ERRCODE='22023'; END IF;
  v_account_number := 'SUP-' || replace(p_supplier_id::text,'-','') || '-' || p_currency_code;
  INSERT INTO public.commercial_party_accounts(account_number,account_type,organization_id,display_name,currency_code,status,allow_credit)
  VALUES(v_account_number,'business',p_organization_id,v_supplier_name,p_currency_code,'active',true)
  RETURNING id INTO v_party_id;
  INSERT INTO public.commercial_supplier_party_accounts(organization_id,supplier_id,currency_code,party_account_id)
  VALUES(p_organization_id,p_supplier_id,p_currency_code,v_party_id);
  RETURN v_party_id;
END;
$function$;

CREATE OR REPLACE FUNCTION commercial_private.commercial_create_purchase_order(
  p_organization_id uuid, p_supplier_id uuid, p_order_number text, p_currency_code text,
  p_receive_location_id uuid, p_lines jsonb, p_idempotency_key text, p_expected_at timestamptz DEFAULT NULL,
  p_notes text DEFAULT NULL
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE v_uid uuid:=auth.uid(); v_order_id uuid; v_existing_fingerprint text; v_fingerprint text;
  v_line jsonb; v_offering uuid; v_desc text; v_qty numeric; v_cost bigint; v_line_no integer:=0;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_organization_id IS NULL OR p_supplier_id IS NULL OR p_receive_location_id IS NULL
    OR p_order_number IS NULL OR length(btrim(p_order_number)) NOT BETWEEN 1 AND 80
    OR p_currency_code !~ '^[A-Z]{3}$' OR p_lines IS NULL OR jsonb_typeof(p_lines)<>'array'
    OR jsonb_array_length(p_lines)=0 OR p_idempotency_key IS NULL OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200 THEN
    RAISE EXCEPTION 'invalid_purchase_order_request' USING ERRCODE='22023';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=p_organization_id AND m.user_id=v_uid AND m.state='active' AND m.role IN ('owner','admin','manager')) THEN
    RAISE EXCEPTION 'purchase_management_permission_required' USING ERRCODE='42501';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM public.commercial_suppliers s WHERE s.organization_id=p_organization_id AND s.id=p_supplier_id AND s.state='active') THEN
    RAISE EXCEPTION 'supplier_not_active' USING ERRCODE='22023';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM public.commercial_organization_locations l WHERE l.organization_id=p_organization_id AND l.id=p_receive_location_id AND l.state='active') THEN
    RAISE EXCEPTION 'purchase_receive_location_invalid' USING ERRCODE='22023';
  END IF;
  PERFORM pg_advisory_xact_lock(hashtext(p_organization_id::text),hashtext('purchase-create:'||btrim(p_idempotency_key)));
  v_fingerprint:=md5(jsonb_build_object('supplier_id',p_supplier_id,'order_number',btrim(p_order_number),'currency_code',p_currency_code,'receive_location_id',p_receive_location_id,'lines',p_lines,'expected_at',p_expected_at,'notes',p_notes)::text);
  SELECT id,create_request_fingerprint INTO v_order_id,v_existing_fingerprint FROM public.commercial_purchase_orders
    WHERE organization_id=p_organization_id AND create_idempotency_key=btrim(p_idempotency_key);
  IF FOUND THEN
    IF v_existing_fingerprint IS DISTINCT FROM v_fingerprint THEN RAISE EXCEPTION 'purchase_idempotency_key_reused_with_different_request' USING ERRCODE='23505'; END IF;
    RETURN jsonb_build_object('purchase_order_id',v_order_id,'status',(SELECT status FROM public.commercial_purchase_orders WHERE id=v_order_id),'idempotent_replay',true);
  END IF;
  INSERT INTO public.commercial_purchase_orders(organization_id,supplier_id,order_number,status,currency_code,receive_location_id,expected_at,notes,created_by,create_idempotency_key,create_request_fingerprint)
  VALUES(p_organization_id,p_supplier_id,btrim(p_order_number),'draft',p_currency_code,p_receive_location_id,p_expected_at,p_notes,v_uid,btrim(p_idempotency_key),v_fingerprint)
  RETURNING id INTO v_order_id;
  FOR v_line IN SELECT value FROM jsonb_array_elements(p_lines) LOOP
    v_line_no:=v_line_no+1;
    BEGIN
      v_offering:=(v_line->>'offering_id')::uuid; v_qty:=(v_line->>'quantity_ordered')::numeric;
      v_cost:=(v_line->>'unit_cost_minor')::bigint; v_desc:=btrim(v_line->>'item_description');
    EXCEPTION WHEN others THEN RAISE EXCEPTION 'invalid_purchase_order_line' USING ERRCODE='22023'; END;
    IF v_offering IS NULL OR v_qty IS NULL OR v_qty<=0 OR v_qty<>trunc(v_qty) OR v_qty>9223372036854775807
      OR v_cost IS NULL OR v_cost<0 OR v_desc IS NULL OR length(v_desc) NOT BETWEEN 1 AND 500 THEN
      RAISE EXCEPTION 'invalid_purchase_order_line' USING ERRCODE='22023';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.commercial_offerings o JOIN public.commercial_activities a ON a.id=o.activity_id
      JOIN public.commercial_products p ON p.id=o.product_id
      WHERE o.id=v_offering AND a.organization_id=p_organization_id AND a.state='active' AND o.state='active'
        AND p.state='active' AND upper(o.currency_code)=p_currency_code) THEN
      RAISE EXCEPTION 'purchase_offering_currency_or_scope_mismatch' USING ERRCODE='22023';
    END IF;
    INSERT INTO public.commercial_purchase_order_items(organization_id,purchase_order_id,line_number,item_description,quantity_ordered,unit_cost_minor,offering_id)
    VALUES(p_organization_id,v_order_id,v_line_no,v_desc,v_qty,v_cost,v_offering);
  END LOOP;
  INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
  VALUES(p_organization_id,v_uid,'purchase_order.created','purchase_order',v_order_id::text,jsonb_build_object('supplier_id',p_supplier_id,'order_number',btrim(p_order_number),'line_count',v_line_no));
  RETURN jsonb_build_object('purchase_order_id',v_order_id,'status','draft','idempotent_replay',false);
END;
$function$;

CREATE OR REPLACE FUNCTION commercial_private.commercial_transition_purchase_order(
  p_organization_id uuid, p_purchase_order_id uuid, p_action text
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE v_uid uuid:=auth.uid(); v_status text; v_received numeric; v_invoiced bigint; v_new_status text;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_action NOT IN ('submit','approve','cancel','close') THEN RAISE EXCEPTION 'invalid_purchase_order_action' USING ERRCODE='22023'; END IF;
  IF NOT EXISTS (SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=p_organization_id AND m.user_id=v_uid AND m.state='active' AND m.role IN ('owner','admin','manager')) THEN
    RAISE EXCEPTION 'purchase_management_permission_required' USING ERRCODE='42501';
  END IF;
  SELECT status INTO v_status FROM public.commercial_purchase_orders WHERE id=p_purchase_order_id AND organization_id=p_organization_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'purchase_order_not_found' USING ERRCODE='P0002'; END IF;
  SELECT coalesce(sum(quantity_received),0) INTO v_received FROM public.commercial_purchase_order_items WHERE purchase_order_id=p_purchase_order_id AND organization_id=p_organization_id;
  SELECT count(*) INTO v_invoiced FROM public.commercial_supplier_invoices WHERE purchase_order_id=p_purchase_order_id AND organization_id=p_organization_id;
  v_new_status:=CASE p_action
    WHEN 'submit' THEN CASE WHEN v_status='draft' THEN 'submitted' ELSE NULL END
    WHEN 'approve' THEN CASE WHEN v_status='submitted' THEN 'approved' ELSE NULL END
    WHEN 'cancel' THEN CASE WHEN v_status IN ('draft','submitted','approved') AND v_received=0 AND v_invoiced=0 THEN 'cancelled' ELSE NULL END
    WHEN 'close' THEN CASE WHEN v_status IN ('received','partially_received') AND v_received>0 THEN 'closed' ELSE NULL END END;
  IF v_new_status IS NULL THEN
    IF p_action='cancel' AND v_received>0 THEN RAISE EXCEPTION 'purchase_order_cancel_after_receipt_forbidden' USING ERRCODE='55000'; END IF;
    RAISE EXCEPTION 'invalid_purchase_order_state_transition' USING ERRCODE='55000';
  END IF;
  UPDATE public.commercial_purchase_orders SET status=v_new_status,
    approved_by=CASE WHEN p_action='approve' THEN v_uid ELSE approved_by END,
    approved_at=CASE WHEN p_action='approve' THEN now() ELSE approved_at END,
    cancelled_by=CASE WHEN p_action='cancel' THEN v_uid ELSE cancelled_by END,
    cancelled_at=CASE WHEN p_action='cancel' THEN now() ELSE cancelled_at END,
    updated_at=now()
  WHERE id=p_purchase_order_id AND organization_id=p_organization_id;
  INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
  VALUES(p_organization_id,v_uid,'purchase_order.'||p_action,'purchase_order',p_purchase_order_id::text,jsonb_build_object('from_status',v_status,'to_status',v_new_status,'received_quantity',v_received,'invoice_count',v_invoiced));
  RETURN jsonb_build_object('purchase_order_id',p_purchase_order_id,'status',v_new_status);
END;
$function$;

CREATE OR REPLACE FUNCTION commercial_private.commercial_receive_purchase_order(
  p_organization_id uuid, p_purchase_order_id uuid, p_location_id uuid, p_lines jsonb, p_idempotency_key text
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE v_uid uuid:=auth.uid(); v_status text; v_default_location uuid; v_receipt_id uuid; v_existing_fingerprint text;
  v_fingerprint text; v_line jsonb; v_item_id uuid; v_qty numeric; v_ordered numeric; v_received numeric;
  v_offering uuid; v_cost bigint; v_item_count integer:=0; v_new_status text; v_inventory jsonb; v_inv_receipt uuid;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_organization_id IS NULL OR p_purchase_order_id IS NULL OR p_location_id IS NULL OR p_lines IS NULL
    OR jsonb_typeof(p_lines)<>'array' OR jsonb_array_length(p_lines)=0 OR p_idempotency_key IS NULL
    OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200 THEN RAISE EXCEPTION 'invalid_purchase_receipt_request' USING ERRCODE='22023'; END IF;
  IF NOT EXISTS (SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=p_organization_id AND m.user_id=v_uid AND m.state='active' AND m.role IN ('owner','admin','manager')) THEN
    RAISE EXCEPTION 'purchase_management_permission_required' USING ERRCODE='42501';
  END IF;
  PERFORM pg_advisory_xact_lock(hashtext(p_organization_id::text),hashtext('purchase-receive:'||btrim(p_idempotency_key)));
  v_fingerprint:=md5(jsonb_build_object('purchase_order_id',p_purchase_order_id,'location_id',p_location_id,'lines',p_lines)::text);
  SELECT id,request_fingerprint INTO v_receipt_id,v_existing_fingerprint FROM public.commercial_purchase_receipts
    WHERE organization_id=p_organization_id AND idempotency_key=btrim(p_idempotency_key);
  IF FOUND THEN
    IF v_existing_fingerprint IS DISTINCT FROM v_fingerprint THEN RAISE EXCEPTION 'purchase_receipt_key_reused_with_different_request' USING ERRCODE='23505'; END IF;
    RETURN jsonb_build_object('receipt_id',v_receipt_id,'idempotent_replay',true);
  END IF;
  SELECT status,receive_location_id INTO v_status,v_default_location FROM public.commercial_purchase_orders
    WHERE id=p_purchase_order_id AND organization_id=p_organization_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'purchase_order_not_found' USING ERRCODE='P0002'; END IF;
  IF v_status NOT IN ('approved','partially_received') THEN RAISE EXCEPTION 'purchase_order_not_receivable' USING ERRCODE='55000'; END IF;
  IF p_location_id IS DISTINCT FROM v_default_location OR NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_locations WHERE id=p_location_id AND organization_id=p_organization_id AND state='active'
  ) THEN RAISE EXCEPTION 'purchase_receipt_location_mismatch' USING ERRCODE='22023'; END IF;
  INSERT INTO public.commercial_purchase_receipts(organization_id,purchase_order_id,location_id,idempotency_key,request_fingerprint,received_by)
  VALUES(p_organization_id,p_purchase_order_id,p_location_id,btrim(p_idempotency_key),v_fingerprint,v_uid)
  RETURNING id INTO v_receipt_id;
  FOR v_line IN SELECT value FROM jsonb_array_elements(p_lines) LOOP
    BEGIN v_item_id:=(v_line->>'purchase_order_item_id')::uuid; v_qty:=(v_line->>'quantity')::numeric;
    EXCEPTION WHEN others THEN RAISE EXCEPTION 'invalid_purchase_receipt_line' USING ERRCODE='22023'; END;
    IF v_item_id IS NULL OR v_qty IS NULL OR v_qty<=0 OR v_qty<>trunc(v_qty) OR v_qty>9223372036854775807 THEN RAISE EXCEPTION 'invalid_purchase_receipt_line' USING ERRCODE='22023'; END IF;
    IF EXISTS (SELECT 1 FROM public.commercial_purchase_receipt_items WHERE receipt_id=v_receipt_id AND purchase_order_item_id=v_item_id) THEN RAISE EXCEPTION 'duplicate_purchase_receipt_line' USING ERRCODE='22023'; END IF;
    SELECT quantity_ordered,quantity_received,offering_id,unit_cost_minor INTO v_ordered,v_received,v_offering,v_cost
      FROM public.commercial_purchase_order_items WHERE id=v_item_id AND organization_id=p_organization_id AND purchase_order_id=p_purchase_order_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'purchase_receipt_line_not_in_order' USING ERRCODE='22023'; END IF;
    IF v_offering IS NULL THEN RAISE EXCEPTION 'purchase_line_missing_inventory_offering' USING ERRCODE='55000'; END IF;
    IF v_received+v_qty>v_ordered THEN RAISE EXCEPTION 'purchase_receipt_exceeds_remaining_quantity' USING ERRCODE='22023'; END IF;
    INSERT INTO public.commercial_inventory_balances(organization_id,location_id,offering_id,quantity_on_hand,quantity_reserved,reorder_point,unit_cost_minor)
    VALUES(p_organization_id,p_location_id,v_offering,0,0,0,NULL) ON CONFLICT (organization_id,location_id,offering_id) DO NOTHING;
    v_inventory:=public.commercial_receive_inventory_stock(p_organization_id,p_location_id,v_offering,v_qty::bigint,v_cost,
      'purchase-receipt:'||v_receipt_id::text||':'||v_item_id::text);
    v_inv_receipt:=(v_inventory->>'receipt_id')::uuid;
    UPDATE public.commercial_purchase_order_items SET quantity_received=quantity_received+v_qty,updated_at=now()
      WHERE id=v_item_id AND purchase_order_id=p_purchase_order_id AND organization_id=p_organization_id;
    INSERT INTO public.commercial_purchase_receipt_items(organization_id,receipt_id,purchase_order_id,purchase_order_item_id,offering_id,quantity,unit_cost_minor,inventory_receipt_id)
    VALUES(p_organization_id,v_receipt_id,p_purchase_order_id,v_item_id,v_offering,v_qty::bigint,v_cost,v_inv_receipt);
    v_item_count:=v_item_count+1;
  END LOOP;
  SELECT CASE WHEN bool_and(quantity_received=quantity_ordered) THEN 'received' ELSE 'partially_received' END INTO v_new_status
    FROM public.commercial_purchase_order_items WHERE purchase_order_id=p_purchase_order_id AND organization_id=p_organization_id;
  UPDATE public.commercial_purchase_orders SET status=v_new_status,updated_at=now()
    WHERE id=p_purchase_order_id AND organization_id=p_organization_id;
  INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
  VALUES(p_organization_id,v_uid,'purchase_order.received','purchase_receipt',v_receipt_id::text,jsonb_build_object('purchase_order_id',p_purchase_order_id,'location_id',p_location_id,'line_count',v_item_count,'new_order_status',v_new_status));
  RETURN jsonb_build_object('receipt_id',v_receipt_id,'purchase_order_id',p_purchase_order_id,'status',v_new_status,'line_count',v_item_count,'idempotent_replay',false);
END;
$function$;

CREATE OR REPLACE FUNCTION commercial_private.commercial_post_supplier_invoice_financials()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE v_inventory_account uuid; v_payable_account uuid; v_party_account uuid; v_entry_id uuid; v_entry_number bigint;
BEGIN
  v_inventory_account:=commercial_private.commercial_ensure_supplier_ledger_account(NEW.organization_id,NEW.currency_code,'inventory_asset');
  v_payable_account:=commercial_private.commercial_ensure_supplier_ledger_account(NEW.organization_id,NEW.currency_code,'accounts_payable');
  v_party_account:=commercial_private.commercial_ensure_supplier_party_account(NEW.organization_id,NEW.supplier_id,NEW.currency_code);
  v_entry_number:=nextval('public.commercial_supplier_journal_entry_number_seq');
  INSERT INTO public.commercial_journal_entries(organization_id,entry_number,entry_date,description,source_type,source_id,status,idempotency_key,created_by,posted_by,posted_at,metadata)
  VALUES(NEW.organization_id,v_entry_number,NEW.recorded_at::date,'إثبات فاتورة المورد '||NEW.invoice_number,'supplier_invoice',NEW.id,'draft',
    'supplier-invoice:'||NEW.id::text,NEW.recorded_by,NULL,NULL,
    jsonb_build_object('supplier_id',NEW.supplier_id,'purchase_order_id',NEW.purchase_order_id,'currency_code',NEW.currency_code,'total_minor',NEW.total_minor))
  RETURNING id INTO v_entry_id;
  INSERT INTO public.commercial_journal_lines(organization_id,journal_entry_id,line_number,ledger_account_id,description,debit_minor,credit_minor,currency_code,party_account_id)
  VALUES(NEW.organization_id,v_entry_id,1,v_inventory_account,'إثبات تكلفة فاتورة المورد',NEW.total_minor,0,NEW.currency_code,NULL),
        (NEW.organization_id,v_entry_id,2,v_payable_account,'ذمم مستحقة للمورد',0,NEW.total_minor,NEW.currency_code,v_party_account);
  UPDATE public.commercial_journal_entries SET status='posted',posted_by=NEW.recorded_by,posted_at=NEW.recorded_at WHERE id=v_entry_id AND organization_id=NEW.organization_id;
  END IF;
  INSERT INTO public.commercial_supplier_obligations(organization_id,supplier_invoice_id,supplier_id,party_account_id,journal_entry_id,currency_code,amount_due_minor,amount_paid_minor,state)
  VALUES(NEW.organization_id,NEW.id,NEW.supplier_id,v_party_account,v_entry_id,NEW.currency_code,NEW.total_minor,CASE WHEN NEW.total_minor=0 THEN 0 ELSE 0 END,CASE WHEN NEW.total_minor=0 THEN 'paid' ELSE 'open' END);
  RETURN NEW;
END;
$function$;

DROP TRIGGER IF EXISTS commercial_supplier_invoice_financials_after_insert ON public.commercial_supplier_invoices;
CREATE TRIGGER commercial_supplier_invoice_financials_after_insert
AFTER INSERT ON public.commercial_supplier_invoices
FOR EACH ROW EXECUTE FUNCTION commercial_private.commercial_post_supplier_invoice_financials();

CREATE OR REPLACE FUNCTION commercial_private.commercial_record_supplier_payment(
  p_organization_id uuid,p_obligation_id uuid,p_amount_minor bigint,p_currency_code text,
  p_payment_method text,p_evidence_reference text,p_idempotency_key text
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE v_uid uuid:=auth.uid(); v_ob public.commercial_supplier_obligations%ROWTYPE; v_existing public.commercial_supplier_payments%ROWTYPE;
  v_fingerprint text; v_cash_account uuid; v_payable_account uuid; v_entry_id uuid; v_entry_number bigint; v_paid numeric;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_organization_id IS NULL OR p_obligation_id IS NULL OR p_amount_minor IS NULL OR p_amount_minor<=0
    OR p_currency_code !~ '^[A-Z]{3}$' OR p_payment_method NOT IN ('cash','bank_transfer','check','other')
    OR p_evidence_reference IS NULL OR length(btrim(p_evidence_reference)) NOT BETWEEN 3 AND 200
    OR p_idempotency_key IS NULL OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200 THEN
    RAISE EXCEPTION 'invalid_supplier_payment_request' USING ERRCODE='22023';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=p_organization_id AND m.user_id=v_uid AND m.state='active' AND m.role IN ('owner','admin','manager')) THEN
    RAISE EXCEPTION 'supplier_payment_permission_required' USING ERRCODE='42501';
  END IF;
  PERFORM pg_advisory_xact_lock(hashtext(p_organization_id::text),hashtext('supplier-payment:'||btrim(p_idempotency_key)));
  v_fingerprint:=md5(jsonb_build_object('obligation_id',p_obligation_id,'amount_minor',p_amount_minor,'currency_code',p_currency_code,'payment_method',p_payment_method,'evidence_reference',btrim(p_evidence_reference))::text);
  SELECT * INTO v_existing FROM public.commercial_supplier_payments WHERE organization_id=p_organization_id AND idempotency_key=btrim(p_idempotency_key);
  IF FOUND THEN
    IF v_existing.request_fingerprint IS DISTINCT FROM v_fingerprint THEN RAISE EXCEPTION 'supplier_payment_key_reused_with_different_request' USING ERRCODE='23505'; END IF;
    RETURN jsonb_build_object('payment_id',v_existing.id,'journal_entry_id',v_existing.journal_entry_id,'idempotent_replay',true);
  END IF;
  SELECT * INTO v_ob FROM public.commercial_supplier_obligations WHERE id=p_obligation_id AND organization_id=p_organization_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'supplier_obligation_not_found' USING ERRCODE='P0002'; END IF;
  IF v_ob.currency_code<>p_currency_code THEN RAISE EXCEPTION 'supplier_payment_currency_mismatch' USING ERRCODE='22023'; END IF;
  IF p_amount_minor>v_ob.amount_due_minor-v_ob.amount_paid_minor THEN RAISE EXCEPTION 'supplier_payment_exceeds_outstanding_balance' USING ERRCODE='22023'; END IF;
  SELECT ledger_account_id INTO v_payable_account FROM public.commercial_supplier_gl_accounts
    WHERE organization_id=p_organization_id AND currency_code=p_currency_code AND account_role='accounts_payable';
  SELECT m.ledger_account_id INTO v_cash_account FROM public.commercial_gl_account_mappings m
    JOIN public.commercial_ledger_accounts a ON a.organization_id=m.organization_id AND a.id=m.ledger_account_id
    WHERE m.organization_id=p_organization_id AND m.mapping_key='cash' AND a.currency_code=p_currency_code AND a.is_active;
  IF v_payable_account IS NULL OR v_cash_account IS NULL THEN RAISE EXCEPTION 'supplier_payment_cash_account_not_configured_for_currency' USING ERRCODE='55000'; END IF;
  v_entry_number:=nextval('public.commercial_supplier_journal_entry_number_seq');
  INSERT INTO public.commercial_journal_entries(organization_id,entry_number,entry_date,description,source_type,source_id,status,idempotency_key,created_by,posted_by,posted_at,metadata)
  VALUES(p_organization_id,v_entry_number,current_date,'سداد فاتورة مورد','supplier_payment',p_ob.supplier_invoice_id,'draft',
    'supplier-payment:'||btrim(p_idempotency_key),v_uid,NULL,NULL,
    jsonb_build_object('obligation_id',p_obligation_id,'payment_method',p_payment_method,'evidence_reference',btrim(p_evidence_reference),'amount_minor',p_amount_minor,'currency_code',p_currency_code))
  RETURNING id INTO v_entry_id;
  INSERT INTO public.commercial_journal_lines(organization_id,journal_entry_id,line_number,ledger_account_id,description,debit_minor,credit_minor,currency_code,party_account_id)
  VALUES(p_organization_id,v_entry_id,1,v_payable_account,'تخفيض ذمم المورد',p_amount_minor,0,p_currency_code,v_ob.party_account_id),
        (p_organization_id,v_entry_id,2,v_cash_account,'سداد للمورد',0,p_amount_minor,p_currency_code,NULL);
  UPDATE public.commercial_journal_entries SET status='posted',posted_by=v_uid,posted_at=now() WHERE id=v_entry_id AND organization_id=p_organization_id;
  INSERT INTO public.commercial_supplier_payments(organization_id,obligation_id,supplier_invoice_id,amount_minor,currency_code,payment_method,evidence_reference,idempotency_key,request_fingerprint,journal_entry_id,recorded_by)
  VALUES(p_organization_id,p_obligation_id,v_ob.supplier_invoice_id,p_amount_minor,p_currency_code,p_payment_method,btrim(p_evidence_reference),btrim(p_idempotency_key),v_fingerprint,v_entry_id,v_uid)
  RETURNING id INTO v_existing.id;
  v_paid:=v_ob.amount_paid_minor+p_amount_minor;
  UPDATE public.commercial_supplier_obligations SET amount_paid_minor=v_paid,state=CASE WHEN v_paid=amount_due_minor THEN 'paid' ELSE 'partially_paid' END,updated_at=now()
  WHERE id=p_obligation_id AND organization_id=p_organization_id;
  INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
  VALUES(p_organization_id,v_uid,'supplier_payment.recorded','supplier_payment',v_existing.id::text,jsonb_build_object('obligation_id',p_obligation_id,'amount_minor',p_amount_minor,'currency_code',p_currency_code,'journal_entry_id',v_entry_id));
  RETURN jsonb_build_object('payment_id',v_existing.id,'journal_entry_id',v_entry_id,'outstanding_minor',v_ob.amount_due_minor-v_paid,'idempotent_replay',false);
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_create_purchase_order(
  p_organization_id uuid,p_supplier_id uuid,p_order_number text,p_currency_code text,
  p_receive_location_id uuid,p_lines jsonb,p_idempotency_key text,p_expected_at timestamptz DEFAULT NULL,p_notes text DEFAULT NULL
) RETURNS jsonb LANGUAGE sql SECURITY DEFINER SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$ SELECT commercial_private.commercial_create_purchase_order($1,$2,$3,$4,$5,$6,$7,$8,$9) $function$;

CREATE OR REPLACE FUNCTION public.commercial_transition_purchase_order(p_organization_id uuid,p_purchase_order_id uuid,p_action text)
RETURNS jsonb LANGUAGE sql SECURITY DEFINER SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$ SELECT commercial_private.commercial_transition_purchase_order($1,$2,$3) $function$;

CREATE OR REPLACE FUNCTION public.commercial_receive_purchase_order(p_organization_id uuid,p_purchase_order_id uuid,p_location_id uuid,p_lines jsonb,p_idempotency_key text)
RETURNS jsonb LANGUAGE sql SECURITY DEFINER SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$ SELECT commercial_private.commercial_receive_purchase_order($1,$2,$3,$4,$5) $function$;

CREATE OR REPLACE FUNCTION public.commercial_record_supplier_payment(p_organization_id uuid,p_obligation_id uuid,p_amount_minor bigint,p_currency_code text,p_payment_method text,p_evidence_reference text,p_idempotency_key text)
RETURNS jsonb LANGUAGE sql SECURITY DEFINER SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$ SELECT commercial_private.commercial_record_supplier_payment($1,$2,$3,$4,$5,$6,$7) $function$;

REVOKE ALL ON FUNCTION commercial_private.commercial_ensure_supplier_ledger_account(uuid,text,text) FROM PUBLIC,anon,authenticated;
REVOKE ALL ON FUNCTION commercial_private.commercial_ensure_supplier_party_account(uuid,uuid,text) FROM PUBLIC,anon,authenticated;
REVOKE ALL ON FUNCTION commercial_private.commercial_create_purchase_order(uuid,uuid,text,text,uuid,jsonb,text,timestamptz,text) FROM PUBLIC,anon,authenticated;
REVOKE ALL ON FUNCTION commercial_private.commercial_transition_purchase_order(uuid,uuid,text) FROM PUBLIC,anon,authenticated;
REVOKE ALL ON FUNCTION commercial_private.commercial_receive_purchase_order(uuid,uuid,uuid,jsonb,text) FROM PUBLIC,anon,authenticated;
REVOKE ALL ON FUNCTION commercial_private.commercial_record_supplier_payment(uuid,uuid,bigint,text,text,text,text) FROM PUBLIC,anon,authenticated;
REVOKE ALL ON FUNCTION public.commercial_create_purchase_order(uuid,uuid,text,text,uuid,jsonb,text,timestamptz,text) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_transition_purchase_order(uuid,uuid,text) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_receive_purchase_order(uuid,uuid,uuid,jsonb,text) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_record_supplier_payment(uuid,uuid,bigint,text,text,text,text) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_create_purchase_order(uuid,uuid,text,text,uuid,jsonb,text,timestamptz,text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_transition_purchase_order(uuid,uuid,text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_receive_purchase_order(uuid,uuid,uuid,jsonb,text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_record_supplier_payment(uuid,uuid,bigint,text,text,text,text) TO authenticated;
),
  amount_due_minor bigint NOT NULL CHECK (amount_due_minor >= 0),
  amount_paid_minor bigint NOT NULL DEFAULT 0 CHECK (amount_paid_minor >= 0 AND amount_paid_minor <= amount_due_minor),
  state text NOT NULL DEFAULT 'open' CHECK (state IN ('open','partially_paid','paid')),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (organization_id, supplier_id)
    REFERENCES public.commercial_suppliers(organization_id, id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS public.commercial_supplier_payments (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
  obligation_id uuid NOT NULL REFERENCES public.commercial_supplier_obligations(id) ON DELETE RESTRICT,
  supplier_invoice_id uuid NOT NULL REFERENCES public.commercial_supplier_invoices(id) ON DELETE RESTRICT,
  amount_minor bigint NOT NULL CHECK (amount_minor > 0),
  currency_code text NOT NULL CHECK (currency_code ~ '^[A-Z]{3}$'),
  payment_method text NOT NULL CHECK (payment_method IN ('cash','bank_transfer','check','other')),
  evidence_reference text NOT NULL CHECK (length(btrim(evidence_reference)) BETWEEN 3 AND 200),
  idempotency_key text NOT NULL CHECK (length(btrim(idempotency_key)) BETWEEN 8 AND 200),
  request_fingerprint text NOT NULL,
  journal_entry_id uuid NOT NULL REFERENCES public.commercial_journal_entries(id) ON DELETE RESTRICT,
  recorded_by uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
  recorded_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (organization_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS commercial_supplier_obligations_supplier_idx
  ON public.commercial_supplier_obligations(organization_id, supplier_id, state, created_at DESC);
CREATE INDEX IF NOT EXISTS commercial_supplier_payments_obligation_idx
  ON public.commercial_supplier_payments(organization_id, obligation_id, recorded_at DESC);

ALTER TABLE public.commercial_purchase_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.commercial_purchase_receipt_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.commercial_supplier_gl_accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.commercial_supplier_party_accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.commercial_supplier_obligations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.commercial_supplier_payments ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS commercial_purchase_receipts_org_read ON public.commercial_purchase_receipts;
CREATE POLICY commercial_purchase_receipts_org_read ON public.commercial_purchase_receipts FOR SELECT TO authenticated
USING (EXISTS (SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=commercial_purchase_receipts.organization_id AND m.user_id=auth.uid() AND m.state='active'));
DROP POLICY IF EXISTS commercial_purchase_receipt_items_org_read ON public.commercial_purchase_receipt_items;
CREATE POLICY commercial_purchase_receipt_items_org_read ON public.commercial_purchase_receipt_items FOR SELECT TO authenticated
USING (EXISTS (SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=commercial_purchase_receipt_items.organization_id AND m.user_id=auth.uid() AND m.state='active'));
DROP POLICY IF EXISTS commercial_supplier_obligations_org_read ON public.commercial_supplier_obligations;
CREATE POLICY commercial_supplier_obligations_org_read ON public.commercial_supplier_obligations FOR SELECT TO authenticated
USING (EXISTS (SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=commercial_supplier_obligations.organization_id AND m.user_id=auth.uid() AND m.state='active'));
DROP POLICY IF EXISTS commercial_supplier_payments_org_read ON public.commercial_supplier_payments;
CREATE POLICY commercial_supplier_payments_org_read ON public.commercial_supplier_payments FOR SELECT TO authenticated
USING (EXISTS (SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=commercial_supplier_payments.organization_id AND m.user_id=auth.uid() AND m.state='active'));
DROP POLICY IF EXISTS commercial_supplier_gl_accounts_org_read ON public.commercial_supplier_gl_accounts;
CREATE POLICY commercial_supplier_gl_accounts_org_read ON public.commercial_supplier_gl_accounts FOR SELECT TO authenticated
USING (EXISTS (SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=commercial_supplier_gl_accounts.organization_id AND m.user_id=auth.uid() AND m.state='active'));
DROP POLICY IF EXISTS commercial_supplier_party_accounts_org_read ON public.commercial_supplier_party_accounts;
CREATE POLICY commercial_supplier_party_accounts_org_read ON public.commercial_supplier_party_accounts FOR SELECT TO authenticated
USING (EXISTS (SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=commercial_supplier_party_accounts.organization_id AND m.user_id=auth.uid() AND m.state='active'));

REVOKE ALL ON public.commercial_purchase_receipts, public.commercial_purchase_receipt_items,
  public.commercial_supplier_gl_accounts, public.commercial_supplier_party_accounts,
  public.commercial_supplier_obligations, public.commercial_supplier_payments FROM anon, authenticated;
GRANT SELECT ON public.commercial_purchase_receipts, public.commercial_purchase_receipt_items,
  public.commercial_supplier_gl_accounts, public.commercial_supplier_party_accounts,
  public.commercial_supplier_obligations, public.commercial_supplier_payments TO authenticated;

CREATE SEQUENCE IF NOT EXISTS public.commercial_supplier_journal_entry_number_seq;
SELECT setval('public.commercial_supplier_journal_entry_number_seq',
  GREATEST(COALESCE((SELECT max(entry_number) FROM public.commercial_journal_entries),0)+1,1), false);

CREATE OR REPLACE FUNCTION commercial_private.commercial_ensure_supplier_ledger_account(
  p_organization_id uuid, p_currency_code text, p_role text
) RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE v_account_id uuid; v_code text; v_name text; v_type text; v_balance text;
BEGIN
  SELECT ledger_account_id INTO v_account_id FROM public.commercial_supplier_gl_accounts
   WHERE organization_id=p_organization_id AND currency_code=p_currency_code AND account_role=p_role;
  IF FOUND THEN
    IF NOT EXISTS (SELECT 1 FROM public.commercial_ledger_accounts WHERE organization_id=p_organization_id AND id=v_account_id AND currency_code=p_currency_code AND is_active) THEN
      RAISE EXCEPTION 'supplier_ledger_account_invalid' USING ERRCODE='55000';
    END IF;
    RETURN v_account_id;
  END IF;
  v_code := CASE WHEN p_role='inventory_asset' THEN 'SUPINV_' ELSE 'SUPAP_' END || p_currency_code;
  v_name := CASE WHEN p_role='inventory_asset' THEN 'مخزون مشتريات الموردين - ' ELSE 'ذمم الموردين - ' END || p_currency_code;
  v_type := CASE WHEN p_role='inventory_asset' THEN 'asset' ELSE 'liability' END;
  v_balance := CASE WHEN p_role='inventory_asset' THEN 'debit' ELSE 'credit' END;
  INSERT INTO public.commercial_ledger_accounts(organization_id,account_code,account_name,account_type,normal_balance,currency_code,is_system,is_active)
  VALUES(p_organization_id,v_code,v_name,v_type,v_balance,p_currency_code,true,true)
  ON CONFLICT (organization_id,account_code) DO UPDATE SET updated_at=now()
  RETURNING id INTO v_account_id;
  IF NOT EXISTS (SELECT 1 FROM public.commercial_ledger_accounts WHERE organization_id=p_organization_id AND id=v_account_id AND currency_code=p_currency_code AND is_active AND account_type=v_type) THEN
    RAISE EXCEPTION 'supplier_ledger_account_code_conflict' USING ERRCODE='23505';
  END IF;
  INSERT INTO public.commercial_supplier_gl_accounts(organization_id,currency_code,account_role,ledger_account_id)
  VALUES(p_organization_id,p_currency_code,p_role,v_account_id);
  RETURN v_account_id;
END;
$function$;

CREATE OR REPLACE FUNCTION commercial_private.commercial_ensure_supplier_party_account(
  p_organization_id uuid, p_supplier_id uuid, p_currency_code text
) RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE v_party_id uuid; v_supplier_name text; v_account_number text;
BEGIN
  SELECT party_account_id INTO v_party_id FROM public.commercial_supplier_party_accounts
  WHERE organization_id=p_organization_id AND supplier_id=p_supplier_id AND currency_code=p_currency_code;
  IF FOUND THEN
    IF NOT EXISTS (SELECT 1 FROM public.commercial_party_accounts WHERE id=v_party_id AND organization_id=p_organization_id AND currency_code=p_currency_code AND status='active') THEN
      RAISE EXCEPTION 'supplier_party_account_invalid' USING ERRCODE='55000';
    END IF;
    RETURN v_party_id;
  END IF;
  SELECT display_name INTO v_supplier_name FROM public.commercial_suppliers
  WHERE organization_id=p_organization_id AND id=p_supplier_id AND state='active';
  IF NOT FOUND THEN RAISE EXCEPTION 'supplier_not_active' USING ERRCODE='22023'; END IF;
  v_account_number := 'SUP-' || replace(p_supplier_id::text,'-','') || '-' || p_currency_code;
  INSERT INTO public.commercial_party_accounts(account_number,account_type,organization_id,display_name,currency_code,status,allow_credit)
  VALUES(v_account_number,'business',p_organization_id,v_supplier_name,p_currency_code,'active',true)
  RETURNING id INTO v_party_id;
  INSERT INTO public.commercial_supplier_party_accounts(organization_id,supplier_id,currency_code,party_account_id)
  VALUES(p_organization_id,p_supplier_id,p_currency_code,v_party_id);
  RETURN v_party_id;
END;
$function$;

CREATE OR REPLACE FUNCTION commercial_private.commercial_create_purchase_order(
  p_organization_id uuid, p_supplier_id uuid, p_order_number text, p_currency_code text,
  p_receive_location_id uuid, p_lines jsonb, p_idempotency_key text, p_expected_at timestamptz DEFAULT NULL,
  p_notes text DEFAULT NULL
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE v_uid uuid:=auth.uid(); v_order_id uuid; v_existing_fingerprint text; v_fingerprint text;
  v_line jsonb; v_offering uuid; v_desc text; v_qty numeric; v_cost bigint; v_line_no integer:=0;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_organization_id IS NULL OR p_supplier_id IS NULL OR p_receive_location_id IS NULL
    OR p_order_number IS NULL OR length(btrim(p_order_number)) NOT BETWEEN 1 AND 80
    OR p_currency_code !~ '^[A-Z]{3}$' OR p_lines IS NULL OR jsonb_typeof(p_lines)<>'array'
    OR jsonb_array_length(p_lines)=0 OR p_idempotency_key IS NULL OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200 THEN
    RAISE EXCEPTION 'invalid_purchase_order_request' USING ERRCODE='22023';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=p_organization_id AND m.user_id=v_uid AND m.state='active' AND m.role IN ('owner','admin','manager')) THEN
    RAISE EXCEPTION 'purchase_management_permission_required' USING ERRCODE='42501';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM public.commercial_suppliers s WHERE s.organization_id=p_organization_id AND s.id=p_supplier_id AND s.state='active') THEN
    RAISE EXCEPTION 'supplier_not_active' USING ERRCODE='22023';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM public.commercial_organization_locations l WHERE l.organization_id=p_organization_id AND l.id=p_receive_location_id AND l.state='active') THEN
    RAISE EXCEPTION 'purchase_receive_location_invalid' USING ERRCODE='22023';
  END IF;
  PERFORM pg_advisory_xact_lock(hashtext(p_organization_id::text),hashtext('purchase-create:'||btrim(p_idempotency_key)));
  v_fingerprint:=md5(jsonb_build_object('supplier_id',p_supplier_id,'order_number',btrim(p_order_number),'currency_code',p_currency_code,'receive_location_id',p_receive_location_id,'lines',p_lines,'expected_at',p_expected_at,'notes',p_notes)::text);
  SELECT id,create_request_fingerprint INTO v_order_id,v_existing_fingerprint FROM public.commercial_purchase_orders
    WHERE organization_id=p_organization_id AND create_idempotency_key=btrim(p_idempotency_key);
  IF FOUND THEN
    IF v_existing_fingerprint IS DISTINCT FROM v_fingerprint THEN RAISE EXCEPTION 'purchase_idempotency_key_reused_with_different_request' USING ERRCODE='23505'; END IF;
    RETURN jsonb_build_object('purchase_order_id',v_order_id,'status',(SELECT status FROM public.commercial_purchase_orders WHERE id=v_order_id),'idempotent_replay',true);
  END IF;
  INSERT INTO public.commercial_purchase_orders(organization_id,supplier_id,order_number,status,currency_code,receive_location_id,expected_at,notes,created_by,create_idempotency_key,create_request_fingerprint)
  VALUES(p_organization_id,p_supplier_id,btrim(p_order_number),'draft',p_currency_code,p_receive_location_id,p_expected_at,p_notes,v_uid,btrim(p_idempotency_key),v_fingerprint)
  RETURNING id INTO v_order_id;
  FOR v_line IN SELECT value FROM jsonb_array_elements(p_lines) LOOP
    v_line_no:=v_line_no+1;
    BEGIN
      v_offering:=(v_line->>'offering_id')::uuid; v_qty:=(v_line->>'quantity_ordered')::numeric;
      v_cost:=(v_line->>'unit_cost_minor')::bigint; v_desc:=btrim(v_line->>'item_description');
    EXCEPTION WHEN others THEN RAISE EXCEPTION 'invalid_purchase_order_line' USING ERRCODE='22023'; END;
    IF v_offering IS NULL OR v_qty IS NULL OR v_qty<=0 OR v_qty<>trunc(v_qty) OR v_qty>9223372036854775807
      OR v_cost IS NULL OR v_cost<0 OR v_desc IS NULL OR length(v_desc) NOT BETWEEN 1 AND 500 THEN
      RAISE EXCEPTION 'invalid_purchase_order_line' USING ERRCODE='22023';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.commercial_offerings o JOIN public.commercial_activities a ON a.id=o.activity_id
      JOIN public.commercial_products p ON p.id=o.product_id
      WHERE o.id=v_offering AND a.organization_id=p_organization_id AND a.state='active' AND o.state='active'
        AND p.state='active' AND upper(o.currency_code)=p_currency_code) THEN
      RAISE EXCEPTION 'purchase_offering_currency_or_scope_mismatch' USING ERRCODE='22023';
    END IF;
    INSERT INTO public.commercial_purchase_order_items(organization_id,purchase_order_id,line_number,item_description,quantity_ordered,unit_cost_minor,offering_id)
    VALUES(p_organization_id,v_order_id,v_line_no,v_desc,v_qty,v_cost,v_offering);
  END LOOP;
  INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
  VALUES(p_organization_id,v_uid,'purchase_order.created','purchase_order',v_order_id::text,jsonb_build_object('supplier_id',p_supplier_id,'order_number',btrim(p_order_number),'line_count',v_line_no));
  RETURN jsonb_build_object('purchase_order_id',v_order_id,'status','draft','idempotent_replay',false);
END;
$function$;

CREATE OR REPLACE FUNCTION commercial_private.commercial_transition_purchase_order(
  p_organization_id uuid, p_purchase_order_id uuid, p_action text
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE v_uid uuid:=auth.uid(); v_status text; v_received numeric; v_invoiced bigint; v_new_status text;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_action NOT IN ('submit','approve','cancel','close') THEN RAISE EXCEPTION 'invalid_purchase_order_action' USING ERRCODE='22023'; END IF;
  IF NOT EXISTS (SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=p_organization_id AND m.user_id=v_uid AND m.state='active' AND m.role IN ('owner','admin','manager')) THEN
    RAISE EXCEPTION 'purchase_management_permission_required' USING ERRCODE='42501';
  END IF;
  SELECT status INTO v_status FROM public.commercial_purchase_orders WHERE id=p_purchase_order_id AND organization_id=p_organization_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'purchase_order_not_found' USING ERRCODE='P0002'; END IF;
  SELECT coalesce(sum(quantity_received),0) INTO v_received FROM public.commercial_purchase_order_items WHERE purchase_order_id=p_purchase_order_id AND organization_id=p_organization_id;
  SELECT count(*) INTO v_invoiced FROM public.commercial_supplier_invoices WHERE purchase_order_id=p_purchase_order_id AND organization_id=p_organization_id;
  v_new_status:=CASE p_action
    WHEN 'submit' THEN CASE WHEN v_status='draft' THEN 'submitted' ELSE NULL END
    WHEN 'approve' THEN CASE WHEN v_status='submitted' THEN 'approved' ELSE NULL END
    WHEN 'cancel' THEN CASE WHEN v_status IN ('draft','submitted','approved') AND v_received=0 AND v_invoiced=0 THEN 'cancelled' ELSE NULL END
    WHEN 'close' THEN CASE WHEN v_status IN ('received','partially_received') AND v_received>0 THEN 'closed' ELSE NULL END END;
  IF v_new_status IS NULL THEN
    IF p_action='cancel' AND v_received>0 THEN RAISE EXCEPTION 'purchase_order_cancel_after_receipt_forbidden' USING ERRCODE='55000'; END IF;
    RAISE EXCEPTION 'invalid_purchase_order_state_transition' USING ERRCODE='55000';
  END IF;
  UPDATE public.commercial_purchase_orders SET status=v_new_status,
    approved_by=CASE WHEN p_action='approve' THEN v_uid ELSE approved_by END,
    approved_at=CASE WHEN p_action='approve' THEN now() ELSE approved_at END,
    cancelled_by=CASE WHEN p_action='cancel' THEN v_uid ELSE cancelled_by END,
    cancelled_at=CASE WHEN p_action='cancel' THEN now() ELSE cancelled_at END,
    updated_at=now()
  WHERE id=p_purchase_order_id AND organization_id=p_organization_id;
  INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
  VALUES(p_organization_id,v_uid,'purchase_order.'||p_action,'purchase_order',p_purchase_order_id::text,jsonb_build_object('from_status',v_status,'to_status',v_new_status,'received_quantity',v_received,'invoice_count',v_invoiced));
  RETURN jsonb_build_object('purchase_order_id',p_purchase_order_id,'status',v_new_status);
END;
$function$;

CREATE OR REPLACE FUNCTION commercial_private.commercial_receive_purchase_order(
  p_organization_id uuid, p_purchase_order_id uuid, p_location_id uuid, p_lines jsonb, p_idempotency_key text
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE v_uid uuid:=auth.uid(); v_status text; v_default_location uuid; v_receipt_id uuid; v_existing_fingerprint text;
  v_fingerprint text; v_line jsonb; v_item_id uuid; v_qty numeric; v_ordered numeric; v_received numeric;
  v_offering uuid; v_cost bigint; v_item_count integer:=0; v_new_status text; v_inventory jsonb; v_inv_receipt uuid;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_organization_id IS NULL OR p_purchase_order_id IS NULL OR p_location_id IS NULL OR p_lines IS NULL
    OR jsonb_typeof(p_lines)<>'array' OR jsonb_array_length(p_lines)=0 OR p_idempotency_key IS NULL
    OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200 THEN RAISE EXCEPTION 'invalid_purchase_receipt_request' USING ERRCODE='22023'; END IF;
  IF NOT EXISTS (SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=p_organization_id AND m.user_id=v_uid AND m.state='active' AND m.role IN ('owner','admin','manager')) THEN
    RAISE EXCEPTION 'purchase_management_permission_required' USING ERRCODE='42501';
  END IF;
  PERFORM pg_advisory_xact_lock(hashtext(p_organization_id::text),hashtext('purchase-receive:'||btrim(p_idempotency_key)));
  v_fingerprint:=md5(jsonb_build_object('purchase_order_id',p_purchase_order_id,'location_id',p_location_id,'lines',p_lines)::text);
  SELECT id,request_fingerprint INTO v_receipt_id,v_existing_fingerprint FROM public.commercial_purchase_receipts
    WHERE organization_id=p_organization_id AND idempotency_key=btrim(p_idempotency_key);
  IF FOUND THEN
    IF v_existing_fingerprint IS DISTINCT FROM v_fingerprint THEN RAISE EXCEPTION 'purchase_receipt_key_reused_with_different_request' USING ERRCODE='23505'; END IF;
    RETURN jsonb_build_object('receipt_id',v_receipt_id,'idempotent_replay',true);
  END IF;
  SELECT status,receive_location_id INTO v_status,v_default_location FROM public.commercial_purchase_orders
    WHERE id=p_purchase_order_id AND organization_id=p_organization_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'purchase_order_not_found' USING ERRCODE='P0002'; END IF;
  IF v_status NOT IN ('approved','partially_received') THEN RAISE EXCEPTION 'purchase_order_not_receivable' USING ERRCODE='55000'; END IF;
  IF p_location_id IS DISTINCT FROM v_default_location OR NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_locations WHERE id=p_location_id AND organization_id=p_organization_id AND state='active'
  ) THEN RAISE EXCEPTION 'purchase_receipt_location_mismatch' USING ERRCODE='22023'; END IF;
  INSERT INTO public.commercial_purchase_receipts(organization_id,purchase_order_id,location_id,idempotency_key,request_fingerprint,received_by)
  VALUES(p_organization_id,p_purchase_order_id,p_location_id,btrim(p_idempotency_key),v_fingerprint,v_uid)
  RETURNING id INTO v_receipt_id;
  FOR v_line IN SELECT value FROM jsonb_array_elements(p_lines) LOOP
    BEGIN v_item_id:=(v_line->>'purchase_order_item_id')::uuid; v_qty:=(v_line->>'quantity')::numeric;
    EXCEPTION WHEN others THEN RAISE EXCEPTION 'invalid_purchase_receipt_line' USING ERRCODE='22023'; END;
    IF v_item_id IS NULL OR v_qty IS NULL OR v_qty<=0 OR v_qty<>trunc(v_qty) OR v_qty>9223372036854775807 THEN RAISE EXCEPTION 'invalid_purchase_receipt_line' USING ERRCODE='22023'; END IF;
    IF EXISTS (SELECT 1 FROM public.commercial_purchase_receipt_items WHERE receipt_id=v_receipt_id AND purchase_order_item_id=v_item_id) THEN RAISE EXCEPTION 'duplicate_purchase_receipt_line' USING ERRCODE='22023'; END IF;
    SELECT quantity_ordered,quantity_received,offering_id,unit_cost_minor INTO v_ordered,v_received,v_offering,v_cost
      FROM public.commercial_purchase_order_items WHERE id=v_item_id AND organization_id=p_organization_id AND purchase_order_id=p_purchase_order_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'purchase_receipt_line_not_in_order' USING ERRCODE='22023'; END IF;
    IF v_offering IS NULL THEN RAISE EXCEPTION 'purchase_line_missing_inventory_offering' USING ERRCODE='55000'; END IF;
    IF v_received+v_qty>v_ordered THEN RAISE EXCEPTION 'purchase_receipt_exceeds_remaining_quantity' USING ERRCODE='22023'; END IF;
    INSERT INTO public.commercial_inventory_balances(organization_id,location_id,offering_id,quantity_on_hand,quantity_reserved,reorder_point,unit_cost_minor)
    VALUES(p_organization_id,p_location_id,v_offering,0,0,0,NULL) ON CONFLICT (organization_id,location_id,offering_id) DO NOTHING;
    v_inventory:=public.commercial_receive_inventory_stock(p_organization_id,p_location_id,v_offering,v_qty::bigint,v_cost,
      'purchase-receipt:'||v_receipt_id::text||':'||v_item_id::text);
    v_inv_receipt:=(v_inventory->>'receipt_id')::uuid;
    UPDATE public.commercial_purchase_order_items SET quantity_received=quantity_received+v_qty,updated_at=now()
      WHERE id=v_item_id AND purchase_order_id=p_purchase_order_id AND organization_id=p_organization_id;
    INSERT INTO public.commercial_purchase_receipt_items(organization_id,receipt_id,purchase_order_id,purchase_order_item_id,offering_id,quantity,unit_cost_minor,inventory_receipt_id)
    VALUES(p_organization_id,v_receipt_id,p_purchase_order_id,v_item_id,v_offering,v_qty::bigint,v_cost,v_inv_receipt);
    v_item_count:=v_item_count+1;
  END LOOP;
  SELECT CASE WHEN bool_and(quantity_received=quantity_ordered) THEN 'received' ELSE 'partially_received' END INTO v_new_status
    FROM public.commercial_purchase_order_items WHERE purchase_order_id=p_purchase_order_id AND organization_id=p_organization_id;
  UPDATE public.commercial_purchase_orders SET status=v_new_status,updated_at=now()
    WHERE id=p_purchase_order_id AND organization_id=p_organization_id;
  INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
  VALUES(p_organization_id,v_uid,'purchase_order.received','purchase_receipt',v_receipt_id::text,jsonb_build_object('purchase_order_id',p_purchase_order_id,'location_id',p_location_id,'line_count',v_item_count,'new_order_status',v_new_status));
  RETURN jsonb_build_object('receipt_id',v_receipt_id,'purchase_order_id',p_purchase_order_id,'status',v_new_status,'line_count',v_item_count,'idempotent_replay',false);
END;
$function$;

CREATE OR REPLACE FUNCTION commercial_private.commercial_post_supplier_invoice_financials()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE v_inventory_account uuid; v_payable_account uuid; v_party_account uuid; v_entry_id uuid; v_entry_number bigint;
BEGIN
  v_inventory_account:=commercial_private.commercial_ensure_supplier_ledger_account(NEW.organization_id,NEW.currency_code,'inventory_asset');
  v_payable_account:=commercial_private.commercial_ensure_supplier_ledger_account(NEW.organization_id,NEW.currency_code,'accounts_payable');
  v_party_account:=commercial_private.commercial_ensure_supplier_party_account(NEW.organization_id,NEW.supplier_id,NEW.currency_code);
  v_entry_number:=nextval('public.commercial_supplier_journal_entry_number_seq');
  INSERT INTO public.commercial_journal_entries(organization_id,entry_number,entry_date,description,source_type,source_id,status,idempotency_key,created_by,posted_by,posted_at,metadata)
  VALUES(NEW.organization_id,v_entry_number,NEW.recorded_at::date,'إثبات فاتورة المورد '||NEW.invoice_number,'supplier_invoice',NEW.id,'posted',
    'supplier-invoice:'||NEW.id::text,NEW.recorded_by,NEW.recorded_by,NEW.recorded_at,
    jsonb_build_object('supplier_id',NEW.supplier_id,'purchase_order_id',NEW.purchase_order_id,'currency_code',NEW.currency_code,'total_minor',NEW.total_minor))
  RETURNING id INTO v_entry_id;
  INSERT INTO public.commercial_journal_lines(organization_id,journal_entry_id,line_number,ledger_account_id,description,debit_minor,credit_minor,currency_code,party_account_id)
  VALUES(NEW.organization_id,v_entry_id,1,v_inventory_account,'إثبات تكلفة فاتورة المورد',NEW.total_minor,0,NEW.currency_code,NULL),
        (NEW.organization_id,v_entry_id,2,v_payable_account,'ذمم مستحقة للمورد',0,NEW.total_minor,NEW.currency_code,v_party_account);
  INSERT INTO public.commercial_supplier_obligations(organization_id,supplier_invoice_id,supplier_id,party_account_id,journal_entry_id,currency_code,amount_due_minor)
  VALUES(NEW.organization_id,NEW.id,NEW.supplier_id,v_party_account,v_entry_id,NEW.currency_code,NEW.total_minor);
  RETURN NEW;
END;
$function$;

DROP TRIGGER IF EXISTS commercial_supplier_invoice_financials_after_insert ON public.commercial_supplier_invoices;
CREATE TRIGGER commercial_supplier_invoice_financials_after_insert
AFTER INSERT ON public.commercial_supplier_invoices
FOR EACH ROW EXECUTE FUNCTION commercial_private.commercial_post_supplier_invoice_financials();

CREATE OR REPLACE FUNCTION commercial_private.commercial_record_supplier_payment(
  p_organization_id uuid,p_obligation_id uuid,p_amount_minor bigint,p_currency_code text,
  p_payment_method text,p_evidence_reference text,p_idempotency_key text
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE v_uid uuid:=auth.uid(); v_ob public.commercial_supplier_obligations%ROWTYPE; v_existing public.commercial_supplier_payments%ROWTYPE;
  v_fingerprint text; v_cash_account uuid; v_payable_account uuid; v_entry_id uuid; v_entry_number bigint; v_paid numeric;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_organization_id IS NULL OR p_obligation_id IS NULL OR p_amount_minor IS NULL OR p_amount_minor<=0
    OR p_currency_code !~ '^[A-Z]{3}$' OR p_payment_method NOT IN ('cash','bank_transfer','check','other')
    OR p_evidence_reference IS NULL OR length(btrim(p_evidence_reference)) NOT BETWEEN 3 AND 200
    OR p_idempotency_key IS NULL OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200 THEN
    RAISE EXCEPTION 'invalid_supplier_payment_request' USING ERRCODE='22023';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=p_organization_id AND m.user_id=v_uid AND m.state='active' AND m.role IN ('owner','admin','manager')) THEN
    RAISE EXCEPTION 'supplier_payment_permission_required' USING ERRCODE='42501';
  END IF;
  PERFORM pg_advisory_xact_lock(hashtext(p_organization_id::text),hashtext('supplier-payment:'||btrim(p_idempotency_key)));
  v_fingerprint:=md5(jsonb_build_object('obligation_id',p_obligation_id,'amount_minor',p_amount_minor,'currency_code',p_currency_code,'payment_method',p_payment_method,'evidence_reference',btrim(p_evidence_reference))::text);
  SELECT * INTO v_existing FROM public.commercial_supplier_payments WHERE organization_id=p_organization_id AND idempotency_key=btrim(p_idempotency_key);
  IF FOUND THEN
    IF v_existing.request_fingerprint IS DISTINCT FROM v_fingerprint THEN RAISE EXCEPTION 'supplier_payment_key_reused_with_different_request' USING ERRCODE='23505'; END IF;
    RETURN jsonb_build_object('payment_id',v_existing.id,'journal_entry_id',v_existing.journal_entry_id,'idempotent_replay',true);
  END IF;
  SELECT * INTO v_ob FROM public.commercial_supplier_obligations WHERE id=p_obligation_id AND organization_id=p_organization_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'supplier_obligation_not_found' USING ERRCODE='P0002'; END IF;
  IF v_ob.currency_code<>p_currency_code THEN RAISE EXCEPTION 'supplier_payment_currency_mismatch' USING ERRCODE='22023'; END IF;
  IF p_amount_minor>v_ob.amount_due_minor-v_ob.amount_paid_minor THEN RAISE EXCEPTION 'supplier_payment_exceeds_outstanding_balance' USING ERRCODE='22023'; END IF;
  SELECT ledger_account_id INTO v_payable_account FROM public.commercial_supplier_gl_accounts
    WHERE organization_id=p_organization_id AND currency_code=p_currency_code AND account_role='accounts_payable';
  SELECT m.ledger_account_id INTO v_cash_account FROM public.commercial_gl_account_mappings m
    JOIN public.commercial_ledger_accounts a ON a.organization_id=m.organization_id AND a.id=m.ledger_account_id
    WHERE m.organization_id=p_organization_id AND m.mapping_key='cash' AND a.currency_code=p_currency_code AND a.is_active;
  IF v_payable_account IS NULL OR v_cash_account IS NULL THEN RAISE EXCEPTION 'supplier_payment_cash_account_not_configured_for_currency' USING ERRCODE='55000'; END IF;
  v_entry_number:=nextval('public.commercial_supplier_journal_entry_number_seq');
  INSERT INTO public.commercial_journal_entries(organization_id,entry_number,entry_date,description,source_type,source_id,status,idempotency_key,created_by,posted_by,posted_at,metadata)
  VALUES(p_organization_id,v_entry_number,current_date,'سداد فاتورة مورد','supplier_payment',p_ob.supplier_invoice_id,'posted',
    'supplier-payment:'||btrim(p_idempotency_key),v_uid,v_uid,now(),
    jsonb_build_object('obligation_id',p_obligation_id,'payment_method',p_payment_method,'evidence_reference',btrim(p_evidence_reference),'amount_minor',p_amount_minor,'currency_code',p_currency_code))
  RETURNING id INTO v_entry_id;
  INSERT INTO public.commercial_journal_lines(organization_id,journal_entry_id,line_number,ledger_account_id,description,debit_minor,credit_minor,currency_code,party_account_id)
  VALUES(p_organization_id,v_entry_id,1,v_payable_account,'تخفيض ذمم المورد',p_amount_minor,0,p_currency_code,v_ob.party_account_id),
        (p_organization_id,v_entry_id,2,v_cash_account,'سداد للمورد',0,p_amount_minor,p_currency_code,NULL);
  INSERT INTO public.commercial_supplier_payments(organization_id,obligation_id,supplier_invoice_id,amount_minor,currency_code,payment_method,evidence_reference,idempotency_key,request_fingerprint,journal_entry_id,recorded_by)
  VALUES(p_organization_id,p_obligation_id,v_ob.supplier_invoice_id,p_amount_minor,p_currency_code,p_payment_method,btrim(p_evidence_reference),btrim(p_idempotency_key),v_fingerprint,v_entry_id,v_uid)
  RETURNING id INTO v_existing.id;
  v_paid:=v_ob.amount_paid_minor+p_amount_minor;
  UPDATE public.commercial_supplier_obligations SET amount_paid_minor=v_paid,state=CASE WHEN v_paid=amount_due_minor THEN 'paid' ELSE 'partially_paid' END,updated_at=now()
  WHERE id=p_obligation_id AND organization_id=p_organization_id;
  INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
  VALUES(p_organization_id,v_uid,'supplier_payment.recorded','supplier_payment',v_existing.id::text,jsonb_build_object('obligation_id',p_obligation_id,'amount_minor',p_amount_minor,'currency_code',p_currency_code,'journal_entry_id',v_entry_id));
  RETURN jsonb_build_object('payment_id',v_existing.id,'journal_entry_id',v_entry_id,'outstanding_minor',v_ob.amount_due_minor-v_paid,'idempotent_replay',false);
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_create_purchase_order(
  p_organization_id uuid,p_supplier_id uuid,p_order_number text,p_currency_code text,
  p_receive_location_id uuid,p_lines jsonb,p_idempotency_key text,p_expected_at timestamptz DEFAULT NULL,p_notes text DEFAULT NULL
) RETURNS jsonb LANGUAGE sql SECURITY DEFINER SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$ SELECT commercial_private.commercial_create_purchase_order($1,$2,$3,$4,$5,$6,$7,$8,$9) $function$;

CREATE OR REPLACE FUNCTION public.commercial_transition_purchase_order(p_organization_id uuid,p_purchase_order_id uuid,p_action text)
RETURNS jsonb LANGUAGE sql SECURITY DEFINER SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$ SELECT commercial_private.commercial_transition_purchase_order($1,$2,$3) $function$;

CREATE OR REPLACE FUNCTION public.commercial_receive_purchase_order(p_organization_id uuid,p_purchase_order_id uuid,p_location_id uuid,p_lines jsonb,p_idempotency_key text)
RETURNS jsonb LANGUAGE sql SECURITY DEFINER SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$ SELECT commercial_private.commercial_receive_purchase_order($1,$2,$3,$4,$5) $function$;

CREATE OR REPLACE FUNCTION public.commercial_record_supplier_payment(p_organization_id uuid,p_obligation_id uuid,p_amount_minor bigint,p_currency_code text,p_payment_method text,p_evidence_reference text,p_idempotency_key text)
RETURNS jsonb LANGUAGE sql SECURITY DEFINER SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$ SELECT commercial_private.commercial_record_supplier_payment($1,$2,$3,$4,$5,$6,$7) $function$;

REVOKE ALL ON FUNCTION commercial_private.commercial_ensure_supplier_ledger_account(uuid,text,text) FROM PUBLIC,anon,authenticated;
REVOKE ALL ON FUNCTION commercial_private.commercial_ensure_supplier_party_account(uuid,uuid,text) FROM PUBLIC,anon,authenticated;
REVOKE ALL ON FUNCTION commercial_private.commercial_create_purchase_order(uuid,uuid,text,text,uuid,jsonb,text,timestamptz,text) FROM PUBLIC,anon,authenticated;
REVOKE ALL ON FUNCTION commercial_private.commercial_transition_purchase_order(uuid,uuid,text) FROM PUBLIC,anon,authenticated;
REVOKE ALL ON FUNCTION commercial_private.commercial_receive_purchase_order(uuid,uuid,uuid,jsonb,text) FROM PUBLIC,anon,authenticated;
REVOKE ALL ON FUNCTION commercial_private.commercial_record_supplier_payment(uuid,uuid,bigint,text,text,text,text) FROM PUBLIC,anon,authenticated;
REVOKE ALL ON FUNCTION public.commercial_create_purchase_order(uuid,uuid,text,text,uuid,jsonb,text,timestamptz,text) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_transition_purchase_order(uuid,uuid,text) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_receive_purchase_order(uuid,uuid,uuid,jsonb,text) FROM PUBLIC,anon;
REVOKE ALL ON FUNCTION public.commercial_record_supplier_payment(uuid,uuid,bigint,text,text,text,text) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_create_purchase_order(uuid,uuid,text,text,uuid,jsonb,text,timestamptz,text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_transition_purchase_order(uuid,uuid,text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_receive_purchase_order(uuid,uuid,uuid,jsonb,text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.commercial_record_supplier_payment(uuid,uuid,bigint,text,text,text,text) TO authenticated;
