-- Explicit, auditable cancellation for posted operating-expense vouchers.
-- The source voucher is retained; only a balanced, linked reversal may offset its original journal.
ALTER TABLE public.commercial_expense_vouchers
  ADD COLUMN IF NOT EXISTS canceled_at timestamptz,
  ADD COLUMN IF NOT EXISTS canceled_by uuid,
  ADD COLUMN IF NOT EXISTS cancellation_reason text,
  ADD COLUMN IF NOT EXISTS reversal_journal_entry_id uuid
    REFERENCES public.commercial_journal_entries(id) ON DELETE RESTRICT;

DO $constraint$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'commercial_expense_vouchers_cancellation_state_check'
      AND conrelid = 'public.commercial_expense_vouchers'::regclass
  ) THEN
    ALTER TABLE public.commercial_expense_vouchers
      ADD CONSTRAINT commercial_expense_vouchers_cancellation_state_check
      CHECK (
        (canceled_at IS NULL AND canceled_by IS NULL AND cancellation_reason IS NULL AND reversal_journal_entry_id IS NULL)
        OR
        (canceled_at IS NOT NULL AND canceled_by IS NOT NULL
          AND cancellation_reason IS NOT NULL
          AND length(btrim(cancellation_reason)) BETWEEN 3 AND 300
          AND reversal_journal_entry_id IS NOT NULL)
      );
  END IF;
END
$constraint$;

CREATE UNIQUE INDEX IF NOT EXISTS commercial_expense_vouchers_reversal_unique
  ON public.commercial_expense_vouchers (organization_id, reversal_journal_entry_id)
  WHERE reversal_journal_entry_id IS NOT NULL;

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

  v_reversal := public.commercial_gl_reverse_entry(v_voucher.journal_entry_id, btrim(p_reason));
  v_reversal_id := NULLIF(v_reversal->>'journal_entry_id','')::uuid;
  IF v_reversal_id IS NULL OR (v_reversal->>'reversed_entry_id')::uuid IS DISTINCT FROM v_voucher.journal_entry_id THEN
    RAISE EXCEPTION 'expense_voucher_reversal_not_confirmed' USING ERRCODE='55000';
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

CREATE OR REPLACE FUNCTION public.commercial_cancel_expense_voucher(
  p_voucher_id uuid,
  p_reason text
) RETURNS jsonb
LANGUAGE sql SECURITY INVOKER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
  SELECT commercial_private.commercial_cancel_expense_voucher(p_voucher_id,p_reason);
$function$;

REVOKE ALL ON FUNCTION public.commercial_cancel_expense_voucher(uuid,text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_cancel_expense_voucher(uuid,text) TO authenticated;
