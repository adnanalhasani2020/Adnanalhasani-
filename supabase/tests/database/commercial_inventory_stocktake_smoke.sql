BEGIN;

INSERT INTO auth.users(id) VALUES
('00000000-0000-0000-0000-000000000001'),
('00000000-0000-0000-0000-000000000002'),
('00000000-0000-0000-0000-000000000003');
INSERT INTO public.commercial_organizations(id)
VALUES('10000000-0000-0000-0000-000000000001');
INSERT INTO public.commercial_organization_locations(id,organization_id)
VALUES('20000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001');
INSERT INTO public.commercial_activities(id,organization_id)
VALUES('30000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001');
INSERT INTO public.commercial_organization_members(organization_id,user_id,role,state) VALUES
('10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','owner','active'),
('10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002','manager','active'),
('10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000003','member','active');
INSERT INTO public.commercial_offerings(id,activity_id,location_ref)
VALUES('60000000-0000-0000-0000-000000000001','30000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000001');
INSERT INTO public.commercial_inventory_balances(
  organization_id,location_id,offering_id,quantity_on_hand,quantity_reserved,unit_cost_minor
) VALUES (
  '10000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000001',
  '60000000-0000-0000-0000-000000000001',10,2,100
);
INSERT INTO public.commercial_inventory_cost_layers(
  organization_id,location_id,offering_id,source_type,source_ref,
  quantity_received,quantity_remaining,source_unit_cost_minor,unit_cost_minor
) VALUES (
  '10000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000001',
  '60000000-0000-0000-0000-000000000001','opening_balance','opening-test-layer',
  10,10,100,100
);
INSERT INTO public.commercial_inventory_costing_policies(organization_id,location_id,costing_method)
VALUES('10000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000001','weighted_average');

SELECT set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000001',true);
SET LOCAL ROLE authenticated;

DO $test$
DECLARE
  v_created jsonb;
  v_replay jsonb;
  v_stocktake uuid;
  v_line uuid;
  v_balance numeric;
  v_reserved numeric;
  v_cost bigint;
  v_layer_qty numeric;
  v_layer_cost bigint;
  v_movements integer;
