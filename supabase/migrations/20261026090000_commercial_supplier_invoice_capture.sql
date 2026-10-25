-- Supplier invoice capture for the Android commercial purchase workflow.
-- This records supplier invoice evidence only. It intentionally does not mutate stock,
-- create payments/obligations, or post general-ledger entries.
CREATE TABLE IF NOT EXISTS public.commercial_supplier_invoices (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id),
  purchase_order_id uuid NOT NULL REFERENCES public.commercial_purchase_orders(id),
  supplier_id uuid NOT NULL REFERENCES public.commercial_suppliers(id),
  invoice_number text NOT NULL CHECK (length(btrim(invoice_number)) BETWEEN 1 AND 120),
  currency_code text NOT NULL CHECK (currency_code ~ '^[A-Z]{3}$'),
  subtotal_minor bigint NOT NULL CHECK (subtotal_minor >= 0),
  tax_minor bigint NOT NULL DEFAULT 0 CHECK (tax_minor >= 0),
  freight_minor bigint NOT NULL DEFAULT 0 CHECK (freight_minor >= 0),
  total_minor bigint NOT NULL CHECK (total_minor >= 0),
  state text NOT NULL DEFAULT 'recorded' CHECK (state = 'recorded'),
  idempotency_key text NOT NULL CHECK (length(btrim(idempotency_key)) BETWEEN 8 AND 200),
  request_fingerprint text NOT NULL,
  recorded_by uuid NOT NULL REFERENCES auth.users(id),
  recorded_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (organization_id, supplier_id, invoice_number),
  UNIQUE (organization_id, idempotency_key),
  CHECK (total_minor::numeric = subtotal_minor::numeric + tax_minor::numeric + freight_minor::numeric)
);

CREATE TABLE IF NOT EXISTS public.commercial_supplier_invoice_items (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id),
  supplier_invoice_id uuid NOT NULL REFERENCES public.commercial_supplier_invoices(id) ON DELETE RESTRICT,
  purchase_order_item_id uuid NOT NULL REFERENCES public.commercial_purchase_order_items(id),
  quantity numeric(20,6) NOT NULL CHECK (quantity > 0),
  unit_price_minor bigint NOT NULL CHECK (unit_price_minor >= 0),
  line_total_minor bigint NOT NULL CHECK (line_total_minor >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (supplier_invoice_id, purchase_order_item_id)
);

CREATE INDEX IF NOT EXISTS idx_commercial_supplier_invoices_purchase
  ON public.commercial_supplier_invoices(organization_id, purchase_order_id, recorded_at DESC);
CREATE INDEX IF NOT EXISTS idx_commercial_supplier_invoice_items_purchase_item
  ON public.commercial_supplier_invoice_items(organization_id, purchase_order_item_id);

ALTER TABLE public.commercial_supplier_invoices ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.commercial_supplier_invoice_items ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS commercial_supplier_invoices_org_read ON public.commercial_supplier_invoices;
CREATE POLICY commercial_supplier_invoices_org_read ON public.commercial_supplier_invoices
  FOR SELECT TO authenticated USING (
    EXISTS (SELECT 1 FROM public.commercial_organization_members m
      WHERE m.organization_id = commercial_supplier_invoices.organization_id
        AND m.user_id = auth.uid() AND m.state = 'active')
  );

DROP POLICY IF EXISTS commercial_supplier_invoice_items_org_read ON public.commercial_supplier_invoice_items;
CREATE POLICY commercial_supplier_invoice_items_org_read ON public.commercial_supplier_invoice_items
  FOR SELECT TO authenticated USING (
    EXISTS (SELECT 1 FROM public.commercial_organization_members m
      WHERE m.organization_id = commercial_supplier_invoice_items.organization_id
        AND m.user_id = auth.uid() AND m.state = 'active')
  );

REVOKE ALL ON public.commercial_supplier_invoices FROM anon, authenticated;
REVOKE ALL ON public.commercial_supplier_invoice_items FROM anon, authenticated;
GRANT SELECT ON public.commercial_supplier_invoices TO authenticated;
GRANT SELECT ON public.commercial_supplier_invoice_items TO authenticated;

