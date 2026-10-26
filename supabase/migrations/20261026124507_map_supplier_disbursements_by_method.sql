-- Map supplier disbursements to the actual payment instrument instead of always crediting cash.
ALTER TABLE public.commercial_gl_account_mappings DROP CONSTRAINT IF EXISTS commercial_gl_account_mappings_mapping_key_check;
ALTER TABLE public.commercial_gl_account_mappings ADD CONSTRAINT commercial_gl_account_mappings_mapping_key_check
CHECK (mapping_key IN ('cash','bank','card_clearing','other_receipts','check_clearing','other_disbursements','accounts_receivable','sales_revenue','sales_returns','sales_discounts','sales_tax_payable','inventory','cost_of_goods_sold','refunds_payable'));

CREATE OR REPLACE FUNCTION commercial_private.commercial_initialize_gl_chart(p_org_id uuid)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE v_currency text;
BEGIN
  SELECT default_currency_code INTO v_currency FROM public.commercial_organizations WHERE id=p_org_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'organization_not_found'; END IF;
  INSERT INTO public.commercial_ledger_accounts(organization_id,account_code,account_name,account_type,normal_balance,currency_code,is_system,is_active)
  VALUES
    (p_org_id,'1000','النقدية والصندوق','asset','debit',v_currency,true,true),
    (p_org_id,'BANK_'||v_currency,'الحسابات البنكية - '||v_currency,'asset','debit',v_currency,true,true),
    (p_org_id,'CARDCLR_'||v_currency,'مقاصة مدفوعات البطاقات - '||v_currency,'asset','debit',v_currency,true,true),
    (p_org_id,'OTHERREC_'||v_currency,'مقبوضات أخرى قيد التسوية - '||v_currency,'asset','debit',v_currency,true,true),
    (p_org_id,'CHECKCLR_'||v_currency,'شيكات قيد التحصيل/التسوية - '||v_currency,'asset','debit',v_currency,true,true),
    (p_org_id,'OTHERDISB_'||v_currency,'مدفوعات أخرى قيد التسوية - '||v_currency,'asset','debit',v_currency,true,true),
    (p_org_id,'1100','ذمم العملاء','asset','debit',v_currency,true,true),
    (p_org_id,'1200','المخزون','asset','debit',v_currency,true,true),
    (p_org_id,'2100','مبالغ مردودات مستحقة للعملاء','liability','credit',v_currency,true,true),
    (p_org_id,'STAX_'||v_currency,'ضريبة مبيعات مستحقة - '||v_currency,'liability','credit',v_currency,true,true),
    (p_org_id,'4000','إيرادات المبيعات','revenue','credit',v_currency,true,true),
    (p_org_id,'4010','مردودات ومسموحات المبيعات','revenue','debit',v_currency,true,true),
    (p_org_id,'SDISC_'||v_currency,'خصومات المبيعات - '||v_currency,'revenue','debit',v_currency,true,true),
    (p_org_id,'5000','تكلفة البضاعة المباعة','expense','debit',v_currency,true,true)
  ON CONFLICT (organization_id,account_code) DO NOTHING;
  INSERT INTO public.commercial_gl_account_mappings(organization_id,mapping_key,ledger_account_id)
  SELECT p_org_id,x.mapping_key,a.id FROM (VALUES
    ('cash','1000','asset','debit'),('bank','BANK_'||v_currency,'asset','debit'),
    ('card_clearing','CARDCLR_'||v_currency,'asset','debit'),('other_receipts','OTHERREC_'||v_currency,'asset','debit'),
    ('check_clearing','CHECKCLR_'||v_currency,'asset','debit'),('other_disbursements','OTHERDISB_'||v_currency,'asset','debit'),
    ('accounts_receivable','1100','asset','debit'),('inventory','1200','asset','debit'),
    ('refunds_payable','2100','liability','credit'),('sales_tax_payable','STAX_'||v_currency,'liability','credit'),
    ('sales_revenue','4000','revenue','credit'),('sales_returns','4010','revenue','debit'),
    ('sales_discounts','SDISC_'||v_currency,'revenue','debit'),('cost_of_goods_sold','5000','expense','debit')
  ) AS x(mapping_key,account_code,account_type,normal_balance)
  JOIN public.commercial_ledger_accounts a ON a.organization_id=p_org_id AND a.account_code=x.account_code
    AND a.currency_code=v_currency AND a.is_active AND a.account_type=x.account_type AND a.normal_balance=x.normal_balance
  ON CONFLICT (organization_id,mapping_key) DO NOTHING;
