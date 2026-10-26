-- A deferred database invariant prevents an expense voucher from committing
-- unless its linked journal is posted, non-empty, and balanced per currency.
-- Deferred execution reads the final row state so the RPC may insert the voucher
-- first and link the journal later in the same transaction.
CREATE OR REPLACE FUNCTION commercial_private.commercial_assert_expense_voucher_posted()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE
  v_voucher public.commercial_expense_vouchers%ROWTYPE;
  v_journal_status text;
  v_line_count bigint;
BEGIN
  SELECT * INTO v_voucher
  FROM public.commercial_expense_vouchers
  WHERE id = NEW.id;

  -- If the row was deleted later in the same transaction, there is nothing to validate.
  IF NOT FOUND THEN
    RETURN NULL;
  END IF;

  IF v_voucher.journal_entry_id IS NULL THEN
    RAISE EXCEPTION 'expense_voucher_journal_link_required_at_commit' USING ERRCODE='23514';
  END IF;

  SELECT status INTO v_journal_status
  FROM public.commercial_journal_entries
  WHERE id = v_voucher.journal_entry_id
    AND organization_id = v_voucher.organization_id;

  IF NOT FOUND OR v_journal_status IS DISTINCT FROM 'posted' THEN
    RAISE EXCEPTION 'expense_voucher_journal_must_be_posted_at_commit' USING ERRCODE='23514';
  END IF;

  SELECT count(*) INTO v_line_count
  FROM public.commercial_journal_lines
  WHERE organization_id = v_voucher.organization_id
    AND journal_entry_id = v_voucher.journal_entry_id;

  IF v_line_count < 2 THEN
    RAISE EXCEPTION 'expense_voucher_journal_lines_missing_at_commit' USING ERRCODE='23514';
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

DROP TRIGGER IF EXISTS commercial_expense_voucher_posted_integrity ON public.commercial_expense_vouchers;
CREATE CONSTRAINT TRIGGER commercial_expense_voucher_posted_integrity
AFTER INSERT OR UPDATE ON public.commercial_expense_vouchers
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION commercial_private.commercial_assert_expense_voucher_posted();

REVOKE ALL ON FUNCTION commercial_private.commercial_assert_expense_voucher_posted() FROM PUBLIC, anon, authenticated;
