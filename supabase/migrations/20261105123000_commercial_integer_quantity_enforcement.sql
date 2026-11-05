-- Replace fractional selling-unit RPC wrappers with integer-only contracts.
-- Keep the existing atomic base-unit checkout, invoice snapshots, idempotency and return lifecycle.
-- Preserve separate invoice rows for each selling unit while deducting aggregated base-stock quantities once.
-- Selling quantities are whole numbers; integer stock-unit factors preserve whole base quantities.
-- The wrapper splits persisted invoice items into stable per-unit rows inside the same transaction.
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
  v_base_qty numeric;
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
       OR coalesce(v_line->>'quantity','') !~ '^(0|[1-9][0-9]{0,5})$'
       OR coalesce(v_line->>'quantity','')::numeric <= 0 THEN
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

    v_base_qty := (v_line->>'quantity')::numeric * v_unit.quantity_in_stock;
    IF v_base_qty <> trunc(v_base_qty) THEN
      RAISE EXCEPTION 'fractional_base_stock_quantity_not_supported' USING ERRCODE='22023';
    END IF;
    IF v_base_qty < 1 OR v_base_qty > 100000 THEN RAISE EXCEPTION 'converted_quantity_out_of_range' USING ERRCODE='22023'; END IF;

    v_lines := v_lines || jsonb_build_array(jsonb_build_object(
      'offering_id',v_line->>'offering_id',
      'quantity',v_base_qty::bigint,
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
       OR coalesce(v_item->>'quantity','') !~ '^(0|[1-9][0-9]{0,5})$'
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



CREATE OR REPLACE FUNCTION public.commercial_receive_inventory_stock(
 p_organization_id uuid, p_location_id uuid, p_offering_id uuid, p_quantity numeric,
 p_unit_cost_minor bigint, p_source_ref text
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE
 b public.commercial_inventory_balances%ROWTYPE;
 v_new_qty numeric;
 v_new_cost numeric;
 v_fingerprint text;
 v_receipt public.commercial_inventory_receipts%ROWTYPE;
BEGIN
 IF auth.uid() IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_organization_id IS NULL OR p_location_id IS NULL OR p_offering_id IS NULL
    OR p_quantity IS NULL OR p_quantity<=0 OR p_quantity<>trunc(p_quantity)
    OR p_unit_cost_minor IS NULL OR p_unit_cost_minor<0
    OR p_source_ref IS NULL OR length(btrim(p_source_ref))<8 OR length(btrim(p_source_ref))>200 THEN
   RAISE EXCEPTION 'invalid_inventory_receipt' USING ERRCODE='22023';
 END IF;
 IF NOT EXISTS(
   SELECT 1 FROM public.commercial_organization_members m
   WHERE m.organization_id=p_organization_id AND m.user_id=auth.uid()
     AND m.state='active' AND m.role IN ('owner','admin','manager')
 ) THEN RAISE EXCEPTION 'inventory_management_permission_required' USING ERRCODE='42501'; END IF;
 v_fingerprint:=md5(concat_ws('|',p_location_id::text,p_offering_id::text,p_quantity::numeric(20,6)::text,p_unit_cost_minor::text));
 PERFORM pg_advisory_xact_lock(hashtext(p_organization_id::text),hashtext('inventory-receipt:'||p_source_ref));
 SELECT * INTO v_receipt FROM public.commercial_inventory_receipts
 WHERE organization_id=p_organization_id AND source_ref=p_source_ref FOR UPDATE;
 IF FOUND THEN
   IF v_receipt.request_fingerprint IS DISTINCT FROM v_fingerprint THEN
     RAISE EXCEPTION 'inventory_receipt_key_reused_with_different_request' USING ERRCODE='23505';
   END IF;
   SELECT * INTO b FROM public.commercial_inventory_balances
   WHERE organization_id=p_organization_id AND location_id=p_location_id AND offering_id=p_offering_id;
   RETURN jsonb_build_object('idempotent_replay',true,'receipt_id',v_receipt.id,
     'quantity_on_hand',b.quantity_on_hand,'unit_cost_minor',b.unit_cost_minor);
 END IF;
 SELECT * INTO b FROM public.commercial_inventory_balances
 WHERE organization_id=p_organization_id AND location_id=p_location_id AND offering_id=p_offering_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'inventory_balance_missing' USING ERRCODE='P0002'; END IF;
 IF b.quantity_on_hand<0 OR b.quantity_reserved<0 OR b.quantity_reserved>b.quantity_on_hand THEN
   RAISE EXCEPTION 'inventory_balance_invalid' USING ERRCODE='55000';
 END IF;
 IF b.unit_cost_minor IS NULL AND b.quantity_on_hand>0 THEN
   RAISE EXCEPTION 'existing_stock_cost_unknown_adjust_stock_through_audited_inventory_process' USING ERRCODE='55000';
 END IF;
 v_new_qty:=b.quantity_on_hand+p_quantity;
 IF v_new_qty>99999999999999.999999 THEN RAISE EXCEPTION 'inventory_quantity_overflow' USING ERRCODE='22003'; END IF;
 v_new_cost:=CASE WHEN b.quantity_on_hand=0 THEN p_unit_cost_minor
   ELSE round((b.quantity_on_hand::numeric*b.unit_cost_minor::numeric+p_quantity::numeric*p_unit_cost_minor::numeric)/v_new_qty) END;
 IF v_new_cost>9223372036854775807 THEN RAISE EXCEPTION 'inventory_cost_overflow' USING ERRCODE='22003'; END IF;
 INSERT INTO public.commercial_inventory_receipts(
   organization_id,location_id,offering_id,source_ref,request_fingerprint,quantity,unit_cost_minor,created_by
 ) VALUES(p_organization_id,p_location_id,p_offering_id,p_source_ref,v_fingerprint,p_quantity,p_unit_cost_minor,auth.uid())
 RETURNING * INTO v_receipt;
 UPDATE public.commercial_inventory_balances SET quantity_on_hand=v_new_qty,
   unit_cost_minor=v_new_cost::bigint,updated_at=now() WHERE id=b.id;
 INSERT INTO public.commercial_stock_movements(offering_id,movement_type,quantity_delta,source_ref,created_by)
 VALUES(p_offering_id,'receipt',p_quantity,'receipt:'||p_organization_id::text||':'||p_source_ref,auth.uid());
 INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
 VALUES(p_organization_id,auth.uid(),'inventory.received','inventory_balance',b.id::text,
   jsonb_build_object('receipt_id',v_receipt.id,'location_id',p_location_id,'offering_id',p_offering_id,
     'quantity',p_quantity,'unit_cost_minor',p_unit_cost_minor,'new_average_cost_minor',v_new_cost::bigint,'source_ref',p_source_ref));
 RETURN jsonb_build_object('idempotent_replay',false,'receipt_id',v_receipt.id,
   'quantity_on_hand',v_new_qty,'unit_cost_minor',v_new_cost::bigint);
END $function$;

CREATE OR REPLACE FUNCTION commercial_private.commercial_create_purchase_order(
  p_organization_id uuid, p_supplier_id uuid, p_order_number text, p_currency_code text,
  p_receive_location_id uuid, p_lines jsonb, p_idempotency_key text, p_expected_at timestamptz DEFAULT NULL,
  p_notes text DEFAULT NULL
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE v_uid uuid:=auth.uid(); v_order_id uuid; v_existing_fingerprint text; v_fingerprint text;
  v_line jsonb; v_offering uuid; v_desc text; v_qty numeric; v_cost bigint; v_line_no integer:=0;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_organization_id IS NULL OR p_supplier_id IS NULL OR p_receive_location_id IS NULL
    OR p_order_number IS NULL OR length(btrim(p_order_number)) NOT BETWEEN 1 AND 80
    OR p_currency_code IS NULL OR p_currency_code !~ '^[A-Z]{3}$' OR p_lines IS NULL OR jsonb_typeof(p_lines)<>'array'
    OR jsonb_array_length(p_lines)=0 OR p_idempotency_key IS NULL OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200 THEN
    RAISE EXCEPTION 'invalid_purchase_order_request' USING ERRCODE='22023';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=p_organization_id AND m.user_id=v_uid AND m.state='active' AND m.role IN ('owner','admin','manager')) THEN
    RAISE EXCEPTION 'purchase_management_permission_required' USING ERRCODE='42501';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM public.commercial_suppliers s WHERE s.organization_id=p_organization_id AND s.id=p_supplier_id AND s.state='active') THEN
    RAISE EXCEPTION 'supplier_not_active' USING ERRCODE='22023';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM public.commercial_organization_locations l WHERE l.organization_id=p_organization_id AND l.id=p_receive_location_id AND l.state='active') THEN
    RAISE EXCEPTION 'purchase_receive_location_invalid' USING ERRCODE='22023';
  END IF;
  PERFORM pg_advisory_xact_lock(hashtext(p_organization_id::text),hashtext('purchase-create:'||btrim(p_idempotency_key)));
  v_fingerprint:=md5(jsonb_build_object('supplier_id',p_supplier_id,'order_number',btrim(p_order_number),'currency_code',p_currency_code,'receive_location_id',p_receive_location_id,'lines',p_lines,'expected_at',p_expected_at,'notes',p_notes)::text);
  SELECT id,create_request_fingerprint INTO v_order_id,v_existing_fingerprint FROM public.commercial_purchase_orders
    WHERE organization_id=p_organization_id AND create_idempotency_key=btrim(p_idempotency_key);
  IF FOUND THEN
    IF v_existing_fingerprint IS DISTINCT FROM v_fingerprint THEN RAISE EXCEPTION 'purchase_idempotency_key_reused_with_different_request' USING ERRCODE='23505'; END IF;
    RETURN jsonb_build_object('purchase_order_id',v_order_id,'status',(SELECT status FROM public.commercial_purchase_orders WHERE id=v_order_id),'idempotent_replay',true);
  END IF;
  INSERT INTO public.commercial_purchase_orders(organization_id,supplier_id,order_number,status,currency_code,receive_location_id,expected_at,notes,created_by,create_idempotency_key,create_request_fingerprint)
  VALUES(p_organization_id,p_supplier_id,btrim(p_order_number),'draft',p_currency_code,p_receive_location_id,p_expected_at,p_notes,v_uid,btrim(p_idempotency_key),v_fingerprint)
  RETURNING id INTO v_order_id;
  FOR v_line IN SELECT value FROM jsonb_array_elements(p_lines) LOOP
    v_line_no:=v_line_no+1;
    BEGIN
      v_offering:=(v_line->>'offering_id')::uuid; v_qty:=(v_line->>'quantity_ordered')::numeric;
      v_cost:=(v_line->>'unit_cost_minor')::bigint; v_desc:=btrim(v_line->>'item_description');
    EXCEPTION WHEN others THEN RAISE EXCEPTION 'invalid_purchase_order_line' USING ERRCODE='22023'; END;
    IF v_offering IS NULL OR v_qty IS NULL OR v_qty<=0 OR v_qty<>trunc(v_qty) OR v_qty>999999999999
      OR v_cost IS NULL OR v_cost<0 OR v_desc IS NULL OR length(v_desc) NOT BETWEEN 1 AND 500 THEN
      RAISE EXCEPTION 'invalid_purchase_order_line' USING ERRCODE='22023';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.commercial_offerings o JOIN public.commercial_activities a ON a.id=o.activity_id
      JOIN public.commercial_products p ON p.id=o.product_id
      WHERE o.id=v_offering AND a.organization_id=p_organization_id AND a.state='active' AND o.state='active'
        AND p.state='active' AND upper(o.currency_code)=p_currency_code) THEN
      RAISE EXCEPTION 'purchase_offering_currency_or_scope_mismatch' USING ERRCODE='22023';
    END IF;
    INSERT INTO public.commercial_purchase_order_items(organization_id,purchase_order_id,line_number,item_description,quantity_ordered,unit_cost_minor,offering_id)
    VALUES(p_organization_id,v_order_id,v_line_no,v_desc,v_qty,v_cost,v_offering);
  END LOOP;
  INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
  VALUES(p_organization_id,v_uid,'purchase_order.created','purchase_order',v_order_id::text,jsonb_build_object('supplier_id',p_supplier_id,'order_number',btrim(p_order_number),'line_count',v_line_no));
  RETURN jsonb_build_object('purchase_order_id',v_order_id,'status','draft','idempotent_replay',false);
