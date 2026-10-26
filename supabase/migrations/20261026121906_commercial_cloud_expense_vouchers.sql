-- Cloud operating expenses with balanced journal posting.
CREATE TABLE IF NOT EXISTS public.commercial_expense_vouchers (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
  category text NOT NULL CHECK (length(btrim(category)) BETWEEN 1 AND 120),
  amount_minor bigint NOT NULL CHECK (amount_minor > 0),
  currency_code text NOT NULL CHECK (currency_code ~ '^[A-Z]{3}$'),
  payment_method text NOT NULL CHECK (payment_method IN ('cash','bank_transfer')),
  evidence_reference text NOT NULL CHECK (length(btrim(evidence_reference)) BETWEEN 3 AND 200),
  note text NOT NULL DEFAULT '' CHECK (length(note) <= 1000),
  idempotency_key text NOT NULL CHECK (length(btrim(idempotency_key)) BETWEEN 8 AND 200),
  request_fingerprint text NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{32}$'),
  journal_entry_id uuid REFERENCES public.commercial_journal_entries(id) ON DELETE RESTRICT,
  recorded_by uuid NOT NULL,
  recorded_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT commercial_expense_vouchers_org_key_unique UNIQUE (organization_id, idempotency_key),
  CONSTRAINT commercial_expense_vouchers_org_journal_unique UNIQUE (organization_id, journal_entry_id)
);
CREATE INDEX IF NOT EXISTS commercial_expense_vouchers_org_recorded_idx ON public.commercial_expense_vouchers (organization_id, recorded_at DESC, id DESC);
ALTER TABLE public.commercial_expense_vouchers ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.commercial_expense_vouchers FROM PUBLIC, anon, authenticated;
GRANT SELECT ON TABLE public.commercial_expense_vouchers TO authenticated;
DROP POLICY IF EXISTS commercial_expense_vouchers_finance_read ON public.commercial_expense_vouchers;
CREATE POLICY commercial_expense_vouchers_finance_read ON public.commercial_expense_vouchers FOR SELECT TO authenticated USING (
  EXISTS (SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=commercial_expense_vouchers.organization_id AND m.user_id=auth.uid() AND m.state='active' AND m.role IN ('owner','admin','manager','accountant'))
);
CREATE OR REPLACE FUNCTION public.commercial_record_expense(
  p_organization_id uuid,p_category text,p_amount_minor bigint,p_currency_code text,
  p_payment_method text,p_evidence_reference text,p_note text,p_idempotency_key text
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE
  v_uid uuid:=auth.uid(); v_fingerprint text; v_existing public.commercial_expense_vouchers%ROWTYPE;
  v_cash_account uuid; v_expense_account uuid; v_voucher_id uuid; v_entry_id uuid; v_account_code text;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_organization_id IS NULL OR p_category IS NULL OR length(btrim(p_category)) NOT BETWEEN 1 AND 120
    OR p_amount_minor IS NULL OR p_amount_minor<=0 OR p_currency_code IS NULL OR p_currency_code !~ '^[A-Z]{3}$'
    OR p_payment_method IS NULL OR p_payment_method NOT IN ('cash','bank_transfer')
    OR p_evidence_reference IS NULL OR length(btrim(p_evidence_reference)) NOT BETWEEN 3 AND 200
    OR p_note IS NULL OR length(p_note)>1000
    OR p_idempotency_key IS NULL OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200 THEN
    RAISE EXCEPTION 'invalid_expense_request' USING ERRCODE='22023';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=p_organization_id AND m.user_id=v_uid AND m.state='active' AND m.role IN ('owner','admin','manager','accountant')) THEN
    RAISE EXCEPTION 'expense_management_permission_required' USING ERRCODE='42501';
  END IF;
  PERFORM pg_advisory_xact_lock(hashtext(p_organization_id::text),hashtext('expense:'||btrim(p_idempotency_key)));
  v_fingerprint:=md5(jsonb_build_object('organization_id',p_organization_id,'category',btrim(p_category),'amount_minor',p_amount_minor,'currency_code',p_currency_code,'payment_method',p_payment_method,'evidence_reference',btrim(p_evidence_reference),'note',p_note)::text);
  SELECT * INTO v_existing FROM public.commercial_expense_vouchers WHERE organization_id=p_organization_id AND idempotency_key=btrim(p_idempotency_key) FOR UPDATE;
  IF FOUND THEN
    IF v_existing.request_fingerprint IS DISTINCT FROM v_fingerprint THEN RAISE EXCEPTION 'expense_idempotency_key_reused_with_different_request' USING ERRCODE='23505'; END IF;
    RETURN jsonb_build_object('voucher_id',v_existing.id,'journal_entry_id',v_existing.journal_entry_id,'amount_minor',v_existing.amount_minor,'currency_code',v_existing.currency_code,'idempotent_replay',true);
  END IF;
  SELECT m.ledger_account_id INTO v_cash_account FROM public.commercial_gl_account_mappings m JOIN public.commercial_ledger_accounts a ON a.organization_id=m.organization_id AND a.id=m.ledger_account_id
    WHERE m.organization_id=p_organization_id AND m.mapping_key=CASE WHEN p_payment_method='cash' THEN 'cash' ELSE 'bank' END AND a.currency_code=p_currency_code AND a.account_type='asset' AND a.is_active;
  IF v_cash_account IS NULL THEN RAISE EXCEPTION 'expense_cash_or_bank_account_not_configured_for_currency' USING ERRCODE='55000'; END IF;
  v_account_code:='EXP_'||p_currency_code||'_'||upper(substr(md5(lower(btrim(p_category))),1,12));
  INSERT INTO public.commercial_ledger_accounts(organization_id,account_code,account_name,account_type,normal_balance,currency_code,is_system,is_active)
    VALUES(p_organization_id,v_account_code,'مصروف: '||btrim(p_category)||' - '||p_currency_code,'expense','debit',p_currency_code,true,true)
    ON CONFLICT (organization_id,account_code) DO UPDATE SET updated_at=now() RETURNING id INTO v_expense_account;
  IF NOT EXISTS (SELECT 1 FROM public.commercial_ledger_accounts a WHERE a.organization_id=p_organization_id AND a.id=v_expense_account AND a.account_type='expense' AND a.normal_balance='debit' AND a.currency_code=p_currency_code AND a.is_active) THEN
    RAISE EXCEPTION 'expense_ledger_account_code_conflict' USING ERRCODE='23505';
  END IF;
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
REVOKE ALL ON FUNCTION public.commercial_record_expense(uuid,text,bigint,text,text,text,text,text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_record_expense(uuid,text,bigint,text,text,text,text,text) TO authenticated;
COMMENT ON TABLE public.commercial_expense_vouchers IS 'Cloud operating-expense vouchers; every row must reference its balanced posted journal entry.';
