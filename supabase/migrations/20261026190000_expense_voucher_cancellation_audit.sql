-- Persist the cancellation event in the same transaction as the reversal and voucher state.
-- The original voucher remains immutable as a record; this trigger only appends an audit event.
CREATE OR REPLACE FUNCTION commercial_private.commercial_audit_expense_voucher_cancellation()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
BEGIN
  IF OLD.canceled_at IS NULL AND NEW.canceled_at IS NOT NULL THEN
    IF NEW.canceled_by IS NULL
       OR NEW.cancellation_reason IS NULL
       OR NEW.reversal_journal_entry_id IS NULL
       OR NEW.journal_entry_id IS NULL THEN
      RAISE EXCEPTION 'expense_voucher_cancellation_audit_fields_required'
        USING ERRCODE='55000';
    END IF;

    INSERT INTO public.commercial_audit_events(
      organization_id, actor_user_id, action, entity_type, entity_id, metadata
    )
    VALUES (
      NEW.organization_id,
      NEW.canceled_by,
      'expense_voucher.cancelled',
      'expense_voucher',
      NEW.id::text,
      jsonb_build_object(
        'voucher_id', NEW.id,
        'amount_minor', NEW.amount_minor,
        'currency_code', NEW.currency_code,
        'original_journal_entry_id', NEW.journal_entry_id,
        'reversal_journal_entry_id', NEW.reversal_journal_entry_id,
        'cancellation_reason', NEW.cancellation_reason,
        'canceled_at', NEW.canceled_at
      )
    );
  END IF;
  RETURN NEW;
END;
$function$;

REVOKE ALL ON FUNCTION commercial_private.commercial_audit_expense_voucher_cancellation() FROM PUBLIC, anon, authenticated;

DROP TRIGGER IF EXISTS commercial_expense_voucher_cancellation_audit_trg
  ON public.commercial_expense_vouchers;
CREATE TRIGGER commercial_expense_voucher_cancellation_audit_trg
AFTER UPDATE OF canceled_at, canceled_by, cancellation_reason, reversal_journal_entry_id
ON public.commercial_expense_vouchers
FOR EACH ROW
WHEN (OLD.canceled_at IS NULL AND NEW.canceled_at IS NOT NULL)
EXECUTE FUNCTION commercial_private.commercial_audit_expense_voucher_cancellation();
