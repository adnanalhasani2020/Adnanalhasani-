-- Persist explicit checkout discount/tax amounts and post them to configured GL accounts.
-- No tax rate or jurisdiction is inferred. Amounts are integer minor units supplied by the caller.
ALTER TABLE public.commercial_sales
  ADD COLUMN IF NOT EXISTS subtotal_minor bigint,
  ADD COLUMN IF NOT EXISTS discount_minor bigint NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS tax_minor bigint NOT NULL DEFAULT 0;

UPDATE public.commercial_sales SET subtotal_minor = total_minor WHERE subtotal_minor IS NULL;
ALTER TABLE public.commercial_sales ALTER COLUMN subtotal_minor SET NOT NULL;

DO $constraints$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='commercial_sales_amounts_nonnegative') THEN
    ALTER TABLE public.commercial_sales ADD CONSTRAINT commercial_sales_amounts_nonnegative
      CHECK (subtotal_minor >= 0 AND discount_minor >= 0 AND tax_minor >= 0 AND total_minor >= 0 AND discount_minor <= subtotal_minor);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='commercial_sales_total_reconciles') THEN
    ALTER TABLE public.commercial_sales ADD CONSTRAINT commercial_sales_total_reconciles
      CHECK (total_minor::numeric = subtotal_minor::numeric - discount_minor::numeric + tax_minor::numeric);
  END IF;
END;
$constraints$;

-- Preserve compatibility with legacy inserts that do not send a subtotal.
CREATE OR REPLACE FUNCTION commercial_private.commercial_sales_amounts_compat()
RETURNS trigger LANGUAGE plpgsql SET search_path TO 'pg_catalog','public'
AS $trigger$
BEGIN
  IF NEW.subtotal_minor IS NULL THEN NEW.subtotal_minor := NEW.total_minor; END IF;
  RETURN NEW;
END;
$trigger$;
DROP TRIGGER IF EXISTS commercial_sales_amounts_compat ON public.commercial_sales;
CREATE TRIGGER commercial_sales_amounts_compat
BEFORE INSERT OR UPDATE OF total_minor, subtotal_minor, discount_minor, tax_minor
ON public.commercial_sales FOR EACH ROW EXECUTE FUNCTION commercial_private.commercial_sales_amounts_compat();

