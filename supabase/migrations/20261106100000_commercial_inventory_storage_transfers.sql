BEGIN;

-- First safe stock-location slice:
-- keep the existing branch-level balance authoritative and add an explicitly
-- allocated sub-ledger for stock placed in warehouse storage locations.
CREATE TABLE IF NOT EXISTS public.commercial_inventory_storage_balances (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
  branch_location_id uuid NOT NULL REFERENCES public.commercial_organization_locations(id) ON DELETE RESTRICT,
  warehouse_id uuid NOT NULL REFERENCES public.commercial_warehouses(id) ON DELETE RESTRICT,
  storage_location_id uuid NOT NULL REFERENCES public.commercial_storage_locations(id) ON DELETE RESTRICT,
  offering_id uuid NOT NULL REFERENCES public.commercial_offerings(id) ON DELETE RESTRICT,
  quantity_on_hand bigint NOT NULL DEFAULT 0 CHECK (quantity_on_hand >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (organization_id, storage_location_id, offering_id)
);

CREATE INDEX IF NOT EXISTS commercial_inventory_storage_balances_offering_idx
  ON public.commercial_inventory_storage_balances
    (organization_id, branch_location_id, offering_id, warehouse_id);

CREATE TABLE IF NOT EXISTS public.commercial_inventory_storage_movements (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL REFERENCES public.commercial_organizations(id) ON DELETE RESTRICT,
  branch_location_id uuid NOT NULL REFERENCES public.commercial_organization_locations(id) ON DELETE RESTRICT,
  warehouse_id uuid NOT NULL REFERENCES public.commercial_warehouses(id) ON DELETE RESTRICT,
  offering_id uuid NOT NULL REFERENCES public.commercial_offerings(id) ON DELETE RESTRICT,
  movement_type text NOT NULL CHECK (movement_type IN ('allocation','internal_transfer')),
  source_storage_location_id uuid REFERENCES public.commercial_storage_locations(id) ON DELETE RESTRICT,
  destination_storage_location_id uuid NOT NULL REFERENCES public.commercial_storage_locations(id) ON DELETE RESTRICT,
  quantity bigint NOT NULL CHECK (quantity > 0),
  idempotency_key text NOT NULL CHECK (length(btrim(idempotency_key)) BETWEEN 8 AND 200),
  request_fingerprint text NOT NULL,
  created_by uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (organization_id, idempotency_key),
  CHECK (
    (movement_type = 'allocation' AND source_storage_location_id IS NULL)
    OR
    (movement_type = 'internal_transfer' AND source_storage_location_id IS NOT NULL
      AND source_storage_location_id <> destination_storage_location_id)
  )
);

CREATE INDEX IF NOT EXISTS commercial_inventory_storage_movements_offering_idx
  ON public.commercial_inventory_storage_movements
    (organization_id, branch_location_id, offering_id, created_at, id);

ALTER TABLE public.commercial_inventory_storage_balances ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.commercial_inventory_storage_movements ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS commercial_inventory_storage_balances_member_read
  ON public.commercial_inventory_storage_balances;
CREATE POLICY commercial_inventory_storage_balances_member_read
  ON public.commercial_inventory_storage_balances
  FOR SELECT TO authenticated
  USING (EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id = commercial_inventory_storage_balances.organization_id
      AND m.user_id = (SELECT auth.uid()) AND m.state = 'active'
  ));

DROP POLICY IF EXISTS commercial_inventory_storage_movements_member_read
  ON public.commercial_inventory_storage_movements;
CREATE POLICY commercial_inventory_storage_movements_member_read
  ON public.commercial_inventory_storage_movements
  FOR SELECT TO authenticated
  USING (EXISTS (
    SELECT 1 FROM public.commercial_organization_members m
    WHERE m.organization_id = commercial_inventory_storage_movements.organization_id
      AND m.user_id = (SELECT auth.uid()) AND m.state = 'active'
  ));

REVOKE ALL ON public.commercial_inventory_storage_balances FROM PUBLIC, anon, authenticated;
REVOKE ALL ON public.commercial_inventory_storage_movements FROM PUBLIC, anon, authenticated;
GRANT SELECT ON public.commercial_inventory_storage_balances,
  public.commercial_inventory_storage_movements TO authenticated;

