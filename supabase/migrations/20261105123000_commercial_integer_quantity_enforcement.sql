-- Replace fractional selling-unit RPC wrappers with integer-only contracts.
-- Keep the existing atomic base-unit checkout, invoice snapshots, idempotency and return lifecycle.
-- Preserve separate invoice rows for each selling unit while deducting aggregated base-stock quantities once.
-- Selling quantities may be fractional to three decimal places when the configured integer stock-unit factor converts them to whole base units.
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

-- Enforce whole-unit quantities without rewriting historical records.
-- Any pre-existing fractional value aborts this migration for explicit reconciliation.
DO $migration$
DECLARE
  v_table text;
  v_column text;
  v_constraint text;
  v_has_fraction boolean;
  v_tables text[] := ARRAY[
    'commercial_sale_items','commercial_sale_return_items',
    'commercial_purchase_order_items','commercial_purchase_receipt_items',
    'commercial_inventory_receipts','commercial_inventory_balances',
    'commercial_stock_movements','commercial_supplier_invoice_items',
    'commercial_inventory_cost_layers','commercial_inventory_cost_allocations',
    'commercial_inventory_return_cost_allocations','commercial_inventory_cost_reversals',
    'commercial_return_restock_postings','commercial_invoice_purchase_request_items',
    'commercial_offering_sell_units','commercial_catalog_product_units','commercial_catalog_item_units',
    'commercial_organization_catalog_item_setup',
    'commercial_catalog_inventory_units'
  ];
  v_columns text[] := ARRAY[
    'quantity','selling_quantity','quantity_in_stock_units',
    'quantity_ordered','quantity_received','quantity_on_hand','quantity_reserved',
    'quantity_delta','quantity_remaining','sale_quantity_in_base','quantity_in_base'
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

COMMENT ON TABLE public.commercial_sale_items IS
'Commercial sale line snapshots. Selling and stock quantities are whole units; monetary columns retain their existing precision.';