-- Keep checkout responses consistent on first success and idempotent retry.
-- Paid amount is always derived from succeeded payment rows; the remainder is never guessed from the request.
CREATE OR REPLACE FUNCTION commercial_private.commercial_checkout_sale_multi_priced(
  p_lines jsonb, p_location_id uuid, p_idempotency_key text, p_customer_id uuid DEFAULT NULL,
  p_payment_mode text DEFAULT 'cash', p_initial_payment_minor bigint DEFAULT NULL,
  p_discount_minor bigint DEFAULT 0, p_tax_minor bigint DEFAULT 0
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog', 'public', 'commercial_private'
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_org uuid;
  v_customer uuid := p_customer_id;
  v_payment_mode text := coalesce(p_payment_mode, 'cash');
  v_initial_payment bigint := coalesce(p_initial_payment_minor, 0);
  v_payment_status text;
  v_activity uuid;
  v_currency text;
  v_line jsonb;
  v_offer uuid;
  v_qty integer;
  v_price bigint;
  v_name text;
  v_on_hand numeric;
  v_reserved numeric;
  v_unit_cost bigint;
  v_cost_total numeric;
  v_map record;
  v_party uuid;
  v_entry uuid;
  v_gl_lines jsonb;
  v_total numeric := 0;
  v_subtotal numeric := 0;
  v_discount bigint := coalesce(p_discount_minor,0);
  v_tax bigint := coalesce(p_tax_minor,0);
  v_line_total numeric;
  v_fingerprint text;
  v_old_fingerprint text;
  v_sale uuid;
  v_receipt bigint;
  v_created timestamptz;
  v_result jsonb;
  v_rows jsonb := '[]'::jsonb;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_location_id IS NULL OR p_lines IS NULL THEN
    RAISE EXCEPTION 'invalid_invoice_lines_or_location' USING ERRCODE='22023';
  END IF;
  IF jsonb_typeof(p_lines) <> 'array' THEN
    RAISE EXCEPTION 'invalid_invoice_lines_or_location' USING ERRCODE='22023';
  END IF;
  IF jsonb_array_length(p_lines) < 1 OR jsonb_array_length(p_lines) > 200 THEN
    RAISE EXCEPTION 'invalid_invoice_lines_or_location' USING ERRCODE='22023';
  END IF;
  IF v_discount < 0 OR v_tax < 0 THEN RAISE EXCEPTION 'invalid_discount_or_tax' USING ERRCODE='22023'; END IF;
  IF v_payment_mode NOT IN ('cash','credit') THEN RAISE EXCEPTION 'invalid_payment_mode' USING ERRCODE='22023'; END IF;
  IF v_initial_payment < 0 THEN RAISE EXCEPTION 'invalid_initial_payment' USING ERRCODE='22023'; END IF;
  IF v_payment_mode = 'cash' AND p_initial_payment_minor IS NOT NULL THEN RAISE EXCEPTION 'cash_payment_does_not_accept_initial_amount' USING ERRCODE='22023'; END IF;
  IF p_idempotency_key IS NULL OR length(btrim(p_idempotency_key)) < 8
     OR length(btrim(p_idempotency_key)) > 200 THEN
    RAISE EXCEPTION 'invalid_idempotency_key' USING ERRCODE='22023';
  END IF;
  IF EXISTS (
    SELECT 1 FROM jsonb_array_elements(p_lines) e
    WHERE jsonb_typeof(e) <> 'object'
       OR coalesce(e->>'offering_id','') !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
       OR coalesce(e->>'quantity','') !~ '^[0-9]{1,6}$'
  ) THEN RAISE EXCEPTION 'invalid_invoice_line' USING ERRCODE='22023'; END IF;
  IF EXISTS (SELECT 1 FROM jsonb_array_elements(p_lines) e WHERE (e->>'quantity')::integer < 1 OR (e->>'quantity')::integer > 100000) THEN
    RAISE EXCEPTION 'invalid_quantity' USING ERRCODE='22023';
  END IF;
  IF (SELECT count(*) FROM jsonb_array_elements(p_lines))
     <> (SELECT count(DISTINCT (e->>'offering_id')::uuid) FROM jsonb_array_elements(p_lines) e) THEN
    RAISE EXCEPTION 'duplicate_offering_lines_not_allowed' USING ERRCODE='22023';
  END IF;

  v_fingerprint := md5(jsonb_build_object(
    'location_id', p_location_id,
    'customer_id', p_customer_id,
    'payment_mode', v_payment_mode,
    'initial_payment_minor', CASE WHEN v_payment_mode='cash' THEN NULL ELSE v_initial_payment END,
    'discount_minor', v_discount, 'tax_minor', v_tax,
    'lines', (SELECT jsonb_agg(jsonb_build_object('offering_id', e->>'offering_id', 'quantity', (e->>'quantity')::integer)
      ORDER BY (e->>'offering_id')::uuid) FROM jsonb_array_elements(p_lines) e)
  )::text);
  PERFORM pg_advisory_xact_lock(hashtext(v_uid::text), hashtext(p_idempotency_key));

  SELECT s.id, s.receipt_number, s.created_at, s.request_fingerprint,
         s.organization_id, s.activity_id, s.currency_code, s.total_minor, s.payment_status
    INTO v_sale, v_receipt, v_created, v_old_fingerprint, v_org, v_activity, v_currency, v_total, v_payment_status
  FROM public.commercial_sales s
  WHERE s.user_id = v_uid AND s.idempotency_key = p_idempotency_key;
  IF FOUND THEN
    IF v_old_fingerprint IS DISTINCT FROM v_fingerprint THEN
      RAISE EXCEPTION 'idempotency_key_reused_with_different_request' USING ERRCODE='23505';
    END IF;
    SELECT je.id INTO v_entry FROM public.commercial_journal_entries je
      WHERE je.organization_id=v_org AND je.source_type='sale' AND je.source_id=v_sale AND je.status='posted'
      ORDER BY je.created_at DESC LIMIT 1;
    RETURN jsonb_build_object('sale_id',v_sale,'invoice_id',v_sale,'receipt_number',v_receipt,'invoice_number',v_receipt,
      'organization_id',v_org,'customer_id',v_customer,'activity_id',v_activity,'currency_code',v_currency,'total_minor',v_total,
      'journal_entry_id',v_entry,
      'line_count',(SELECT count(*) FROM public.commercial_sale_items i WHERE i.sale_id=v_sale),
      'payment_recorded',EXISTS(SELECT 1 FROM public.commercial_payments p WHERE p.sale_id=v_sale AND p.status='succeeded'),
      'paid_minor',(SELECT coalesce(sum(p.amount_minor),0)::bigint FROM public.commercial_payments p WHERE p.sale_id=v_sale AND p.status='succeeded'),
      'outstanding_minor',greatest(v_total::numeric-(SELECT coalesce(sum(p.amount_minor),0)::numeric FROM public.commercial_payments p WHERE p.sale_id=v_sale AND p.status='succeeded'),0)::bigint,
      'state','completed','payment_status',(SELECT s.payment_status FROM public.commercial_sales s WHERE s.id=v_sale),'idempotent_replay',true);
  END IF;

  SELECT a.id, a.organization_id INTO v_activity, v_org
  FROM public.commercial_offerings o
  JOIN public.commercial_activities a ON a.id=o.activity_id
  WHERE o.id=(p_lines->0->>'offering_id')::uuid
    AND o.state='active' AND o.valid_from<=now() AND (o.valid_to IS NULL OR o.valid_to>now())
    AND a.state='active' AND a.deleted_at IS NULL
  FOR SHARE OF o, a;
  IF NOT FOUND OR v_org IS NULL THEN RAISE EXCEPTION 'offering_not_available' USING ERRCODE='P0002'; END IF;
  IF NOT EXISTS (SELECT 1 FROM public.commercial_organizations o WHERE o.id=v_org AND o.state='active' AND o.deleted_at IS NULL) THEN
    RAISE EXCEPTION 'organization_inactive' USING ERRCODE='42501';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=v_org AND m.user_id=v_uid AND m.state='active') THEN
    RAISE EXCEPTION 'organization_access_denied' USING ERRCODE='42501';
  END IF;
  IF v_payment_mode = 'credit' AND v_customer IS NULL THEN RAISE EXCEPTION 'customer_required_for_credit_sale' USING ERRCODE='22023'; END IF;
  IF v_customer IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM public.commercial_customers c
    WHERE c.id = v_customer AND c.organization_id = v_org AND c.state = 'active'
  ) THEN RAISE EXCEPTION 'customer_not_active_in_organization' USING ERRCODE='22023'; END IF;

  IF EXISTS (
    SELECT 1 FROM jsonb_array_elements(p_lines) e
    JOIN public.commercial_offerings o ON o.id=(e->>'offering_id')::uuid
    JOIN public.commercial_activities a ON a.id=o.activity_id
    WHERE a.id IS DISTINCT FROM v_activity OR a.organization_id IS DISTINCT FROM v_org
  ) THEN RAISE EXCEPTION 'invoice_lines_must_belong_to_one_activity' USING ERRCODE='22023'; END IF;

  -- Lock offerings and stock rows in stable offering-id order to reduce deadlocks.
  FOR v_line IN SELECT e FROM jsonb_array_elements(p_lines) e ORDER BY (e->>'offering_id')::uuid LOOP
    v_offer := (v_line->>'offering_id')::uuid;
    v_qty := (v_line->>'quantity')::integer;
    SELECT o.price_minor, p.name, o.currency_code INTO v_price, v_name, v_currency
    FROM public.commercial_offerings o JOIN public.commercial_products p ON p.id=o.product_id
    WHERE o.id=v_offer AND o.activity_id=v_activity AND o.state='active'
      AND o.valid_from<=now() AND (o.valid_to IS NULL OR o.valid_to>now()) AND p.state='active'
    FOR SHARE OF o, p;
    IF NOT FOUND THEN RAISE EXCEPTION 'offering_not_available' USING ERRCODE='P0002'; END IF;
    IF EXISTS (SELECT 1 FROM jsonb_array_elements(v_rows) r WHERE r->>'currency_code' IS DISTINCT FROM v_currency) THEN
      RAISE EXCEPTION 'mixed_currency_invoice_not_supported' USING ERRCODE='22023';
    END IF;
    SELECT b.quantity_on_hand,b.quantity_reserved,b.unit_cost_minor INTO v_on_hand,v_reserved,v_unit_cost
    FROM public.commercial_inventory_balances b
    JOIN public.commercial_organization_locations l ON l.id=b.location_id AND l.organization_id=b.organization_id
    WHERE b.organization_id=v_org AND b.location_id=p_location_id AND b.offering_id=v_offer AND l.state='active'
    FOR UPDATE OF b;
    IF NOT FOUND THEN RAISE EXCEPTION 'inventory_location_not_found' USING ERRCODE='P0002'; END IF;
    IF v_on_hand-v_reserved < v_qty THEN RAISE EXCEPTION 'insufficient_available_stock' USING ERRCODE='22023'; END IF;
    IF v_unit_cost IS NULL OR v_unit_cost < 0 THEN RAISE EXCEPTION 'inventory_cost_basis_missing_sale_blocked' USING ERRCODE='55000'; END IF;
    v_line_total := v_price::numeric * v_qty::numeric;
    v_cost_total := v_unit_cost::numeric * v_qty::numeric;
    IF v_cost_total > 9223372036854775807 THEN RAISE EXCEPTION 'sale_cost_overflow' USING ERRCODE='22003'; END IF;
    v_subtotal := v_subtotal + v_line_total;
    v_total := v_subtotal;
    IF v_total > 9223372036854775807 THEN RAISE EXCEPTION 'sale_total_overflow' USING ERRCODE='22003'; END IF;
    v_rows := v_rows || jsonb_build_array(jsonb_build_object('offering_id',v_offer,'quantity',v_qty,
      'unit_price_minor',v_price,'product_name',v_name,'line_total_minor',v_line_total::bigint,
      'unit_cost_minor',v_unit_cost,'cost_total_minor',v_cost_total::bigint,'currency_code',v_currency));
  END LOOP;

  IF v_discount > v_subtotal THEN RAISE EXCEPTION 'discount_exceeds_subtotal' USING ERRCODE='22023'; END IF;
  v_total := v_subtotal - v_discount + v_tax;
  IF v_total > 9223372036854775807 THEN RAISE EXCEPTION 'sale_total_overflow' USING ERRCODE='22003'; END IF;
  IF (SELECT count(DISTINCT r->>'currency_code') FROM jsonb_array_elements(v_rows) r) <> 1 THEN
    RAISE EXCEPTION 'mixed_currency_invoice_not_supported' USING ERRCODE='22023';
  END IF;
  SELECT r->>'currency_code' INTO v_currency FROM jsonb_array_elements(v_rows) r LIMIT 1;
  IF v_payment_mode='cash' THEN v_initial_payment := v_total::bigint; END IF;
  IF v_initial_payment > 0 AND v_customer IS NULL THEN RAISE EXCEPTION 'customer_required_for_payment_ledger' USING ERRCODE='22023'; END IF;
  IF v_payment_mode='credit' AND v_initial_payment > v_total THEN RAISE EXCEPTION 'initial_payment_exceeds_invoice_total' USING ERRCODE='22023'; END IF;
  -- Keep the sale receivable-open until the payment trigger atomically posts the collection.
  v_payment_status := CASE WHEN v_initial_payment > 0 THEN 'partially_paid' ELSE 'pending' END;

  INSERT INTO public.commercial_sales(receipt_number,user_id,activity_id,organization_id,customer_id,idempotency_key,
    request_fingerprint,status,currency_code,subtotal_minor,discount_minor,tax_minor,total_minor,payment_status,channel)
  VALUES(nextval('public.commercial_sales_receipt_number_seq'::regclass),v_uid,v_activity,v_org,v_customer,p_idempotency_key,
    v_fingerprint,'completed',v_currency,v_subtotal::bigint,v_discount,v_tax,v_total::bigint,v_payment_status,'in_person')
  RETURNING id,receipt_number,created_at INTO v_sale,v_receipt,v_created;

  FOR v_line IN SELECT value FROM jsonb_array_elements(v_rows) LOOP
    v_offer := (v_line->>'offering_id')::uuid; v_qty := (v_line->>'quantity')::integer;
    v_price := (v_line->>'unit_price_minor')::bigint; v_name := v_line->>'product_name';
    v_line_total := (v_line->>'line_total_minor')::bigint;
    INSERT INTO public.commercial_sale_items(sale_id,offering_id,product_name_snapshot,quantity,unit_price_minor,line_total_minor,unit_cost_minor,cost_total_minor)
    VALUES(v_sale,v_offer,v_name,v_qty,v_price,v_line_total::bigint,(v_line->>'unit_cost_minor')::bigint,(v_line->>'cost_total_minor')::bigint);
    UPDATE public.commercial_inventory_balances SET quantity_on_hand=quantity_on_hand-v_qty,updated_at=now()
    WHERE organization_id=v_org AND location_id=p_location_id AND offering_id=v_offer;
    INSERT INTO public.commercial_stock_movements(offering_id,sale_id,movement_type,quantity_delta,source_ref,created_by)
    VALUES(v_offer,v_sale,'sale',-v_qty,'sale:'||v_sale::text||':offering:'||v_offer::text||':location:'||p_location_id::text,v_uid);
  END LOOP;

  -- One posted invoice entry is the source of truth for the customer subledger and GL AR control account.
  v_party := commercial_private.commercial_customer_party_account(v_org,v_customer,v_currency);
  SELECT max(ledger_account_id) FILTER (WHERE mapping_key='accounts_receivable') ar,
         max(ledger_account_id) FILTER (WHERE mapping_key='sales_revenue') revenue,
         max(ledger_account_id) FILTER (WHERE mapping_key='sales_discounts') discounts,
         max(ledger_account_id) FILTER (WHERE mapping_key='sales_tax_payable') tax_payable,
         max(ledger_account_id) FILTER (WHERE mapping_key='inventory') inventory,
         max(ledger_account_id) FILTER (WHERE mapping_key='cost_of_goods_sold') cogs
    INTO v_map FROM public.commercial_gl_account_mappings WHERE organization_id=v_org;
  SELECT coalesce(sum(cost_total_minor::numeric),0) INTO v_cost_total
    FROM public.commercial_sale_items WHERE sale_id=v_sale;
  IF v_map.ar IS NULL OR v_map.revenue IS NULL
     OR (v_discount>0 AND v_map.discounts IS NULL)
     OR (v_tax>0 AND v_map.tax_payable IS NULL)
     OR (v_cost_total>0 AND (v_map.inventory IS NULL OR v_map.cogs IS NULL)) THEN
    RAISE EXCEPTION 'sales_gl_mappings_incomplete' USING ERRCODE='55000';
  END IF;
  v_gl_lines := jsonb_build_array(
    jsonb_build_object('ledger_account_id',v_map.ar,'debit_minor',v_total::bigint,'credit_minor',0,'description','Customer receivable','party_account_id',v_party),
    jsonb_build_object('ledger_account_id',v_map.revenue,'debit_minor',0,'credit_minor',v_subtotal::bigint,'description','Sales revenue')
  );
  IF v_discount>0 THEN v_gl_lines := v_gl_lines || jsonb_build_array(jsonb_build_object('ledger_account_id',v_map.discounts,'debit_minor',v_discount,'credit_minor',0,'description','Sales discount')); END IF;
  IF v_tax>0 THEN v_gl_lines := v_gl_lines || jsonb_build_array(jsonb_build_object('ledger_account_id',v_map.tax_payable,'debit_minor',0,'credit_minor',v_tax,'description','Sales tax payable')); END IF;
  IF v_cost_total>0 THEN
    v_gl_lines := v_gl_lines || jsonb_build_array(
      jsonb_build_object('ledger_account_id',v_map.cogs,'debit_minor',v_cost_total::bigint,'credit_minor',0,'description','Cost of goods sold'),
      jsonb_build_object('ledger_account_id',v_map.inventory,'debit_minor',0,'credit_minor',v_cost_total::bigint,'description','Inventory relieved')
    );
  END IF;
  v_entry := commercial_private.commercial_gl_post_entry(v_org,'sale:'||v_sale::text,'sale',v_sale,
    'Sales invoice '||v_receipt,v_currency,v_gl_lines,v_uid,NULL);

  IF v_initial_payment > 0 THEN
    INSERT INTO public.commercial_payments(organization_id,sale_id,provider,idempotency_key,amount_minor,currency_code,status,payment_method_type)
    VALUES(v_org,v_sale,'ruh_cash_checkout','sale:'||v_sale::text||':initial',v_initial_payment::bigint,v_currency,'succeeded','cash')
    ON CONFLICT(organization_id,idempotency_key) DO NOTHING;
  END IF;
  INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
  VALUES(v_org,v_uid,'sale.completed','sale',v_sale::text,jsonb_build_object('receipt_number',v_receipt,
    'line_count',jsonb_array_length(v_rows),'location_id',p_location_id,'total_minor',v_total::bigint,
    'currency_code',v_currency,'subtotal_minor',v_subtotal::bigint,'discount_minor',v_discount,'tax_minor',v_tax,
    'payment_recorded',v_initial_payment>0,'payment_mode',v_payment_mode,'outstanding_minor',(v_total-v_initial_payment)::bigint));

  RETURN jsonb_build_object('sale_id',v_sale,'invoice_id',v_sale,'receipt_number',v_receipt,'invoice_number',v_receipt,
    'organization_id',v_org,'customer_id',v_customer,'activity_id',v_activity,'currency_code',v_currency,
    'subtotal_minor',v_subtotal::bigint,'discount_minor',v_discount,'tax_minor',v_tax,'total_minor',v_total::bigint,
    'journal_entry_id',v_entry,'line_count',jsonb_array_length(v_rows),'occurred_at',v_created,'state','completed',
    'payment_status',(SELECT s.payment_status FROM public.commercial_sales s WHERE s.id=v_sale),
    'payment_recorded',v_initial_payment>0,
    'paid_minor',(SELECT coalesce(sum(p.amount_minor),0)::bigint FROM public.commercial_payments p WHERE p.sale_id=v_sale AND p.status='succeeded'),
    'outstanding_minor',greatest(v_total::numeric-(SELECT coalesce(sum(p.amount_minor),0)::numeric FROM public.commercial_payments p WHERE p.sale_id=v_sale AND p.status='succeeded'),0)::bigint,
    'payment_mode',v_payment_mode,'idempotent_replay',false);
