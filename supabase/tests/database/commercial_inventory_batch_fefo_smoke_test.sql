-- PostgreSQL 17 smoke test for physical batches and FEFO issue.
DO $test$
DECLARE
  v_org uuid:='10000000-0000-4000-8000-000000000001';
  v_location uuid:='20000000-0000-4000-8000-000000000001';
  v_offering uuid:='30000000-0000-4000-8000-000000000001';
  v_activity uuid:='30000000-0000-4000-8000-000000000002';
  v_product uuid:='30000000-0000-4000-8000-000000000003';
  v_offering_expired uuid:='30000000-0000-4000-8000-000000000004';
  v_sale_expired uuid:='50000000-0000-4000-8000-000000000002';
  v_item_expired uuid:='60000000-0000-4000-8000-000000000002';
  v_user uuid:='40000000-0000-4000-8000-000000000001';
  v_sale uuid:='50000000-0000-4000-8000-000000000001';
  v_item uuid:='60000000-0000-4000-8000-000000000001';
  v_old_layer uuid:='70000000-0000-4000-8000-000000000001';
  v_early_layer uuid:='70000000-0000-4000-8000-000000000002';
  v_receipt uuid:='80000000-0000-4000-8000-000000000001';
  v_alloc jsonb;
  v_batches jsonb;
  v_early numeric;
  v_old numeric;
