-- Strengthen the deferred expense-voucher invariant: the journal must belong to
-- this voucher and must carry exactly its amount in the voucher currency.
CREATE OR REPLACE FUNCTION commercial_private.commercial_assert_expense_voucher_posted()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE
  v_voucher public.commercial_expense_vouchers%ROWTYPE;
  v_journal_status text;
  v_journal_source_type text;
  v_journal_source_id uuid;
  v_line_count bigint;
  v_currency_count bigint;
  v_line_currency text;
  v_debit_total numeric;
  v_credit_total numeric;
BEGIN
  SELECT * INTO v_voucher
  FROM public.commercial_expense_vouchers
  WHERE id = NEW.id;

  IF NOT FOUND THEN
    RETURN NULL;
  END IF;

  IF v_voucher.journal_entry_id IS NULL THEN
    RAISE EXCEPTION 'expense_voucher_journal_link_required_at_commit' USING ERRCODE='23514';
  END IF;

  SELECT status, source_type, source_id
  INTO v_journal_status, v_journal_source_type, v_journal_source_id
  FROM public.commercial_journal_entries
  WHERE id = v_voucher.journal_entry_id
    AND organization_id = v_voucher.organization_id;

  IF NOT FOUND OR v_journal_status IS DISTINCT FROM 'posted' THEN
    RAISE EXCEPTION 'expense_voucher_journal_must_be_posted_at_commit' USING ERRCODE='23514';
  END IF;

  IF v_journal_source_type IS DISTINCT FROM 'expense_voucher'
     OR v_journal_source_id IS DISTINCT FROM v_voucher.id THEN
    RAISE EXCEPTION 'expense_voucher_journal_source_mismatch_at_commit' USING ERRCODE='23514';
  END IF;

  SELECT count(*), count(DISTINCT currency_code), min(currency_code),
         COALESCE(sum(debit_minor),0), COALESCE(sum(credit_minor),0)
  INTO v_line_count, v_currency_count, v_line_currency, v_debit_total, v_credit_total
  FROM public.commercial_journal_lines
  WHERE organization_id = v_voucher.organization_id
    AND journal_entry_id = v_voucher.journal_entry_id;

  IF v_line_count < 2 THEN
    RAISE EXCEPTION 'expense_voucher_journal_lines_missing_at_commit' USING ERRCODE='23514';
  END IF;

  IF v_currency_count <> 1 OR v_line_currency IS DISTINCT FROM v_voucher.currency_code THEN
    RAISE EXCEPTION 'expense_voucher_journal_currency_mismatch_at_commit' USING ERRCODE='23514';
  END IF;

  IF v_debit_total <> v_voucher.amount_minor OR v_credit_total <> v_voucher.amount_minor THEN
    RAISE EXCEPTION 'expense_voucher_journal_amount_mismatch_at_commit' USING ERRCODE='23514';
  END IF;

  IF EXISTS (
    SELECT currency_code
    FROM public.commercial_journal_lines
    WHERE organization_id = v_voucher.organization_id
      AND journal_entry_id = v_voucher.journal_entry_id
    GROUP BY currency_code
    HAVING sum(debit_minor) <> sum(credit_minor)
  ) THEN
    RAISE EXCEPTION 'expense_voucher_journal_unbalanced_at_commit' USING ERRCODE='23514';
  END IF;

  RETURN NULL;
END;
$function$;

REVOKE ALL ON FUNCTION commercial_private.commercial_assert_expense_voucher_posted() FROM PUBLIC, anon, authenticated;
