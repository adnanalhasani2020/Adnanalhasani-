-- Return requests accept quantities in the original sale unit when unit_code is supplied.
ALTER FUNCTION public.commercial_request_sale_return(uuid,text,text,jsonb,text)
  RENAME TO commercial_request_sale_return_base_units;

CREATE OR REPLACE FUNCTION public.commercial_request_sale_return(
  p_sale_id uuid, p_idempotency_key text, p_reason_code text, p_items jsonb, p_reason_note text DEFAULT NULL
) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog','public'
AS $function$
DECLARE v_item jsonb; v_items jsonb := '[]'::jsonb; v_sale_item public.commercial_sale_items%ROWTYPE;
        v_unit_code text; v_factor bigint; v_factor_numeric numeric; v_qty numeric;
BEGIN
  IF auth.uid() IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_items IS NULL OR jsonb_typeof(p_items)<>'array' OR jsonb_array_length(p_items)<1 OR jsonb_array_length(p_items)>100 THEN
    RAISE EXCEPTION 'items_must_be_nonempty_array' USING ERRCODE='22023';
  END IF;
  FOR v_item IN SELECT value FROM jsonb_array_elements(p_items) LOOP
    IF coalesce(v_item->>'sale_item_id','') !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
       OR coalesce(v_item->>'quantity','') !~ '^(0|[1-9][0-9]{0,5})(\.[0-9]{1,3})?$'
       OR coalesce(v_item->>'quantity','')::numeric <= 0 THEN
      RAISE EXCEPTION 'invalid_return_item' USING ERRCODE='22023';
    END IF;
    SELECT * INTO v_sale_item FROM public.commercial_sale_items
    WHERE id=(v_item->>'sale_item_id')::uuid AND sale_id=p_sale_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'sale_item_not_found_for_sale' USING ERRCODE='22023'; END IF;
    IF v_item ? 'unit_code' THEN
      v_unit_code := v_item->>'unit_code';
      -- The conversion factor is immutable invoice-line data. Do not read the current
      -- offering unit master: a manager may have changed its factor after this sale.
      IF v_sale_item.selling_unit_code IS DISTINCT FROM v_unit_code THEN
        RAISE EXCEPTION 'return_sell_unit_does_not_match_sale_snapshot' USING ERRCODE='22023';
      END IF;
      IF v_sale_item.selling_quantity IS NULL OR v_sale_item.selling_quantity<=0
         OR v_sale_item.quantity_in_stock_units IS NULL OR v_sale_item.quantity_in_stock_units<=0 THEN
        RAISE EXCEPTION 'sale_item_sell_unit_snapshot_invalid' USING ERRCODE='55000';
      END IF;
      v_factor_numeric := v_sale_item.quantity_in_stock_units::numeric / v_sale_item.selling_quantity;
      IF v_factor_numeric<=0 OR v_factor_numeric<>trunc(v_factor_numeric) THEN
        RAISE EXCEPTION 'sale_item_sell_unit_snapshot_invalid' USING ERRCODE='55000';
      END IF;
      v_factor := v_factor_numeric::bigint;
      v_qty := (v_item->>'quantity')::numeric * v_factor;
    ELSE
      v_qty := (v_item->>'quantity')::numeric;
    END IF;
    IF v_qty <> trunc(v_qty) THEN RAISE EXCEPTION 'fractional_base_return_quantity_not_supported' USING ERRCODE='22023'; END IF;
    IF v_qty<1 OR v_qty>v_sale_item.quantity THEN RAISE EXCEPTION 'invalid_return_quantity' USING ERRCODE='22023'; END IF;
    v_items := v_items || jsonb_build_array(
      jsonb_build_object('sale_item_id',v_sale_item.id,'quantity',v_qty::bigint,
        'restock',coalesce((v_item->>'restock')::boolean,true),
        'condition_code',coalesce(v_item->>'condition_code','uninspected')));
  END LOOP;
  RETURN public.commercial_request_sale_return_base_units(p_sale_id,p_idempotency_key,p_reason_code,v_items,p_reason_note);
END;
$function$;
REVOKE ALL ON FUNCTION public.commercial_request_sale_return(uuid,text,text,jsonb,text) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_request_sale_return(uuid,text,text,jsonb,text) TO authenticated;

COMMENT ON TABLE public.commercial_offering_sell_units IS
'Per-offering sale-unit conversion. quantity_in_stock is an exact integer multiplier to the base stock unit; all inventory, COGS and return quantities remain in base units.';