END;
$function$;

CREATE OR REPLACE FUNCTION commercial_private.commercial_receive_purchase_order(
  p_organization_id uuid, p_purchase_order_id uuid, p_location_id uuid, p_lines jsonb, p_idempotency_key text
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE v_uid uuid:=auth.uid(); v_status text; v_default_location uuid; v_receipt_id uuid; v_existing_fingerprint text;
  v_fingerprint text; v_line jsonb; v_item_id uuid; v_qty numeric; v_ordered numeric; v_received numeric;
  v_offering uuid; v_cost bigint; v_item_count integer:=0; v_new_status text; v_inventory jsonb; v_inv_receipt uuid;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_organization_id IS NULL OR p_purchase_order_id IS NULL OR p_location_id IS NULL OR p_lines IS NULL
    OR jsonb_typeof(p_lines)<>'array' OR jsonb_array_length(p_lines)=0 OR p_idempotency_key IS NULL
    OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200 THEN RAISE EXCEPTION 'invalid_purchase_receipt_request' USING ERRCODE='22023'; END IF;
  IF NOT EXISTS (SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=p_organization_id AND m.user_id=v_uid AND m.state='active' AND m.role IN ('owner','admin','manager')) THEN
    RAISE EXCEPTION 'purchase_management_permission_required' USING ERRCODE='42501';
  END IF;
  PERFORM pg_advisory_xact_lock(hashtext(p_organization_id::text),hashtext('purchase-receive:'||btrim(p_idempotency_key)));
  v_fingerprint:=md5(jsonb_build_object('purchase_order_id',p_purchase_order_id,'location_id',p_location_id,'lines',p_lines)::text);
  SELECT id,request_fingerprint INTO v_receipt_id,v_existing_fingerprint FROM public.commercial_purchase_receipts
    WHERE organization_id=p_organization_id AND idempotency_key=btrim(p_idempotency_key);
  IF FOUND THEN
    IF v_existing_fingerprint IS DISTINCT FROM v_fingerprint THEN RAISE EXCEPTION 'purchase_receipt_key_reused_with_different_request' USING ERRCODE='23505'; END IF;
    RETURN jsonb_build_object('receipt_id',v_receipt_id,'idempotent_replay',true);
  END IF;
  SELECT status,receive_location_id INTO v_status,v_default_location FROM public.commercial_purchase_orders
    WHERE id=p_purchase_order_id AND organization_id=p_organization_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'purchase_order_not_found' USING ERRCODE='P0002'; END IF;
  IF v_status NOT IN ('approved','partially_received') THEN RAISE EXCEPTION 'purchase_order_not_receivable' USING ERRCODE='55000'; END IF;
  IF p_location_id IS DISTINCT FROM v_default_location OR NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_locations WHERE id=p_location_id AND organization_id=p_organization_id AND state='active'
  ) THEN RAISE EXCEPTION 'purchase_receipt_location_mismatch' USING ERRCODE='22023'; END IF;
  INSERT INTO public.commercial_purchase_receipts(organization_id,purchase_order_id,location_id,idempotency_key,request_fingerprint,received_by)
  VALUES(p_organization_id,p_purchase_order_id,p_location_id,btrim(p_idempotency_key),v_fingerprint,v_uid)
  RETURNING id INTO v_receipt_id;
  FOR v_line IN SELECT value FROM jsonb_array_elements(p_lines) LOOP
    BEGIN v_item_id:=(v_line->>'purchase_order_item_id')::uuid; v_qty:=(v_line->>'quantity')::numeric;
    EXCEPTION WHEN others THEN RAISE EXCEPTION 'invalid_purchase_receipt_line' USING ERRCODE='22023'; END;
    IF v_item_id IS NULL OR v_qty IS NULL OR v_qty<=0 OR v_qty<>trunc(v_qty) OR v_qty>999999999999 THEN RAISE EXCEPTION 'invalid_purchase_receipt_line' USING ERRCODE='22023'; END IF;
    IF EXISTS (SELECT 1 FROM public.commercial_purchase_receipt_items WHERE receipt_id=v_receipt_id AND purchase_order_item_id=v_item_id) THEN RAISE EXCEPTION 'duplicate_purchase_receipt_line' USING ERRCODE='22023'; END IF;
    SELECT quantity_ordered,quantity_received,offering_id,unit_cost_minor INTO v_ordered,v_received,v_offering,v_cost
      FROM public.commercial_purchase_order_items WHERE id=v_item_id AND organization_id=p_organization_id AND purchase_order_id=p_purchase_order_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'purchase_receipt_line_not_in_order' USING ERRCODE='22023'; END IF;
    IF v_offering IS NULL THEN RAISE EXCEPTION 'purchase_line_missing_inventory_offering' USING ERRCODE='55000'; END IF;
    IF v_received+v_qty>v_ordered THEN RAISE EXCEPTION 'purchase_receipt_exceeds_remaining_quantity' USING ERRCODE='22023'; END IF;
    INSERT INTO public.commercial_inventory_balances(organization_id,location_id,offering_id,quantity_on_hand,quantity_reserved,reorder_point,unit_cost_minor)
    VALUES(p_organization_id,p_location_id,v_offering,0,0,0,NULL) ON CONFLICT (organization_id,location_id,offering_id) DO NOTHING;
    v_inventory:=public.commercial_receive_inventory_stock(p_organization_id,p_location_id,v_offering,v_qty::numeric,v_cost,
      'purchase-receipt:'||v_receipt_id::text||':'||v_item_id::text);
    v_inv_receipt:=(v_inventory->>'receipt_id')::uuid;
    UPDATE public.commercial_purchase_order_items SET quantity_received=quantity_received+v_qty,updated_at=now()
      WHERE id=v_item_id AND purchase_order_id=p_purchase_order_id AND organization_id=p_organization_id;
    INSERT INTO public.commercial_purchase_receipt_items(organization_id,receipt_id,purchase_order_id,purchase_order_item_id,offering_id,quantity,unit_cost_minor,inventory_receipt_id)
    VALUES(p_organization_id,v_receipt_id,p_purchase_order_id,v_item_id,v_offering,v_qty,v_cost,v_inv_receipt);
    v_item_count:=v_item_count+1;
  END LOOP;
  SELECT CASE WHEN bool_and(quantity_received=quantity_ordered) THEN 'received' ELSE 'partially_received' END INTO v_new_status
    FROM public.commercial_purchase_order_items WHERE purchase_order_id=p_purchase_order_id AND organization_id=p_organization_id;
  UPDATE public.commercial_purchase_orders SET status=v_new_status,updated_at=now()
    WHERE id=p_purchase_order_id AND organization_id=p_organization_id;
  INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
  VALUES(p_organization_id,v_uid,'purchase_order.received','purchase_receipt',v_receipt_id::text,jsonb_build_object('purchase_order_id',p_purchase_order_id,'location_id',p_location_id,'line_count',v_item_count,'new_order_status',v_new_status));
  RETURN jsonb_build_object('receipt_id',v_receipt_id,'purchase_order_id',p_purchase_order_id,'status',v_new_status,'line_count',v_item_count,'idempotent_replay',false);
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_customer_portal_create_order(
  p_organization_id uuid,
  p_lines jsonb,
  p_payment_mode text,
  p_customer_note text DEFAULT NULL,
  p_reserved_from timestamptz DEFAULT NULL,
  p_reserved_until timestamptz DEFAULT NULL,
  p_idempotency_key text DEFAULT NULL
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_account_id uuid;
  v_customer_id uuid;
  v_account_currency text;
  v_credit_limit_minor bigint;
  v_posted_balance numeric := 0;
  v_open_credit_orders numeric := 0;
  v_currency text;
  v_order_type text := 'purchase';
  v_subtotal bigint := 0;
  v_line jsonb;
  v_offering record;
  v_qty numeric;
  v_line_total bigint;
  v_order_id uuid;
  v_existing public.commercial_customer_orders%ROWTYPE;
  v_key text := nullif(btrim(p_idempotency_key), '');
  v_count integer := 0;
  v_allow_credit boolean := false;
  v_existing_count integer;
  v_matching_count integer;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  PERFORM public.commercial_customer_portal_link_verified_email();
  IF p_organization_id IS NULL THEN RAISE EXCEPTION 'organization_required' USING ERRCODE='22023'; END IF;
  IF p_payment_mode IS NULL OR p_payment_mode NOT IN ('cash','credit') THEN RAISE EXCEPTION 'invalid_payment_mode' USING ERRCODE='22023'; END IF;
  IF v_key IS NULL OR length(v_key) < 8 OR length(v_key) > 200 THEN RAISE EXCEPTION 'invalid_idempotency_key' USING ERRCODE='22023'; END IF;
  IF p_lines IS NULL OR jsonb_typeof(p_lines) <> 'array' OR jsonb_array_length(p_lines) < 1 OR jsonb_array_length(p_lines) > 100 THEN
    RAISE EXCEPTION 'invalid_order_lines' USING ERRCODE='22023';
  END IF;
  IF p_customer_note IS NOT NULL AND length(p_customer_note) > 1000 THEN RAISE EXCEPTION 'customer_note_too_long' USING ERRCODE='22023'; END IF;
  IF p_reserved_until IS NOT NULL AND (p_reserved_from IS NULL OR p_reserved_until <= p_reserved_from) THEN
    RAISE EXCEPTION 'invalid_reservation_window' USING ERRCODE='22023';
  END IF;
  IF p_reserved_from IS NOT NULL AND p_reserved_from < now() THEN
    RAISE EXCEPTION 'reservation_must_be_future' USING ERRCODE='22023';
  END IF;

  -- The customer account is organization-specific and must be explicitly linked to this auth user.
  SELECT a.id, c.id, a.allow_credit, a.currency_code, a.credit_limit_minor
    INTO v_account_id, v_customer_id, v_allow_credit, v_account_currency, v_credit_limit_minor
  FROM public.commercial_customer_party_accounts cpa
  JOIN public.commercial_customers c
    ON c.id = cpa.customer_id AND c.organization_id = cpa.organization_id AND c.state = 'active' AND c.auth_user_id = v_uid
  JOIN public.commercial_party_accounts a
    ON a.id = cpa.party_account_id AND a.organization_id = cpa.organization_id
   AND a.account_type IN ('business','organization') AND a.status = 'active'
  JOIN public.commercial_organizations o
    ON o.id = cpa.organization_id AND o.state = 'active' AND o.deleted_at IS NULL
  WHERE cpa.organization_id = p_organization_id
  LIMIT 1;
  IF v_account_id IS NULL THEN RAISE EXCEPTION 'customer_not_linked_to_organization' USING ERRCODE='42501'; END IF;
  IF p_payment_mode = 'credit' AND NOT coalesce(v_allow_credit, false) THEN
    RAISE EXCEPTION 'customer_credit_not_authorized' USING ERRCODE='42501';
  END IF;
  IF v_account_currency IS NULL THEN RAISE EXCEPTION 'customer_account_currency_missing' USING ERRCODE='22023'; END IF;

  -- Serialize retries by account/key; the unique constraint remains the final guard.
  PERFORM pg_advisory_xact_lock(hashtextextended(v_account_id::text || ':' || v_key, 0));
  SELECT * INTO v_existing
  FROM public.commercial_customer_orders
  WHERE buyer_account_id = v_account_id AND idempotency_key = v_key;
  IF FOUND THEN
    IF v_existing.organization_id <> p_organization_id
       OR coalesce(v_existing.customer_note,'') <> coalesce(nullif(btrim(p_customer_note),''),'')
       OR v_existing.reserved_from IS DISTINCT FROM p_reserved_from
       OR v_existing.reserved_until IS DISTINCT FROM p_reserved_until
       OR v_existing.payment_status <> (CASE WHEN p_payment_mode='credit' THEN 'credit' ELSE 'unpaid' END) THEN
      RAISE EXCEPTION 'idempotency_key_reused_with_different_request' USING ERRCODE='22023';
    END IF;
    SELECT count(*) INTO v_existing_count FROM public.commercial_customer_order_items WHERE order_id = v_existing.id;
    SELECT count(*) INTO v_matching_count
    FROM public.commercial_customer_order_items oi
    JOIN jsonb_array_elements(p_lines) j(value)
      ON oi.offering_id = nullif(j.value->>'offering_id','')::uuid
     AND oi.quantity = nullif(j.value->>'quantity','')::numeric
    WHERE oi.order_id = v_existing.id;
    IF v_existing_count <> jsonb_array_length(p_lines) OR v_matching_count <> v_existing_count THEN
      RAISE EXCEPTION 'idempotency_key_reused_with_different_request' USING ERRCODE='22023';
    END IF;
    RETURN jsonb_build_object('order_id',v_existing.id,'order_number',v_existing.order_number,
      'status',v_existing.status,'payment_status',v_existing.payment_status,
      'total_minor',v_existing.total_minor,'currency_code',v_existing.currency_code,'replayed',true);
  END IF;

  -- Lock each active offer while reading its trusted price and validate all lines before inserting.
  FOR v_line IN SELECT value FROM jsonb_array_elements(p_lines)
  LOOP
    IF jsonb_typeof(v_line) <> 'object' OR nullif(v_line->>'offering_id','') IS NULL
       OR nullif(v_line->>'quantity','') IS NULL THEN
      RAISE EXCEPTION 'invalid_order_line' USING ERRCODE='22023';
    END IF;
    BEGIN
      IF (v_line->>'offering_id') !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
        RAISE EXCEPTION 'invalid_order_offering_id' USING ERRCODE='22023';
      END IF;
      v_qty := (v_line->>'quantity')::numeric;
    EXCEPTION WHEN invalid_text_representation THEN
      RAISE EXCEPTION 'invalid_order_quantity' USING ERRCODE='22023';
    END;
    IF v_qty <= 0 OR v_qty > 1000000 OR v_qty <> trunc(v_qty) THEN
      RAISE EXCEPTION 'invalid_order_quantity' USING ERRCODE='22023';
    END IF;
    SELECT ofr.id, ofr.price_minor, ofr.currency_code, p.name, a.activity_type
      INTO v_offering
    FROM public.commercial_offerings ofr
    JOIN public.commercial_activities a ON a.id=ofr.activity_id
    JOIN public.commercial_products p ON p.id=ofr.product_id
    WHERE ofr.id=(v_line->>'offering_id')::uuid
      AND ofr.state='active' AND ofr.valid_from <= now()
      AND (ofr.valid_to IS NULL OR ofr.valid_to > now())
      AND a.organization_id=p_organization_id AND a.state='active' AND a.deleted_at IS NULL
      AND p.state='active'
    FOR SHARE OF ofr, a, p;
    IF NOT FOUND THEN RAISE EXCEPTION 'offering_not_available_for_organization' USING ERRCODE='22023'; END IF;
    IF v_currency IS NULL THEN v_currency := v_offering.currency_code;
    ELSIF v_currency <> v_offering.currency_code THEN RAISE EXCEPTION 'mixed_order_currencies_not_supported' USING ERRCODE='22023'; END IF;
    IF v_offering.price_minor < 0 OR v_qty * v_offering.price_minor > 9000000000000000000 THEN
      RAISE EXCEPTION 'order_total_overflow' USING ERRCODE='22003';
    END IF;
    v_line_total := round(v_qty * v_offering.price_minor)::bigint;
    IF v_line_total < 0 OR v_subtotal > 9000000000000000000 - v_line_total THEN
      RAISE EXCEPTION 'order_total_overflow' USING ERRCODE='22003';
    END IF;
    v_subtotal := v_subtotal + v_line_total;
    v_count := v_count + 1;
    IF v_offering.activity_type = 'service' THEN v_order_type := 'service'; END IF;
  END LOOP;

  IF (SELECT count(DISTINCT value->>'offering_id') FROM jsonb_array_elements(p_lines)) <> v_count THEN
    RAISE EXCEPTION 'duplicate_order_offering' USING ERRCODE='22023';
  END IF;


  IF v_currency <> v_account_currency THEN
    RAISE EXCEPTION 'customer_account_currency_mismatch' USING ERRCODE='22023';
  END IF;

  IF p_payment_mode = 'credit' AND v_credit_limit_minor IS NOT NULL THEN
    -- Serialize credit-order submissions for this account before checking exposure.
    PERFORM pg_advisory_xact_lock(hashtextextended(v_account_id::text || ':credit-limit', 0));
    SELECT coalesce(sum(jl.debit_minor::numeric - jl.credit_minor::numeric), 0)
      INTO v_posted_balance
    FROM public.commercial_journal_lines jl
    JOIN public.commercial_journal_entries je
      ON je.id = jl.journal_entry_id AND je.organization_id = jl.organization_id
    WHERE jl.organization_id = p_organization_id
      AND jl.party_account_id = v_account_id
      AND je.status = 'posted';

    SELECT coalesce(sum(o.total_minor::numeric), 0)
      INTO v_open_credit_orders
    FROM public.commercial_customer_orders o
    WHERE o.organization_id = p_organization_id
      AND o.buyer_account_id = v_account_id
      AND o.payment_status = 'credit'
      AND o.status IN ('pending', 'confirmed', 'in_progress');

    IF greatest(v_posted_balance, 0) + v_open_credit_orders + v_subtotal > v_credit_limit_minor THEN
      RAISE EXCEPTION 'customer_credit_limit_exceeded' USING ERRCODE='22023';
    END IF;
  END IF;

  IF p_reserved_from IS NOT NULL THEN v_order_type := 'booking'; END IF;

  INSERT INTO public.commercial_customer_orders(
    organization_id,buyer_account_id,created_by,order_type,status,payment_status,currency_code,
    subtotal_minor,discount_minor,tax_minor,total_minor,reserved_from,reserved_until,customer_note,idempotency_key
  ) VALUES (
    p_organization_id,v_account_id,v_uid,v_order_type,'pending',
    (CASE WHEN p_payment_mode='credit' THEN 'credit' ELSE 'unpaid' END),
    v_currency,v_subtotal,0,0,v_subtotal,p_reserved_from,p_reserved_until,
    nullif(btrim(p_customer_note),''),v_key
  ) RETURNING id INTO v_order_id;

  FOR v_line IN SELECT value FROM jsonb_array_elements(p_lines)
  LOOP
    SELECT ofr.price_minor, ofr.currency_code, p.name, a.activity_type
      INTO v_offering
    FROM public.commercial_offerings ofr
    JOIN public.commercial_activities a ON a.id=ofr.activity_id
    JOIN public.commercial_products p ON p.id=ofr.product_id
    WHERE ofr.id=(v_line->>'offering_id')::uuid
      AND a.organization_id=p_organization_id AND a.state='active' AND a.deleted_at IS NULL
      AND ofr.state='active' AND ofr.valid_from <= now()
      AND (ofr.valid_to IS NULL OR ofr.valid_to > now()) AND p.state='active';
    v_qty := (v_line->>'quantity')::numeric;
    v_line_total := round(v_qty * v_offering.price_minor)::bigint;
    INSERT INTO public.commercial_customer_order_items(
      order_id,offering_id,item_type,item_name_snapshot,quantity,unit_price_minor,line_total_minor
    ) VALUES (
      v_order_id,(v_line->>'offering_id')::uuid,
      CASE WHEN v_offering.activity_type = 'service' THEN 'service' ELSE 'product' END,
      v_offering.name,v_qty,v_offering.price_minor,v_line_total
    );
  END LOOP;

  RETURN (SELECT jsonb_build_object('order_id',o.id,'order_number',o.order_number,'status',o.status,
    'payment_status',o.payment_status,'total_minor',o.total_minor,'currency_code',o.currency_code,'replayed',false)
    FROM public.commercial_customer_orders o WHERE o.id=v_order_id);
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_customer_portal_create_delegated_order(p_delegation_id uuid, p_organization_id uuid, p_lines jsonb, p_payment_mode text, p_customer_note text DEFAULT NULL::text, p_reserved_from timestamp with time zone DEFAULT NULL::timestamp with time zone, p_reserved_until timestamp with time zone DEFAULT NULL::timestamp with time zone, p_idempotency_key text DEFAULT NULL::text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_parent_uid uuid;
  v_parent_personal_account_id uuid;
  v_child_personal_account_id uuid;
  v_delegation public.commercial_account_delegations%ROWTYPE;
  v_account_id uuid;
  v_customer_id uuid;
  v_account_currency text;
  v_credit_limit_minor bigint;
  v_posted_balance numeric := 0;
  v_open_credit_orders numeric := 0;
  v_currency text;
  v_order_type text := 'purchase';
  v_subtotal bigint := 0;
  v_line jsonb;
  v_offering record;
  v_qty numeric;
  v_line_total bigint;
  v_order_id uuid;
  v_existing public.commercial_customer_orders%ROWTYPE;
  v_key text := nullif(btrim(p_idempotency_key), '');
  v_count integer := 0;
  v_allow_credit boolean := false;
  v_existing_count integer;
  v_matching_count integer;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  SELECT d.* INTO v_delegation
  FROM public.commercial_account_delegations d
  JOIN public.commercial_party_accounts parent_account ON parent_account.id=d.parent_account_id
  JOIN public.commercial_party_accounts child_account ON child_account.id=d.child_account_id
  WHERE d.id=p_delegation_id AND d.organization_id=p_organization_id
    AND d.status='active' AND (d.expires_at IS NULL OR d.expires_at > now())
    AND parent_account.account_type='personal' AND parent_account.status='active'
    AND child_account.account_type='personal' AND child_account.status='active'
    AND child_account.owner_user_id=v_uid
  FOR UPDATE OF d;
  IF NOT FOUND THEN RAISE EXCEPTION 'delegated_access_not_active_or_not_owned' USING ERRCODE='42501'; END IF;
  SELECT parent_account.owner_user_id, child_account.id
    INTO v_parent_uid, v_child_personal_account_id
  FROM public.commercial_party_accounts parent_account
  JOIN public.commercial_party_accounts child_account ON child_account.id=v_delegation.child_account_id
  WHERE parent_account.id=v_delegation.parent_account_id;
  v_parent_personal_account_id := v_delegation.parent_account_id;
  IF p_reserved_from IS NOT NULL THEN
    IF NOT coalesce((v_delegation.permissions->>'book')::boolean,false) THEN
      RAISE EXCEPTION 'delegated_booking_permission_required' USING ERRCODE='42501';
    END IF;
  ELSE
    IF NOT coalesce((v_delegation.permissions->>'purchase')::boolean,false) THEN
      RAISE EXCEPTION 'delegated_purchase_permission_required' USING ERRCODE='42501';
    END IF;
  END IF;
  -- The parent was linked by the merchant approval flow; do not auto-link the child to a separate customer account.
  IF p_organization_id IS NULL THEN RAISE EXCEPTION 'organization_required' USING ERRCODE='22023'; END IF;
  IF p_payment_mode IS NULL OR p_payment_mode NOT IN ('cash','credit') THEN RAISE EXCEPTION 'invalid_payment_mode' USING ERRCODE='22023'; END IF;
  IF v_key IS NULL OR length(v_key) < 8 OR length(v_key) > 200 THEN RAISE EXCEPTION 'invalid_idempotency_key' USING ERRCODE='22023'; END IF;
  IF p_lines IS NULL OR jsonb_typeof(p_lines) <> 'array' OR jsonb_array_length(p_lines) < 1 OR jsonb_array_length(p_lines) > 100 THEN
    RAISE EXCEPTION 'invalid_order_lines' USING ERRCODE='22023';
  END IF;
  IF p_customer_note IS NOT NULL AND length(p_customer_note) > 1000 THEN RAISE EXCEPTION 'customer_note_too_long' USING ERRCODE='22023'; END IF;
  IF p_reserved_until IS NOT NULL AND (p_reserved_from IS NULL OR p_reserved_until <= p_reserved_from) THEN
    RAISE EXCEPTION 'invalid_reservation_window' USING ERRCODE='22023';
  END IF;
  IF p_reserved_from IS NOT NULL AND p_reserved_from < now() THEN
    RAISE EXCEPTION 'reservation_must_be_future' USING ERRCODE='22023';
  END IF;

  -- The customer account is organization-specific and must be explicitly linked to this auth user.
  SELECT a.id, c.id, a.allow_credit, a.currency_code, a.credit_limit_minor
    INTO v_account_id, v_customer_id, v_allow_credit, v_account_currency, v_credit_limit_minor
  FROM public.commercial_customer_party_accounts cpa
  JOIN public.commercial_customers c
    ON c.id = cpa.customer_id AND c.organization_id = cpa.organization_id AND c.state = 'active' AND c.auth_user_id = v_parent_uid
  JOIN public.commercial_party_accounts a
    ON a.id = cpa.party_account_id AND a.organization_id = cpa.organization_id
   AND a.account_type IN ('business','organization') AND a.status = 'active'
  JOIN public.commercial_organizations o
    ON o.id = cpa.organization_id AND o.state = 'active' AND o.deleted_at IS NULL
  WHERE cpa.organization_id = p_organization_id
  LIMIT 1;
  IF v_account_id IS NULL THEN RAISE EXCEPTION 'customer_not_linked_to_organization' USING ERRCODE='42501'; END IF;
  IF p_payment_mode = 'credit' AND NOT coalesce(v_allow_credit, false) THEN
    RAISE EXCEPTION 'customer_credit_not_authorized' USING ERRCODE='42501';
  END IF;
  IF v_account_currency IS NULL THEN RAISE EXCEPTION 'customer_account_currency_missing' USING ERRCODE='22023'; END IF;

  -- Serialize retries by account/key; the unique constraint remains the final guard.
  PERFORM pg_advisory_xact_lock(hashtextextended(v_account_id::text || ':' || v_key, 0));
  SELECT * INTO v_existing
  FROM public.commercial_customer_orders
  WHERE buyer_account_id = v_account_id AND idempotency_key = v_key;
  IF FOUND THEN
    IF v_existing.organization_id <> p_organization_id
       OR coalesce(v_existing.customer_note,'') <> coalesce(nullif(btrim(p_customer_note),''),'')
       OR v_existing.reserved_from IS DISTINCT FROM p_reserved_from
       OR v_existing.reserved_until IS DISTINCT FROM p_reserved_until
       OR v_existing.payment_status <> (CASE WHEN p_payment_mode='credit' THEN 'credit' ELSE 'unpaid' END)
       OR v_existing.delegated_access_id IS DISTINCT FROM p_delegation_id
       OR v_existing.principal_account_id IS DISTINCT FROM v_parent_personal_account_id
       OR v_existing.acting_account_id IS DISTINCT FROM v_child_personal_account_id THEN
      RAISE EXCEPTION 'idempotency_key_reused_with_different_request' USING ERRCODE='22023';
    END IF;
    SELECT count(*) INTO v_existing_count FROM public.commercial_customer_order_items WHERE order_id = v_existing.id;
    SELECT count(*) INTO v_matching_count
    FROM public.commercial_customer_order_items oi
    JOIN jsonb_array_elements(p_lines) j(value)
      ON oi.offering_id = nullif(j.value->>'offering_id','')::uuid
     AND oi.quantity = nullif(j.value->>'quantity','')::numeric
    WHERE oi.order_id = v_existing.id;
    IF v_existing_count <> jsonb_array_length(p_lines) OR v_matching_count <> v_existing_count THEN
      RAISE EXCEPTION 'idempotency_key_reused_with_different_request' USING ERRCODE='22023';
    END IF;
    RETURN jsonb_build_object('order_id',v_existing.id,'order_number',v_existing.order_number,
      'status',v_existing.status,'payment_status',v_existing.payment_status,
      'total_minor',v_existing.total_minor,'currency_code',v_existing.currency_code,'replayed',true);
  END IF;

  -- Lock each active offer while reading its trusted price and validate all lines before inserting.
  FOR v_line IN SELECT value FROM jsonb_array_elements(p_lines)
  LOOP
    IF jsonb_typeof(v_line) <> 'object' OR nullif(v_line->>'offering_id','') IS NULL
       OR nullif(v_line->>'quantity','') IS NULL THEN
      RAISE EXCEPTION 'invalid_order_line' USING ERRCODE='22023';
    END IF;
    BEGIN
      IF (v_line->>'offering_id') !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
        RAISE EXCEPTION 'invalid_order_offering_id' USING ERRCODE='22023';
      END IF;
      v_qty := (v_line->>'quantity')::numeric;
    EXCEPTION WHEN invalid_text_representation THEN
      RAISE EXCEPTION 'invalid_order_quantity' USING ERRCODE='22023';
    END;
    IF v_qty <= 0 OR v_qty > 1000000 OR v_qty <> trunc(v_qty) THEN
      RAISE EXCEPTION 'invalid_order_quantity' USING ERRCODE='22023';
    END IF;
    SELECT ofr.id, ofr.price_minor, ofr.currency_code, p.name, a.activity_type, a.id AS activity_id
      INTO v_offering
    FROM public.commercial_offerings ofr
    JOIN public.commercial_activities a ON a.id=ofr.activity_id
    JOIN public.commercial_products p ON p.id=ofr.product_id
    WHERE ofr.id=(v_line->>'offering_id')::uuid
      AND ofr.state='active' AND ofr.valid_from <= now()
      AND (ofr.valid_to IS NULL OR ofr.valid_to > now())
      AND a.organization_id=p_organization_id AND a.state='active' AND a.deleted_at IS NULL
      AND p.state='active'
    FOR SHARE OF ofr, a, p;
    IF NOT FOUND THEN RAISE EXCEPTION 'offering_not_available_for_organization' USING ERRCODE='22023'; END IF;
    IF v_delegation.activity_id IS NOT NULL AND v_offering.activity_id <> v_delegation.activity_id THEN
      RAISE EXCEPTION 'delegated_activity_scope_denied' USING ERRCODE='42501';
    END IF;
    IF v_currency IS NULL THEN v_currency := v_offering.currency_code;
    ELSIF v_currency <> v_offering.currency_code THEN RAISE EXCEPTION 'mixed_order_currencies_not_supported' USING ERRCODE='22023'; END IF;
    IF v_offering.price_minor < 0 OR v_qty * v_offering.price_minor > 9000000000000000000 THEN
      RAISE EXCEPTION 'order_total_overflow' USING ERRCODE='22003';
    END IF;
    v_line_total := round(v_qty * v_offering.price_minor)::bigint;
    IF v_line_total < 0 OR v_subtotal > 9000000000000000000 - v_line_total THEN
      RAISE EXCEPTION 'order_total_overflow' USING ERRCODE='22003';
    END IF;
    v_subtotal := v_subtotal + v_line_total;
    v_count := v_count + 1;
    IF v_offering.activity_type = 'service' THEN v_order_type := 'service'; END IF;
  END LOOP;

  IF (SELECT count(DISTINCT value->>'offering_id') FROM jsonb_array_elements(p_lines)) <> v_count THEN
    RAISE EXCEPTION 'duplicate_order_offering' USING ERRCODE='22023';
  END IF;


  IF v_currency <> v_account_currency THEN
    RAISE EXCEPTION 'customer_account_currency_mismatch' USING ERRCODE='22023';
  END IF;

  IF p_payment_mode = 'credit' AND v_credit_limit_minor IS NOT NULL THEN
    -- Serialize credit-order submissions for this account before checking exposure.
    PERFORM pg_advisory_xact_lock(hashtextextended(v_account_id::text || ':credit-limit', 0));
    SELECT coalesce(sum(jl.debit_minor::numeric - jl.credit_minor::numeric), 0)
      INTO v_posted_balance
    FROM public.commercial_journal_lines jl
    JOIN public.commercial_journal_entries je
      ON je.id = jl.journal_entry_id AND je.organization_id = jl.organization_id
    WHERE jl.organization_id = p_organization_id
      AND jl.party_account_id = v_account_id
      AND je.status = 'posted';

    SELECT coalesce(sum(o.total_minor::numeric), 0)
      INTO v_open_credit_orders
    FROM public.commercial_customer_orders o
    WHERE o.organization_id = p_organization_id
      AND o.buyer_account_id = v_account_id
      AND o.payment_status = 'credit'
      AND o.status IN ('pending', 'confirmed', 'in_progress');

    IF greatest(v_posted_balance, 0) + v_open_credit_orders + v_subtotal > v_credit_limit_minor THEN
      RAISE EXCEPTION 'customer_credit_limit_exceeded' USING ERRCODE='22023';
    END IF;
  END IF;

  IF p_reserved_from IS NOT NULL THEN v_order_type := 'booking'; END IF;
  IF v_currency <> v_delegation.spend_currency_code THEN
    RAISE EXCEPTION 'delegated_spend_currency_mismatch' USING ERRCODE='22023';
  END IF;
  IF v_delegation.spend_limit_minor IS NOT NULL THEN
    IF v_delegation.spent_minor::numeric + v_subtotal::numeric > v_delegation.spend_limit_minor::numeric THEN
      RAISE EXCEPTION 'delegated_spend_limit_exceeded' USING ERRCODE='22023';
    END IF;
  END IF;

  INSERT INTO public.commercial_customer_orders(
    organization_id,buyer_account_id,created_by,order_type,status,payment_status,currency_code,
    subtotal_minor,discount_minor,tax_minor,total_minor,reserved_from,reserved_until,customer_note,idempotency_key,
    delegated_access_id,principal_account_id,acting_account_id
  ) VALUES (
    p_organization_id,v_account_id,v_uid,v_order_type,'pending',
    (CASE WHEN p_payment_mode='credit' THEN 'credit' ELSE 'unpaid' END),
    v_currency,v_subtotal,0,0,v_subtotal,p_reserved_from,p_reserved_until,
    nullif(btrim(p_customer_note),''),v_key,p_delegation_id,v_parent_personal_account_id,v_child_personal_account_id
  ) RETURNING id INTO v_order_id;

  FOR v_line IN SELECT value FROM jsonb_array_elements(p_lines)
  LOOP
    SELECT ofr.price_minor, ofr.currency_code, p.name, a.activity_type
      INTO v_offering
    FROM public.commercial_offerings ofr
    JOIN public.commercial_activities a ON a.id=ofr.activity_id
    JOIN public.commercial_products p ON p.id=ofr.product_id
    WHERE ofr.id=(v_line->>'offering_id')::uuid
      AND a.organization_id=p_organization_id AND a.state='active' AND a.deleted_at IS NULL
      AND ofr.state='active' AND ofr.valid_from <= now()
      AND (ofr.valid_to IS NULL OR ofr.valid_to > now()) AND p.state='active';
    v_qty := (v_line->>'quantity')::numeric;
    v_line_total := round(v_qty * v_offering.price_minor)::bigint;
    INSERT INTO public.commercial_customer_order_items(
      order_id,offering_id,item_type,item_name_snapshot,quantity,unit_price_minor,line_total_minor
    ) VALUES (
      v_order_id,(v_line->>'offering_id')::uuid,
      CASE WHEN v_offering.activity_type = 'service' THEN 'service' ELSE 'product' END,
      v_offering.name,v_qty,v_offering.price_minor,v_line_total
    );
  END LOOP;

  UPDATE public.commercial_account_delegations
  SET spent_minor=spent_minor + v_subtotal, updated_at=now()
  WHERE id=p_delegation_id;

  RETURN (SELECT jsonb_build_object('order_id',o.id,'order_number',o.order_number,'status',o.status,
    'payment_status',o.payment_status,'total_minor',o.total_minor,'currency_code',o.currency_code,
    'delegated_access_id',o.delegated_access_id,'principal_account_id',o.principal_account_id,
    'acting_account_id',o.acting_account_id,'replayed',false)
    FROM public.commercial_customer_orders o WHERE o.id=v_order_id);
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_customer_portal_create_multi_service_booking(
  p_organization_id uuid,
  p_lines jsonb,
  p_resource_id uuid,
  p_starts_at timestamptz,
  p_ends_at timestamptz,
  p_payment_mode text,
  p_customer_note text DEFAULT NULL,
  p_idempotency_key text DEFAULT NULL
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_account_id uuid;
  v_account_currency text;
  v_resource public.commercial_booking_resources%ROWTYPE;
  v_existing public.commercial_bookings%ROWTYPE;
  v_existing_order public.commercial_customer_orders%ROWTYPE;
  v_order jsonb;
  v_order_id uuid;
  v_order_total bigint;
  v_key text := nullif(btrim(p_idempotency_key), '');
  v_line jsonb;
  v_offering record;
  v_qty numeric;
  v_count integer := 0;
  v_match_count integer := 0;
  v_requested_capacity numeric := 0;
  v_used_capacity numeric := 0;
  v_currency text;
  v_result jsonb;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501';
  END IF;
  PERFORM public.commercial_customer_portal_link_verified_email();

  IF p_organization_id IS NULL OR p_resource_id IS NULL THEN
    RAISE EXCEPTION 'booking_fields_required' USING ERRCODE='22023';
  END IF;
  IF p_starts_at IS NULL OR p_ends_at IS NULL OR p_starts_at <= now() OR p_ends_at <= p_starts_at THEN
    RAISE EXCEPTION 'invalid_reservation_window' USING ERRCODE='22023';
  END IF;
  IF p_payment_mode IS NULL OR p_payment_mode NOT IN ('cash','credit') THEN
    RAISE EXCEPTION 'invalid_payment_mode' USING ERRCODE='22023';
  END IF;
  IF v_key IS NULL OR length(v_key) < 8 OR length(v_key) > 200 THEN
    RAISE EXCEPTION 'invalid_idempotency_key' USING ERRCODE='22023';
  END IF;
  IF p_customer_note IS NOT NULL AND length(p_customer_note) > 1000 THEN
    RAISE EXCEPTION 'customer_note_too_long' USING ERRCODE='22023';
  END IF;
  IF p_lines IS NULL OR jsonb_typeof(p_lines) <> 'array'
     OR jsonb_array_length(p_lines) < 1 OR jsonb_array_length(p_lines) > 50 THEN
    RAISE EXCEPTION 'invalid_booking_lines' USING ERRCODE='22023';
  END IF;

  -- Validate the complete submitted line set before looking up/replaying a booking.
  FOR v_line IN SELECT value FROM jsonb_array_elements(p_lines)
  LOOP
    IF jsonb_typeof(v_line) <> 'object'
       OR nullif(v_line->>'offering_id','') IS NULL
       OR nullif(v_line->>'quantity','') IS NULL
       OR (v_line->>'offering_id') !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
      RAISE EXCEPTION 'invalid_booking_line' USING ERRCODE='22023';
    END IF;
    BEGIN
      v_qty := (v_line->>'quantity')::numeric;
    EXCEPTION WHEN invalid_text_representation THEN
      RAISE EXCEPTION 'invalid_booking_quantity' USING ERRCODE='22023';
    END;
    IF v_qty <= 0 OR v_qty > 100000 OR v_qty <> trunc(v_qty) THEN
      RAISE EXCEPTION 'invalid_booking_quantity' USING ERRCODE='22023';
    END IF;
    v_count := v_count + 1;
  END LOOP;
  IF (SELECT count(DISTINCT value->>'offering_id') FROM jsonb_array_elements(p_lines)) <> v_count THEN
    RAISE EXCEPTION 'duplicate_booking_offering' USING ERRCODE='22023';
  END IF;

  SELECT a.id, a.currency_code
    INTO v_account_id, v_account_currency
  FROM public.commercial_customer_party_accounts cpa
  JOIN public.commercial_customers c
    ON c.id=cpa.customer_id AND c.organization_id=cpa.organization_id
   AND c.state='active' AND c.auth_user_id=v_uid
  JOIN public.commercial_party_accounts a
    ON a.id=cpa.party_account_id AND a.organization_id=cpa.organization_id
   AND a.account_type IN ('business','organization') AND a.status='active'
  JOIN public.commercial_organizations o
    ON o.id=cpa.organization_id AND o.state='active' AND o.deleted_at IS NULL
  WHERE cpa.organization_id=p_organization_id
  LIMIT 1;
  IF v_account_id IS NULL THEN
    RAISE EXCEPTION 'customer_not_linked_to_organization' USING ERRCODE='42501';
  END IF;
  IF p_payment_mode='credit' AND NOT EXISTS (
    SELECT 1 FROM public.commercial_party_accounts a
    WHERE a.id=v_account_id AND a.organization_id=p_organization_id
      AND a.status='active' AND a.allow_credit
  ) THEN
    RAISE EXCEPTION 'customer_credit_not_authorized' USING ERRCODE='42501';
  END IF;

  -- Serialize retries for this account/key; unique constraints remain the final protection.
  PERFORM pg_advisory_xact_lock(hashtextextended(v_account_id::text || ':' || v_key, 0));

  SELECT * INTO v_existing
  FROM public.commercial_bookings b
  WHERE b.customer_account_id=v_account_id AND b.idempotency_key=v_key
  FOR UPDATE;
  IF FOUND THEN
    IF v_existing.organization_id <> p_organization_id
       OR v_existing.resource_id <> p_resource_id
       OR v_existing.starts_at <> p_starts_at
       OR v_existing.ends_at <> p_ends_at
       OR coalesce(v_existing.notes,'') <> coalesce(nullif(btrim(p_customer_note),''),'') THEN
      RAISE EXCEPTION 'idempotency_key_reused_with_different_request' USING ERRCODE='22023';
    END IF;

    SELECT * INTO v_existing_order
    FROM public.commercial_customer_orders o
    WHERE o.id=v_existing.order_id AND o.organization_id=p_organization_id
      AND o.buyer_account_id=v_account_id
      AND o.payment_status=CASE WHEN p_payment_mode='credit' THEN 'credit' ELSE 'unpaid' END;
    IF NOT FOUND THEN
      RAISE EXCEPTION 'idempotency_key_reused_with_different_request' USING ERRCODE='22023';
    END IF;

    SELECT count(*) INTO v_match_count
    FROM public.commercial_customer_order_items oi
    JOIN jsonb_array_elements(p_lines) j(value)
      ON oi.offering_id=(j.value->>'offering_id')::uuid
     AND oi.quantity=(j.value->>'quantity')::numeric
    WHERE oi.order_id=v_existing_order.id AND oi.item_type='service';
    IF v_match_count <> jsonb_array_length(p_lines)
       OR (SELECT count(*) FROM public.commercial_customer_order_items oi WHERE oi.order_id=v_existing_order.id)
          <> jsonb_array_length(p_lines)
       OR v_existing_order.reserved_from IS DISTINCT FROM p_starts_at
       OR v_existing_order.reserved_until IS DISTINCT FROM p_ends_at THEN
      RAISE EXCEPTION 'idempotency_key_reused_with_different_request' USING ERRCODE='22023';
    END IF;

    RETURN jsonb_build_object(
      'booking_id',v_existing.id,'booking_number',v_existing.booking_number,
      'order_id',v_existing.order_id,'order_number',v_existing_order.order_number,
      'status',v_existing.status,'price_minor',v_existing.price_minor,
      'currency_code',v_existing.currency_code,'replayed',true,
      'items',(SELECT coalesce(jsonb_agg(jsonb_build_object(
        'offering_id',oi.offering_id,'name',oi.item_name_snapshot,'quantity',oi.quantity,
        'unit_price_minor',oi.unit_price_minor,'line_total_minor',oi.line_total_minor
      ) ORDER BY oi.created_at,oi.id),'[]'::jsonb)
      FROM public.commercial_customer_order_items oi WHERE oi.order_id=v_existing_order.id)
    );
  END IF;

  -- Lock the capacity row while validating all requested services and the shared resource.
  SELECT * INTO v_resource
  FROM public.commercial_booking_resources r
  WHERE r.id=p_resource_id AND r.organization_id=p_organization_id AND r.status='active'
  FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'booking_resource_not_available' USING ERRCODE='22023'; END IF;

  FOR v_line IN SELECT value FROM jsonb_array_elements(p_lines)
  LOOP
    SELECT f.id, f.price_minor, f.currency_code, a.activity_type
      INTO v_offering
    FROM public.commercial_offerings f
    JOIN public.commercial_activities a ON a.id=f.activity_id
    JOIN public.commercial_products p ON p.id=f.product_id
    WHERE f.id=(v_line->>'offering_id')::uuid
      AND f.state='active' AND f.valid_from<=now() AND (f.valid_to IS NULL OR f.valid_to>now())
      AND a.organization_id=p_organization_id AND a.activity_type='service'
      AND a.state='active' AND a.deleted_at IS NULL AND p.state='active'
    FOR SHARE OF f,a,p;
    IF NOT FOUND THEN RAISE EXCEPTION 'booking_service_not_available' USING ERRCODE='22023'; END IF;
    IF v_offering.currency_code <> v_account_currency THEN
      RAISE EXCEPTION 'booking_currency_mismatch' USING ERRCODE='22023';
    END IF;
    IF v_currency IS NULL THEN v_currency := v_offering.currency_code;
    ELSIF v_currency <> v_offering.currency_code THEN
      RAISE EXCEPTION 'mixed_order_currencies_not_supported' USING ERRCODE='22023';
    END IF;
  END LOOP;

  -- Capacity is consumed by requested service units, not merely by booking rows.
  -- Legacy/other bookings without service order lines conservatively consume one unit.
  SELECT coalesce(sum((j.value->>'quantity')::numeric), 0)
    INTO v_requested_capacity
  FROM jsonb_array_elements(p_lines) AS j(value);

  SELECT coalesce(sum(active_booking.consumed_units), 0)
    INTO v_used_capacity
  FROM (
    SELECT b.id,
           CASE
             WHEN count(oi.id) FILTER (WHERE oi.item_type='service') > 0
               THEN sum(oi.quantity) FILTER (WHERE oi.item_type='service')
             ELSE 1::numeric
           END AS consumed_units
    FROM public.commercial_bookings b
    LEFT JOIN public.commercial_customer_order_items oi ON oi.order_id=b.order_id
    WHERE b.organization_id=p_organization_id AND b.resource_id=p_resource_id
      AND b.status IN ('requested','confirmed','checked_in')
      AND b.starts_at<p_ends_at AND b.ends_at>p_starts_at
    GROUP BY b.id
  ) AS active_booking;

  IF v_used_capacity + v_requested_capacity > v_resource.capacity THEN
    RAISE EXCEPTION 'booking_resource_capacity_exceeded' USING ERRCODE='22023';
  END IF;

  v_order := public.commercial_customer_portal_create_order(
    p_organization_id,p_lines,p_payment_mode,p_customer_note,p_starts_at,p_ends_at,v_key
  );
  v_order_id := (v_order->>'order_id')::uuid;
  v_order_total := (v_order->>'total_minor')::bigint;

  -- Defense in depth: booking orders may contain service lines only.
  IF EXISTS (
    SELECT 1 FROM public.commercial_customer_order_items oi
    WHERE oi.order_id=v_order_id AND oi.item_type IS DISTINCT FROM 'service'
  ) OR (SELECT count(*) FROM public.commercial_customer_order_items oi WHERE oi.order_id=v_order_id)
       <> jsonb_array_length(p_lines) THEN
    RAISE EXCEPTION 'booking_order_contains_invalid_lines' USING ERRCODE='22023';
  END IF;

  INSERT INTO public.commercial_bookings(
    organization_id,resource_id,customer_account_id,order_id,status,starts_at,ends_at,
    currency_code,price_minor,paid_minor,created_by,notes,idempotency_key
  ) VALUES (
    p_organization_id,p_resource_id,v_account_id,v_order_id,'requested',p_starts_at,p_ends_at,
    v_currency,v_order_total,0,v_uid,nullif(btrim(p_customer_note),''),v_key
  ) RETURNING id,booking_number INTO v_existing.id,v_existing.booking_number;

  SELECT coalesce(jsonb_agg(jsonb_build_object(
    'offering_id',oi.offering_id,'name',oi.item_name_snapshot,'quantity',oi.quantity,
    'unit_price_minor',oi.unit_price_minor,'line_total_minor',oi.line_total_minor
  ) ORDER BY oi.created_at,oi.id),'[]'::jsonb)
  INTO v_result
  FROM public.commercial_customer_order_items oi WHERE oi.order_id=v_order_id;

  RETURN jsonb_build_object(
    'booking_id',v_existing.id,'booking_number',v_existing.booking_number,
    'order_id',v_order_id,'order_number',v_order->>'order_number',
    'status','requested','price_minor',v_order_total,'currency_code',v_currency,
    'replayed',coalesce((v_order->>'replayed')::boolean,false),'items',v_result
  );
END;
$function$;

CREATE OR REPLACE FUNCTION commercial_private.commercial_create_inventory_item(
  p_organization_id uuid,
  p_activity_id uuid,
  p_location_id uuid,
  p_name text,
  p_unit_name text,
  p_price_minor bigint,
  p_currency_code text,
  p_initial_quantity integer,
  p_reorder_point numeric DEFAULT 0
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog', 'public'
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_product_id uuid;
  v_offering_id uuid;
  v_existing_id uuid;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE = '42501'; END IF;
  IF p_organization_id IS NULL OR p_activity_id IS NULL OR p_location_id IS NULL THEN
    RAISE EXCEPTION 'organization_activity_and_location_required' USING ERRCODE = '22023';
  END IF;
  IF p_name IS NULL OR length(btrim(p_name)) NOT BETWEEN 1 AND 180
     OR p_unit_name IS NULL OR length(btrim(p_unit_name)) NOT BETWEEN 1 AND 40 THEN
    RAISE EXCEPTION 'invalid_product_name_or_unit' USING ERRCODE = '22023';
  END IF;
  IF p_price_minor IS NULL OR p_price_minor < 0 THEN RAISE EXCEPTION 'invalid_price' USING ERRCODE = '22023'; END IF;
  IF p_currency_code IS NULL OR p_currency_code !~ '^[A-Z]{3}$' THEN RAISE EXCEPTION 'invalid_currency_code' USING ERRCODE = '22023'; END IF;
  IF p_initial_quantity IS NULL OR p_initial_quantity < 0 OR p_initial_quantity > 100000000
     OR p_reorder_point IS NULL OR p_reorder_point < 0 OR p_reorder_point <> trunc(p_reorder_point) THEN
    RAISE EXCEPTION 'invalid_inventory_quantity' USING ERRCODE = '22023';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id = p_organization_id AND m.user_id = v_uid
      AND m.state = 'active' AND m.role IN ('owner','admin','manager')
  ) THEN RAISE EXCEPTION 'organization_write_access_denied' USING ERRCODE = '42501'; END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_activities a
    JOIN public.commercial_organizations o ON o.id = a.organization_id
    WHERE a.id = p_activity_id AND a.organization_id = p_organization_id
      AND a.state = 'active' AND a.deleted_at IS NULL
      AND o.state = 'active' AND o.deleted_at IS NULL
  ) THEN RAISE EXCEPTION 'activity_not_in_active_organization' USING ERRCODE = '42501'; END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_locations l
    WHERE l.id = p_location_id AND l.organization_id = p_organization_id AND l.state = 'active'
  ) THEN RAISE EXCEPTION 'location_not_in_organization' USING ERRCODE = '42501'; END IF;

  PERFORM pg_advisory_xact_lock(hashtext(p_activity_id::text),
    hashtext(lower(btrim(p_name)) || '|' || p_location_id::text || '|' || upper(p_currency_code)));
  SELECT o.id INTO v_existing_id
  FROM public.commercial_offerings o JOIN public.commercial_products p ON p.id = o.product_id
  WHERE o.activity_id = p_activity_id AND o.location_ref = p_location_id::text
    AND o.state = 'active' AND lower(btrim(p.name)) = lower(btrim(p_name))
    AND lower(btrim(p.unit_name)) = lower(btrim(p_unit_name))
  LIMIT 1;
  IF FOUND THEN RAISE EXCEPTION 'duplicate_active_inventory_item' USING ERRCODE = '23505'; END IF;

  INSERT INTO public.commercial_products(name, unit_name, state)
  VALUES (btrim(p_name), btrim(p_unit_name), 'active') RETURNING id INTO v_product_id;
  INSERT INTO public.commercial_offerings(activity_id, product_id, price_minor, currency_code, scope_key, location_ref, state, valid_from)
  VALUES (p_activity_id, v_product_id, p_price_minor, upper(p_currency_code), 'retail', p_location_id::text, 'active', now())
  RETURNING id INTO v_offering_id;
  INSERT INTO public.commercial_inventory_balances(organization_id, location_id, offering_id, quantity_on_hand, quantity_reserved, reorder_point)
  VALUES (p_organization_id, p_location_id, v_offering_id, p_initial_quantity, 0, p_reorder_point);
  IF p_initial_quantity > 0 THEN
    INSERT INTO public.commercial_stock_movements(offering_id, movement_type, quantity_delta, source_ref, created_by)
    VALUES (v_offering_id, 'opening', p_initial_quantity,
      'opening:' || v_offering_id::text || ':location:' || p_location_id::text, v_uid);
  END IF;
  INSERT INTO public.commercial_audit_events(organization_id, actor_user_id, action, entity_type, entity_id, metadata)
  VALUES (p_organization_id, v_uid, 'inventory_item.created', 'offering', v_offering_id::text,
    jsonb_build_object('activity_id', p_activity_id, 'location_id', p_location_id, 'product_id', v_product_id,
      'initial_quantity', p_initial_quantity, 'price_minor', p_price_minor, 'currency_code', upper(p_currency_code)));
  RETURN jsonb_build_object('product_id', v_product_id, 'offering_id', v_offering_id,
    'organization_id', p_organization_id, 'activity_id', p_activity_id, 'location_id', p_location_id,
    'quantity_on_hand', p_initial_quantity, 'price_minor', p_price_minor, 'currency_code', upper(p_currency_code));
END;
$function$;

CREATE OR REPLACE FUNCTION public.commercial_catalog_create_inventory_item_from_global(
 p_organization_id uuid,p_activity_id uuid,p_activity_node_code text,p_location_id uuid,p_warehouse_id uuid,
 p_global_item_id uuid,p_base_unit_code text,p_sale_unit_code text,p_unit_code text,p_display_name text,
 p_price_minor bigint,p_currency_code text,p_initial_quantity integer,p_reorder_point numeric,p_category_id uuid,p_idempotency_key text
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,commercial_private AS $$
DECLARE v_uid uuid:=auth.uid(); v_node uuid; v_global public.commercial_global_catalog_items%ROWTYPE;
 v_factor numeric(30,12); v_unit_name text; v_result jsonb; v_offering uuid; v_fp text;
 v_saved public.commercial_catalog_inventory_creation_requests%ROWTYPE; v_existing uuid;
BEGIN
 IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_organization_id IS NULL OR p_activity_id IS NULL OR p_location_id IS NULL OR p_warehouse_id IS NULL
  OR p_global_item_id IS NULL OR p_price_minor IS NULL OR p_price_minor<=0 OR p_currency_code IS NULL
  OR p_initial_quantity IS NULL OR p_initial_quantity<0 OR p_initial_quantity>100000000
  OR p_reorder_point IS NULL OR p_reorder_point<0 OR p_reorder_point<>trunc(p_reorder_point) OR p_currency_code !~ '^[A-Z]{3}$'
  OR p_unit_code IS NULL OR p_base_unit_code IS NULL OR p_sale_unit_code IS NULL
  OR nullif(btrim(p_display_name),'') IS NULL OR length(btrim(p_display_name))>180
  OR length(btrim(coalesce(p_idempotency_key,''))) NOT BETWEEN 8 AND 200
  OR nullif(btrim(p_activity_node_code),'') IS NULL THEN
  RAISE EXCEPTION 'invalid_catalog_inventory_creation_request' USING ERRCODE='22023';
 END IF;
 IF NOT public.commercial_catalog_member_of(p_organization_id,ARRAY['owner','admin','manager']::text[]) THEN
  RAISE EXCEPTION 'catalog_inventory_creation_forbidden' USING ERRCODE='42501';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM public.commercial_activities WHERE id=p_activity_id AND organization_id=p_organization_id AND state='active' AND deleted_at IS NULL) THEN
  RAISE EXCEPTION 'activity_not_in_active_organization' USING ERRCODE='42501';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM public.commercial_organization_locations WHERE id=p_location_id AND organization_id=p_organization_id AND state='active') THEN
  RAISE EXCEPTION 'branch_not_in_organization' USING ERRCODE='22023';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM public.commercial_warehouses WHERE id=p_warehouse_id AND organization_id=p_organization_id AND branch_location_id=p_location_id AND state='active') THEN
  RAISE EXCEPTION 'warehouse_not_in_branch' USING ERRCODE='22023';
 END IF;
 SELECT id INTO v_node FROM public.commercial_global_business_activity_nodes WHERE code=upper(btrim(p_activity_node_code)) AND status='active' AND is_selectable;
 IF v_node IS NULL THEN RAISE EXCEPTION 'active_activity_not_found' USING ERRCODE='22023'; END IF;
 SELECT * INTO v_global FROM public.commercial_global_catalog_items WHERE id=p_global_item_id AND is_active AND (owner_organization_id IS NULL OR owner_organization_id=p_organization_id);
 IF NOT FOUND THEN RAISE EXCEPTION 'catalog_item_not_active_or_accessible' USING ERRCODE='22023'; END IF;
 IF NOT EXISTS(SELECT 1 FROM public.commercial_catalog_product_units WHERE product_id=p_global_item_id AND unit_code=upper(btrim(p_base_unit_code)) AND is_active AND unit_role='base' AND quantity_in_base=1) THEN
  RAISE EXCEPTION 'base_unit_not_defined_by_catalog' USING ERRCODE='22023';
 END IF;
 SELECT quantity_in_base INTO v_factor FROM public.commercial_catalog_product_units WHERE product_id=p_global_item_id AND unit_code=upper(btrim(p_sale_unit_code)) AND is_active ORDER BY is_default DESC,created_at LIMIT 1;
 IF v_factor IS NULL THEN RAISE EXCEPTION 'sale_unit_not_defined_by_catalog' USING ERRCODE='22023'; END IF;
 SELECT display_name INTO v_unit_name FROM public.commercial_unit_of_measure WHERE code=btrim(p_unit_code) AND is_active;
 IF v_unit_name IS NULL THEN RAISE EXCEPTION 'active_unit_of_measure_not_found' USING ERRCODE='22023'; END IF;
 v_fp:=md5(jsonb_build_object('activity_id',p_activity_id,'activity_node_code',upper(btrim(p_activity_node_code)),
  'location_id',p_location_id,'warehouse_id',p_warehouse_id,'global_item_id',p_global_item_id,
  'base_unit_code',upper(btrim(p_base_unit_code)),'sale_unit_code',upper(btrim(p_sale_unit_code)),
  'unit_code',btrim(p_unit_code),'display_name',btrim(p_display_name),'price_minor',p_price_minor,
  'currency_code',upper(p_currency_code),'initial_quantity',p_initial_quantity,'reorder_point',p_reorder_point,
  'category_id',p_category_id)::text);
 PERFORM pg_advisory_xact_lock(hashtext(p_organization_id::text),hashtext('catalog-inventory:'||btrim(p_idempotency_key)));
 SELECT * INTO v_saved FROM public.commercial_catalog_inventory_creation_requests WHERE organization_id=p_organization_id AND idempotency_key=btrim(p_idempotency_key);
 IF FOUND THEN
  IF v_saved.request_fingerprint IS DISTINCT FROM v_fp THEN RAISE EXCEPTION 'catalog_creation_key_reused_with_different_request' USING ERRCODE='23505'; END IF;
  RETURN v_saved.response||jsonb_build_object('idempotent_replay',true);
 END IF;
 SELECT m.offering_id INTO v_existing FROM public.commercial_catalog_item_offerings m
 JOIN public.commercial_offerings o ON o.id=m.offering_id AND o.activity_id=p_activity_id AND o.location_ref=p_location_id::text AND o.state='active'
 WHERE m.organization_id=p_organization_id AND m.global_item_id=p_global_item_id AND m.location_id=p_location_id LIMIT 1;
 IF v_existing IS NOT NULL THEN
  v_result:=jsonb_build_object('global_item_id',p_global_item_id,'offering_id',v_existing,'location_id',p_location_id,
   'warehouse_id',p_warehouse_id,'created_new_offering',false,'already_linked',true,'idempotent_replay',false);
  INSERT INTO public.commercial_catalog_inventory_creation_requests(organization_id,idempotency_key,request_fingerprint,global_item_id,location_id,offering_id,response,created_by)
  VALUES(p_organization_id,btrim(p_idempotency_key),v_fp,p_global_item_id,p_location_id,v_existing,v_result,v_uid);
  RETURN v_result;
 END IF;
 INSERT INTO public.commercial_organization_catalog_items(organization_id,global_item_id,merchant_display_name,merchant_description,price_minor,currency_code,is_available,metadata,created_by,is_enabled,imported_at,pricing_status)
 VALUES(p_organization_id,p_global_item_id,v_global.name_ar,v_global.description_ar,0,upper(p_currency_code),false,
  jsonb_build_object('linked_without_pricing',true,'pricing_required',true,'source','global_catalog'),v_uid,true,now(),'unpriced')
 ON CONFLICT(organization_id,global_item_id) DO NOTHING;
 INSERT INTO public.commercial_organization_catalog_item_activities(organization_id,global_item_id,activity_node_id,created_by)
 VALUES(p_organization_id,p_global_item_id,v_node,v_uid) ON CONFLICT DO NOTHING;
 INSERT INTO public.commercial_organization_catalog_item_setup(organization_id,global_item_id,activity_node_id,branch_location_id,warehouse_id,base_unit_code,sale_unit_code,sale_quantity_in_base,created_by,updated_at)
 VALUES(p_organization_id,p_global_item_id,v_node,p_location_id,p_warehouse_id,upper(btrim(p_base_unit_code)),upper(btrim(p_sale_unit_code)),v_factor,v_uid,now())
 ON CONFLICT(organization_id,global_item_id,activity_node_id,branch_location_id,warehouse_id)
 DO UPDATE SET base_unit_code=EXCLUDED.base_unit_code,sale_unit_code=EXCLUDED.sale_unit_code,sale_quantity_in_base=EXCLUDED.sale_quantity_in_base,updated_at=now();
 v_result:=public.commercial_create_inventory_item_with_category_and_unit(p_organization_id,p_activity_id,p_location_id,btrim(p_display_name),v_unit_name,p_price_minor,upper(p_currency_code),p_initial_quantity,p_reorder_point,p_category_id,btrim(p_unit_code));
 v_offering:=(v_result->>'offering_id')::uuid;
 INSERT INTO public.commercial_catalog_item_offerings(organization_id,global_item_id,offering_id,location_id,linked_by)
 VALUES(p_organization_id,p_global_item_id,v_offering,p_location_id,v_uid);
 v_result:=v_result||jsonb_build_object('global_item_id',p_global_item_id,'warehouse_id',p_warehouse_id,
  'activity_node_code',upper(btrim(p_activity_node_code)),'created_new_offering',true,'already_linked',false,'idempotent_replay',false);
 INSERT INTO public.commercial_catalog_inventory_creation_requests(organization_id,idempotency_key,request_fingerprint,global_item_id,location_id,offering_id,response,created_by)
 VALUES(p_organization_id,btrim(p_idempotency_key),v_fp,p_global_item_id,p_location_id,v_offering,v_result,v_uid);
 INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
 VALUES(p_organization_id,v_uid,'catalog_inventory_item.created','offering',v_offering::text,jsonb_build_object('global_item_id',p_global_item_id,'activity_node_code',upper(btrim(p_activity_node_code)),'location_id',p_location_id,'warehouse_id',p_warehouse_id,'price_minor',p_price_minor,'currency_code',upper(p_currency_code),'initial_quantity',p_initial_quantity));
 RETURN v_result;