BEGIN
  INSERT INTO auth.users(id) VALUES(v_user);
  INSERT INTO public.commercial_organizations(id) VALUES(v_org);
  INSERT INTO public.commercial_organization_locations(id,organization_id,state) VALUES(v_location,v_org,'active');
  INSERT INTO public.commercial_activities(id,organization_id,deleted_at) VALUES(v_activity,v_org,NULL);
  INSERT INTO public.commercial_products(id,name) VALUES(v_product,'Test Product');
  INSERT INTO public.commercial_offerings(id,activity_id,product_id) VALUES(v_offering,v_activity,v_product);
  INSERT INTO public.commercial_organization_members(organization_id,user_id,state,role) VALUES(v_org,v_user,'active','manager');
  PERFORM set_config('request.jwt.claim.sub',v_user::text,false);

  PERFORM public.commercial_set_inventory_rotation_policy(v_org,v_location,'fefo');
  INSERT INTO public.commercial_inventory_costing_policies(id,organization_id,location_id,costing_method)
  VALUES(gen_random_uuid(),v_org,v_location,'fifo');

  INSERT INTO public.commercial_inventory_balances(id,organization_id,location_id,offering_id,quantity_on_hand,quantity_reserved,reorder_point,unit_cost_minor,updated_at)
  VALUES(gen_random_uuid(),v_org,v_location,v_offering,6,0,0,100,now());
  INSERT INTO public.commercial_sales(id,user_id,organization_id,status)
  VALUES(v_sale,v_user,v_org,'completed');
  INSERT INTO public.commercial_sale_items(id,sale_id,offering_id,quantity,quantity_in_stock_units)
  VALUES(v_item,v_sale,v_offering,4,4);
  INSERT INTO public.commercial_stock_movements(id,offering_id,sale_id,movement_type,quantity_delta,source_ref,created_at,created_by)
  VALUES(gen_random_uuid(),v_offering,v_sale,'sale',-4,
    'sale:'||v_sale::text||':offering:'||v_offering::text||':location:'||v_location::text,now(),v_user);

  INSERT INTO public.commercial_inventory_cost_layers(
    id,organization_id,location_id,offering_id,source_type,source_ref,received_at,
    quantity_received,quantity_remaining,source_unit_cost_minor,unit_cost_minor,created_at,
    batch_number,expires_on,stock_condition
  ) VALUES
    (v_old_layer,v_org,v_location,v_offering,'purchase_receipt','batch-old',now()-interval '5 days',3,3,80,80,now()-interval '5 days','LOT-OLD',date '2027-02-01','sellable'),
    (v_early_layer,v_org,v_location,v_offering,'purchase_receipt','batch-fefo',now()-interval '4 days',3,3,120,120,now()-interval '4 days','LOT-FEFO',date '2026-12-01','sellable');

  v_alloc:=commercial_private.commercial_allocate_sale_item_cost(v_item,v_location);
  SELECT coalesce(sum(quantity),0) INTO v_early FROM public.commercial_inventory_cost_allocations
    WHERE sale_item_id=v_item AND cost_layer_id=v_early_layer;
  SELECT coalesce(sum(quantity),0) INTO v_old FROM public.commercial_inventory_cost_allocations
    WHERE sale_item_id=v_item AND cost_layer_id=v_old_layer;
  IF v_early<>3 OR v_old<>1 THEN
    RAISE EXCEPTION 'fefo_allocation_order_failed: early %, old %',v_early,v_old;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM public.commercial_inventory_cost_allocations
    WHERE sale_item_id=v_item AND rotation_method='fefo') THEN
    RAISE EXCEPTION 'allocation_rotation_method_not_audited';
  END IF;
  v_batches:=public.commercial_list_inventory_batches(v_org,v_location,v_activity);
  IF jsonb_array_length(v_batches)<>1 OR v_batches->0->>'batch_number'<>'LOT-OLD' THEN
    RAISE EXCEPTION 'member_batch_projection_failed: %',v_batches;
  END IF;

  -- Expired stock must not be allocated even when its physical balance is positive.
  INSERT INTO public.commercial_offerings(id,activity_id,product_id) VALUES(v_offering_expired,v_activity,v_product);
  INSERT INTO public.commercial_inventory_balances(id,organization_id,location_id,offering_id,quantity_on_hand,quantity_reserved,reorder_point,unit_cost_minor,updated_at)
  VALUES(gen_random_uuid(),v_org,v_location,v_offering_expired,1,0,0,100,now());
  INSERT INTO public.commercial_sales(id,user_id,organization_id,status) VALUES(v_sale_expired,v_user,v_org,'completed');
  INSERT INTO public.commercial_sale_items(id,sale_id,offering_id,quantity,quantity_in_stock_units)
  VALUES(v_item_expired,v_sale_expired,v_offering_expired,1,1);
  INSERT INTO public.commercial_stock_movements(id,offering_id,sale_id,movement_type,quantity_delta,source_ref,created_at,created_by)
  VALUES(gen_random_uuid(),v_offering_expired,v_sale_expired,'sale',-1,
    'sale:'||v_sale_expired::text||':offering:'||v_offering_expired::text||':location:'||v_location::text,now(),v_user);
  INSERT INTO public.commercial_inventory_cost_layers(
    id,organization_id,location_id,offering_id,source_type,source_ref,received_at,
    quantity_received,quantity_remaining,source_unit_cost_minor,unit_cost_minor,created_at,
    batch_number,expires_on,stock_condition
  ) VALUES(gen_random_uuid(),v_org,v_location,v_offering_expired,'purchase_receipt','expired-lot',now()-interval '2 days',1,1,100,100,now(),'EXPIRED',current_date-1,'expired');
  BEGIN
    PERFORM commercial_private.commercial_allocate_sale_item_cost(v_item_expired,v_location);
    RAISE EXCEPTION 'expired_batch_was_sold';
  EXCEPTION WHEN SQLSTATE '55000' THEN
    NULL;
  END;

  -- Receipt-item metadata must be copied to the exact cost layer created by that receipt.
  INSERT INTO public.commercial_purchase_receipts(id,organization_id,location_id)
  VALUES(v_receipt,v_org,v_location);
  INSERT INTO public.commercial_inventory_receipts(id,organization_id,location_id,offering_id,source_ref,request_fingerprint,quantity,unit_cost_minor,created_by,created_at)
  VALUES(v_receipt,v_org,v_location,v_offering,'batch-metadata-receipt','fingerprint',2,100,v_user,now());
  INSERT INTO public.commercial_inventory_cost_layers(
    id,organization_id,location_id,offering_id,inventory_receipt_id,source_type,source_ref,received_at,
    quantity_received,quantity_remaining,source_unit_cost_minor,unit_cost_minor,created_at
  ) VALUES(gen_random_uuid(),v_org,v_location,v_offering,v_receipt,'purchase_receipt','metadata-layer',now(),2,2,100,100,now());
  INSERT INTO public.commercial_purchase_receipt_items(
    id,organization_id,receipt_id,purchase_order_id,purchase_order_item_id,offering_id,quantity,unit_cost_minor,inventory_receipt_id,batch_number,expires_on
  ) VALUES(gen_random_uuid(),v_org,v_receipt,gen_random_uuid(),gen_random_uuid(),v_offering,2,100,v_receipt,'LOT-META',date '2027-01-15');
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_inventory_cost_layers
    WHERE inventory_receipt_id=v_receipt AND batch_number='LOT-META' AND expires_on=date '2027-01-15'
  ) THEN RAISE EXCEPTION 'receipt_batch_metadata_not_persisted'; END IF;
END;
$test$;

DO $test$
DECLARE v_user uuid:='40000000-0000-4000-8000-000000000001';
BEGIN
  PERFORM set_config('request.jwt.claim.sub',v_user::text,false);
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_inventory_rotation_policies
    WHERE rotation_method='fefo'
  ) THEN RAISE EXCEPTION 'rotation_policy_not_persisted'; END IF;
END;
$test$;


-- Opening stock with unknown valuation is split into verified physical lots.
DO $opening$
DECLARE
  v_org uuid:='10000000-0000-4000-8000-000000000001';
  v_location uuid:='20000000-0000-4000-8000-000000000002';
  v_offering uuid:='30000000-0000-4000-8000-000000000005';
  v_activity uuid:='30000000-0000-4000-8000-000000000002';
  v_product uuid:='30000000-0000-4000-8000-000000000003';
  v_user uuid:='40000000-0000-4000-8000-000000000001';
  v_result jsonb;
  v_replay jsonb;
  v_cost bigint;
  v_layers integer;
  v_source_costs jsonb;
