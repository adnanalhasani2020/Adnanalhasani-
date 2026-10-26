-- Assign a stable, organization-scoped number to every confirmed payment receipt.
-- Existing payments are backfilled deterministically; new numbers are serialized per organization.
ALTER TABLE public.commercial_payments
  ADD COLUMN IF NOT EXISTS receipt_number bigint;

WITH numbered AS (
  SELECT id, row_number() OVER (
    PARTITION BY organization_id ORDER BY created_at, id
  )::bigint AS assigned_number
  FROM public.commercial_payments
  WHERE receipt_number IS NULL
)
UPDATE public.commercial_payments p
SET receipt_number = numbered.assigned_number
FROM numbered
WHERE p.id = numbered.id;

CREATE UNIQUE INDEX IF NOT EXISTS commercial_payments_org_receipt_number_uidx
  ON public.commercial_payments (organization_id, receipt_number);

CREATE OR REPLACE FUNCTION commercial_private.commercial_assign_payment_receipt_number()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
BEGIN
  IF NEW.receipt_number IS NULL THEN
    PERFORM pg_advisory_xact_lock(hashtext(NEW.organization_id::text), hashtext('commercial-payment-receipt-number'));
    SELECT COALESCE(MAX(p.receipt_number), 0) + 1
      INTO NEW.receipt_number
    FROM public.commercial_payments p
    WHERE p.organization_id = NEW.organization_id;
  END IF;
  RETURN NEW;
END;
$function$;

DROP TRIGGER IF EXISTS commercial_payments_assign_receipt_number ON public.commercial_payments;
CREATE TRIGGER commercial_payments_assign_receipt_number
BEFORE INSERT ON public.commercial_payments
FOR EACH ROW
EXECUTE FUNCTION commercial_private.commercial_assign_payment_receipt_number();

ALTER TABLE public.commercial_payments
  ALTER COLUMN receipt_number SET NOT NULL;

REVOKE ALL ON FUNCTION commercial_private.commercial_assign_payment_receipt_number() FROM PUBLIC, anon, authenticated;
