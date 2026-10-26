-- Keep exposed RPC wrappers SECURITY INVOKER; place guarded privileged writes in non-exposed schema.
GRANT USAGE ON SCHEMA commercial_private TO authenticated;

CREATE OR REPLACE FUNCTION commercial_private.commercial_record_expense(
  p_organization_id uuid,p_category text,p_amount_minor bigint,p_currency_code text,
  p_payment_method text,p_evidence_reference text,p_note text,p_idempotency_key text
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE v_uid uuid:=auth.uid(); v_fingerprint text; v_existing public.commercial_expense_vouchers%ROWTYPE;
  v_cash_account uuid; v_expense_account uuid; v_voucher_id uuid; v_entry_id uuid; v_account_code text;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_organization_id IS NULL OR p_category IS NULL OR length(btrim(p_category)) NOT BETWEEN 1 AND 120
    OR p_amount_minor IS NULL OR p_amount_minor<=0 OR p_currency_code IS NULL OR p_currency_code !~ '^[A-Z]{3}$'
    OR p_payment_method IS NULL OR p_payment_method NOT IN ('cash','bank_transfer')
    OR p_evidence_reference IS NULL OR length(btrim(p_evidence_reference)) NOT BETWEEN 3 AND 200
    OR p_note IS NULL OR length(p_note)>1000 OR p_idempotency_key IS NULL OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200 THEN
    RAISE EXCEPTION 'invalid_expense_request' USING ERRCODE='22023';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=p_organization_id AND m.user_id=v_uid AND m.state='active' AND m.role IN ('owner','admin','manager')) THEN RAISE EXCEPTION 'expense_management_permission_required' USING ERRCODE='42501'; END IF;
  PERFORM pg_advisory_xact_lock(hashtext(p_organization_id::text),hashtext('expense:'||btrim(p_idempotency_key)));
  v_fingerprint:=md5(jsonb_build_object('organization_id',p_organization_id,'category',btrim(p_category),'amount_minor',p_amount_minor,'currency_code',p_currency_code,'payment_method',p_payment_method,'evidence_reference',btrim(p_evidence_reference),'note',p_note)::text);
  SELECT * INTO v_existing FROM public.commercial_expense_vouchers WHERE organization_id=p_organization_id AND idempotency_key=btrim(p_idempotency_key) FOR UPDATE;
  IF FOUND THEN
    IF v_existing.request_fingerprint IS DISTINCT FROM v_fingerprint THEN RAISE EXCEPTION 'expense_idempotency_key_reused_with_different_request' USING ERRCODE='23505'; END IF;
    RETURN jsonb_build_object('voucher_id',v_existing.id,'journal_entry_id',v_existing.journal_entry_id,'amount_minor',v_existing.amount_minor,'currency_code',v_existing.currency_code,'idempotent_replay',true);
  END IF;
  SELECT m.ledger_account_id INTO v_cash_account FROM public.commercial_gl_account_mappings m JOIN public.commercial_ledger_accounts a ON a.organization_id=m.organization_id AND a.id=m.ledger_account_id
  WHERE m.organization_id=p_organization_id AND m.mapping_key=CASE WHEN p_payment_method='cash' THEN 'cash' ELSE 'bank' END
    AND a.currency_code=p_currency_code AND a.account_type='asset' AND a.normal_balance='debit' AND a.is_active;
  IF v_cash_account IS NULL THEN RAISE EXCEPTION 'expense_cash_or_bank_account_not_configured_for_currency' USING ERRCODE='55000'; END IF;
  v_account_code:='EXP_'||p_currency_code||'_'||upper(substr(md5(lower(btrim(p_category))),1,12));
  INSERT INTO public.commercial_ledger_accounts(organization_id,account_code,account_name,account_type,normal_balance,currency_code,is_system,is_active)
    VALUES(p_organization_id,v_account_code,'مصروف: '||btrim(p_category)||' - '||p_currency_code,'expense','debit',p_currency_code,true,true)
    ON CONFLICT (organization_id,account_code) DO UPDATE SET updated_at=now() RETURNING id INTO v_expense_account;
  IF NOT EXISTS (SELECT 1 FROM public.commercial_ledger_accounts a WHERE a.organization_id=p_organization_id AND a.id=v_expense_account AND a.account_type='expense' AND a.normal_balance='debit' AND a.currency_code=p_currency_code AND a.is_active) THEN RAISE EXCEPTION 'expense_ledger_account_code_conflict' USING ERRCODE='23505'; END IF;
  INSERT INTO public.commercial_expense_vouchers(organization_id,category,amount_minor,currency_code,payment_method,evidence_reference,note,idempotency_key,request_fingerprint,recorded_by)
    VALUES(p_organization_id,btrim(p_category),p_amount_minor,p_currency_code,p_payment_method,btrim(p_evidence_reference),p_note,btrim(p_idempotency_key),v_fingerprint,v_uid) RETURNING id INTO v_voucher_id;
  v_entry_id:=commercial_private.commercial_gl_post_entry(p_organization_id,'expense-voucher:'||v_voucher_id::text,'expense_voucher',v_voucher_id,'سند صرف: '||btrim(p_category),p_currency_code,
    jsonb_build_array(jsonb_build_object('ledger_account_id',v_expense_account,'debit_minor',p_amount_minor,'credit_minor',0,'description','مصروف: '||btrim(p_category)),
      jsonb_build_object('ledger_account_id',v_cash_account,'debit_minor',0,'credit_minor',p_amount_minor,'description',CASE WHEN p_payment_method='cash' THEN 'صرف نقدي' ELSE 'صرف بنكي' END)),v_uid,NULL);
  UPDATE public.commercial_expense_vouchers SET journal_entry_id=v_entry_id WHERE id=v_voucher_id AND organization_id=p_organization_id;
  INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
    VALUES(p_organization_id,v_uid,'expense_voucher.recorded','expense_voucher',v_voucher_id::text,jsonb_build_object('amount_minor',p_amount_minor,'currency_code',p_currency_code,'category',btrim(p_category),'payment_method',p_payment_method,'journal_entry_id',v_entry_id));
  RETURN jsonb_build_object('voucher_id',v_voucher_id,'journal_entry_id',v_entry_id,'amount_minor',p_amount_minor,'currency_code',p_currency_code,'idempotent_replay',false);