END;
$function$;

INSERT INTO public.commercial_ledger_accounts(organization_id,account_code,account_name,account_type,normal_balance,currency_code,is_system,is_active)
SELECT o.id,x.account_code,x.account_name,'asset','debit',o.default_currency_code,true,true
FROM public.commercial_organizations o CROSS JOIN LATERAL (VALUES
  ('CHECKCLR_'||o.default_currency_code,'شيكات قيد التحصيل/التسوية - '||o.default_currency_code),
  ('OTHERDISB_'||o.default_currency_code,'مدفوعات أخرى قيد التسوية - '||o.default_currency_code)
) AS x(account_code,account_name) ON CONFLICT (organization_id,account_code) DO NOTHING;
INSERT INTO public.commercial_gl_account_mappings(organization_id,mapping_key,ledger_account_id)
SELECT o.id,x.mapping_key,a.id FROM public.commercial_organizations o
CROSS JOIN LATERAL (VALUES ('check_clearing','CHECKCLR_'||o.default_currency_code),('other_disbursements','OTHERDISB_'||o.default_currency_code)) AS x(mapping_key,account_code)
JOIN public.commercial_ledger_accounts a ON a.organization_id=o.id AND a.account_code=x.account_code AND a.currency_code=o.default_currency_code
  AND a.is_active AND a.account_type='asset' AND a.normal_balance='debit'
ON CONFLICT (organization_id,mapping_key) DO NOTHING;

