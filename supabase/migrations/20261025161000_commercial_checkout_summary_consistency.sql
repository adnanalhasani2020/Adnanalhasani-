-- Normalize checkout response on first execution and idempotent replay.
-- The sale + sale_items remain the canonical operational invoice projection;
-- this does not claim tax-invoice compliance or create a second document ledger.
CREATE OR REPLACE FUNCTION public.commercial_checkout_sale_multi(
  p_lines jsonb,
  p_location_id uuid,
  p_idempotency_key text,
  p_customer_id uuid DEFAULT NULL,
  p_payment_mode text DEFAULT 'cash',
  p_initial_payment_minor bigint DEFAULT NULL
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path TO 'pg_catalog', 'public', 'commercial_private'
AS $function$
DECLARE
  v_result jsonb;
  v_sale uuid;
  v_total bigint;
  v_paid bigint;
  v_initial_paid bigint;
  v_later_paid bigint;
  v_customer uuid;
  v_receipt bigint;
  v_party uuid;
  v_ar uuid;
  v_entry uuid;
  v_lines jsonb;
BEGIN
  IF auth.uid() IS NULL THEN
    RAISE EXCEPTION 'authentication_required' USING ERRCODE = '42501';
  END IF;

  v_result := commercial_private.commercial_checkout_sale_multi(
    p_lines,
    p_location_id,
    p_idempotency_key,
    p_customer_id,
    p_payment_mode,
    p_initial_payment_minor
  );

  v_sale := (v_result->>'sale_id')::uuid;
  IF v_sale IS NULL THEN
    RAISE EXCEPTION 'checkout_result_missing_sale_id' USING ERRCODE = '22023';
  END IF;

  SELECT s.total_minor, s.customer_id, s.receipt_number
    INTO v_total, v_customer, v_receipt
  FROM public.commercial_sales s
  WHERE s.id = v_sale
    AND s.user_id = auth.uid();

  IF NOT FOUND THEN
    RAISE EXCEPTION 'checkout_sale_not_visible' USING ERRCODE = '42501';
  END IF;

  SELECT coalesce(sum(p.amount_minor), 0)::bigint,
         coalesce(sum(p.amount_minor) FILTER (WHERE p.idempotency_key = 'sale:'||v_sale::text||':initial'), 0)::bigint,
         coalesce(sum(p.amount_minor) FILTER (WHERE p.idempotency_key IS DISTINCT FROM 'sale:'||v_sale::text||':initial'), 0)::bigint
    INTO v_paid, v_initial_paid, v_later_paid
  FROM public.commercial_payments p
  WHERE p.sale_id = v_sale
    AND p.organization_id = (v_result->>'organization_id')::uuid
    AND p.status = 'succeeded';

  SELECT cpa.party_account_id INTO v_party
  FROM public.commercial_customer_party_accounts cpa
  WHERE cpa.organization_id = (v_result->>'organization_id')::uuid
    AND cpa.customer_id = v_customer;

  SELECT m.ledger_account_id INTO v_ar
  FROM public.commercial_gl_account_mappings m
  WHERE m.organization_id = (v_result->>'organization_id')::uuid
    AND m.mapping_key = 'accounts_receivable';

  SELECT je.id INTO v_entry
  FROM public.commercial_journal_entries je
  WHERE je.organization_id = (v_result->>'organization_id')::uuid
    AND je.source_type = 'sale' AND je.source_id = v_sale AND je.status = 'posted'
  ORDER BY je.created_at DESC LIMIT 1;

  SELECT coalesce(
    jsonb_agg(
      jsonb_build_object(
        'offering_id', i.offering_id,
        'product_name', i.product_name_snapshot,
        'quantity', i.quantity,
        'unit_price_minor', i.unit_price_minor,
        'line_total_minor', i.line_total_minor
      )
      ORDER BY i.created_at, i.id
    ),
    '[]'::jsonb
  )
  INTO v_lines
  FROM public.commercial_sale_items i
  WHERE i.sale_id = v_sale;

  RETURN v_result || jsonb_build_object(
    'invoice_id', v_sale,
    'invoice_number', v_receipt,
    'customer_id', v_customer,
    'total_minor', v_total,
    'initial_paid_minor', v_initial_paid,
    'later_collected_minor', v_later_paid,
    'paid_minor', v_paid,
    'outstanding_minor', greatest(v_total - v_paid, 0),
    'customer_party_account_id', v_party,
    'accounts_receivable_account_id', v_ar,
    'journal_entry_id', v_entry,
    'payment_recorded', v_paid > 0,
    'lines', v_lines
  );
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_checkout_sale_multi(jsonb,uuid,text,uuid,text,bigint) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_checkout_sale_multi(jsonb,uuid,text,uuid,text,bigint) TO authenticated;
