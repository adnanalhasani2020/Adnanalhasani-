BEGIN;

INSERT INTO auth.users(id)
VALUES ('00000000-0000-0000-0000-000000000001');
INSERT INTO public.commercial_organizations(id)
VALUES ('10000000-0000-0000-0000-000000000001');
INSERT INTO public.commercial_organization_locations(id, organization_id)
VALUES ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001');
INSERT INTO public.commercial_activities(id, organization_id)
VALUES ('30000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001');
INSERT INTO public.commercial_organization_members(organization_id, user_id, role, state)
VALUES ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'owner', 'active');
INSERT INTO public.commercial_warehouses(id, organization_id, branch_location_id, state)
VALUES ('40000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 'active');
INSERT INTO public.commercial_storage_locations(id, organization_id, warehouse_id, state, location_type)
VALUES
  ('50000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001', 'active', 'internal'),
  ('50000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001', 'active', 'internal');
INSERT INTO public.commercial_offerings(id, activity_id, location_ref)
VALUES ('60000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001');
INSERT INTO public.commercial_inventory_balances(organization_id, location_id, offering_id, quantity_on_hand, quantity_reserved)
VALUES ('10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', '60000000-0000-0000-0000-000000000001', 10, 4);

SELECT set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000001', true);
SET LOCAL ROLE authenticated;

DO $smoke$
DECLARE
  v_result jsonb;
  v_source numeric;
  v_destination numeric;
  v_total numeric;
  v_movements integer;
BEGIN
  IF NOT has_function_privilege(
    'authenticated',
    'public.commercial_allocate_inventory_to_storage_location(uuid,uuid,uuid,uuid,uuid,numeric,text)',
    'EXECUTE'
  ) THEN
    RAISE EXCEPTION 'authenticated_allocation_execute_grant_missing';
  END IF;
  IF has_function_privilege(
    'anon',
    'public.commercial_allocate_inventory_to_storage_location(uuid,uuid,uuid,uuid,uuid,numeric,text)',
    'EXECUTE'
  ) THEN
    RAISE EXCEPTION 'anonymous_allocation_execute_grant_present';
  END IF;
  IF NOT has_function_privilege(
    'authenticated',
    'public.commercial_transfer_inventory_storage_location(uuid,uuid,uuid,uuid,uuid,uuid,numeric,text)',
    'EXECUTE'
  ) THEN
    RAISE EXCEPTION 'authenticated_transfer_execute_grant_missing';
  END IF;
  IF has_function_privilege(
    'anon',
    'public.commercial_transfer_inventory_storage_location(uuid,uuid,uuid,uuid,uuid,uuid,numeric,text)',
    'EXECUTE'
  ) THEN
    RAISE EXCEPTION 'anonymous_transfer_execute_grant_present';
  END IF;

  BEGIN
    PERFORM public.commercial_allocate_inventory_to_storage_location(
      '10000000-0000-0000-0000-000000000001',
      '20000000-0000-0000-0000-000000000001',
      '40000000-0000-0000-0000-000000000001',
      '50000000-0000-0000-0000-000000000001',
      '60000000-0000-0000-0000-000000000001',
      7,
      'alloc-reserved-001'
    );
    RAISE EXCEPTION 'expected_reserved_stock_allocation_rejection';
  EXCEPTION WHEN check_violation THEN
    IF SQLERRM <> 'inventory_storage_allocation_exceeds_unallocated_stock' THEN
      RAISE;
    END IF;
  END;

  v_result := public.commercial_allocate_inventory_to_storage_location(
    '10000000-0000-0000-0000-000000000001',
    '20000000-0000-0000-0000-000000000001',
    '40000000-0000-0000-0000-000000000001',
    '50000000-0000-0000-0000-000000000001',
    '60000000-0000-0000-0000-000000000001',
    6,
    'alloc-reserved-001'
  );
  IF v_result->>'idempotent_replay' <> 'false' THEN
    RAISE EXCEPTION 'first_allocation_not_recorded';
  END IF;

  v_result := public.commercial_allocate_inventory_to_storage_location(
    '10000000-0000-0000-0000-000000000001',
    '20000000-0000-0000-0000-000000000001',
    '40000000-0000-0000-0000-000000000001',
    '50000000-0000-0000-0000-000000000001',
    '60000000-0000-0000-0000-000000000001',
    6,
    'alloc-reserved-001'
  );
  IF v_result->>'idempotent_replay' <> 'true' THEN
    RAISE EXCEPTION 'allocation_retry_not_idempotent';
  END IF;

  BEGIN
    PERFORM public.commercial_allocate_inventory_to_storage_location(
      '10000000-0000-0000-0000-000000000001',
      '20000000-0000-0000-0000-000000000001',
      '40000000-0000-0000-0000-000000000001',
      '50000000-0000-0000-0000-000000000001',
      '60000000-0000-0000-0000-000000000001',
      5,
      'alloc-reserved-001'
    );
    RAISE EXCEPTION 'expected_idempotency_payload_conflict';
  EXCEPTION WHEN unique_violation THEN
    IF SQLERRM <> 'idempotency_key_reused_with_different_request' THEN
      RAISE;
    END IF;
  END;

  v_result := public.commercial_transfer_inventory_storage_location(
    '10000000-0000-0000-0000-000000000001',
    '20000000-0000-0000-0000-000000000001',
    '40000000-0000-0000-0000-000000000001',
    '50000000-0000-0000-0000-000000000001',
    '50000000-0000-0000-0000-000000000002',
    '60000000-0000-0000-0000-000000000001',
    2,
    'transfer-storage-001'
  );
  IF v_result->>'idempotent_replay' <> 'false' THEN
    RAISE EXCEPTION 'first_transfer_not_recorded';
  END IF;

  v_result := public.commercial_transfer_inventory_storage_location(
    '10000000-0000-0000-0000-000000000001',
    '20000000-0000-0000-0000-000000000001',
    '40000000-0000-0000-0000-000000000001',
    '50000000-0000-0000-0000-000000000001',
    '50000000-0000-0000-0000-000000000002',
    '60000000-0000-0000-0000-000000000001',
    2,
    'transfer-storage-001'
  );
  IF v_result->>'idempotent_replay' <> 'true' THEN
    RAISE EXCEPTION 'transfer_retry_not_idempotent';
  END IF;

  BEGIN
    PERFORM public.commercial_transfer_inventory_storage_location(
      '10000000-0000-0000-0000-000000000001',
      '20000000-0000-0000-0000-000000000001',
      '40000000-0000-0000-0000-000000000001',
      '50000000-0000-0000-0000-000000000001',
      '50000000-0000-0000-0000-000000000002',
      '60000000-0000-0000-0000-000000000001',
      5,
      'transfer-insufficient-001'
    );
    RAISE EXCEPTION 'expected_insufficient_source_rejection';
  EXCEPTION WHEN check_violation THEN
    IF SQLERRM <> 'inventory_storage_insufficient' THEN
      RAISE;
    END IF;
  END;

  SELECT quantity_on_hand INTO v_source
  FROM public.commercial_inventory_storage_balances
  WHERE storage_location_id = '50000000-0000-0000-0000-000000000001'
    AND offering_id = '60000000-0000-0000-0000-000000000001';
  SELECT quantity_on_hand INTO v_destination
  FROM public.commercial_inventory_storage_balances
  WHERE storage_location_id = '50000000-0000-0000-0000-000000000002'
    AND offering_id = '60000000-0000-0000-0000-000000000001';
  SELECT quantity_on_hand INTO v_total
  FROM public.commercial_inventory_balances
  WHERE location_id = '20000000-0000-0000-0000-000000000001'
    AND offering_id = '60000000-0000-0000-0000-000000000001';
  SELECT count(*)::integer INTO v_movements
  FROM public.commercial_inventory_storage_movements;

  IF v_source <> 4 OR v_destination <> 2 THEN
    RAISE EXCEPTION 'storage_transfer_balance_mismatch: source %, destination %', v_source, v_destination;
  END IF;
  IF v_total <> 10 THEN
    RAISE EXCEPTION 'aggregate_branch_balance_changed: %', v_total;
  END IF;
  IF v_movements <> 2 THEN
    RAISE EXCEPTION 'unexpected_storage_movement_count: %', v_movements;
  END IF;
END
$smoke$;

RESET ROLE;

DO $smoke$
BEGIN
  BEGIN
    UPDATE public.commercial_inventory_storage_movements
    SET quantity = quantity + 1
    WHERE idempotency_key = 'alloc-reserved-001';
    RAISE EXCEPTION 'expected_immutable_movement_update_rejection';
  EXCEPTION WHEN SQLSTATE '55000' THEN
    IF SQLERRM <> 'inventory_storage_movement_is_immutable' THEN
      RAISE;
    END IF;
  END;
END
$smoke$;

ROLLBACK;
