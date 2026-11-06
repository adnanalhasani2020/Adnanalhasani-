BEGIN;

-- The valuation method (FIFO/LIFO/average) and physical stock rotation
-- (FIFO/FEFO) are separate decisions. FEFO changes which layer is issued,
-- while the cost snapshot remains the selected layer's actual source cost.
CREATE OR REPLACE FUNCTION commercial_private.commercial_allocate_sale_item_cost(
  p_sale_item_id uuid,p_location_id uuid
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'pg_catalog','public','commercial_private'
AS $function$
DECLARE
 v_item public.commercial_sale_items%ROWTYPE;
 v_org uuid; v_sale_user uuid; v_method text:='weighted_average'; v_rotation text:='fifo';
 v_balance public.commercial_inventory_balances%ROWTYPE;
 v_layer public.commercial_inventory_cost_layers%ROWTYPE;
 v_quantity numeric; v_expected_sale_quantity numeric; v_movement_count integer; v_movement_delta numeric;
 v_remaining numeric; v_take numeric; v_total_cost numeric:=0; v_unit_cost bigint; v_layer_cost bigint;
 v_existing_qty numeric; v_existing_cost numeric;
BEGIN
 IF auth.uid() IS NULL THEN RAISE EXCEPTION 'authentication_required' USING ERRCODE='42501'; END IF;
 IF p_sale_item_id IS NULL OR p_location_id IS NULL THEN RAISE EXCEPTION 'invalid_inventory_cost_allocation_request' USING ERRCODE='22023'; END IF;
 SELECT i.* INTO v_item FROM public.commercial_sale_items i WHERE i.id=p_sale_item_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'sale_item_not_found' USING ERRCODE='P0002'; END IF;
 SELECT s.organization_id,s.user_id INTO v_org,v_sale_user FROM public.commercial_sales s WHERE s.id=v_item.sale_id;
 IF NOT FOUND OR v_sale_user IS DISTINCT FROM auth.uid() THEN RAISE EXCEPTION 'sale_item_not_owned_by_authenticated_user' USING ERRCODE='42501'; END IF;
 v_quantity:=coalesce(v_item.quantity_in_stock_units::numeric,v_item.quantity::numeric);
 IF v_quantity IS NULL OR v_quantity<=0 THEN RAISE EXCEPTION 'invalid_sale_item_stock_quantity' USING ERRCODE='22023'; END IF;

 SELECT count(*)::integer,min(sm.quantity_delta) INTO v_movement_count,v_movement_delta
 FROM public.commercial_stock_movements sm
 WHERE sm.sale_id=v_item.sale_id AND sm.offering_id=v_item.offering_id AND sm.movement_type='sale'
 AND sm.source_ref='sale:'||v_item.sale_id::text||':offering:'||v_item.offering_id::text||':location:'||p_location_id::text;
 SELECT coalesce(sum(coalesce(i.quantity_in_stock_units,i.quantity)::numeric),0) INTO v_expected_sale_quantity
 FROM public.commercial_sale_items i WHERE i.sale_id=v_item.sale_id AND i.offering_id=v_item.offering_id;
 IF v_movement_count<>1 OR v_movement_delta IS DISTINCT FROM -v_expected_sale_quantity THEN
   RAISE EXCEPTION 'sale_inventory_movement_cost_scope_mismatch' USING ERRCODE='55000';
 END IF;

 SELECT p.costing_method INTO v_method FROM public.commercial_inventory_costing_policies p
 WHERE p.organization_id=v_org AND (p.location_id=p_location_id OR p.location_id IS NULL)
 ORDER BY (p.location_id IS NOT NULL) DESC LIMIT 1;
 v_method:=coalesce(v_method,'weighted_average');
 SELECT p.rotation_method INTO v_rotation FROM public.commercial_inventory_rotation_policies p
 WHERE p.organization_id=v_org AND (p.location_id=p_location_id OR p.location_id IS NULL)
 ORDER BY (p.location_id IS NOT NULL) DESC LIMIT 1;
 v_rotation:=coalesce(v_rotation,'fifo');

 SELECT * INTO v_balance FROM public.commercial_inventory_balances b
 WHERE b.organization_id=v_org AND b.location_id=p_location_id AND b.offering_id=v_item.offering_id FOR UPDATE;
 IF NOT FOUND OR v_balance.quantity_on_hand<0 OR v_balance.unit_cost_minor<0 THEN
   RAISE EXCEPTION 'inventory_balance_invalid_for_cost_allocation' USING ERRCODE='55000';
 END IF;

 SELECT coalesce(sum(a.quantity),0),coalesce(sum(a.total_cost_minor),0) INTO v_existing_qty,v_existing_cost
 FROM public.commercial_inventory_cost_allocations a WHERE a.sale_item_id=p_sale_item_id;
 IF v_existing_qty>0 THEN
   IF v_existing_qty<>v_quantity THEN RAISE EXCEPTION 'sale_item_cost_allocation_quantity_mismatch' USING ERRCODE='55000'; END IF;
   v_unit_cost:=round(v_existing_cost/v_existing_qty)::bigint;
   UPDATE public.commercial_sale_items SET unit_cost_minor=v_unit_cost,cost_total_minor=v_existing_cost::bigint WHERE id=p_sale_item_id;
   RETURN jsonb_build_object('sale_item_id',p_sale_item_id,'costing_method',v_method,'rotation_method',v_rotation,
     'quantity',v_existing_qty,'cost_total_minor',v_existing_cost::bigint,'unit_cost_minor',v_unit_cost,'idempotent_replay',true);
 END IF;
 IF v_method='weighted_average' AND v_balance.unit_cost_minor IS NULL THEN
   RAISE EXCEPTION 'inventory_average_cost_unknown' USING ERRCODE='55000';
 END IF;

 v_remaining:=v_quantity;
 FOR v_layer IN
   SELECT l.* FROM public.commercial_inventory_cost_layers l
   WHERE l.organization_id=v_org AND l.location_id=p_location_id AND l.offering_id=v_item.offering_id
     AND l.quantity_remaining>0 AND l.stock_condition='sellable'
     AND (l.expires_on IS NULL OR l.expires_on>=current_date)
   ORDER BY
     CASE WHEN v_rotation='fefo' THEN l.expires_on END ASC NULLS LAST,
     l.received_at ASC,
     l.id ASC
   FOR UPDATE
 LOOP
   EXIT WHEN v_remaining=0;
   v_take:=least(v_remaining,v_layer.quantity_remaining);
   v_layer_cost:=CASE WHEN v_method='weighted_average' THEN v_balance.unit_cost_minor ELSE v_layer.source_unit_cost_minor END;
   INSERT INTO public.commercial_inventory_cost_allocations(
     organization_id,location_id,offering_id,sale_id,sale_item_id,cost_layer_id,costing_method,quantity,unit_cost_minor,total_cost_minor
   ) VALUES(v_org,p_location_id,v_item.offering_id,v_item.sale_id,p_sale_item_id,v_layer.id,v_method,v_take,v_layer_cost,round(v_take*v_layer_cost::numeric));
   UPDATE public.commercial_inventory_cost_layers SET quantity_remaining=quantity_remaining-v_take WHERE id=v_layer.id;
   v_total_cost:=v_total_cost+round(v_take*v_layer_cost::numeric);
   v_remaining:=v_remaining-v_take;
 END LOOP;
 IF v_remaining>0 THEN
   RAISE EXCEPTION 'inventory_cost_layers_insufficient' USING ERRCODE='55000',
     DETAIL='Insufficient verified, sellable, non-expired batch quantity for this sale.';
 END IF;
 IF v_total_cost>9223372036854775807 THEN RAISE EXCEPTION 'inventory_cost_overflow' USING ERRCODE='22003'; END IF;
 v_unit_cost:=round(v_total_cost/v_quantity)::bigint;
 UPDATE public.commercial_sale_items SET unit_cost_minor=v_unit_cost,cost_total_minor=v_total_cost::bigint WHERE id=p_sale_item_id;
 RETURN jsonb_build_object('sale_item_id',p_sale_item_id,'costing_method',v_method,'rotation_method',v_rotation,
   'quantity',v_quantity,'cost_total_minor',v_total_cost::bigint,'unit_cost_minor',v_unit_cost,'idempotent_replay',false);
END;
$function$;

COMMIT;