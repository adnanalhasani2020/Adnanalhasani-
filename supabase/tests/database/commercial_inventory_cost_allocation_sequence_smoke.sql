-- PostgreSQL 17 smoke test for deterministic cost-allocation order.
-- Run after commercial_integer_quantity_schema_fixture.sql and the forward migration.
DO $test$
DECLARE
  v_sale uuid:='51000000-0000-4000-8000-000000000001';
  v_item uuid:='61000000-0000-4000-8000-000000000001';
  v_first_layer uuid:='71000000-0000-4000-8000-000000000001';
  v_second_layer uuid:='71000000-0000-4000-8000-000000000002';
  v_sequence bigint[];
  v_last_layer uuid;
BEGIN
  INSERT INTO public.commercial_sale_items(id,sale_id,offering_id,quantity,quantity_in_stock_units)
  VALUES(v_item,v_sale,'31000000-0000-4000-8000-000000000001',5,5);

  -- Equal timestamps deliberately reproduce transaction-stable now() behavior.
  -- A supplied sequence is ignored; the database assigns the actual insert order.
  INSERT INTO public.commercial_inventory_cost_allocations(
    id,organization_id,location_id,offering_id,sale_id,sale_item_id,cost_layer_id,
    costing_method,quantity,unit_cost_minor,total_cost_minor,created_at,allocation_sequence
  ) VALUES
    ('81000000-0000-4000-8000-000000000001','11000000-0000-4000-8000-000000000001',
     '21000000-0000-4000-8000-000000000001','31000000-0000-4000-8000-000000000001',
     v_sale,v_item,v_first_layer,'fifo',3,100,300,now(),900);
  INSERT INTO public.commercial_inventory_cost_allocations(
    id,organization_id,location_id,offering_id,sale_id,sale_item_id,cost_layer_id,
    costing_method,quantity,unit_cost_minor,total_cost_minor,created_at,allocation_sequence
  ) VALUES
    ('81000000-0000-4000-8000-000000000002','11000000-0000-4000-8000-000000000001',
     '21000000-0000-4000-8000-000000000001','31000000-0000-4000-8000-000000000001',
     v_sale,v_item,v_second_layer,'fifo',2,200,400,now(),900);

  SELECT array_agg(allocation_sequence ORDER BY allocation_sequence)
    INTO v_sequence
  FROM public.commercial_inventory_cost_allocations
  WHERE sale_item_id=v_item;
  IF v_sequence IS DISTINCT FROM ARRAY[1,2]::bigint[] THEN
    RAISE EXCEPTION 'allocation_sequence_not_monotonic: %',v_sequence;
  END IF;

  SELECT cost_layer_id INTO v_last_layer
  FROM public.commercial_inventory_cost_allocations
  WHERE sale_item_id=v_item
  ORDER BY allocation_sequence DESC NULLS LAST,created_at DESC,id DESC
  LIMIT 1;
  IF v_last_layer IS DISTINCT FROM v_second_layer THEN
    RAISE EXCEPTION 'reverse_issue_order_did_not_select_last_consumed_layer: %',v_last_layer;
  END IF;
END;
$test$;


-- Exercise the actual return-restoration trigger with a partial return. The
-- same transaction timestamp is used for both allocations; sequence, not UUID,
-- must determine which original cost layer is restored first.
CREATE TABLE IF NOT EXISTS public.commercial_sales_returns (
  id uuid,
  sale_id uuid,
  organization_id uuid
);
ALTER TABLE public.commercial_inventory_return_cost_allocations
  ALTER COLUMN id SET DEFAULT gen_random_uuid();
CREATE UNIQUE INDEX IF NOT EXISTS commercial_inventory_return_cost_allocations_return_item_sale_alloc_uidx
  ON public.commercial_inventory_return_cost_allocations(return_item_id,sale_cost_allocation_id);

DO $return_test$
DECLARE
  v_org uuid:='12000000-0000-4000-8000-000000000001';
  v_location uuid:='22000000-0000-4000-8000-000000000001';
  v_offering uuid:='32000000-0000-4000-8000-000000000001';
  v_sale uuid:='52000000-0000-4000-8000-000000000001';
  v_sale_item uuid:='62000000-0000-4000-8000-000000000001';
  v_return uuid:='92000000-0000-4000-8000-000000000001';
  v_return_item uuid:='93000000-0000-4000-8000-000000000001';
  v_first_layer uuid:='72000000-0000-4000-8000-000000000001';
  v_second_layer uuid:='72000000-0000-4000-8000-000000000002';
  v_first_allocation uuid:='82000000-0000-4000-8000-000000000001';
  v_second_allocation uuid:='82000000-0000-4000-8000-000000000002';
  v_first_returned numeric;
  v_second_returned numeric;
  v_first_remaining numeric;
  v_second_remaining numeric;