BEGIN
  PERFORM set_config('request.jwt.claim.sub',v_user::text,false);
  INSERT INTO public.commercial_organization_locations(id,organization_id,state)
  VALUES(v_location,v_org,'active');
  INSERT INTO public.commercial_offerings(id,activity_id,product_id)
  VALUES(v_offering,v_activity,v_product);
  INSERT INTO public.commercial_inventory_balances(
    id,organization_id,location_id,offering_id,quantity_on_hand,quantity_reserved,reorder_point,unit_cost_minor,updated_at
  ) VALUES(gen_random_uuid(),v_org,v_location,v_offering,50,0,0,NULL,now());

  v_result:=public.commercial_record_opening_inventory_batches(
    v_org,v_location,v_offering,
    jsonb_build_array(
      jsonb_build_object('quantity',20,'unit_cost_minor',100,'batch_number','OPEN-LOT-A','expires_on','2027-03-01','stock_condition','sellable'),
      jsonb_build_object('quantity',20,'unit_cost_minor',200,'batch_number','OPEN-LOT-B','expires_on','2027-06-01','stock_condition','quarantine'),
      jsonb_build_object('quantity',5,'unit_cost_minor',200,'batch_number','OPEN-LOT-C','expires_on','2027-08-01','stock_condition','damaged'),
      jsonb_build_object('quantity',5,'unit_cost_minor',200,'batch_number','OPEN-LOT-D','expires_on',current_date-1,'stock_condition','sellable')
    ),
    'opening-valuation-smoke-01'
  );
  IF (v_result->>'valued_quantity')::numeric<>50 OR (v_result->>'covered_quantity')::numeric<>50 THEN
    RAISE EXCEPTION 'opening_valuation_quantity_mismatch: %',v_result;
  END IF;
  SELECT unit_cost_minor INTO v_cost FROM public.commercial_inventory_balances
  WHERE organization_id=v_org AND location_id=v_location AND offering_id=v_offering;
  IF v_cost<>160 THEN RAISE EXCEPTION 'opening_weighted_average_cost_mismatch: %',v_cost; END IF;
  SELECT count(*)::integer,jsonb_agg(source_unit_cost_minor ORDER BY batch_number)
  INTO v_layers,v_source_costs
  FROM public.commercial_inventory_cost_layers
  WHERE organization_id=v_org AND location_id=v_location AND offering_id=v_offering;
  IF v_layers<>4 OR v_source_costs<>'[100, 200, 200, 200]'::jsonb THEN
    RAISE EXCEPTION 'opening_batch_layers_missing: count %, costs %',v_layers,v_source_costs;
  END IF;
  IF EXISTS (
    SELECT 1 FROM public.commercial_inventory_cost_layers
    WHERE organization_id=v_org AND location_id=v_location AND offering_id=v_offering
      AND unit_cost_minor<>160
  ) THEN RAISE EXCEPTION 'opening_weighted_average_layers_not_revalued'; END IF;
  IF (SELECT stock_condition FROM public.commercial_inventory_cost_layers WHERE batch_number='OPEN-LOT-A')<>'sellable'
     OR (SELECT stock_condition FROM public.commercial_inventory_cost_layers WHERE batch_number='OPEN-LOT-B')<>'quarantine'
     OR (SELECT stock_condition FROM public.commercial_inventory_cost_layers WHERE batch_number='OPEN-LOT-C')<>'damaged'
     OR (SELECT stock_condition FROM public.commercial_inventory_cost_layers WHERE batch_number='OPEN-LOT-D')<>'expired' THEN
    RAISE EXCEPTION 'opening_batch_condition_not_preserved_or_expiry_not_enforced';
  END IF;

  v_replay:=public.commercial_record_opening_inventory_batches(
    v_org,v_location,v_offering,
    jsonb_build_array(
      jsonb_build_object('quantity',20,'unit_cost_minor',100,'batch_number','OPEN-LOT-A','expires_on','2027-03-01','stock_condition','sellable'),
      jsonb_build_object('quantity',20,'unit_cost_minor',200,'batch_number','OPEN-LOT-B','expires_on','2027-06-01','stock_condition','quarantine'),
      jsonb_build_object('quantity',5,'unit_cost_minor',200,'batch_number','OPEN-LOT-C','expires_on','2027-08-01','stock_condition','damaged'),
      jsonb_build_object('quantity',5,'unit_cost_minor',200,'batch_number','OPEN-LOT-D','expires_on',current_date-1,'stock_condition','sellable')
    ),
    'opening-valuation-smoke-01'
  );
  IF v_replay->>'idempotent_replay'<>'true' THEN
    RAISE EXCEPTION 'opening_valuation_retry_not_idempotent: %',v_replay;
  END IF;

  BEGIN
    PERFORM public.commercial_record_opening_inventory_batches(
      v_org,v_location,v_offering,
      jsonb_build_array(jsonb_build_object('quantity',49,'unit_cost_minor',100,'batch_number','WRONG','expires_on',NULL)),
      'opening-valuation-smoke-02'
    );
    RAISE EXCEPTION 'opening_valuation_allowed_partial_coverage';
  EXCEPTION WHEN SQLSTATE '22023' THEN
    NULL;
  END;
END;
$opening$;
