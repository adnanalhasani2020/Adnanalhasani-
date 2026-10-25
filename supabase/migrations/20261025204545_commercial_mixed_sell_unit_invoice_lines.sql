-- Preserve separate invoice rows for each selling unit while deducting aggregated base-stock quantities once.
-- The audited base checkout accepts one line per offering; this wrapper groups only for stock/financial posting,
-- then splits the persisted invoice item into stable per-unit rows inside the same transaction.
CREATE OR REPLACE FUNCTION public.commercial_checkout_sale_multi(
  p_lines jsonb,
  p_location_id uuid,
  p_idempotency_key text,
  p_customer_id uuid,
  p_payment_mode text,
  p_initial_payment_minor bigint,
  p_discount_minor bigint,
  p_tax_minor bigint
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE
  v_line jsonb;
  v_lines jsonb := '[]'::jsonb;
  v_base_lines jsonb;
  v_base_qty bigint;
  v_unit record;
  v_sale jsonb;
  v_sale_id uuid;
  v_effective_key text;
  v_original_lines jsonb;
  v_group record;
  v_item public.commercial_sale_items%ROWTYPE;
  v_unit_line jsonb;
  v_existing_count integer;
  v_expected_count integer;
  v_first boolean;
  v_product_name text;
  v_unit_price bigint;
  v_unit_cost bigint;
  v_expected_base bigint;
  v_expected_selling numeric;
  v_expected_code text;
  v_all_match boolean;
  v_result_lines jsonb;
BEGIN
  IF auth.uid() IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_lines IS NULL OR jsonb_typeof(p_lines)<>'array' OR jsonb_array_length(p_lines)<1 OR jsonb_array_length(p_lines)>200 THEN
    RAISE EXCEPTION 'invalid_invoice_lines_or_location' USING ERRCODE='22023';
  END IF;
  IF p_idempotency_key IS NULL OR length(btrim(p_idempotency_key))<8 OR length(btrim(p_idempotency_key))>200 THEN
    RAISE EXCEPTION 'invalid_idempotency_key' USING ERRCODE='22023';
  END IF;

  v_original_lines := (
    SELECT jsonb_agg(jsonb_build_object(
      'offering_id',e->>'offering_id',
      'quantity',e->>'quantity',
      'unit_code',coalesce(e->>'unit_code','<base>')
    ) ORDER BY e->>'offering_id',coalesce(e->>'unit_code','<base>'),e->>'quantity')
    FROM jsonb_array_elements(p_lines) e
  );
  v_effective_key := btrim(p_idempotency_key)||'-u-'||substr(md5(v_original_lines::text),1,12);
  IF length(v_effective_key)>200 THEN
    v_effective_key:=substr(v_effective_key,1,187)||'-u-'||substr(md5(v_original_lines::text),1,12);
  END IF;

  FOR v_line IN SELECT value FROM jsonb_array_elements(p_lines) LOOP
    IF coalesce(v_line->>'offering_id','') !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
       OR coalesce(v_line->>'quantity','') !~ '^[1-9][0-9]{0,5}$' THEN
      RAISE EXCEPTION 'invalid_invoice_line' USING ERRCODE='22023';
    END IF;

    SELECT su.quantity_in_stock, su.unit_code, su.display_name
      INTO v_unit
    FROM public.commercial_offering_sell_units su
    WHERE su.offering_id=(v_line->>'offering_id')::uuid
      AND ((nullif(v_line->>'unit_code','') IS NOT NULL AND su.unit_code=v_line->>'unit_code')
           OR (nullif(v_line->>'unit_code','') IS NULL AND su.is_base=true))
      AND su.is_active=true;
    IF NOT FOUND THEN RAISE EXCEPTION 'sell_unit_not_configured_for_offering' USING ERRCODE='22023'; END IF;

    v_base_qty := (v_line->>'quantity')::bigint * v_unit.quantity_in_stock;
    IF v_base_qty < 1 OR v_base_qty > 100000 THEN RAISE EXCEPTION 'converted_quantity_out_of_range' USING ERRCODE='22023'; END IF;

    v_lines := v_lines || jsonb_build_array(jsonb_build_object(
      'offering_id',v_line->>'offering_id',
      'quantity',v_base_qty,
      'unit_code',v_unit.unit_code,
      'selling_quantity',(v_line->>'quantity')::numeric,
      'unit_name',v_unit.display_name,
      'factor',v_unit.quantity_in_stock
    ));
  END LOOP;

  -- Normalize duplicate requests for the same unit, but never merge different unit codes.
  SELECT coalesce(jsonb_agg(jsonb_build_object(
      'offering_id',x.offering_id,
      'quantity',x.base_quantity,
      'unit_code',x.unit_code,
      'selling_quantity',x.selling_quantity,
      'unit_name',x.unit_name,
      'factor',x.factor
    ) ORDER BY x.offering_id,x.unit_code),'[]'::jsonb)
    INTO v_lines
  FROM (
    SELECT e->>'offering_id' AS offering_id,
           e->>'unit_code' AS unit_code,
           sum((e->>'quantity')::bigint) AS base_quantity,
           sum((e->>'selling_quantity')::numeric) AS selling_quantity,
           max(e->>'unit_name') AS unit_name,
           max((e->>'factor')::bigint) AS factor
    FROM jsonb_array_elements(v_lines) e
    GROUP BY e->>'offering_id',e->>'unit_code'
  ) x;

  IF EXISTS (
    SELECT 1 FROM (
      SELECT e->>'offering_id' AS offering_id,sum((e->>'quantity')::bigint) AS total_base
      FROM jsonb_array_elements(v_lines) e GROUP BY e->>'offering_id'
    ) q WHERE q.total_base>100000
  ) THEN RAISE EXCEPTION 'converted_quantity_out_of_range' USING ERRCODE='22023'; END IF;

  -- The audited base checkout requires one line per offering and posts stock/GL totals once.
  SELECT coalesce(jsonb_agg(jsonb_build_object(
      'offering_id',q.offering_id,'quantity',q.total_base
    ) ORDER BY q.offering_id),'[]'::jsonb)
    INTO v_base_lines
  FROM (
    SELECT e->>'offering_id' AS offering_id,
           sum((e->>'quantity')::bigint) AS total_base
    FROM jsonb_array_elements(v_lines) e
    GROUP BY e->>'offering_id'
  ) q;

  SELECT public.commercial_checkout_sale_multi_base_units(
    v_base_lines,p_location_id,v_effective_key,p_customer_id,p_payment_mode,
    p_initial_payment_minor,p_discount_minor,p_tax_minor
  ) INTO v_sale;
  v_sale_id := (v_sale->>'sale_id')::uuid;
  IF v_sale_id IS NULL THEN RAISE EXCEPTION 'checkout_result_missing_sale_id' USING ERRCODE='22023'; END IF;

  -- On a replay, accept only an already-correct per-unit projection. Otherwise split the single
  -- base checkout row exactly once, retaining the same base-unit price and line-total arithmetic.
  FOR v_group IN
    SELECT e->>'offering_id' AS offering_id,
           sum((e->>'quantity')::bigint) AS total_base,
           count(*)::integer AS unit_count
    FROM jsonb_array_elements(v_lines) e
    GROUP BY e->>'offering_id'
    ORDER BY e->>'offering_id'
  LOOP
    SELECT count(*)::integer INTO v_existing_count
    FROM public.commercial_sale_items i
    WHERE i.sale_id=v_sale_id AND i.offering_id=v_group.offering_id::uuid;

    v_expected_count := v_group.unit_count;
    v_all_match := (v_existing_count=v_expected_count);
    IF v_all_match THEN
      FOR v_unit_line IN
        SELECT e FROM jsonb_array_elements(v_lines) e
        WHERE e->>'offering_id'=v_group.offering_id
      LOOP
        v_expected_base := (v_unit_line->>'quantity')::bigint;
        v_expected_selling := (v_unit_line->>'selling_quantity')::numeric;
        v_expected_code := v_unit_line->>'unit_code';
        IF NOT EXISTS (
          SELECT 1 FROM public.commercial_sale_items i
          WHERE i.sale_id=v_sale_id
            AND i.offering_id=v_group.offering_id::uuid
            AND i.selling_unit_code=v_expected_code
            AND i.selling_quantity=v_expected_selling
            AND i.quantity=v_expected_base
            AND i.quantity_in_stock_units=v_expected_base
        ) THEN
          v_all_match := false;
          EXIT;
        END IF;
      END LOOP;
    END IF;

    IF v_all_match THEN
      CONTINUE;
    END IF;

    IF v_existing_count<>1 THEN
      RAISE EXCEPTION 'sale_item_sell_unit_projection_mismatch' USING ERRCODE='55000';
    END IF;

    SELECT i.* INTO v_item
    FROM public.commercial_sale_items i
    WHERE i.sale_id=v_sale_id AND i.offering_id=v_group.offering_id::uuid
    ORDER BY i.created_at,i.id
    LIMIT 1
    FOR UPDATE;
    IF NOT FOUND OR v_item.quantity::bigint<>v_group.total_base THEN
      RAISE EXCEPTION 'sale_item_base_quantity_mismatch' USING ERRCODE='55000';
    END IF;

    v_product_name := v_item.product_name_snapshot;
    v_unit_price := v_item.unit_price_minor;
    v_unit_cost := v_item.unit_cost_minor;
    v_first := true;

    FOR v_unit_line IN
      SELECT e FROM jsonb_array_elements(v_lines) e
      WHERE e->>'offering_id'=v_group.offering_id
      ORDER BY e->>'unit_code'
    LOOP
      v_expected_base := (v_unit_line->>'quantity')::bigint;
      IF v_first THEN
        UPDATE public.commercial_sale_items
        SET quantity=v_expected_base::integer,
            unit_price_minor=v_unit_price,
            line_total_minor=(v_unit_price::numeric*v_expected_base)::bigint,
            selling_unit_code=v_unit_line->>'unit_code',
            selling_unit_name=v_unit_line->>'unit_name',
            selling_quantity=(v_unit_line->>'selling_quantity')::numeric,
            quantity_in_stock_units=v_expected_base
        WHERE id=v_item.id;
        v_first := false;
      ELSE
        INSERT INTO public.commercial_sale_items(
          sale_id,offering_id,product_name_snapshot,quantity,unit_price_minor,line_total_minor,
          unit_cost_minor,selling_unit_code,selling_unit_name,selling_quantity,quantity_in_stock_units
        ) VALUES (
          v_sale_id,v_group.offering_id::uuid,v_product_name,v_expected_base::integer,v_unit_price,
          (v_unit_price::numeric*v_expected_base)::bigint,v_unit_cost,
          v_unit_line->>'unit_code',v_unit_line->>'unit_name',
          (v_unit_line->>'selling_quantity')::numeric,v_expected_base
        );
      END IF;
    END LOOP;
  END LOOP;

  SELECT coalesce(jsonb_agg(jsonb_build_object(
      'offering_id',i.offering_id,
      'product_name',i.product_name_snapshot,
      'quantity',i.quantity,
      'unit_price_minor',i.unit_price_minor,
      'line_total_minor',i.line_total_minor,
      'selling_unit_code',i.selling_unit_code,
      'selling_unit_name',i.selling_unit_name,
      'selling_quantity',i.selling_quantity,
      'quantity_in_stock_units',i.quantity_in_stock_units
    ) ORDER BY i.created_at,i.id),'[]'::jsonb)
    INTO v_result_lines
  FROM public.commercial_sale_items i
  WHERE i.sale_id=v_sale_id;

  RETURN v_sale || jsonb_build_object(
    'sell_units_normalized',true,
    'line_count',(SELECT count(*) FROM public.commercial_sale_items i WHERE i.sale_id=v_sale_id),
    'lines',v_result_lines
  );
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_checkout_sale_multi(jsonb,uuid,text,uuid,text,bigint,bigint,bigint) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_checkout_sale_multi(jsonb,uuid,text,uuid,text,bigint,bigint,bigint) TO authenticated;