END;
$function$;
REVOKE ALL ON FUNCTION commercial_private.commercial_record_expense(uuid,text,bigint,text,text,text,text,text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION commercial_private.commercial_record_expense(uuid,text,bigint,text,text,text,text,text) TO authenticated;
CREATE OR REPLACE FUNCTION public.commercial_record_expense(p_organization_id uuid,p_category text,p_amount_minor bigint,p_currency_code text,p_payment_method text,p_evidence_reference text,p_note text,p_idempotency_key text)
RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$ SELECT commercial_private.commercial_record_expense(p_organization_id,p_category,p_amount_minor,p_currency_code,p_payment_method,p_evidence_reference,p_note,p_idempotency_key); $function$;
REVOKE ALL ON FUNCTION public.commercial_record_expense(uuid,text,bigint,text,text,text,text,text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_record_expense(uuid,text,bigint,text,text,text,text,text) TO authenticated;

CREATE OR REPLACE FUNCTION commercial_private.commercial_record_customer_payment(p_sale_id uuid,p_amount_minor bigint,p_payment_method_type text,p_idempotency_key text)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE v_uid uuid:=auth.uid(); v_sale public.commercial_sales%ROWTYPE; v_payment public.commercial_payments%ROWTYPE; v_existing public.commercial_payments%ROWTYPE; v_entry_id uuid;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_sale_id IS NULL OR p_amount_minor IS NULL OR p_amount_minor<=0 OR p_payment_method_type IS NULL OR p_payment_method_type NOT IN ('cash','bank_transfer','card','other')
    OR p_idempotency_key IS NULL OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200 THEN RAISE EXCEPTION 'invalid_customer_payment_request' USING ERRCODE='22023'; END IF;
  PERFORM pg_advisory_xact_lock(hashtext(v_uid::text),hashtext(btrim(p_idempotency_key)));
  SELECT * INTO v_existing FROM public.commercial_payments WHERE idempotency_key=btrim(p_idempotency_key)
    AND organization_id IN (SELECT m.organization_id FROM public.commercial_organization_members m WHERE m.user_id=v_uid AND m.state='active') FOR UPDATE;
  IF FOUND THEN
    IF NOT EXISTS (SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=v_existing.organization_id AND m.user_id=v_uid AND m.state='active' AND m.role IN ('owner','admin','manager')) THEN RAISE EXCEPTION 'accounting_permission_required' USING ERRCODE='42501'; END IF;
    IF v_existing.sale_id IS DISTINCT FROM p_sale_id OR v_existing.amount_minor IS DISTINCT FROM p_amount_minor OR v_existing.payment_method_type IS DISTINCT FROM p_payment_method_type THEN RAISE EXCEPTION 'idempotency_key_reused_with_different_request' USING ERRCODE='23505'; END IF;
    IF v_existing.status<>'succeeded' THEN RAISE EXCEPTION 'existing_payment_not_succeeded' USING ERRCODE='55000'; END IF;
    SELECT id INTO v_entry_id FROM public.commercial_journal_entries WHERE organization_id=v_existing.organization_id AND source_type='payment' AND source_id=v_existing.id AND status='posted' ORDER BY created_at DESC LIMIT 1;
    IF v_entry_id IS NULL THEN RAISE EXCEPTION 'customer_payment_journal_not_posted' USING ERRCODE='55000'; END IF;
    RETURN jsonb_build_object('payment_id',v_existing.id,'sale_id',v_existing.sale_id,'amount_minor',v_existing.amount_minor,'currency_code',v_existing.currency_code,'status',v_existing.status,'journal_entry_id',v_entry_id,'idempotent_replay',true);
  END IF;
  SELECT * INTO v_sale FROM public.commercial_sales WHERE id=p_sale_id FOR UPDATE;
  IF NOT FOUND OR v_sale.organization_id IS NULL THEN RAISE EXCEPTION 'completed_sale_required' USING ERRCODE='P0002'; END IF;
  IF NOT EXISTS (SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=v_sale.organization_id AND m.user_id=v_uid AND m.state='active' AND m.role IN ('owner','admin','manager')) THEN RAISE EXCEPTION 'accounting_permission_required' USING ERRCODE='42501'; END IF;
  PERFORM pg_advisory_xact_lock(hashtext(v_sale.organization_id::text),hashtext('customer-payment:'||btrim(p_idempotency_key)));
  SELECT * INTO v_existing FROM public.commercial_payments WHERE organization_id=v_sale.organization_id AND idempotency_key=btrim(p_idempotency_key) FOR UPDATE;
  IF FOUND THEN
    IF v_existing.sale_id IS DISTINCT FROM p_sale_id OR v_existing.amount_minor IS DISTINCT FROM p_amount_minor OR v_existing.payment_method_type IS DISTINCT FROM p_payment_method_type THEN RAISE EXCEPTION 'idempotency_key_reused_with_different_request' USING ERRCODE='23505'; END IF;
    IF v_existing.status<>'succeeded' THEN RAISE EXCEPTION 'existing_payment_not_succeeded' USING ERRCODE='55000'; END IF;
    SELECT id INTO v_entry_id FROM public.commercial_journal_entries WHERE organization_id=v_existing.organization_id AND source_type='payment' AND source_id=v_existing.id AND status='posted' ORDER BY created_at DESC LIMIT 1;
    IF v_entry_id IS NULL THEN RAISE EXCEPTION 'customer_payment_journal_not_posted' USING ERRCODE='55000'; END IF;
    RETURN jsonb_build_object('payment_id',v_existing.id,'sale_id',v_existing.sale_id,'amount_minor',v_existing.amount_minor,'currency_code',v_existing.currency_code,'status',v_existing.status,'journal_entry_id',v_entry_id,'idempotent_replay',true);
  END IF;
  IF v_sale.status<>'completed' OR v_sale.customer_id IS NULL OR v_sale.payment_status NOT IN ('pending','partially_paid') THEN RAISE EXCEPTION 'outstanding_customer_sale_required' USING ERRCODE='55000'; END IF;
  IF EXISTS (SELECT 1 FROM public.commercial_payments p WHERE p.sale_id=v_sale.id AND p.status IN ('refunded','partially_refunded'))
    OR EXISTS (SELECT 1 FROM public.commercial_sales_returns r WHERE r.sale_id=v_sale.id AND r.status NOT IN ('rejected','cancelled')) THEN RAISE EXCEPTION 'sale_return_reconciliation_required' USING ERRCODE='22023'; END IF;
  INSERT INTO public.commercial_payments(organization_id,sale_id,provider,provider_payment_id,idempotency_key,amount_minor,currency_code,status,payment_method_type)
  VALUES(v_sale.organization_id,v_sale.id,'manual','manual:'||btrim(p_idempotency_key),btrim(p_idempotency_key),p_amount_minor,v_sale.currency_code,'succeeded',p_payment_method_type) RETURNING * INTO v_payment;
  SELECT id INTO v_entry_id FROM public.commercial_journal_entries WHERE organization_id=v_payment.organization_id AND source_type='payment' AND source_id=v_payment.id AND status='posted' ORDER BY created_at DESC LIMIT 1;
  IF v_entry_id IS NULL THEN RAISE EXCEPTION 'customer_payment_journal_not_posted' USING ERRCODE='55000'; END IF;
  RETURN jsonb_build_object('payment_id',v_payment.id,'sale_id',v_sale.id,'amount_minor',v_payment.amount_minor,'currency_code',v_payment.currency_code,'status',v_payment.status,'journal_entry_id',v_entry_id,'idempotent_replay',false);
END;
$function$;
REVOKE ALL ON FUNCTION commercial_private.commercial_record_customer_payment(uuid,bigint,text,text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION commercial_private.commercial_record_customer_payment(uuid,bigint,text,text) TO authenticated;
CREATE OR REPLACE FUNCTION public.commercial_record_customer_payment(p_sale_id uuid,p_amount_minor bigint,p_payment_method_type text,p_idempotency_key text)
RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$ SELECT commercial_private.commercial_record_customer_payment(p_sale_id,p_amount_minor,p_payment_method_type,p_idempotency_key); $function$;
REVOKE ALL ON FUNCTION public.commercial_record_customer_payment(uuid,bigint,text,text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_record_customer_payment(uuid,bigint,text,text) TO authenticated;