BEGIN
  INSERT INTO public.commercial_sales_returns(id,sale_id,organization_id)
  VALUES(v_return,v_sale,v_org);
  INSERT INTO public.commercial_sale_items(id,sale_id,offering_id,quantity,quantity_in_stock_units)
  VALUES(v_sale_item,v_sale,v_offering,5,5);
  INSERT INTO public.commercial_inventory_balances(
    id,organization_id,location_id,offering_id,quantity_on_hand,quantity_reserved,reorder_point,unit_cost_minor,updated_at
  ) VALUES(gen_random_uuid(),v_org,v_location,v_offering,1,0,0,100,now());
  INSERT INTO public.commercial_inventory_cost_layers(
    id,organization_id,location_id,offering_id,source_type,source_ref,received_at,
    quantity_received,quantity_remaining,source_unit_cost_minor,unit_cost_minor,created_at
  ) VALUES
    (v_first_layer,v_org,v_location,v_offering,'purchase_receipt','return-layer-first',now()-interval '2 days',3,0,100,100,now()),
    (v_second_layer,v_org,v_location,v_offering,'purchase_receipt','return-layer-second',now()-interval '1 day',2,0,200,200,now());
  INSERT INTO public.commercial_inventory_cost_allocations(
    id,organization_id,location_id,offering_id,sale_id,sale_item_id,cost_layer_id,
    costing_method,quantity,unit_cost_minor,total_cost_minor,created_at
  ) VALUES
    (v_first_allocation,v_org,v_location,v_offering,v_sale,v_sale_item,v_first_layer,'fifo',3,100,300,now());
  INSERT INTO public.commercial_inventory_cost_allocations(
    id,organization_id,location_id,offering_id,sale_id,sale_item_id,cost_layer_id,
    costing_method,quantity,unit_cost_minor,total_cost_minor,created_at
  ) VALUES
    (v_second_allocation,v_org,v_location,v_offering,v_sale,v_sale_item,v_second_layer,'fifo',2,200,400,now());
  INSERT INTO public.commercial_return_restock_postings(
    return_item_id,return_id,organization_id,location_id,offering_id,quantity,unit_cost_minor,source_ref,created_by,created_at
  ) VALUES(v_return_item,v_return,v_org,v_location,v_offering,4,100,'return-smoke',NULL,now());
  INSERT INTO public.commercial_sale_return_items(
    id,return_id,sale_item_id,quantity,restock,restocked_quantity,condition_code,created_at,updated_at
  ) VALUES(v_return_item,v_return,v_sale_item,4,true,0,'resellable',now(),now());

  DROP TRIGGER IF EXISTS commercial_return_restore_cost_layers_smoke_trg
    ON public.commercial_sale_return_items;
  CREATE TRIGGER commercial_return_restore_cost_layers_smoke_trg
  AFTER UPDATE OF restocked_quantity ON public.commercial_sale_return_items
  FOR EACH ROW
  WHEN (NEW.restocked_quantity>OLD.restocked_quantity)
  EXECUTE FUNCTION commercial_private.commercial_restore_return_cost_layers();

  -- First partial unit must return to the second (last consumed) layer.
  UPDATE public.commercial_sale_return_items SET restocked_quantity=1 WHERE id=v_return_item;
  SELECT coalesce(sum(quantity),0) INTO v_first_returned
  FROM public.commercial_inventory_return_cost_allocations
  WHERE return_item_id=v_return_item AND cost_layer_id=v_first_layer;
  SELECT coalesce(sum(quantity),0) INTO v_second_returned
  FROM public.commercial_inventory_return_cost_allocations
  WHERE return_item_id=v_return_item AND cost_layer_id=v_second_layer;
  IF v_first_returned<>0 OR v_second_returned<>1 THEN
    RAISE EXCEPTION 'first_partial_return_restored_wrong_layer: first %, second %',
      v_first_returned,v_second_returned;
  END IF;

  -- A later partial return consumes the remaining quantity of the second layer,
  -- then continues into the first layer without exceeding original allocations.
  UPDATE public.commercial_inventory_balances
  SET quantity_on_hand=4
  WHERE organization_id=v_org AND location_id=v_location AND offering_id=v_offering;
  UPDATE public.commercial_sale_return_items SET restocked_quantity=4 WHERE id=v_return_item;

  SELECT coalesce(sum(quantity),0) INTO v_first_returned
  FROM public.commercial_inventory_return_cost_allocations
  WHERE return_item_id=v_return_item AND cost_layer_id=v_first_layer;
  SELECT coalesce(sum(quantity),0) INTO v_second_returned
  FROM public.commercial_inventory_return_cost_allocations
  WHERE return_item_id=v_return_item AND cost_layer_id=v_second_layer;
  SELECT quantity_remaining INTO v_first_remaining
  FROM public.commercial_inventory_cost_layers WHERE id=v_first_layer;
  SELECT quantity_remaining INTO v_second_remaining
  FROM public.commercial_inventory_cost_layers WHERE id=v_second_layer;
  IF v_first_returned<>2 OR v_second_returned<>2
     OR v_first_remaining<>2 OR v_second_remaining<>2 THEN
    RAISE EXCEPTION 'cumulative_partial_return_layer_mismatch: returned first %, second %, remaining first %, second %',
      v_first_returned,v_second_returned,v_first_remaining,v_second_remaining;
  END IF;
END;
$return_test$;