END; $$;

-- Enforce whole-unit quantities without rewriting historical records.
-- Any pre-existing fractional value aborts this migration for explicit reconciliation.
DO $migration$
DECLARE
  v_table text;
  v_column text;
  v_constraint text;
  v_has_fraction boolean;
  v_tables text[] := ARRAY[
    'commercial_sale_items','commercial_sale_return_items','commercial_customer_order_items',
    'commercial_purchase_order_items','commercial_purchase_receipt_items',
    'commercial_inventory_receipts','commercial_inventory_balances',
    'commercial_stock_movements','commercial_supplier_invoice_items',
    'commercial_inventory_cost_layers','commercial_inventory_cost_allocations',
    'commercial_inventory_return_cost_allocations','commercial_inventory_cost_reversals',
    'commercial_return_restock_postings','commercial_invoice_purchase_request_items',
    'commercial_offering_sell_units','commercial_catalog_product_units','commercial_catalog_item_units','commercial_global_units',
    'commercial_global_catalog_items','commercial_catalog_product_recipes','commercial_catalog_recipe_ingredients',
    'commercial_organization_catalog_item_setup',
    'commercial_catalog_inventory_units'
  ];
  v_columns text[] := ARRAY[
    'quantity','selling_quantity','quantity_in_stock_units',
    'quantity_ordered','quantity_received','quantity_on_hand','quantity_reserved',
    'quantity_delta','quantity_remaining','sale_quantity_in_base','quantity_in_base','factor_to_base','reorder_point',
    'net_quantity','yield_quantity'
  ];
