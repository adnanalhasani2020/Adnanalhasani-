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
     v_sale,v_item,v_first_layer,'fifo',3,100,300,now(),900),
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
