-- Validate account type/balance/currency before assigning any default GL mapping.
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
SELECT o.id,x.account_code,x.account_name,x.account_type,x.normal_balance,o.default_currency_code,true,true
FROM public.commercial_organizations o CROSS JOIN LATERAL (VALUES
  ('BANK_'||o.default_currency_code,'الحسابات البنكية - '||o.default_currency_code,'asset','debit'),
  ('STAX_'||o.default_currency_code,'ضريبة مبيعات مستحقة - '||o.default_currency_code,'liability','credit'),
  ('SDISC_'||o.default_currency_code,'خصومات المبيعات - '||o.default_currency_code,'revenue','debit')
) AS x(account_code,account_name,account_type,normal_balance)
ON CONFLICT (organization_id,account_code) DO NOTHING;

INSERT INTO public.commercial_gl_account_mappings(organization_id,mapping_key,ledger_account_id)
SELECT o.id,x.mapping_key,a.id FROM public.commercial_organizations o
CROSS JOIN LATERAL (VALUES
  ('bank','BANK_'||o.default_currency_code,'asset','debit'),
  ('sales_tax_payable','STAX_'||o.default_currency_code,'liability','credit'),
  ('sales_discounts','SDISC_'||o.default_currency_code,'revenue','debit')
) AS x(mapping_key,account_code,account_type,normal_balance)
JOIN public.commercial_ledger_accounts a ON a.organization_id=o.id AND a.account_code=x.account_code
  AND a.currency_code=o.default_currency_code AND a.is_active AND a.account_type=x.account_type AND a.normal_balance=x.normal_balance
ON CONFLICT (organization_id,mapping_key) DO NOTHING;