BEGIN
  FOREACH v_table IN ARRAY v_tables LOOP
    FOREACH v_column IN ARRAY v_columns LOOP
      IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema='public' AND table_name=v_table AND column_name=v_column
          AND data_type IN ('numeric','decimal','real','double precision')
      ) THEN
        EXECUTE format(
          'SELECT EXISTS (SELECT 1 FROM public.%I WHERE %I IS NOT NULL AND %I <> trunc(%I))',
          v_table,v_column,v_column,v_column
        ) INTO v_has_fraction;
        IF v_has_fraction THEN
          RAISE EXCEPTION 'fractional_historical_quantity_requires_reconciliation: %.%',
            v_table,v_column USING ERRCODE='55000';
        END IF;
        v_constraint := left(v_table || '_' || v_column || '_whole_qty_ck', 63);
        IF NOT EXISTS (
          SELECT 1 FROM pg_constraint
          WHERE conrelid=format('public.%I',v_table)::regclass AND conname=v_constraint
        ) THEN
          EXECUTE format(
            'ALTER TABLE public.%I ADD CONSTRAINT %I CHECK (%I IS NULL OR %I = trunc(%I)) NOT VALID',
            v_table,v_constraint,v_column,v_column,v_column
          );
          EXECUTE format('ALTER TABLE public.%I VALIDATE CONSTRAINT %I',v_table,v_constraint);
        END IF;
      END IF;
    END LOOP;
  END LOOP;
END;
$migration$;