CREATE OR REPLACE FUNCTION public.commercial_allocate_inventory_to_storage_location(
  p_organization_id uuid,
  p_branch_location_id uuid,
  p_warehouse_id uuid,
  p_storage_location_id uuid,
  p_offering_id uuid,
  p_quantity bigint,
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
  v_allocated bigint;
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
  IF v_balance.quantity_on_hand < 0 THEN
    RAISE EXCEPTION 'inventory_balance_invalid' USING ERRCODE = '23514';
  END IF;

  SELECT coalesce(sum(quantity_on_hand),0)::bigint INTO v_allocated
  FROM public.commercial_inventory_storage_balances
  WHERE organization_id = p_organization_id
    AND branch_location_id = p_branch_location_id AND offering_id = p_offering_id;
  IF v_allocated + p_quantity > v_balance.quantity_on_hand THEN
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

CREATE OR REPLACE FUNCTION public.commercial_transfer_inventory_storage_location(
  p_organization_id uuid,
  p_branch_location_id uuid,
  p_warehouse_id uuid,
  p_source_storage_location_id uuid,
  p_destination_storage_location_id uuid,
  p_offering_id uuid,
  p_quantity bigint,
  p_idempotency_key text
) RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $function$
DECLARE
  v_uid uuid := auth.uid();
  v_source public.commercial_inventory_storage_balances%ROWTYPE;
  v_existing public.commercial_inventory_storage_movements%ROWTYPE;
  v_fingerprint text;
  v_movement_id uuid;
  v_destination_balance_id uuid;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'authentication_required' USING ERRCODE = '42501';
  END IF;
  IF p_organization_id IS NULL OR p_branch_location_id IS NULL OR p_warehouse_id IS NULL
     OR p_source_storage_location_id IS NULL OR p_destination_storage_location_id IS NULL
     OR p_source_storage_location_id = p_destination_storage_location_id
     OR p_offering_id IS NULL OR p_quantity IS NULL OR p_quantity <= 0 OR p_quantity > 100000000
     OR p_idempotency_key IS NULL
     OR length(btrim(p_idempotency_key)) NOT BETWEEN 8 AND 200 THEN
    RAISE EXCEPTION 'invalid_inventory_storage_transfer' USING ERRCODE = '22023';
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
    WHERE sl.id IN (p_source_storage_location_id,p_destination_storage_location_id)
      AND sl.organization_id = p_organization_id AND sl.warehouse_id = p_warehouse_id
      AND sl.state = 'active' AND sl.location_type IN ('internal','receiving','packing','quality','quarantine')
    GROUP BY sl.warehouse_id HAVING count(DISTINCT sl.id) = 2
  ) OR NOT EXISTS (
    SELECT 1 FROM public.commercial_offerings o
    JOIN public.commercial_activities a ON a.id = o.activity_id
    WHERE o.id = p_offering_id AND a.organization_id = p_organization_id
      AND o.location_ref = p_branch_location_id::text
  ) THEN
    RAISE EXCEPTION 'inventory_storage_scope_mismatch' USING ERRCODE = '22023';
  END IF;

  v_fingerprint := md5(jsonb_build_object(
    'branch_location_id',p_branch_location_id,'warehouse_id',p_warehouse_id,
    'source_storage_location_id',p_source_storage_location_id,
    'destination_storage_location_id',p_destination_storage_location_id,
    'offering_id',p_offering_id,'quantity',p_quantity,'movement_type','internal_transfer'
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

  SELECT * INTO v_source
  FROM public.commercial_inventory_storage_balances
  WHERE organization_id = p_organization_id
    AND branch_location_id = p_branch_location_id
    AND warehouse_id = p_warehouse_id
    AND storage_location_id = p_source_storage_location_id
    AND offering_id = p_offering_id
  FOR UPDATE;
  IF NOT FOUND OR v_source.quantity_on_hand < p_quantity THEN
    RAISE EXCEPTION 'inventory_storage_insufficient' USING ERRCODE = '23514';
  END IF;

  UPDATE public.commercial_inventory_storage_balances
  SET quantity_on_hand = quantity_on_hand - p_quantity, updated_at = now()
  WHERE id = v_source.id;

  INSERT INTO public.commercial_inventory_storage_balances(
    organization_id, branch_location_id, warehouse_id, storage_location_id, offering_id, quantity_on_hand
  ) VALUES (
    p_organization_id, p_branch_location_id, p_warehouse_id,
    p_destination_storage_location_id, p_offering_id, p_quantity
  )
  ON CONFLICT (organization_id, storage_location_id, offering_id)
  DO UPDATE SET quantity_on_hand = public.commercial_inventory_storage_balances.quantity_on_hand + EXCLUDED.quantity_on_hand,
                updated_at = now()
  RETURNING id INTO v_destination_balance_id;

  INSERT INTO public.commercial_inventory_storage_movements(
    organization_id, branch_location_id, warehouse_id, offering_id, movement_type,
    source_storage_location_id, destination_storage_location_id, quantity,
    idempotency_key, request_fingerprint, created_by
  ) VALUES (
    p_organization_id, p_branch_location_id, p_warehouse_id, p_offering_id, 'internal_transfer',
    p_source_storage_location_id, p_destination_storage_location_id, p_quantity,
    btrim(p_idempotency_key), v_fingerprint, v_uid
  ) RETURNING id INTO v_movement_id;

  INSERT INTO public.commercial_audit_events(
    organization_id, actor_user_id, action, entity_type, entity_id, metadata
  ) VALUES (
    p_organization_id, v_uid, 'inventory.storage.transferred', 'inventory_storage_balance', v_destination_balance_id::text,
    jsonb_build_object('branch_location_id',p_branch_location_id,'warehouse_id',p_warehouse_id,
      'source_storage_location_id',p_source_storage_location_id,
      'destination_storage_location_id',p_destination_storage_location_id,
      'offering_id',p_offering_id,'quantity',p_quantity,'movement_id',v_movement_id)
  );
  RETURN jsonb_build_object('movement_id',v_movement_id,'source_balance_id',v_source.id,
    'destination_balance_id',v_destination_balance_id,'idempotent_replay',false);
END;
$function$;

REVOKE ALL ON FUNCTION public.commercial_allocate_inventory_to_storage_location(uuid,uuid,uuid,uuid,uuid,bigint,text)
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_allocate_inventory_to_storage_location(uuid,uuid,uuid,uuid,uuid,bigint,text)
  TO authenticated;
REVOKE ALL ON FUNCTION public.commercial_transfer_inventory_storage_location(uuid,uuid,uuid,uuid,uuid,uuid,bigint,text)
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.commercial_transfer_inventory_storage_location(uuid,uuid,uuid,uuid,uuid,uuid,bigint,text)
  TO authenticated;

COMMENT ON TABLE public.commercial_inventory_storage_balances IS
  'Optional warehouse-bin sub-ledger. Aggregate commercial_inventory_balances remains authoritative; allocated quantity may never exceed its branch/offering on-hand balance.';
COMMENT ON TABLE public.commercial_inventory_storage_movements IS
  'Append-only idempotent allocations and internal storage transfers. These movements do not change the aggregate branch balance or financial cost layers.';

COMMIT;
