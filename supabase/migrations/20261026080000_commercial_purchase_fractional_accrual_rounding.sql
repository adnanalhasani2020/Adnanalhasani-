-- Correct minor-unit rounding across partial receipts and multiple supplier invoices.
-- Existing rows receive a stable sequence; future rows receive monotonically allocated sequence values.
-- The purchase-order row lock serializes receipt/invoice writes before these sequence values are assigned,
-- avoiding timestamp ordering bugs when a transaction waits on a lock (PostgreSQL now() is transaction-start time).
ALTER TABLE public.commercial_purchase_receipts
  ADD COLUMN IF NOT EXISTS accounting_sequence bigint GENERATED ALWAYS AS IDENTITY;
CREATE UNIQUE INDEX IF NOT EXISTS commercial_purchase_receipts_accounting_sequence_uq
  ON public.commercial_purchase_receipts(accounting_sequence);

ALTER TABLE public.commercial_supplier_invoices
  ADD COLUMN IF NOT EXISTS accounting_sequence bigint GENERATED ALWAYS AS IDENTITY;
CREATE UNIQUE INDEX IF NOT EXISTS commercial_supplier_invoices_accounting_sequence_uq
  ON public.commercial_supplier_invoices(accounting_sequence);

-- No existing journal entries are rewritten; future accounting postings use cumulative per-line deltas.
CREATE OR REPLACE FUNCTION commercial_private.commercial_post_purchase_receipt_financials()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE v_order public.commercial_purchase_orders%ROWTYPE; v_party_account uuid; v_inventory_account uuid;
  v_grni_account uuid; v_entry_id uuid; v_entry_number bigint; v_total numeric; v_line_number integer:=0;
BEGIN
  SELECT * INTO v_order FROM public.commercial_purchase_orders
    WHERE id=NEW.purchase_order_id AND organization_id=NEW.organization_id;
  IF NOT FOUND THEN RAISE EXCEPTION 'purchase_order_not_found' USING ERRCODE='P0002'; END IF;
  -- Round cumulative received value per purchase line, then post only this receipt's delta.
  -- This prevents repeated partial receipts from rounding the same fractional minor unit upward.
  SELECT coalesce(sum(
      round((prior.quantity_before + ri.quantity::numeric) * ri.unit_cost_minor::numeric)
      - round(prior.quantity_before * ri.unit_cost_minor::numeric)
    ),0) INTO v_total
    FROM public.commercial_purchase_receipt_items ri
    CROSS JOIN LATERAL (
      SELECT coalesce(sum(pri.quantity::numeric),0) AS quantity_before
      FROM public.commercial_purchase_receipt_items pri
      JOIN public.commercial_purchase_receipts pr
        ON pr.id=pri.receipt_id AND pr.organization_id=pri.organization_id
      WHERE pri.organization_id=ri.organization_id
        AND pri.purchase_order_id=ri.purchase_order_id
        AND pri.purchase_order_item_id=ri.purchase_order_item_id
        AND pr.accounting_sequence < NEW.accounting_sequence
    ) prior
    WHERE ri.organization_id=NEW.organization_id AND ri.receipt_id=NEW.id;
  IF v_total=0 THEN RETURN NEW; END IF;
  IF v_total<0 OR v_total>9223372036854775807::numeric THEN RAISE EXCEPTION 'purchase_receipt_value_overflow' USING ERRCODE='22003'; END IF;
  v_party_account:=commercial_private.commercial_ensure_supplier_party_account(NEW.organization_id,v_order.supplier_id,v_order.currency_code);
  v_inventory_account:=commercial_private.commercial_ensure_supplier_ledger_account(NEW.organization_id,v_order.currency_code,'inventory_asset');
  v_grni_account:=commercial_private.commercial_ensure_supplier_ledger_account(NEW.organization_id,v_order.currency_code,'goods_received_not_invoiced');
  v_entry_number:=nextval('public.commercial_supplier_journal_entry_number_seq');
  INSERT INTO public.commercial_journal_entries(organization_id,entry_number,entry_date,description,source_type,source_id,status,idempotency_key,created_by,metadata)
  VALUES(NEW.organization_id,v_entry_number,NEW.received_at::date,'إثبات استلام أمر شراء '||v_order.order_number,'purchase_receipt',NEW.id,'draft',
    'purchase-receipt:'||NEW.id::text,NEW.received_by,
    jsonb_build_object('purchase_order_id',NEW.purchase_order_id,'receipt_id',NEW.id,'currency_code',v_order.currency_code,'value_minor',v_total::bigint))
  RETURNING id INTO v_entry_id;
  INSERT INTO public.commercial_journal_lines(organization_id,journal_entry_id,line_number,ledger_account_id,description,debit_minor,credit_minor,currency_code,party_account_id)
  VALUES(NEW.organization_id,v_entry_id,1,v_inventory_account,'إثبات قيمة المخزون المستلم',v_total::bigint,0,v_order.currency_code,NULL),
        (NEW.organization_id,v_entry_id,2,v_grni_account,'التزام بضائع مستلمة غير مفوترة',0,v_total::bigint,v_order.currency_code,v_party_account);
  UPDATE public.commercial_journal_entries SET status='posted',posted_by=NEW.received_by,posted_at=NEW.received_at
    WHERE id=v_entry_id AND organization_id=NEW.organization_id;
  INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
  VALUES(NEW.organization_id,NEW.received_by,'purchase_receipt.accounting_posted','purchase_receipt',NEW.id::text,
    jsonb_build_object('purchase_order_id',NEW.purchase_order_id,'journal_entry_id',v_entry_id,'value_minor',v_total::bigint,'currency_code',v_order.currency_code));
  RETURN NEW;
