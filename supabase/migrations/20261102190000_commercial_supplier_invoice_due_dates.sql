-- Persist actual supplier document dates. Existing invoices remain NULL until verified
-- against their source documents; no dates are inferred from recorded_at.
ALTER TABLE public.commercial_supplier_invoices
  ADD COLUMN IF NOT EXISTS invoice_date date,
  ADD COLUMN IF NOT EXISTS due_date date;

ALTER TABLE public.commercial_supplier_invoices
  DROP CONSTRAINT IF EXISTS commercial_supplier_invoice_dates_consistency_ck;
ALTER TABLE public.commercial_supplier_invoices
  ADD CONSTRAINT commercial_supplier_invoice_dates_consistency_ck CHECK (
    (invoice_date IS NULL AND due_date IS NULL)
    OR (invoice_date IS NOT NULL AND due_date IS NOT NULL AND due_date >= invoice_date)
  );

CREATE INDEX IF NOT EXISTS commercial_supplier_invoices_due_date_idx
  ON public.commercial_supplier_invoices (organization_id, due_date, supplier_id)
  WHERE due_date IS NOT NULL;

-- The trigger makes the legacy RPC fail closed unless it is called through the
-- date-aware wrapper below, which supplies transaction-local, validated dates.
CREATE OR REPLACE FUNCTION commercial_private.commercial_require_supplier_invoice_dates()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE
  v_invoice_date text := nullif(current_setting('app.supplier_invoice_date', true), '');
  v_due_date text := nullif(current_setting('app.supplier_due_date', true), '');
BEGIN
  IF v_invoice_date IS NULL OR v_due_date IS NULL THEN
    RAISE EXCEPTION 'supplier_invoice_dates_required' USING ERRCODE='22023';
  END IF;
  BEGIN
    NEW.invoice_date := v_invoice_date::date;
    NEW.due_date := v_due_date::date;
  EXCEPTION WHEN others THEN
    RAISE EXCEPTION 'invalid_supplier_invoice_dates' USING ERRCODE='22023';
  END;
  IF NEW.invoice_date > current_date OR NEW.due_date < NEW.invoice_date THEN
    RAISE EXCEPTION 'invalid_supplier_invoice_dates' USING ERRCODE='22023';
  END IF;
  RETURN NEW;
END;
$function$;

DROP TRIGGER IF EXISTS commercial_supplier_invoice_dates_required ON public.commercial_supplier_invoices;
CREATE TRIGGER commercial_supplier_invoice_dates_required
BEFORE INSERT ON public.commercial_supplier_invoices
FOR EACH ROW EXECUTE FUNCTION commercial_private.commercial_require_supplier_invoice_dates();

CREATE OR REPLACE FUNCTION commercial_private.commercial_record_supplier_invoice_with_dates(
  p_organization_id uuid,
  p_purchase_order_id uuid,
  p_invoice_number text,
  p_currency_code text,
  p_lines jsonb,
  p_tax_minor bigint,
  p_freight_minor bigint,
  p_idempotency_key text,
  p_invoice_date date,
  p_due_date date
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE
  v_existing_invoice_date date;
  v_existing_due_date date;
  v_existing_id uuid;
  v_result jsonb;
BEGIN
  IF auth.uid() IS NULL THEN
    RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501';
  END IF;
  IF p_organization_id IS NULL OR p_idempotency_key IS NULL
     OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200
     OR p_invoice_date IS NULL OR p_due_date IS NULL
     OR p_invoice_date > current_date OR p_due_date < p_invoice_date THEN
    RAISE EXCEPTION 'invalid_supplier_invoice_dates' USING ERRCODE='22023';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id=p_organization_id AND m.user_id=auth.uid()
      AND m.state='active' AND m.role IN ('owner','admin','manager')
  ) THEN
    RAISE EXCEPTION 'organization_write_access_denied' USING ERRCODE='42501';
  END IF;

  PERFORM pg_advisory_xact_lock(hashtext(p_organization_id::text), hashtext(p_idempotency_key));
  SELECT id, invoice_date, due_date
    INTO v_existing_id, v_existing_invoice_date, v_existing_due_date
  FROM public.commercial_supplier_invoices
  WHERE organization_id=p_organization_id AND idempotency_key=btrim(p_idempotency_key);
  IF FOUND AND (
    v_existing_invoice_date IS DISTINCT FROM p_invoice_date
    OR v_existing_due_date IS DISTINCT FROM p_due_date
  ) THEN
    RAISE EXCEPTION 'idempotency_key_reused_with_different_request' USING ERRCODE='23505';
  END IF;

  PERFORM set_config('app.supplier_invoice_date', p_invoice_date::text, true);
  PERFORM set_config('app.supplier_due_date', p_due_date::text, true);

  v_result := commercial_private.commercial_record_supplier_invoice(
    p_organization_id,p_purchase_order_id,p_invoice_number,p_currency_code,p_lines,
    p_tax_minor,p_freight_minor,p_idempotency_key
  );

  RETURN v_result || jsonb_build_object(
    'invoice_date',p_invoice_date,'due_date',p_due_date
  );
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_record_supplier_invoice_with_dates(
  p_organization_id uuid,
  p_purchase_order_id uuid,
  p_invoice_number text,
  p_currency_code text,
  p_lines jsonb,
  p_tax_minor bigint,
  p_freight_minor bigint,
  p_idempotency_key text,
  p_invoice_date date,
  p_due_date date
) RETURNS jsonb
LANGUAGE sql
SECURITY INVOKER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
  SELECT commercial_private.commercial_record_supplier_invoice_with_dates(
    $1,$2,$3,$4,$5,$6,$7,$8,$9,$10
  )
$function$;

REVOKE ALL ON FUNCTION commercial_private.commercial_record_supplier_invoice_with_dates(uuid,uuid,text,text,jsonb,bigint,bigint,text,date,date)
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION commercial_private.commercial_record_supplier_invoice_with_dates(uuid,uuid,text,text,jsonb,bigint,bigint,text,date,date)
  TO authenticated, service_role;
REVOKE ALL ON FUNCTION public.commercial_record_supplier_invoice_with_dates(uuid,uuid,text,text,jsonb,bigint,bigint,text,date,date)
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_record_supplier_invoice_with_dates(uuid,uuid,text,text,jsonb,bigint,bigint,text,date,date)
  TO authenticated, service_role;