CREATE OR REPLACE FUNCTION commercial_private.commercial_record_supplier_payment(
  p_organization_id uuid,p_obligation_id uuid,p_amount_minor bigint,p_currency_code text,
  p_payment_method text,p_evidence_reference text,p_idempotency_key text
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE v_uid uuid:=auth.uid(); v_ob public.commercial_supplier_obligations%ROWTYPE; v_existing public.commercial_supplier_payments%ROWTYPE;
  v_fingerprint text; v_payment_account uuid; v_payment_mapping_key text; v_payable_account uuid; v_entry_id uuid; v_entry_number bigint; v_paid numeric;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_organization_id IS NULL OR p_obligation_id IS NULL OR p_amount_minor IS NULL OR p_amount_minor<=0
    OR p_currency_code IS NULL OR p_currency_code !~ '^[A-Z]{3}$' OR p_payment_method IS NULL OR p_payment_method NOT IN ('cash','bank_transfer','check','other')
    OR p_evidence_reference IS NULL OR length(btrim(p_evidence_reference)) NOT BETWEEN 3 AND 200
    OR p_idempotency_key IS NULL OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200 THEN RAISE EXCEPTION 'invalid_supplier_payment_request' USING ERRCODE='22023'; END IF;
  IF NOT EXISTS (SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=p_organization_id AND m.user_id=v_uid AND m.state='active' AND m.role IN ('owner','admin','manager')) THEN RAISE EXCEPTION 'supplier_payment_permission_required' USING ERRCODE='42501'; END IF;
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
  SELECT ledger_account_id INTO v_payable_account FROM public.commercial_supplier_gl_accounts WHERE organization_id=p_organization_id AND currency_code=p_currency_code AND account_role='accounts_payable';
  v_payment_mapping_key:=CASE p_payment_method WHEN 'cash' THEN 'cash' WHEN 'bank_transfer' THEN 'bank' WHEN 'check' THEN 'check_clearing' WHEN 'other' THEN 'other_disbursements' ELSE NULL END;
  SELECT m.ledger_account_id INTO v_payment_account FROM public.commercial_gl_account_mappings m JOIN public.commercial_ledger_accounts a ON a.organization_id=m.organization_id AND a.id=m.ledger_account_id
    WHERE m.organization_id=p_organization_id AND m.mapping_key=v_payment_mapping_key AND a.currency_code=p_currency_code AND a.account_type='asset' AND a.normal_balance='debit' AND a.is_active;
  IF v_payable_account IS NULL OR v_payment_account IS NULL THEN RAISE EXCEPTION 'supplier_payment_account_not_configured_for_currency' USING ERRCODE='55000'; END IF;
  v_entry_number:=nextval('public.commercial_supplier_journal_entry_number_seq');
  INSERT INTO public.commercial_journal_entries(organization_id,entry_number,entry_date,description,source_type,source_id,status,idempotency_key,created_by,posted_by,posted_at,metadata)
  VALUES(p_organization_id,v_entry_number,current_date,'سداد فاتورة مورد','supplier_payment',v_ob.supplier_invoice_id,'draft','supplier-payment:'||btrim(p_idempotency_key),v_uid,NULL,NULL,
    jsonb_build_object('obligation_id',p_obligation_id,'payment_method',p_payment_method,'payment_mapping_key',v_payment_mapping_key,'evidence_reference',btrim(p_evidence_reference),'amount_minor',p_amount_minor,'currency_code',p_currency_code))
  RETURNING id INTO v_entry_id;
  INSERT INTO public.commercial_journal_lines(organization_id,journal_entry_id,line_number,ledger_account_id,description,debit_minor,credit_minor,currency_code,party_account_id)
  VALUES(p_organization_id,v_entry_id,1,v_payable_account,'تخفيض ذمم المورد',p_amount_minor,0,p_currency_code,v_ob.party_account_id),
        (p_organization_id,v_entry_id,2,v_payment_account,'سداد للمورد ('||v_payment_mapping_key||')',0,p_amount_minor,p_currency_code,NULL);
  UPDATE public.commercial_journal_entries SET status='posted',posted_by=v_uid,posted_at=now() WHERE id=v_entry_id AND organization_id=p_organization_id;
  INSERT INTO public.commercial_supplier_payments(organization_id,obligation_id,supplier_invoice_id,amount_minor,currency_code,payment_method,evidence_reference,idempotency_key,request_fingerprint,journal_entry_id,recorded_by)
  VALUES(p_organization_id,p_obligation_id,v_ob.supplier_invoice_id,p_amount_minor,p_currency_code,p_payment_method,btrim(p_evidence_reference),btrim(p_idempotency_key),v_fingerprint,v_entry_id,v_uid)
  RETURNING id INTO v_existing.id;
  v_paid:=v_ob.amount_paid_minor+p_amount_minor;
  UPDATE public.commercial_supplier_obligations SET amount_paid_minor=v_paid,state=CASE WHEN v_paid=amount_due_minor THEN 'paid' ELSE 'partially_paid' END,updated_at=now()
  WHERE id=p_obligation_id AND organization_id=p_organization_id;
  INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
  VALUES(p_organization_id,v_uid,'supplier_payment.recorded','supplier_payment',v_existing.id::text,jsonb_build_object('obligation_id',p_obligation_id,'amount_minor',p_amount_minor,'currency_code',p_currency_code,'journal_entry_id',v_entry_id));
  RETURN jsonb_build_object('payment_id',v_existing.id,'journal_entry_id',v_entry_id,'outstanding_minor',v_ob.amount_due_minor-v_paid,'payment_mapping_key',v_payment_mapping_key,'idempotent_replay',false);
END;
$function$;
REVOKE ALL ON FUNCTION commercial_private.commercial_record_supplier_payment(uuid,uuid,bigint,text,text,text,text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION commercial_private.commercial_record_supplier_payment(uuid,uuid,bigint,text,text,text,text) TO authenticated;
CREATE OR REPLACE FUNCTION public.commercial_record_supplier_payment(p_organization_id uuid,p_obligation_id uuid,p_amount_minor bigint,p_currency_code text,p_payment_method text,p_evidence_reference text,p_idempotency_key text)
RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$ SELECT commercial_private.commercial_record_supplier_payment(p_organization_id,p_obligation_id,p_amount_minor,p_currency_code,p_payment_method,p_evidence_reference,p_idempotency_key); $function$;
REVOKE ALL ON FUNCTION public.commercial_record_supplier_payment(uuid,uuid,bigint,text,text,text,text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_record_supplier_payment(uuid,uuid,bigint,text,text,text,text) TO authenticated;
