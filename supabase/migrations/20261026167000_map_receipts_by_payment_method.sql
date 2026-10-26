-- Route each receipt method to its own correctly classified GL asset account.
ALTER TABLE public.commercial_gl_account_mappings DROP CONSTRAINT IF EXISTS commercial_gl_account_mappings_mapping_key_check;
ALTER TABLE public.commercial_gl_account_mappings ADD CONSTRAINT commercial_gl_account_mappings_mapping_key_check
CHECK (mapping_key IN ('cash','bank','card_clearing','other_receipts','accounts_receivable','sales_revenue','sales_returns','sales_discounts','sales_tax_payable','inventory','cost_of_goods_sold','refunds_payable'));
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
  ('CARDCLR_'||o.default_currency_code,'مقاصة مدفوعات البطاقات - '||o.default_currency_code),
  ('OTHERREC_'||o.default_currency_code,'مقبوضات أخرى قيد التسوية - '||o.default_currency_code)
) AS x(account_code,account_name) ON CONFLICT (organization_id,account_code) DO NOTHING;
INSERT INTO public.commercial_gl_account_mappings(organization_id,mapping_key,ledger_account_id)
SELECT o.id,x.mapping_key,a.id FROM public.commercial_organizations o
CROSS JOIN LATERAL (VALUES ('card_clearing','CARDCLR_'||o.default_currency_code),('other_receipts','OTHERREC_'||o.default_currency_code)) AS x(mapping_key,account_code)
JOIN public.commercial_ledger_accounts a ON a.organization_id=o.id AND a.account_code=x.account_code
  AND a.currency_code=o.default_currency_code AND a.is_active AND a.account_type='asset' AND a.normal_balance='debit'
ON CONFLICT (organization_id,mapping_key) DO NOTHING;

CREATE OR REPLACE FUNCTION public.commercial_gl_post_payment(p_payment_id uuid)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE p public.commercial_payments%ROWTYPE; s public.commercial_sales%ROWTYPE; m record;
  v_party uuid; v_entry uuid; v_paid numeric; v_actor uuid; v_receipt_mapping_key text;
BEGIN
  IF auth.uid() IS NULL AND pg_trigger_depth()=0 THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  SELECT * INTO p FROM public.commercial_payments WHERE id=p_payment_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'payment_not_found' USING ERRCODE='P0002'; END IF;
  IF p.status<>'succeeded' OR p.sale_id IS NULL THEN RAISE EXCEPTION 'succeeded_payment_with_sale_required' USING ERRCODE='55000'; END IF;
  SELECT * INTO s FROM public.commercial_sales WHERE id=p.sale_id FOR UPDATE;
  IF NOT FOUND OR s.organization_id IS DISTINCT FROM p.organization_id OR s.currency_code<>p.currency_code THEN RAISE EXCEPTION 'payment_sale_scope_or_currency_mismatch' USING ERRCODE='55000'; END IF;
  IF pg_trigger_depth()=0 AND NOT EXISTS(SELECT 1 FROM public.commercial_organization_members om WHERE om.organization_id=p.organization_id AND om.user_id=auth.uid() AND om.state='active' AND om.role IN ('owner','admin','manager')) THEN RAISE EXCEPTION 'accounting_permission_required' USING ERRCODE='42501'; END IF;
  IF s.payment_status NOT IN ('pending','partially_paid') THEN RAISE EXCEPTION 'payment_not_applicable_to_paid_or_failed_sale' USING ERRCODE='55000'; END IF;
  IF s.customer_id IS NULL THEN RAISE EXCEPTION 'payment_sale_customer_required' USING ERRCODE='55000'; END IF;
  v_receipt_mapping_key:=CASE p.payment_method_type WHEN 'cash' THEN 'cash' WHEN 'bank_transfer' THEN 'bank' WHEN 'card' THEN 'card_clearing' WHEN 'other' THEN 'other_receipts' ELSE NULL END;
  v_party:=commercial_private.commercial_customer_party_account(s.organization_id,s.customer_id,s.currency_code);
  SELECT max(ledger_account_id) FILTER(WHERE mapping_key=v_receipt_mapping_key) receipt_account,
         max(ledger_account_id) FILTER(WHERE mapping_key='accounts_receivable') ar INTO m
  FROM public.commercial_gl_account_mappings WHERE organization_id=p.organization_id;
  IF m.receipt_account IS NULL OR m.ar IS NULL THEN RAISE EXCEPTION 'receipt_and_ar_mappings_required' USING ERRCODE='55000'; END IF;
  SELECT COALESCE(sum(amount_minor),0) INTO v_paid FROM public.commercial_payments WHERE sale_id=s.id AND status='succeeded' AND id<>p.id;
  IF v_paid+p.amount_minor>s.total_minor THEN RAISE EXCEPTION 'payment_exceeds_sale_total' USING ERRCODE='55000'; END IF;
  v_actor:=COALESCE(auth.uid(),s.user_id);
  v_entry:=commercial_private.commercial_gl_post_entry(p.organization_id,'payment:'||p.id,'payment',p.id,'Customer collection for receipt '||s.receipt_number,p.currency_code,
    jsonb_build_array(
      jsonb_build_object('ledger_account_id',m.receipt_account,'debit_minor',p.amount_minor,'credit_minor',0,'description',CASE v_receipt_mapping_key WHEN 'cash' THEN 'Customer cash collection' WHEN 'bank' THEN 'Customer bank collection' WHEN 'card_clearing' THEN 'Card collection clearing' ELSE 'Other receipt clearing' END),
      jsonb_build_object('ledger_account_id',m.ar,'debit_minor',0,'credit_minor',p.amount_minor,'description','Reduce customer receivable','party_account_id',v_party)
    ),v_actor,NULL);
  UPDATE public.commercial_sales SET payment_status=CASE WHEN v_paid+p.amount_minor=s.total_minor THEN 'paid' ELSE 'partially_paid' END,updated_at=now() WHERE id=s.id;
  RETURN jsonb_build_object('payment_id',p.id,'journal_entry_id',v_entry,'posted',true,'receipt_mapping_key',v_receipt_mapping_key);
END;
$function$;