END;
$function$;

DROP TRIGGER IF EXISTS commercial_purchase_receipt_financials_after_insert ON public.commercial_purchase_receipts;
CREATE CONSTRAINT TRIGGER commercial_purchase_receipt_financials_after_insert
AFTER INSERT ON public.commercial_purchase_receipts
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION commercial_private.commercial_post_purchase_receipt_financials();

CREATE OR REPLACE FUNCTION commercial_private.commercial_post_supplier_invoice_financials()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE v_inventory_account uuid; v_payable_account uuid; v_grni_account uuid; v_variance_account uuid;
  v_tax_freight_account uuid; v_party_account uuid; v_entry_id uuid; v_entry_number bigint;
  v_expected_subtotal numeric; v_invoice_subtotal numeric; v_price_variance numeric;
  v_tax_freight numeric; v_total numeric; v_line_number integer:=0;
BEGIN
  -- Allocate expected cost incrementally per purchase line across invoices. A later partial
  -- invoice receives only the rounded cumulative-cost delta, avoiding repeated half-unit rounding.
  SELECT coalesce(sum(
      round((prior.quantity_before + si.quantity::numeric) * poi.unit_cost_minor::numeric)
      - round(prior.quantity_before * poi.unit_cost_minor::numeric)
    ),0)
    INTO v_expected_subtotal
    FROM public.commercial_supplier_invoice_items si
    JOIN public.commercial_purchase_order_items poi ON poi.id=si.purchase_order_item_id
      AND poi.organization_id=si.organization_id AND poi.purchase_order_id=NEW.purchase_order_id
    CROSS JOIN LATERAL (
      SELECT coalesce(sum(prior_item.quantity::numeric),0) AS quantity_before
      FROM public.commercial_supplier_invoice_items prior_item
      JOIN public.commercial_supplier_invoices prior_invoice
        ON prior_invoice.id=prior_item.supplier_invoice_id
       AND prior_invoice.organization_id=prior_item.organization_id
      WHERE prior_item.organization_id=si.organization_id
        AND prior_invoice.purchase_order_id=NEW.purchase_order_id
        AND prior_item.purchase_order_item_id=si.purchase_order_item_id
        AND prior_invoice.state='recorded'
        AND prior_invoice.accounting_sequence < NEW.accounting_sequence
    ) prior
    WHERE si.organization_id=NEW.organization_id AND si.supplier_invoice_id=NEW.id;
  SELECT coalesce(sum(line_total_minor),0) INTO v_invoice_subtotal
    FROM public.commercial_supplier_invoice_items WHERE organization_id=NEW.organization_id AND supplier_invoice_id=NEW.id;
  v_price_variance:=v_invoice_subtotal-v_expected_subtotal;
  v_tax_freight:=NEW.tax_minor::numeric+NEW.freight_minor::numeric;
  v_total:=NEW.total_minor::numeric;
  IF v_expected_subtotal<0 OR v_expected_subtotal>9223372036854775807::numeric
    OR abs(v_price_variance)>9223372036854775807::numeric OR v_tax_freight>9223372036854775807::numeric THEN
    RAISE EXCEPTION 'supplier_invoice_accounting_overflow' USING ERRCODE='22003';
  END IF;
  -- Zero-value invoices are valid (for example, free goods). Any accrued-cost difference
  -- is balanced through the configured purchase-price-variance account; no payable is created.
  v_party_account:=commercial_private.commercial_ensure_supplier_party_account(NEW.organization_id,NEW.supplier_id,NEW.currency_code);
  IF v_expected_subtotal>0 OR v_total>0 THEN
    v_grni_account:=commercial_private.commercial_ensure_supplier_ledger_account(NEW.organization_id,NEW.currency_code,'goods_received_not_invoiced');
    IF v_total>0 THEN v_payable_account:=commercial_private.commercial_ensure_supplier_ledger_account(NEW.organization_id,NEW.currency_code,'accounts_payable'); END IF;
    IF v_price_variance<>0 THEN v_variance_account:=commercial_private.commercial_ensure_supplier_ledger_account(NEW.organization_id,NEW.currency_code,'purchase_price_variance'); END IF;
    IF v_tax_freight>0 THEN v_tax_freight_account:=commercial_private.commercial_ensure_supplier_ledger_account(NEW.organization_id,NEW.currency_code,'purchase_tax_freight_expense'); END IF;
    v_entry_number:=nextval('public.commercial_supplier_journal_entry_number_seq');
    INSERT INTO public.commercial_journal_entries(organization_id,entry_number,entry_date,description,source_type,source_id,status,idempotency_key,created_by,metadata)
    VALUES(NEW.organization_id,v_entry_number,NEW.recorded_at::date,'تسوية فاتورة المورد '||NEW.invoice_number,'supplier_invoice',NEW.id,'draft',
      'supplier-invoice:'||NEW.id::text,NEW.recorded_by,
      jsonb_build_object('supplier_id',NEW.supplier_id,'purchase_order_id',NEW.purchase_order_id,'currency_code',NEW.currency_code,
        'expected_cost_minor',v_expected_subtotal::bigint,'invoice_subtotal_minor',v_invoice_subtotal::bigint,
        'price_variance_minor',v_price_variance::bigint,'tax_minor',NEW.tax_minor,'freight_minor',NEW.freight_minor,'total_minor',NEW.total_minor))
    RETURNING id INTO v_entry_id;
    IF v_expected_subtotal>0 THEN
      v_line_number:=v_line_number+1;
      INSERT INTO public.commercial_journal_lines(organization_id,journal_entry_id,line_number,ledger_account_id,description,debit_minor,credit_minor,currency_code,party_account_id)
      VALUES(NEW.organization_id,v_entry_id,v_line_number,v_grni_account,'تسوية البضائع المستلمة غير المفوترة',v_expected_subtotal::bigint,0,NEW.currency_code,v_party_account);
    END IF;
    IF v_price_variance>0 THEN
      v_line_number:=v_line_number+1;
      INSERT INTO public.commercial_journal_lines(organization_id,journal_entry_id,line_number,ledger_account_id,description,debit_minor,credit_minor,currency_code,party_account_id)
      VALUES(NEW.organization_id,v_entry_id,v_line_number,v_variance_account,'فروق أسعار شراء',v_price_variance::bigint,0,NEW.currency_code,NULL);
    ELSIF v_price_variance<0 THEN
      v_line_number:=v_line_number+1;
      INSERT INTO public.commercial_journal_lines(organization_id,journal_entry_id,line_number,ledger_account_id,description,debit_minor,credit_minor,currency_code,party_account_id)
      VALUES(NEW.organization_id,v_entry_id,v_line_number,v_variance_account, 'فروق أسعار شراء',0,abs(v_price_variance)::bigint,NEW.currency_code,NULL);
    END IF;
    IF v_tax_freight>0 THEN
      v_line_number:=v_line_number+1;
      INSERT INTO public.commercial_journal_lines(organization_id,journal_entry_id,line_number,ledger_account_id,description,debit_minor,credit_minor,currency_code,party_account_id)
      VALUES(NEW.organization_id,v_entry_id,v_line_number,v_tax_freight_account,'ضرائب وشحن فاتورة المورد',v_tax_freight::bigint,0,NEW.currency_code,NULL);
    END IF;
    IF v_total>0 THEN
      v_line_number:=v_line_number+1;
      INSERT INTO public.commercial_journal_lines(organization_id,journal_entry_id,line_number,ledger_account_id,description,debit_minor,credit_minor,currency_code,party_account_id)
      VALUES(NEW.organization_id,v_entry_id,v_line_number,v_payable_account,'ذمم مستحقة للمورد',0,v_total::bigint,NEW.currency_code,v_party_account);
    END IF;
    UPDATE public.commercial_journal_entries SET status='posted',posted_by=NEW.recorded_by,posted_at=NEW.recorded_at
      WHERE id=v_entry_id AND organization_id=NEW.organization_id;
  END IF;
  INSERT INTO public.commercial_supplier_obligations(organization_id,supplier_invoice_id,supplier_id,party_account_id,journal_entry_id,currency_code,amount_due_minor,amount_paid_minor,state)
  VALUES(NEW.organization_id,NEW.id,NEW.supplier_id,v_party_account,v_entry_id,NEW.currency_code,NEW.total_minor,0,
    CASE WHEN NEW.total_minor=0 THEN 'paid' ELSE 'open' END);
  RETURN NEW;
END;
$function$;

DROP TRIGGER IF EXISTS commercial_supplier_invoice_financials_after_insert ON public.commercial_supplier_invoices;
CREATE CONSTRAINT TRIGGER commercial_supplier_invoice_financials_after_insert
AFTER INSERT ON public.commercial_supplier_invoices
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION commercial_private.commercial_post_supplier_invoice_financials();

REVOKE ALL ON FUNCTION commercial_private.commercial_post_purchase_receipt_financials() FROM PUBLIC,anon,authenticated;
REVOKE ALL ON FUNCTION commercial_private.commercial_post_supplier_invoice_financials() FROM PUBLIC,anon,authenticated;


-- Fix the supplier payment journal source: the obligation row variable is v_ob, not p_ob.
-- The old reference only failed when a payment reached journal-entry insertion, escaping source-level tests.
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
    OR p_currency_code IS NULL OR p_currency_code !~ '^[A-Z]{3}$' OR p_payment_method IS NULL OR p_payment_method NOT IN ('cash','bank_transfer','check','other')
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
  VALUES(p_organization_id,v_entry_number,current_date,'سداد فاتورة مورد','supplier_payment',v_ob.supplier_invoice_id,'draft',
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
