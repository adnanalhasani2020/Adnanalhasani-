BEGIN;

-- Keep storage allocations within unreserved stock. The branch balance remains
-- authoritative; reserved units cannot be assigned to a physical bin.

CREATE OR REPLACE FUNCTION public.commercial_allocate_inventory_to_storage_location(
  p_organization_id uuid,
  p_branch_location_id uuid,
  p_warehouse_id uuid,
  p_storage_location_id uuid,
  p_offering_id uuid,
  p_quantity numeric(20,6),
  p_idempotency_key text
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_balance public.commercial_inventory_balances%ROWTYPE;
  v_existing public.commercial_inventory_storage_movements%ROWTYPE;
  v_allocated numeric(20,6);
  v_fingerprint text;
  v_balance_id uuid;
  v_movement_id uuid;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'authentication_required' USING ERRCODE = '42501';
  END IF;
  IF p_organization_id IS NULL OR p_branch_location_id IS NULL OR p_warehouse_id IS NULL
     OR p_storage_location_id IS NULL OR p_offering_id IS NULL
     OR p_quantity IS NULL OR p_quantity <= 0 OR p_quantity > 100000000
     OR p_idempotency_key IS NULL
     OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200 THEN
    RAISE EXCEPTION 'invalid_inventory_storage_allocation' USING ERRCODE = '22023';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id = p_organization_id AND m.user_id = v_uid
      AND m.state = 'active' AND m.role IN ('owner','admin','manager')
  ) THEN
    RAISE EXCEPTION 'inventory_management_permission_required' USING ERRCODE = '42501';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM public.commercial_warehouses w
    WHERE w.id = p_warehouse_id AND w.organization_id = p_organization_id
      AND w.branch_location_id = p_branch_location_id AND w.state = 'active'
  ) OR NOT EXISTS (
    SELECT 1 FROM public.commercial_storage_locations sl
    WHERE sl.id = p_storage_location_id AND sl.organization_id = p_organization_id
      AND sl.warehouse_id = p_warehouse_id AND sl.state = 'active'
      AND sl.location_type IN ('internal','receiving','packing','quality','quarantine')
  ) OR NOT EXISTS (
    SELECT 1 FROM public.commercial_offerings o
    JOIN public.commercial_activities a ON a.id = o.activity_id
    WHERE o.id = p_offering_id AND a.organization_id = p_organization_id
      AND o.location_ref = p_branch_location_id::text
  ) THEN
    RAISE EXCEPTION 'inventory_storage_scope_mismatch' USING ERRCODE = '22023';
  END IF;

  v_fingerprint := md5(jsonb_build_object(
    'branch_location_id', p_branch_location_id, 'warehouse_id', p_warehouse_id,
    'storage_location_id', p_storage_location_id, 'offering_id', p_offering_id,
    'quantity', p_quantity, 'movement_type', 'allocation'
  )::text);
  PERFORM pg_advisory_xact_lock(
    hashtext(p_organization_id::text),
    hashtext('inventory-storage:' || p_branch_location_id::text || ':' || p_offering_id::text)
  );
  SELECT * INTO v_existing
  FROM public.commercial_inventory_storage_movements
  WHERE organization_id = p_organization_id AND idempotency_key = btrim(p_idempotency_key);
  IF FOUND THEN
    IF v_existing.request_fingerprint IS DISTINCT FROM v_fingerprint THEN
      RAISE EXCEPTION 'idempotency_key_reused_with_different_request' USING ERRCODE = '23505';
    END IF;
    RETURN jsonb_build_object('movement_id',v_existing.id,'idempotent_replay',true);
  END IF;

  SELECT * INTO v_balance
  FROM public.commercial_inventory_balances
  WHERE organization_id = p_organization_id
    AND location_id = p_branch_location_id AND offering_id = p_offering_id
  FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'inventory_balance_not_found' USING ERRCODE = 'P0002'; END IF;
  IF v_balance.quantity_on_hand < 0
     OR coalesce(v_balance.quantity_reserved, 0) < 0
     OR coalesce(v_balance.quantity_reserved, 0) > v_balance.quantity_on_hand THEN
    RAISE EXCEPTION 'inventory_balance_invalid' USING ERRCODE = '23514';
  END IF;

  SELECT coalesce(sum(quantity_on_hand),0)::numeric(20,6) INTO v_allocated
  FROM public.commercial_inventory_storage_balances
  WHERE organization_id = p_organization_id
    AND branch_location_id = p_branch_location_id AND offering_id = p_offering_id;
  IF v_allocated + p_quantity > v_balance.quantity_on_hand - coalesce(v_balance.quantity_reserved, 0) THEN
    RAISE EXCEPTION 'inventory_storage_allocation_exceeds_unallocated_stock' USING ERRCODE = '23514';
  END IF;

  INSERT INTO public.commercial_inventory_storage_balances(
    organization_id, branch_location_id, warehouse_id, storage_location_id, offering_id, quantity_on_hand
  ) VALUES (
    p_organization_id, p_branch_location_id, p_warehouse_id, p_storage_location_id, p_offering_id, p_quantity
  )
  ON CONFLICT (organization_id, storage_location_id, offering_id)
  DO UPDATE SET quantity_on_hand = public.commercial_inventory_storage_balances.quantity_on_hand + EXCLUDED.quantity_on_hand,
                updated_at = now()
  RETURNING id INTO v_balance_id;

  INSERT INTO public.commercial_inventory_storage_movements(
    organization_id, branch_location_id, warehouse_id, offering_id, movement_type,
    destination_storage_location_id, quantity, idempotency_key, request_fingerprint, created_by
  ) VALUES (
    p_organization_id, p_branch_location_id, p_warehouse_id, p_offering_id, 'allocation',
    p_storage_location_id, p_quantity, btrim(p_idempotency_key), v_fingerprint, v_uid
  ) RETURNING id INTO v_movement_id;

  INSERT INTO public.commercial_audit_events(
    organization_id, actor_user_id, action, entity_type, entity_id, metadata
  ) VALUES (
    p_organization_id, v_uid, 'inventory.storage.allocated', 'inventory_storage_balance', v_balance_id::text,
    jsonb_build_object('branch_location_id',p_branch_location_id,'warehouse_id',p_warehouse_id,
      'storage_location_id',p_storage_location_id,'offering_id',p_offering_id,'quantity',p_quantity,
      'movement_id',v_movement_id)
  );
  RETURN jsonb_build_object('movement_id',v_movement_id,'balance_id',v_balance_id,'idempotent_replay',false);
END;
$function$;

COMMIT;
