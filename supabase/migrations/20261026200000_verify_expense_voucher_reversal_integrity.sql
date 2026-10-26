-- Verify the actual persisted reversal, not only the reversal RPC response.
-- Any failed assertion aborts the transaction and preserves the original voucher/journal.
CREATE OR REPLACE FUNCTION commercial_private.commercial_cancel_expense_voucher(
  p_voucher_id uuid,
  p_reason text
) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_voucher public.commercial_expense_vouchers%ROWTYPE;
  v_reversal jsonb;
  v_reversal_id uuid;
  v_original_journal_status text;
  v_reversal_status text;
  v_original_line_count bigint;
  v_reversal_line_count bigint;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501';
  END IF;
  IF p_voucher_id IS NULL OR p_reason IS NULL OR length(btrim(p_reason)) NOT BETWEEN 3 AND 300 THEN
    RAISE EXCEPTION 'expense_voucher_cancellation_reason_required' USING ERRCODE='22023';
  END IF;

  SELECT * INTO v_voucher
  FROM public.commercial_expense_vouchers
  WHERE id = p_voucher_id
  FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'expense_voucher_not_found' USING ERRCODE='P0002';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id = v_voucher.organization_id
      AND m.user_id = v_uid AND m.state = 'active'
      AND m.role IN ('owner','admin','manager','accountant')
  ) THEN
    RAISE EXCEPTION 'expense_voucher_cancellation_permission_required' USING ERRCODE='42501';
  END IF;

  -- The row lock serializes simultaneous requests. A retry with the same reason
  -- returns the existing reversal; a changed reason cannot rewrite the audit trail.
  IF v_voucher.canceled_at IS NOT NULL THEN
    IF v_voucher.cancellation_reason IS DISTINCT FROM btrim(p_reason) THEN
      RAISE EXCEPTION 'expense_voucher_already_canceled_with_different_reason' USING ERRCODE='23505';
    END IF;
    RETURN jsonb_build_object(
      'voucher_id', v_voucher.id,
      'journal_entry_id', v_voucher.journal_entry_id,
      'reversal_journal_entry_id', v_voucher.reversal_journal_entry_id,
      'canceled_at', v_voucher.canceled_at,
      'idempotent_replay', true
    );
  END IF;

  IF v_voucher.journal_entry_id IS NULL THEN
    RAISE EXCEPTION 'expense_voucher_has_no_journal_entry' USING ERRCODE='55000';
  END IF;

  SELECT status INTO v_original_journal_status
  FROM public.commercial_journal_entries
  WHERE id = v_voucher.journal_entry_id
    AND organization_id = v_voucher.organization_id
  FOR UPDATE;
  IF NOT FOUND OR v_original_journal_status IS DISTINCT FROM 'posted' THEN
    RAISE EXCEPTION 'expense_voucher_original_journal_not_posted' USING ERRCODE='55000';
  END IF;

  v_reversal := public.commercial_gl_reverse_entry(v_voucher.journal_entry_id, btrim(p_reason));
  v_reversal_id := NULLIF(v_reversal->>'journal_entry_id','')::uuid;
  IF v_reversal_id IS NULL OR (v_reversal->>'reversed_entry_id')::uuid IS DISTINCT FROM v_voucher.journal_entry_id THEN
    RAISE EXCEPTION 'expense_voucher_reversal_not_confirmed' USING ERRCODE='55000';
  END IF;

  -- Verify the committed database rows and their lineage explicitly. A successful
  -- function response alone is not proof that a posted, balanced mirror exists.
  SELECT status INTO v_reversal_status
  FROM public.commercial_journal_entries
  WHERE id = v_reversal_id
    AND organization_id = v_voucher.organization_id
    AND reversal_of = v_voucher.journal_entry_id
  FOR UPDATE;
  IF NOT FOUND OR v_reversal_status IS DISTINCT FROM 'posted' THEN
    RAISE EXCEPTION 'expense_voucher_reversal_not_posted_or_linked' USING ERRCODE='55000';
  END IF;

  SELECT count(*) INTO v_original_line_count
  FROM public.commercial_journal_lines
  WHERE organization_id = v_voucher.organization_id
    AND journal_entry_id = v_voucher.journal_entry_id;
  SELECT count(*) INTO v_reversal_line_count
  FROM public.commercial_journal_lines
  WHERE organization_id = v_voucher.organization_id
    AND journal_entry_id = v_reversal_id;
  IF v_original_line_count = 0 OR v_reversal_line_count = 0 THEN
    RAISE EXCEPTION 'expense_voucher_reversal_lines_missing' USING ERRCODE='55000';
  END IF;

  -- Each currency must balance independently in both entries.
  IF EXISTS (
    SELECT currency_code
    FROM public.commercial_journal_lines
    WHERE organization_id = v_voucher.organization_id
      AND journal_entry_id IN (v_voucher.journal_entry_id, v_reversal_id)
    GROUP BY journal_entry_id, currency_code
    HAVING sum(debit_minor) <> sum(credit_minor)
  ) THEN
    RAISE EXCEPTION 'expense_voucher_reversal_unbalanced' USING ERRCODE='55000';
  END IF;

  -- Aggregate by account, currency, and party so line ordering/splitting does
  -- not matter; the reversal must exactly swap original debits and credits.
  IF EXISTS (
    WITH original_lines AS (
      SELECT ledger_account_id, currency_code, party_account_id,
             sum(debit_minor) AS debit_minor, sum(credit_minor) AS credit_minor
      FROM public.commercial_journal_lines
      WHERE organization_id = v_voucher.organization_id
        AND journal_entry_id = v_voucher.journal_entry_id
      GROUP BY ledger_account_id, currency_code, party_account_id
    ),
    reversal_lines AS (
      SELECT ledger_account_id, currency_code, party_account_id,
             sum(debit_minor) AS debit_minor, sum(credit_minor) AS credit_minor
      FROM public.commercial_journal_lines
      WHERE organization_id = v_voucher.organization_id
        AND journal_entry_id = v_reversal_id
      GROUP BY ledger_account_id, currency_code, party_account_id
    )
    SELECT 1
    FROM original_lines o
    FULL JOIN reversal_lines r
      ON r.ledger_account_id = o.ledger_account_id
     AND r.currency_code = o.currency_code
     AND r.party_account_id IS NOT DISTINCT FROM o.party_account_id
    WHERE o.ledger_account_id IS NULL OR r.ledger_account_id IS NULL
       OR r.debit_minor IS DISTINCT FROM o.credit_minor
       OR r.credit_minor IS DISTINCT FROM o.debit_minor
  ) THEN
    RAISE EXCEPTION 'expense_voucher_reversal_does_not_mirror_original' USING ERRCODE='55000';
  END IF;

  UPDATE public.commercial_expense_vouchers
  SET canceled_at = now(),
      canceled_by = v_uid,
      cancellation_reason = btrim(p_reason),
      reversal_journal_entry_id = v_reversal_id
  WHERE id = v_voucher.id AND canceled_at IS NULL;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'expense_voucher_cancellation_race' USING ERRCODE='40001';
  END IF;

  RETURN jsonb_build_object(
    'voucher_id', v_voucher.id,
    'journal_entry_id', v_voucher.journal_entry_id,
    'reversal_journal_entry_id', v_reversal_id,
    'canceled_at', now(),
    'idempotent_replay', false
  );
END;
$function$;

REVOKE ALL ON FUNCTION commercial_private.commercial_cancel_expense_voucher(uuid,text) FROM PUBLIC, anon;
GRANT USAGE ON SCHEMA commercial_private TO authenticated;
GRANT EXECUTE ON FUNCTION commercial_private.commercial_cancel_expense_voucher(uuid,text) TO authenticated;