BEGIN
  IF NOT has_function_privilege('authenticated',
    'public.commercial_create_inventory_stocktake(uuid,uuid,uuid,text,text)','EXECUTE')
    OR has_function_privilege('anon',
    'public.commercial_create_inventory_stocktake(uuid,uuid,uuid,text,text)','EXECUTE') THEN
    RAISE EXCEPTION 'stocktake_create_rpc_grants_invalid';
  END IF;
  IF has_table_privilege('authenticated','public.commercial_inventory_stocktakes','INSERT') THEN
    RAISE EXCEPTION 'authenticated_direct_stocktake_insert_must_be_revoked';
  END IF;

  v_created := public.commercial_create_inventory_stocktake(
    '10000000-0000-0000-0000-000000000001','30000000-0000-0000-0000-000000000001',
    '20000000-0000-0000-0000-000000000001','stocktake-create-001','cycle count'
  );
  v_stocktake := (v_created->>'stocktake_id')::uuid;
  IF v_created->>'status'<>'draft' OR (v_created->>'line_count')::integer<>1 THEN
    RAISE EXCEPTION 'stocktake_draft_snapshot_invalid';
  END IF;
  v_replay := public.commercial_create_inventory_stocktake(
    '10000000-0000-0000-0000-000000000001','30000000-0000-0000-0000-000000000001',
    '20000000-0000-0000-0000-000000000001','stocktake-create-001','cycle count'
  );
  IF v_replay->>'idempotent_replay'<>'true' OR v_replay->>'stocktake_id'<>v_stocktake::text THEN
    RAISE EXCEPTION 'stocktake_create_retry_not_idempotent';
  END IF;
  SELECT id INTO v_line FROM public.commercial_inventory_stocktake_lines WHERE stocktake_id=v_stocktake;

  PERFORM public.commercial_set_inventory_stocktake_counts(v_stocktake,
    jsonb_build_array(jsonb_build_object('line_id',v_line,'counted_quantity','8')));
  PERFORM public.commercial_submit_inventory_stocktake(v_stocktake);

  BEGIN
    PERFORM public.commercial_approve_inventory_stocktake(v_stocktake);
    RAISE EXCEPTION 'stocktake_creator_self_approval_was_allowed';
  EXCEPTION WHEN insufficient_privilege THEN
    IF SQLERRM<>'inventory_stocktake_self_approval_forbidden' THEN RAISE; END IF;
  END;

  PERFORM set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000002',true);
  PERFORM public.commercial_approve_inventory_stocktake(v_stocktake);
  PERFORM set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000001',true);
  v_replay := public.commercial_post_inventory_stocktake(v_stocktake,'stocktake-post-001');
  IF v_replay->>'status'<>'posted' OR v_replay->>'idempotent_replay'<>'false' THEN
    RAISE EXCEPTION 'stocktake_post_result_invalid';
  END IF;
  v_replay := public.commercial_post_inventory_stocktake(v_stocktake,'stocktake-post-001');
  IF v_replay->>'idempotent_replay'<>'true' THEN RAISE EXCEPTION 'stocktake_post_retry_not_idempotent'; END IF;

  SELECT quantity_on_hand,quantity_reserved,unit_cost_minor
  INTO v_balance,v_reserved,v_cost FROM public.commercial_inventory_balances
  WHERE offering_id='60000000-0000-0000-0000-000000000001';
  SELECT sum(quantity_remaining) INTO v_layer_qty FROM public.commercial_inventory_cost_layers
  WHERE offering_id='60000000-0000-0000-0000-000000000001';
  IF v_balance<>8 OR v_reserved<>2 OR v_cost<>100 OR v_layer_qty<>8 THEN
    RAISE EXCEPTION 'negative_variance_post_mismatch: balance %, reserved %, cost %, layers %',
      v_balance,v_reserved,v_cost,v_layer_qty;
  END IF;

  PERFORM set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000002',true);
  v_replay := public.commercial_reverse_inventory_stocktake(v_stocktake,'validated recount reversal','stocktake-reverse-001');
  IF v_replay->>'status'<>'reversed' THEN RAISE EXCEPTION 'stocktake_reverse_failed'; END IF;
  v_replay := public.commercial_reverse_inventory_stocktake(v_stocktake,'validated recount reversal','stocktake-reverse-001');
  IF v_replay->>'idempotent_replay'<>'true' THEN RAISE EXCEPTION 'stocktake_reverse_retry_not_idempotent'; END IF;

  SELECT quantity_on_hand,unit_cost_minor INTO v_balance,v_cost FROM public.commercial_inventory_balances
  WHERE offering_id='60000000-0000-0000-0000-000000000001';
  SELECT sum(quantity_remaining) INTO v_layer_qty FROM public.commercial_inventory_cost_layers
  WHERE offering_id='60000000-0000-0000-0000-000000000001';
  IF v_balance<>10 OR v_cost<>100 OR v_layer_qty<>10 THEN
    RAISE EXCEPTION 'negative_variance_reversal_mismatch: balance %, cost %, layers %',v_balance,v_cost,v_layer_qty;
  END IF;

  -- Positive variance must carry an explicit cost; weighted average is snapshotted and reversible.
  PERFORM set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000001',true);
  v_created := public.commercial_create_inventory_stocktake(
    '10000000-0000-0000-0000-000000000001','30000000-0000-0000-0000-000000000001',
    '20000000-0000-0000-0000-000000000001','stocktake-create-002','positive count'
  );
  v_stocktake := (v_created->>'stocktake_id')::uuid;
  SELECT id INTO v_line FROM public.commercial_inventory_stocktake_lines WHERE stocktake_id=v_stocktake;
  PERFORM public.commercial_set_inventory_stocktake_counts(v_stocktake,
    jsonb_build_array(jsonb_build_object('line_id',v_line,'counted_quantity','12','adjustment_unit_cost_minor','150')));
  PERFORM public.commercial_submit_inventory_stocktake(v_stocktake);
  PERFORM set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000002',true);
  PERFORM public.commercial_approve_inventory_stocktake(v_stocktake);
  PERFORM set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000001',true);
  PERFORM public.commercial_post_inventory_stocktake(v_stocktake,'stocktake-post-002');
  SELECT quantity_on_hand,unit_cost_minor INTO v_balance,v_cost FROM public.commercial_inventory_balances
  WHERE offering_id='60000000-0000-0000-0000-000000000001';
  IF v_balance<>12 OR v_cost<>108 THEN
    RAISE EXCEPTION 'positive_variance_weighted_average_mismatch: balance %, cost %',v_balance,v_cost;
  END IF;
  PERFORM set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000002',true);
  PERFORM public.commercial_reverse_inventory_stocktake(v_stocktake,'recount confirmed','stocktake-reverse-002');
  SELECT quantity_on_hand,unit_cost_minor INTO v_balance,v_cost FROM public.commercial_inventory_balances
  WHERE offering_id='60000000-0000-0000-0000-000000000001';
  SELECT sum(quantity_remaining),max(unit_cost_minor) INTO v_layer_qty,v_layer_cost
  FROM public.commercial_inventory_cost_layers WHERE offering_id='60000000-0000-0000-0000-000000000001' AND quantity_remaining>0;
  IF v_balance<>10 OR v_cost<>100 OR v_layer_qty<>10 OR v_layer_cost<>100 THEN
    RAISE EXCEPTION 'positive_variance_reversal_cost_mismatch: balance %, cost %, layers %, layer cost %',
      v_balance,v_cost,v_layer_qty,v_layer_cost;
  END IF;

  -- Reserved stock cannot be counted away.
  PERFORM set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000001',true);
  v_created := public.commercial_create_inventory_stocktake(
    '10000000-0000-0000-0000-000000000001','30000000-0000-0000-0000-000000000001',
    '20000000-0000-0000-0000-000000000001','stocktake-create-003','reserved count'
  );
  v_stocktake := (v_created->>'stocktake_id')::uuid;
  SELECT id INTO v_line FROM public.commercial_inventory_stocktake_lines WHERE stocktake_id=v_stocktake;
  PERFORM public.commercial_set_inventory_stocktake_counts(v_stocktake,
    jsonb_build_array(jsonb_build_object('line_id',v_line,'counted_quantity','1')));
  PERFORM public.commercial_submit_inventory_stocktake(v_stocktake);
  PERFORM set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000002',true);
  PERFORM public.commercial_approve_inventory_stocktake(v_stocktake);
  PERFORM set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000001',true);
  BEGIN
    PERFORM public.commercial_post_inventory_stocktake(v_stocktake,'stocktake-post-003');
    RAISE EXCEPTION 'stocktake_below_reserved_quantity_was_allowed';
  EXCEPTION WHEN SQLSTATE '55000' THEN
    IF SQLERRM<>'inventory_stocktake_below_reserved_quantity' THEN RAISE; END IF;
  END;

  -- Non-managers cannot create stocktakes.
  PERFORM set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000003',true);
  BEGIN
    PERFORM public.commercial_create_inventory_stocktake(
      '10000000-0000-0000-0000-000000000001','30000000-0000-0000-0000-000000000001',
      '20000000-0000-0000-0000-000000000001','stocktake-denied-001','member attempt'
    );
    RAISE EXCEPTION 'non_manager_stocktake_create_was_allowed';
  EXCEPTION WHEN insufficient_privilege THEN
    IF SQLERRM<>'inventory_management_permission_required' THEN RAISE; END IF;
  END;

  -- The person who submitted the count cannot approve their own submitted document.
  PERFORM set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000001',true);
  v_created := public.commercial_create_inventory_stocktake(
    '10000000-0000-0000-0000-000000000001','30000000-0000-0000-0000-000000000001',
    '20000000-0000-0000-0000-000000000001','stocktake-create-004','independent reviewer'
  );
  v_stocktake := (v_created->>'stocktake_id')::uuid;
  SELECT id INTO v_line FROM public.commercial_inventory_stocktake_lines WHERE stocktake_id=v_stocktake;
  PERFORM set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000002',true);
  PERFORM public.commercial_set_inventory_stocktake_counts(v_stocktake,
    jsonb_build_array(jsonb_build_object('line_id',v_line,'counted_quantity','9')));
  PERFORM public.commercial_submit_inventory_stocktake(v_stocktake);
  BEGIN
    PERFORM public.commercial_approve_inventory_stocktake(v_stocktake);
    RAISE EXCEPTION 'stocktake_submitter_self_approval_was_allowed';
  EXCEPTION WHEN insufficient_privilege THEN
    IF SQLERRM<>'inventory_stocktake_self_approval_forbidden' THEN RAISE; END IF;
  END;

  -- After approval, any intervening movement invalidates the captured count.
  PERFORM set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000001',true);
  PERFORM public.commercial_approve_inventory_stocktake(v_stocktake);
  UPDATE public.commercial_inventory_balances SET quantity_on_hand=9
  WHERE offering_id='60000000-0000-0000-0000-000000000001';
  INSERT INTO public.commercial_stock_movements(offering_id,movement_type,quantity_delta,source_ref,created_by)
  VALUES('60000000-0000-0000-0000-000000000001','adjustment',-1,'test-stocktake-intervening-movement',
    '00000000-0000-0000-0000-000000000001');
  BEGIN
    PERFORM public.commercial_post_inventory_stocktake(v_stocktake,'stocktake-post-004');
    RAISE EXCEPTION 'stale_stocktake_snapshot_was_posted';
  EXCEPTION WHEN SQLSTATE '55000' THEN
    IF SQLERRM<>'inventory_stocktake_stale_snapshot' THEN RAISE; END IF;
  END;
END
$test$;

RESET ROLE;
ROLLBACK;
