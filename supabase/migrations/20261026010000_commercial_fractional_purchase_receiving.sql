-- Support fractional procurement quantities end-to-end (up to 6 decimal places).
ALTER TABLE public.commercial_purchase_order_items ALTER COLUMN quantity_ordered TYPE numeric(18,6) USING quantity_ordered::numeric(18,6), ALTER COLUMN quantity_received TYPE numeric(18,6) USING quantity_received::numeric(18,6);
ALTER TABLE public.commercial_purchase_receipt_items ALTER COLUMN quantity TYPE numeric(18,6) USING quantity::numeric(18,6);
ALTER TABLE public.commercial_inventory_receipts ALTER COLUMN quantity TYPE numeric(20,6) USING quantity::numeric(20,6);
ALTER TABLE public.commercial_inventory_balances ALTER COLUMN quantity_on_hand TYPE numeric(20,6) USING quantity_on_hand::numeric(20,6), ALTER COLUMN quantity_reserved TYPE numeric(20,6) USING quantity_reserved::numeric(20,6);
ALTER TABLE public.commercial_stock_movements ALTER COLUMN quantity_delta TYPE numeric(20,6) USING quantity_delta::numeric(20,6);

-- Preserve the integer overload for existing callers; fractional receipts use this numeric overload.
CREATE OR REPLACE FUNCTION public.commercial_receive_inventory_stock(
 p_organization_id uuid, p_location_id uuid, p_offering_id uuid, p_quantity numeric,
 p_unit_cost_minor bigint, p_source_ref text
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE b public.commercial_inventory_balances%ROWTYPE; v_new_qty numeric; v_new_cost numeric; v_fingerprint text; v_receipt public.commercial_inventory_receipts%ROWTYPE;
BEGIN
 IF auth.uid() IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_organization_id IS NULL OR p_location_id IS NULL OR p_offering_id IS NULL OR p_quantity IS NULL OR p_quantity<=0 OR scale(p_quantity)>6 OR p_unit_cost_minor IS NULL OR p_unit_cost_minor<0 OR p_source_ref IS NULL OR length(btrim(p_source_ref))<8 OR length(btrim(p_source_ref))>200 THEN RAISE EXCEPTION 'invalid_inventory_receipt' USING ERRCODE='22023'; END IF;
 IF NOT EXISTS(SELECT 1 FROM public.commercial_organization_members m WHERE m.organization_id=p_organization_id AND m.user_id=auth.uid() AND m.state='active' AND m.role IN ('owner','admin','manager')) THEN RAISE EXCEPTION 'inventory_management_permission_required' USING ERRCODE='42501'; END IF;
 v_fingerprint:=md5(concat_ws('|',p_location_id::text,p_offering_id::text,p_quantity::numeric(20,6)::text,p_unit_cost_minor::text));
 PERFORM pg_advisory_xact_lock(hashtext(p_organization_id::text),hashtext('inventory-receipt:'||p_source_ref));
 SELECT * INTO v_receipt FROM public.commercial_inventory_receipts WHERE organization_id=p_organization_id AND source_ref=p_source_ref FOR UPDATE;
 IF FOUND THEN
  IF v_receipt.request_fingerprint IS DISTINCT FROM v_fingerprint THEN RAISE EXCEPTION 'inventory_receipt_key_reused_with_different_request' USING ERRCODE='23505'; END IF;
  SELECT * INTO b FROM public.commercial_inventory_balances WHERE organization_id=p_organization_id AND location_id=p_location_id AND offering_id=p_offering_id;
  RETURN jsonb_build_object('idempotent_replay',true,'receipt_id',v_receipt.id,'quantity_on_hand',b.quantity_on_hand,'unit_cost_minor',b.unit_cost_minor);
 END IF;
 SELECT * INTO b FROM public.commercial_inventory_balances WHERE organization_id=p_organization_id AND location_id=p_location_id AND offering_id=p_offering_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'inventory_balance_missing' USING ERRCODE='P0002'; END IF;
 IF b.quantity_on_hand<0 OR b.quantity_reserved<0 OR b.quantity_reserved>b.quantity_on_hand THEN RAISE EXCEPTION 'inventory_balance_invalid' USING ERRCODE='55000'; END IF;
 IF b.unit_cost_minor IS NULL AND b.quantity_on_hand>0 THEN RAISE EXCEPTION 'existing_stock_cost_unknown_adjust_stock_through_audited_inventory_process' USING ERRCODE='55000'; END IF;
 v_new_qty:=b.quantity_on_hand+p_quantity;
 IF v_new_qty>99999999999999.999999 THEN RAISE EXCEPTION 'inventory_quantity_overflow' USING ERRCODE='22003'; END IF;
 v_new_cost:=CASE WHEN b.quantity_on_hand=0 THEN p_unit_cost_minor ELSE round((b.quantity_on_hand::numeric*b.unit_cost_minor::numeric+p_quantity::numeric*p_unit_cost_minor::numeric)/v_new_qty) END;
 IF v_new_cost>9223372036854775807 THEN RAISE EXCEPTION 'inventory_cost_overflow' USING ERRCODE='22003'; END IF;
 INSERT INTO public.commercial_inventory_receipts(organization_id,location_id,offering_id,source_ref,request_fingerprint,quantity,unit_cost_minor,created_by)
 VALUES(p_organization_id,p_location_id,p_offering_id,p_source_ref,v_fingerprint,p_quantity,p_unit_cost_minor,auth.uid()) RETURNING * INTO v_receipt;
 UPDATE public.commercial_inventory_balances SET quantity_on_hand=v_new_qty,unit_cost_minor=v_new_cost::bigint,updated_at=now() WHERE id=b.id;
 INSERT INTO public.commercial_stock_movements(offering_id,movement_type,quantity_delta,source_ref,created_by)
 VALUES(p_offering_id,'adjustment',p_quantity,'receipt:'||p_organization_id::text||':'||p_source_ref,auth.uid());
 INSERT INTO public.commercial_audit_events(organization_id,actor_user_id,action,entity_type,entity_id,metadata)
 VALUES(p_organization_id,auth.uid(),'inventory.received','inventory_balance',b.id::text,jsonb_build_object('receipt_id',v_receipt.id,'location_id',p_location_id,'offering_id',p_offering_id,'quantity',p_quantity,'unit_cost_minor',p_unit_cost_minor,'new_average_cost_minor',v_new_cost::bigint,'source_ref',p_source_ref));
 RETURN jsonb_build_object('idempotent_replay',false,'receipt_id',v_receipt.id,'quantity_on_hand',v_new_qty,'unit_cost_minor',v_new_cost::bigint);
END $function$;
REVOKE ALL ON FUNCTION public.commercial_receive_inventory_stock(uuid,uuid,uuid,numeric,bigint,text) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_receive_inventory_stock(uuid,uuid,uuid,numeric,bigint,text) TO authenticated;

-- Purchase order quantities accept positive decimals with at most six decimal places.
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
    IF v_offering IS NULL OR v_qty IS NULL OR v_qty<=0 OR scale(v_qty)>6 OR v_qty>999999999999.999999
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
-- Receipt and stock updates stay in one transaction with exact fractional quantities.
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
    IF v_item_id IS NULL OR v_qty IS NULL OR v_qty<=0 OR scale(v_qty)>6 OR v_qty>999999999999.999999 THEN RAISE EXCEPTION 'invalid_purchase_receipt_line' USING ERRCODE='22023'; END IF;
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