END;
$function$;



CREATE OR REPLACE FUNCTION public.commercial_checkout_sale_multi_priced(
  p_lines jsonb, p_location_id uuid, p_idempotency_key text, p_customer_id uuid DEFAULT NULL,
  p_payment_mode text DEFAULT 'cash', p_initial_payment_minor bigint DEFAULT NULL,
  p_discount_minor bigint DEFAULT 0, p_tax_minor bigint DEFAULT 0
) RETURNS jsonb
LANGUAGE plpgsql SECURITY INVOKER
SET search_path TO 'pg_catalog', 'public', 'commercial_private'
AS $wrapper$
BEGIN
  IF auth.uid() IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  RETURN commercial_private.commercial_checkout_sale_multi_priced(
    p_lines,p_location_id,p_idempotency_key,p_customer_id,p_payment_mode,p_initial_payment_minor,p_discount_minor,p_tax_minor);
END;
$wrapper$;

REVOKE ALL ON FUNCTION commercial_private.commercial_checkout_sale_multi_priced(jsonb,uuid,text,uuid,text,bigint,bigint,bigint) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION commercial_private.commercial_checkout_sale_multi_priced(jsonb,uuid,text,uuid,text,bigint,bigint,bigint) TO authenticated;
REVOKE ALL ON FUNCTION public.commercial_checkout_sale_multi_priced(jsonb,uuid,text,uuid,text,bigint,bigint,bigint) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_checkout_sale_multi_priced(jsonb,uuid,text,uuid,text,bigint,bigint,bigint) TO authenticated;
