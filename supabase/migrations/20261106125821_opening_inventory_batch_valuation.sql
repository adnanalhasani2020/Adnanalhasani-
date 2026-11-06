BEGIN;

CREATE TABLE IF NOT EXISTS commercial_private.commercial_inventory_opening_valuation_requests (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL,
  location_id uuid NOT NULL,
  offering_id uuid NOT NULL,
  idempotency_key text NOT NULL,
  request_fingerprint text NOT NULL,
  result jsonb NOT NULL,
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (organization_id,idempotency_key)
);
REVOKE ALL ON TABLE commercial_private.commercial_inventory_opening_valuation_requests FROM PUBLIC,anon,authenticated;

CREATE OR REPLACE FUNCTION public.commercial_record_opening_inventory_batches(
  p_organization_id uuid,
  p_location_id uuid,
  p_offering_id uuid,
  p_batches jsonb,
  p_idempotency_key text
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE
  v_uid uuid:=auth.uid();
  v_balance public.commercial_inventory_balances%ROWTYPE;
  v_request commercial_private.commercial_inventory_opening_valuation_requests%ROWTYPE;
  v_line jsonb;
  v_quantity numeric;
  v_total_quantity numeric:=0;
  v_covered_quantity numeric:=0;
  v_batch_number text;
  v_expires_on date;
  v_cost bigint;
  v_line_number integer:=0;
  v_layer_ids jsonb:='[]'::jsonb;
  v_total_value numeric:=0;
  v_average_cost bigint;
  v_fingerprint text;
  v_source_ref text;
  v_result jsonb;
  v_activity_org uuid;
  v_location_org uuid;
BEGIN
  IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
  IF p_organization_id IS NULL OR p_location_id IS NULL OR p_offering_id IS NULL
     OR p_batches IS NULL OR jsonb_typeof(p_batches)<>'array'
     OR jsonb_array_length(p_batches)<1 OR jsonb_array_length(p_batches)>100
     OR p_idempotency_key IS NULL OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 160 THEN
    RAISE EXCEPTION 'invalid_opening_inventory_valuation_request' USING ERRCODE='22023';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id=p_organization_id AND m.user_id=v_uid
      AND m.state='active' AND m.role IN ('owner','admin','manager')
  ) THEN RAISE EXCEPTION 'inventory_valuation_permission_required' USING ERRCODE='42501'; END IF;

  PERFORM pg_advisory_xact_lock(
    hashtext(p_organization_id::text),
    hashtext('opening-inventory-valuation:'||btrim(p_idempotency_key))
  );
  v_fingerprint:=md5(jsonb_build_object(
    'location_id',p_location_id,'offering_id',p_offering_id,'batches',p_batches
  )::text);
  SELECT * INTO v_request
  FROM commercial_private.commercial_inventory_opening_valuation_requests
  WHERE organization_id=p_organization_id AND idempotency_key=btrim(p_idempotency_key)
  FOR UPDATE;
  IF FOUND THEN
    IF v_request.request_fingerprint IS DISTINCT FROM v_fingerprint THEN
      RAISE EXCEPTION 'opening_inventory_idempotency_key_reused' USING ERRCODE='23505';
    END IF;
    RETURN v_request.result || jsonb_build_object('idempotent_replay',true);
  END IF;

  SELECT b.* INTO v_balance
  FROM public.commercial_inventory_balances b
  WHERE b.organization_id=p_organization_id
    AND b.location_id=p_location_id
    AND b.offering_id=p_offering_id
  FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'opening_inventory_balance_not_found' USING ERRCODE='P0002'; END IF;
  IF v_balance.quantity_on_hand<=0 OR v_balance.quantity_reserved<0
     OR v_balance.quantity_reserved>v_balance.quantity_on_hand THEN
    RAISE EXCEPTION 'opening_inventory_balance_invalid' USING ERRCODE='55000';
  END IF;
  SELECT a.organization_id INTO v_activity_org
  FROM public.commercial_offerings o
  JOIN public.commercial_activities a ON a.id=o.activity_id
  WHERE o.id=p_offering_id AND o.state='active' AND a.deleted_at IS NULL;
  IF v_activity_org IS DISTINCT FROM p_organization_id THEN
    RAISE EXCEPTION 'opening_inventory_offering_scope_mismatch' USING ERRCODE='42501';
  END IF;
  SELECT l.organization_id INTO v_location_org
  FROM public.commercial_organization_locations l
  WHERE l.id=p_location_id AND l.state='active';
  IF v_location_org IS DISTINCT FROM p_organization_id THEN
    RAISE EXCEPTION 'opening_inventory_location_scope_mismatch' USING ERRCODE='42501';
  END IF;

  SELECT coalesce(sum(l.quantity_remaining),0) INTO v_covered_quantity
  FROM public.commercial_inventory_cost_layers l
  WHERE l.organization_id=p_organization_id AND l.location_id=p_location_id
    AND l.offering_id=p_offering_id AND l.quantity_remaining>0;
  IF v_covered_quantity>v_balance.quantity_on_hand THEN
    RAISE EXCEPTION 'opening_inventory_cost_layers_exceed_stock_balance' USING ERRCODE='55000';
  END IF;

  FOR v_line IN SELECT value FROM jsonb_array_elements(p_batches) LOOP
    v_line_number:=v_line_number+1;
    BEGIN
      v_quantity:=(v_line->>'quantity')::numeric;
      v_cost:=(v_line->>'unit_cost_minor')::bigint;
      v_batch_number:=nullif(btrim(v_line->>'batch_number'),'');
      v_expires_on:=nullif(btrim(v_line->>'expires_on'),'')::date;
    EXCEPTION WHEN others THEN
      RAISE EXCEPTION 'invalid_opening_inventory_batch:%',v_line_number USING ERRCODE='22023';
    END;
    IF v_quantity IS NULL OR v_quantity<=0 OR v_quantity<>trunc(v_quantity)
       OR v_quantity>999999999999 OR v_cost IS NULL OR v_cost<0
       OR (v_batch_number IS NOT NULL AND length(v_batch_number)>100) THEN
      RAISE EXCEPTION 'invalid_opening_inventory_batch:%',v_line_number USING ERRCODE='22023';
    END IF;
    v_total_quantity:=v_total_quantity+v_quantity;
    v_total_value:=v_total_value+(v_quantity*v_cost::numeric);
  END LOOP;

  IF v_total_quantity<>v_balance.quantity_on_hand-v_covered_quantity THEN
    RAISE EXCEPTION 'opening_batches_must_cover_exact_unvalued_quantity'
      USING ERRCODE='22023',
      DETAIL='The submitted batch quantities must exactly equal the current stock quantity not covered by verified cost layers.';
  END IF;
  IF v_total_value>9223372036854775807::numeric*v_total_quantity THEN
    RAISE EXCEPTION 'opening_inventory_valuation_overflow' USING ERRCODE='22003';
  END IF;

  v_line_number:=0;
  FOR v_line IN SELECT value FROM jsonb_array_elements(p_batches) LOOP
    v_line_number:=v_line_number+1;
    v_quantity:=(v_line->>'quantity')::numeric;
    v_cost:=(v_line->>'unit_cost_minor')::bigint;
    v_batch_number:=nullif(btrim(v_line->>'batch_number'),'');
    v_expires_on:=nullif(btrim(v_line->>'expires_on'),'')::date;
    v_source_ref:='opening-valuation:'||v_balance.id::text||':'||btrim(p_idempotency_key)||':'||v_line_number::text;
    INSERT INTO public.commercial_inventory_cost_layers(
      organization_id,location_id,offering_id,source_type,source_ref,received_at,
      quantity_received,quantity_remaining,source_unit_cost_minor,unit_cost_minor,
      batch_number,expires_on,stock_condition
    ) VALUES (
      p_organization_id,p_location_id,p_offering_id,'opening_balance',v_source_ref,
      coalesce(v_balance.updated_at,now()),v_quantity,v_quantity,v_cost,v_cost,
      v_batch_number,v_expires_on,
      CASE WHEN v_expires_on IS NOT NULL AND v_expires_on<current_date THEN 'expired' ELSE 'sellable' END
    ) RETURNING id INTO v_request.id;
    v_layer_ids:=v_layer_ids||jsonb_build_array(v_request.id);
  END LOOP;

  SELECT coalesce(sum(l.quantity_remaining),0),
         coalesce(sum(l.quantity_remaining*l.source_unit_cost_minor::numeric),0)
  INTO v_covered_quantity,v_total_value
  FROM public.commercial_inventory_cost_layers l
  WHERE l.organization_id=p_organization_id AND l.location_id=p_location_id
    AND l.offering_id=p_offering_id AND l.quantity_remaining>0;
  IF v_covered_quantity<>v_balance.quantity_on_hand THEN
    RAISE EXCEPTION 'opening_inventory_cost_coverage_incomplete' USING ERRCODE='55000';
  END IF;
  IF v_covered_quantity>0 THEN
    v_average_cost:=round(v_total_value/v_covered_quantity)::bigint;
    UPDATE public.commercial_inventory_balances
    SET unit_cost_minor=v_average_cost,updated_at=now()
    WHERE id=v_balance.id;
    UPDATE public.commercial_inventory_cost_layers
    SET unit_cost_minor=v_average_cost
    WHERE organization_id=p_organization_id AND location_id=p_location_id
      AND offering_id=p_offering_id AND quantity_remaining>0
      AND EXISTS (
        SELECT 1 FROM public.commercial_inventory_costing_policies cp
        WHERE cp.organization_id=p_organization_id
          AND (cp.location_id=p_location_id OR cp.location_id IS NULL)
          AND cp.costing_method='weighted_average'
      );
  END IF;

  v_result:=jsonb_build_object(
    'offering_id',p_offering_id,'location_id',p_location_id,
    'valued_quantity',v_total_quantity,'covered_quantity',v_covered_quantity,
    'average_unit_cost_minor',v_average_cost,'cost_layer_ids',v_layer_ids,
    'idempotent_replay',false
  );
  INSERT INTO commercial_private.commercial_inventory_opening_valuation_requests(
    organization_id,location_id,offering_id,idempotency_key,request_fingerprint,result,created_by
  ) VALUES(
    p_organization_id,p_location_id,p_offering_id,btrim(p_idempotency_key),v_fingerprint,v_result,v_uid
  );
  INSERT INTO public.commercial_audit_events(
    organization_id,actor_user_id,action,entity_type,entity_id,metadata
  ) VALUES(
    p_organization_id,v_uid,'inventory.opening_valuation.recorded','inventory_cost_layer',
    v_balance.id::text,
    jsonb_build_object('location_id',p_location_id,'offering_id',p_offering_id,
      'valued_quantity',v_total_quantity,'average_unit_cost_minor',v_average_cost,
      'cost_layer_ids',v_layer_ids,'batch_count',jsonb_array_length(p_batches))
  );
  RETURN v_result;
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_record_opening_inventory_batches(uuid,uuid,uuid,jsonb,text) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.commercial_record_opening_inventory_batches(uuid,uuid,uuid,jsonb,text) TO authenticated;

COMMIT;