CREATE OR REPLACE FUNCTION commercial_private.commercial_record_supplier_invoice(
  p_organization_id uuid,
  p_purchase_order_id uuid,
  p_invoice_number text,
  p_currency_code text,
  p_lines jsonb,
  p_tax_minor bigint DEFAULT 0,
  p_freight_minor bigint DEFAULT 0,
  p_idempotency_key text DEFAULT NULL
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog', 'public', 'commercial_private'
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_supplier_id uuid;
  v_status text;
  v_currency text;
  v_invoice_id uuid;
  v_existing_fingerprint text;
  v_fingerprint text;
  v_line jsonb;
  v_item_id uuid;
  v_qty numeric;
  v_price bigint;
  v_received numeric;
  v_ordered numeric;
  v_cost bigint;
  v_line_total numeric;
  v_subtotal numeric := 0;
  v_total numeric;
  v_existing_qty numeric;
  v_seen uuid[] := ARRAY[]::uuid[];
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_organization_id IS NULL OR p_purchase_order_id IS NULL
     OR p_invoice_number IS NULL OR length(btrim(p_invoice_number)) NOT BETWEEN 1 AND 120
     OR p_currency_code IS NULL OR p_currency_code !~ '^[A-Z]{3}$'
     OR p_tax_minor IS NULL OR p_tax_minor < 0
     OR p_freight_minor IS NULL OR p_freight_minor < 0
     OR p_idempotency_key IS NULL OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200
     OR p_lines IS NULL OR jsonb_typeof(p_lines) <> 'array' OR jsonb_array_length(p_lines) = 0 THEN
    RAISE EXCEPTION 'invalid_supplier_invoice_request' USING ERRCODE='22023';
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id=p_organization_id AND m.user_id=v_uid
      AND m.state='active' AND m.role IN ('owner','admin','manager')
  ) THEN RAISE EXCEPTION 'organization_write_access_denied' USING ERRCODE='42501'; END IF;

  PERFORM pg_advisory_xact_lock(hashtext(p_organization_id::text), hashtext(p_idempotency_key));
  v_fingerprint := md5(jsonb_build_object(
    'organization_id',p_organization_id,'purchase_order_id',p_purchase_order_id,
    'invoice_number',btrim(p_invoice_number),'currency_code',p_currency_code,
    'lines',p_lines,'tax_minor',p_tax_minor,'freight_minor',p_freight_minor
  )::text);

  SELECT id, request_fingerprint INTO v_invoice_id, v_existing_fingerprint
  FROM public.commercial_supplier_invoices
  WHERE organization_id=p_organization_id AND idempotency_key=btrim(p_idempotency_key);
  IF FOUND THEN
    IF v_existing_fingerprint IS DISTINCT FROM v_fingerprint THEN
      RAISE EXCEPTION 'idempotency_key_reused_with_different_request' USING ERRCODE='23505';
    END IF;
    RETURN jsonb_build_object('invoice_id',v_invoice_id,'state','recorded','idempotent_replay',true);
  END IF;

  SELECT po.supplier_id, po.status, upper(po.currency_code)
    INTO v_supplier_id, v_status, v_currency
  FROM public.commercial_purchase_orders po
  WHERE po.id=p_purchase_order_id AND po.organization_id=p_organization_id
  FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'purchase_order_not_found' USING ERRCODE='P0002'; END IF;
  IF v_status NOT IN ('approved','open','partially_received','partial','received','completed') THEN
    RAISE EXCEPTION 'purchase_order_not_ready_for_supplier_invoice' USING ERRCODE='22023';
  END IF;
  IF v_currency IS DISTINCT FROM p_currency_code THEN
    RAISE EXCEPTION 'supplier_invoice_currency_mismatch' USING ERRCODE='22023';
  END IF;

  FOR v_line IN SELECT value FROM jsonb_array_elements(p_lines) LOOP
    BEGIN
      v_item_id := (v_line->>'purchase_order_item_id')::uuid;
      v_qty := (v_line->>'quantity')::numeric;
      v_price := (v_line->>'unit_price_minor')::bigint;
    EXCEPTION WHEN others THEN
      RAISE EXCEPTION 'invalid_supplier_invoice_line' USING ERRCODE='22023';
    END;
    IF v_item_id = ANY(v_seen) OR v_qty IS NULL OR v_qty <= 0 OR v_qty > 1000000000000
       OR v_qty <> round(v_qty, 6) OR v_price IS NULL OR v_price < 0 THEN
      RAISE EXCEPTION 'invalid_supplier_invoice_line' USING ERRCODE='22023';
    END IF;
    v_seen := array_append(v_seen, v_item_id);

    SELECT poi.quantity_ordered, poi.quantity_received, poi.unit_cost_minor
      INTO v_ordered, v_received, v_cost
    FROM public.commercial_purchase_order_items poi
    WHERE poi.id=v_item_id AND poi.purchase_order_id=p_purchase_order_id
      AND poi.organization_id=p_organization_id
    FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'supplier_invoice_line_not_in_purchase_order' USING ERRCODE='22023'; END IF;
    IF v_received IS NULL OR v_received <= 0 OR v_received > v_ordered THEN
      RAISE EXCEPTION 'supplier_invoice_requires_received_quantity' USING ERRCODE='22023';
    END IF;
    IF v_cost IS NOT NULL AND v_cost < 0 THEN RAISE EXCEPTION 'invalid_purchase_unit_cost' USING ERRCODE='22023'; END IF;

    SELECT coalesce(sum(si.quantity),0) INTO v_existing_qty
    FROM public.commercial_supplier_invoice_items si
    JOIN public.commercial_supplier_invoices sh ON sh.id=si.supplier_invoice_id
    WHERE si.organization_id=p_organization_id AND si.purchase_order_item_id=v_item_id
      AND sh.state='recorded';
    IF v_existing_qty + v_qty > v_received THEN
      RAISE EXCEPTION 'supplier_invoice_quantity_exceeds_uninvoiced_received' USING ERRCODE='22023';
    END IF;

    v_line_total := v_qty * v_price;
    IF v_line_total > 9223372036854775807::numeric THEN RAISE EXCEPTION 'supplier_invoice_total_overflow' USING ERRCODE='22003'; END IF;
    v_subtotal := v_subtotal + v_line_total;
    IF v_subtotal > 9223372036854775807::numeric THEN RAISE EXCEPTION 'supplier_invoice_total_overflow' USING ERRCODE='22003'; END IF;
  END LOOP;

  v_total := v_subtotal + p_tax_minor::numeric + p_freight_minor::numeric;
  IF v_total > 9223372036854775807::numeric THEN RAISE EXCEPTION 'supplier_invoice_total_overflow' USING ERRCODE='22003'; END IF;

  INSERT INTO public.commercial_supplier_invoices(
    organization_id,purchase_order_id,supplier_id,invoice_number,currency_code,
    subtotal_minor,tax_minor,freight_minor,total_minor,idempotency_key,request_fingerprint,recorded_by
  ) VALUES (
    p_organization_id,p_purchase_order_id,v_supplier_id,btrim(p_invoice_number),p_currency_code,
    v_subtotal::bigint,p_tax_minor,p_freight_minor,v_total::bigint,btrim(p_idempotency_key),v_fingerprint,v_uid
  ) RETURNING id INTO v_invoice_id;

  FOR v_line IN SELECT value FROM jsonb_array_elements(p_lines) LOOP
    v_item_id := (v_line->>'purchase_order_item_id')::uuid;
    v_qty := (v_line->>'quantity')::numeric;
    v_price := (v_line->>'unit_price_minor')::bigint;
    v_line_total := v_qty * v_price;
    INSERT INTO public.commercial_supplier_invoice_items(
      organization_id,supplier_invoice_id,purchase_order_item_id,quantity,unit_price_minor,line_total_minor
    ) VALUES (p_organization_id,v_invoice_id,v_item_id,v_qty,v_price,v_line_total::bigint);
  END LOOP;

  INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
  VALUES (p_organization_id,v_uid,'supplier_invoice.recorded','supplier_invoice',v_invoice_id::text,
    jsonb_build_object('purchase_order_id',p_purchase_order_id,'supplier_id',v_supplier_id,
      'invoice_number',btrim(p_invoice_number),'currency_code',p_currency_code,
      'subtotal_minor',v_subtotal::bigint,'tax_minor',p_tax_minor,'freight_minor',p_freight_minor,
      'total_minor',v_total::bigint,'line_count',jsonb_array_length(p_lines)));

  RETURN jsonb_build_object('invoice_id',v_invoice_id,'purchase_order_id',p_purchase_order_id,
    'invoice_number',btrim(p_invoice_number),'currency_code',p_currency_code,
    'subtotal_minor',v_subtotal::bigint,'tax_minor',p_tax_minor,'freight_minor',p_freight_minor,
    'total_minor',v_total::bigint,'state','recorded','idempotent_replay',false);
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_record_supplier_invoice(
  p_organization_id uuid,
  p_purchase_order_id uuid,
  p_invoice_number text,
  p_currency_code text,
  p_lines jsonb,
  p_tax_minor bigint DEFAULT 0,
  p_freight_minor bigint DEFAULT 0,
  p_idempotency_key text DEFAULT NULL
) RETURNS jsonb
LANGUAGE plpgsql SECURITY INVOKER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
BEGIN
  IF auth.uid() IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  RETURN commercial_private.commercial_record_supplier_invoice(
    p_organization_id,p_purchase_order_id,p_invoice_number,p_currency_code,p_lines,
    p_tax_minor,p_freight_minor,p_idempotency_key);
END;
$function$;

REVOKE ALL ON FUNCTION commercial_private.commercial_record_supplier_invoice(uuid,uuid,text,text,jsonb,bigint,bigint,text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION commercial_private.commercial_record_supplier_invoice(uuid,uuid,text,text,jsonb,bigint,bigint,text) TO authenticated, service_role;
REVOKE ALL ON FUNCTION public.commercial_record_supplier_invoice(uuid,uuid,text,text,jsonb,bigint,bigint,text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_record_supplier_invoice(uuid,uuid,text,text,jsonb,bigint,bigint,text) TO authenticated;
