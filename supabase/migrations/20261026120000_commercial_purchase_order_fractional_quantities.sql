-- Align the authoritative purchase-order RPC with six-decimal quantity storage and receipt validation.
-- Preserve existing tenant, authorization, idempotency, offering-scope and audit checks.
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
